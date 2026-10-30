# Godance — Moving production data onto an encrypted volume

A droplet's own disk is **not encrypted at rest**. DigitalOcean's shared
responsibility page for Droplets says so and points to Volumes and Spaces for
encrypted storage. Everything Docker writes lives on that disk today:
Postgres, Redis, Caddy's certificates and every container's logs. So do
`backup.sh`'s local dumps in `/var/backups/godance`.

DigitalOcean Volumes are encrypted at rest with LUKS, and their snapshots
inherit that encryption. This runbook attaches a Volume, moves Docker's
whole data directory onto it, and then moves the local dumps there too. The
compose file, the app and the deploy workflow stay as they are.

Run it once on an existing droplet. A new droplet needs only the short
setup in [A new droplet](#a-new-droplet), before its first `docker compose up`.

**What it protects against:** someone reading the storage outside
DigitalOcean's storage cluster, such as a discarded or stolen disk.
DigitalOcean manages the keys, so it does not protect against someone with
root on the droplet or access to the DigitalOcean account. The privacy
policy should say that much and no more: data is stored on disks encrypted
at rest by our hosting provider.

**Cost:** Volumes are $0.10 per GiB per month, so 50 GiB is $5 a month.
A Volume can grow later without downtime; it cannot shrink.

**Downtime:** the API is down from step 7 to step 12. Expect about
5 minutes plus about 2 minutes per 5 GB that `du` reports for
`/var/lib/docker` in step 1 (a copy and a checksum pass). Book a 30-minute
window. Stripe retries webhooks it could not deliver, and the scheduled
jobs pick up where they left off.

**Not covered:** `.env.prod`, the operating system and the image store
when Docker uses the containerd image store (step 1 tells you). None of
those hold user data. Managed PostgreSQL is also encrypted at rest,
including its backups, and is still on the "Hardening" list in
[README.md](README.md); this runbook doesn't block moving there later.

Run everything as root on the droplet unless a step says otherwise.

## Before the window (no downtime)

**1. Measure.**

```bash
apt-get install -y rsync jq
du -sh /var/lib/docker /var/lib/docker/volumes /var/backups/godance
docker info -f 'root={{.DockerRootDir}} driver={{.Driver}}'
df -h /
```

`driver=overlay2` means images live under `/var/lib/docker` and move
with it. `driver=overlayfs` means the containerd image store: images stay
in `/var/lib/containerd` on the droplet disk, which is fine, since images
hold code and no user data.

Optional, to copy less: `docker image prune -af && docker builder prune -af`.
This deletes images no container uses, including older backend releases;
GHCR still has every release for a rollback.

**2. Create the Volume.** In the DigitalOcean control panel: **Create →
Volumes**, 50 GiB, attach it to the Godance droplet (which puts it in the
droplet's datacenter), name it `godance-data`, and choose **Manually Format
& Mount**. "Automatically Format & Mount" would mount it elsewhere and add
its own `/etc/fstab` line.

**3. Format and mount it.**

```bash
VOL=/dev/disk/by-id/scsi-0DO_Volume_godance-data
ls -l "$VOL"     # must exist: the attached Volume
# Formats only a blank Volume. If blkid finds a filesystem, stop and check.
blkid "$VOL" && echo "has a filesystem already: not formatting" || mkfs.ext4 -L godance-data "$VOL"
mkdir -p /mnt/godance_data
grep -q ' /mnt/godance_data ' /etc/fstab || \
  echo "$VOL /mnt/godance_data ext4 defaults,nofail,discard,noatime 0 2" >> /etc/fstab
systemctl daemon-reload
mount /mnt/godance_data
findmnt /mnt/godance_data     # SOURCE is the Volume's device, not /dev/vda1
```

**4. Never let Docker start without the Volume.** If the Volume were missing
at boot, Docker would start on an empty `/mnt/godance_data` and the backend
would run against a new, empty database. This makes Docker wait for the
mount and refuse to start without it:

```bash
mkdir -p /etc/systemd/system/docker.service.d
cat >/etc/systemd/system/docker.service.d/godance-data.conf <<'EOF'
# Docker's data lives on the encrypted Volume. Never start without it.
[Unit]
RequiresMountsFor=/mnt/godance_data
EOF
systemctl daemon-reload
```

**5. Take a fresh backup** and check it reached Spaces:

```bash
/opt/godance/backend/deploy/backup.sh     # ends with "done — godance-<stamp>.sql.gz (<size> bytes)"
```

**6. Pause deploys** for the window, from your laptop. A push to `main`
would otherwise restart the backend halfway through:

```bash
gh workflow disable deploy.yml --repo GoDanceAi/godance
```

## The window

Use one shell for steps 7–12; later steps reuse its variables.

**7. Stop public traffic and the backend, then record the row counts.**

```bash
cd /opt/godance/backend
C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod"
# The release production runs now, so step 12 starts the same one.
export BACKEND_TAG="$(docker inspect -f '{{.Config.Image}}' "$($C ps -q backend)" | sed 's/.*://')"
echo "$BACKEND_TAG"
$C stop -t 60 caddy backend

cat >/root/counts.sql <<'EOF'
SELECT table_name, (xpath('/row/n/text()', query_to_xml(
         format('SELECT count(*) AS n FROM public.%I', table_name), false, true, '')))[1]
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
 ORDER BY 1;
EOF
counts() { $C exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d godance -At -v ON_ERROR_STOP=1' </root/counts.sql; }
counts >/root/counts-before.txt
cat /root/counts-before.txt     # one line per table: name|rows
```

**8. Stop everything, then Docker itself.**

```bash
$C stop -t 60
systemctl stop docker.socket docker.service
systemctl is-active docker      # inactive
```

**9. Copy Docker's data onto the Volume.**

```bash
# --delete makes a retry after a rollback exact too.
rsync -aHAX --numeric-ids --delete --info=progress2 /var/lib/docker/ /mnt/godance_data/docker/
# A checksummed comparison: prints nothing when the copy is exact.
rsync -aHAX --numeric-ids --delete --checksum --dry-run --itemize-changes /var/lib/docker/ /mnt/godance_data/docker/
```

**10. Point Docker at the Volume and start it.** The old directory is set
aside, not deleted, so you can roll back.

```bash
mkdir -p /etc/docker
[ -s /etc/docker/daemon.json ] || echo '{}' >/etc/docker/daemon.json
cp /etc/docker/daemon.json /root/daemon.json.pre-volume
jq '. + {"data-root": "/mnt/godance_data/docker"}' /root/daemon.json.pre-volume >/etc/docker/daemon.json
mv /var/lib/docker /var/lib/docker.pre-volume
systemctl start docker
docker info -f '{{.DockerRootDir}}'     # /mnt/godance_data/docker
```

**11. Start the data services and compare.**

```bash
$C up -d --no-build --wait postgres redis
docker inspect -f '{{range .Mounts}}{{.Source}}{{"\n"}}{{end}}' "$($C ps -q postgres)"
#   /mnt/godance_data/docker/volumes/…/_data
counts >/root/counts-after.txt
diff /root/counts-before.txt /root/counts-after.txt && echo "row counts match"
```

If Docker won't start, the mount is wrong or the counts differ, **roll
back now** (below). Nothing public has run yet, so nothing is lost.

**12. Reopen traffic and check.**

```bash
$C up -d --no-build --wait backend caddy
curl -fsS https://api.godance.ai/healthz | jq -r .git_sha            # the first 7 characters of $BACKEND_TAG
curl -fsS https://api.godance.ai/actuator/health | jq -r .status     # UP
```

Then sign in at the app and check that the balance and history look right.

**13. Reboot once** (about 2 more minutes down). This proves the Volume
mounts at boot and Docker comes up from it on its own:

```bash
reboot
# reconnect after a minute
findmnt /mnt/godance_data && docker info -f '{{.DockerRootDir}}'
curl -fsS https://api.godance.ai/actuator/health | jq -r .status     # UP within ~2 minutes
```

**14. Resume deploys**, from your laptop:

```bash
gh workflow enable deploy.yml --repo GoDanceAi/godance
```

## Move the backup dumps

`backup.sh` keeps 14 days of dumps locally. Move them onto the Volume and
point the cron job there:

```bash
mkdir -p /mnt/godance_data/backups
mv /var/backups/godance/godance-*.sql.gz /mnt/godance_data/backups/
cat >/etc/cron.d/godance-backup <<'EOF'
PATH=/usr/local/bin:/usr/bin:/bin
LOCAL_DIR=/mnt/godance_data/backups
0 2 * * * root /opt/godance/backend/deploy/backup.sh >> /var/log/godance-backup.log 2>&1
EOF
LOCAL_DIR=/mnt/godance_data/backups /opt/godance/backend/deploy/backup.sh
ls /mnt/godance_data/backups /var/backups/godance     # new dump on the Volume; old directory empty
```

## Rolling back

**Until step 12** nothing has written to the new copy, so going back loses
nothing:

```bash
cd /opt/godance/backend
C="docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod"
# In a new shell, first: export BACKEND_TAG=<the sha step 7 printed>
$C stop -t 60
systemctl stop docker.socket docker.service
cp /root/daemon.json.pre-volume /etc/docker/daemon.json
rm /etc/systemd/system/docker.service.d/godance-data.conf
systemctl daemon-reload
mv -T /var/lib/docker.pre-volume /var/lib/docker
systemctl start docker
$C up -d --no-build --wait
```

**After step 12** production writes to the Volume. Don't switch back to
`/var/lib/docker.pre-volume`: it predates everything written since. Fix the
problem in place, or restore the latest dump from Spaces.

## A week later: remove the unencrypted copies

```bash
rm -rf /var/lib/docker.pre-volume
rmdir /var/backups/godance
fstrim -v /
```

`fstrim` tells the virtual disk the freed blocks are unused. DigitalOcean
doesn't document what happens to those blocks on its hosts, so if that
matters, build a fresh droplet from steps 1–4 and restore a dump there.

In the control panel, delete Droplet snapshots taken before the move; they
hold the old, unencrypted database. If Droplet backups are on, the backups
from before the move hold it too until they age out. Droplet backups and
snapshots do not include Volumes, so from now on the database is backed up
by `backup.sh` to Spaces, which is encrypted at rest. Volume snapshots
($0.06 per GiB per month) are an optional second copy.

Once this is done, the "encrypted at rest" placeholder in the privacy
policy draft can be resolved with the wording above.

## A new droplet

Before the first `docker compose up` in [README.md](README.md), do steps
2–4, then point Docker at the Volume:

```bash
apt-get install -y jq
mkdir -p /etc/docker
[ -s /etc/docker/daemon.json ] || echo '{}' >/etc/docker/daemon.json
jq '. + {"data-root": "/mnt/godance_data/docker"}' /etc/docker/daemon.json >/tmp/daemon.json
mv /tmp/daemon.json /etc/docker/daemon.json
systemctl restart docker
docker info -f '{{.DockerRootDir}}'     # /mnt/godance_data/docker
```

The backup cron job in README.md already keeps its dumps on the Volume.
