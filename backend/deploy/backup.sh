#!/usr/bin/env bash
# Godance — daily Postgres logical backup to DigitalOcean Spaces.
#
# Dumps the postgres container, gzips, uploads to
# s3://$OBJECT_STORAGE_BUCKET/db-backups/, then prunes dumps older than
# RETENTION_DAYS (local + remote).
#
# Install as a daily cron — see deploy/README.md "Backups".
# Requires on the droplet: docker compose + AWS CLI v2.

set -euo pipefail

RETENTION_DAYS="${RETENTION_DAYS:-14}"
LOCAL_DIR="${LOCAL_DIR:-/var/backups/godance}"

# Resolve paths relative to this script so cron's cwd doesn't matter.
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$DEPLOY_DIR/.env.prod"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.prod.yml"

[ -f "$ENV_FILE" ] || { echo "[backup] missing $ENV_FILE" >&2; exit 1; }

# Read individual keys — do NOT `source` the file. It is a docker env file;
# values like JAVA_OPTS contain unquoted spaces that break shell parsing.
getenv() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

DATABASE_USERNAME="$(getenv DATABASE_USERNAME)"
OBJECT_STORAGE_BUCKET="$(getenv OBJECT_STORAGE_BUCKET)"
OBJECT_STORAGE_ENDPOINT="$(getenv OBJECT_STORAGE_ENDPOINT)"
export AWS_ACCESS_KEY_ID="$(getenv OBJECT_STORAGE_ACCESS_KEY)"
export AWS_SECRET_ACCESS_KEY="$(getenv OBJECT_STORAGE_SECRET_KEY)"
export AWS_DEFAULT_REGION="$(getenv OBJECT_STORAGE_REGION)"
S3_BASE="s3://$OBJECT_STORAGE_BUCKET/db-backups"

mkdir -p "$LOCAL_DIR"
STAMP="$(date -u +%Y-%m-%d-%H%M)"
DUMP="$LOCAL_DIR/godance-$STAMP.sql.gz"

echo "[backup] dumping postgres → $DUMP"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  pg_dump -U "$DATABASE_USERNAME" -d godance --no-owner --no-acl | gzip > "$DUMP"

SIZE="$(stat -c%s "$DUMP")"
if [ "$SIZE" -lt 1000 ]; then
  echo "[backup] dump is only $SIZE bytes — pg_dump likely failed, aborting" >&2
  rm -f "$DUMP"
  exit 1
fi

echo "[backup] uploading → $S3_BASE/"
aws s3 cp "$DUMP" "$S3_BASE/$(basename "$DUMP")" --endpoint-url "$OBJECT_STORAGE_ENDPOINT"

echo "[backup] pruning dumps older than $RETENTION_DAYS days"
find "$LOCAL_DIR" -name 'godance-*.sql.gz' -type f -mtime "+$RETENTION_DAYS" -delete

CUTOFF="$(date -u -d "-$RETENTION_DAYS days" +%Y-%m-%d)"
aws s3 ls "$S3_BASE/" --endpoint-url "$OBJECT_STORAGE_ENDPOINT" \
  | awk '{print $4}' \
  | grep -E '^godance-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}\.sql\.gz$' \
  | while read -r key; do
      kdate="${key#godance-}"; kdate="${kdate:0:10}"
      if [[ "$kdate" < "$CUTOFF" ]]; then
        echo "[backup] pruning remote $key"
        aws s3 rm "$S3_BASE/$key" --endpoint-url "$OBJECT_STORAGE_ENDPOINT"
      fi
    done

echo "[backup] done — $(basename "$DUMP") ($SIZE bytes)"
