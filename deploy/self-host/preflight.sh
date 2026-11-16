#!/bin/sh
# =====================================================================
# Godance (self-hosted) — checks .env before the API may start.
#
# docker-compose.yml runs this in a throwaway container with .env as its
# environment, and starts the API only if it exits 0. It names every
# variable that needs attention and never prints a value.
# =====================================================================

problems=0
fail() {
    echo "preflight: $*" >&2
    problems=$((problems + 1))
}

# --- Placeholders ----------------------------------------------------
# .env.example marks every value you must choose with change-me. Some would
# boot and be dangerous: the object-storage secret is a password on a
# public endpoint, and a known webhook secret lets anyone add credit.
for name in $(env | grep -i 'change-me' | sed 's/=.*//' | sort -u); do
    fail "$name still holds a change-me placeholder"
done

# --- Secrets the API checks itself (named here first) ----------------
key_bytes=$(printf '%s' "${KEY_VAULT_KEY:-}" | base64 -d 2>/dev/null | wc -c)
[ "$key_bytes" -eq 32 ] || fail "KEY_VAULT_KEY must be 32 random bytes in Base64 (openssl rand -base64 32)"
[ "${#JWT_SIGNING_KEY}" -ge 32 ] || fail "JWT_SIGNING_KEY must be at least 32 characters (openssl rand -hex 32)"
[ -n "${DATABASE_USERNAME:-}" ] || fail "DATABASE_USERNAME is empty"
[ -n "${DATABASE_PASSWORD:-}" ] || fail "DATABASE_PASSWORD is empty"

case "$(printf '%s' "${REGION:-}" | tr 'A-Z' 'a-z')" in
    digitalocean|alicloud|local) ;;
    *) fail "REGION must be digitalocean, alicloud or local" ;;
esac

# --- Addresses -------------------------------------------------------
# check_url NAME URL: an origin (scheme://host[:port], nothing after it) whose
# port is the one Caddy listens on for that scheme. Sets url_host.
check_url() {
    name=$1 url=$2 url_host=
    case "$url" in
        http://*) scheme=http ;;
        https://*) scheme=https ;;
        *) fail "$name must start with http:// or https://"; return ;;
    esac
    rest=${url#*://}
    case "$rest" in
        */*|"") fail "$name must be an origin only, with no path or trailing slash"; return ;;
    esac
    url_host=${rest%%:*}
    port=
    [ "$url_host" = "$rest" ] || port=${rest#*:}
    if [ "$scheme" = http ]; then want=${HTTP_PORT:-}; default=80; else want=${HTTPS_PORT:-}; default=443; fi
    [ -n "$port" ] || port=$default
    [ "$port" = "$want" ] || fail "$name uses port $port, but Caddy listens for $scheme on ${want:-?} (HTTP_PORT / HTTPS_PORT)"
}

check_url GODANCE_URL "${GODANCE_URL:-}"
app_host=$url_host

# --- Object storage --------------------------------------------------
if [ "${GODANCE_BUNDLED_STORAGE:-}" = "true" ]; then
    check_url GODANCE_FILES_URL "${GODANCE_FILES_URL:-}"
    files_host=$url_host
    [ "$files_host" = "${GODANCE_FILES_HOST:-}" ] || fail "GODANCE_FILES_HOST must be the host name in GODANCE_FILES_URL"
    [ -z "$app_host" ] || [ "$files_host" != "$app_host" ] || fail "GODANCE_FILES_URL needs its own host name, not GODANCE_URL's"
    [ "${#OBJECT_STORAGE_ACCESS_KEY}" -ge 3 ] || fail "OBJECT_STORAGE_ACCESS_KEY must be at least 3 characters (MinIO's user name)"
    [ "${#OBJECT_STORAGE_SECRET_KEY}" -ge 8 ] || fail "OBJECT_STORAGE_SECRET_KEY must be at least 8 characters (MinIO's password)"
    [ -n "${STORAGE_SUBNET:-}" ] || fail "STORAGE_SUBNET is empty"
else
    case "${OBJECT_STORAGE_ENDPOINT:-}" in
        http://*|https://*) ;;
        *) fail "OBJECT_STORAGE_ENDPOINT must be your storage service's URL when docker-compose.minio.yml is not used" ;;
    esac
    [ -n "${OBJECT_STORAGE_ACCESS_KEY:-}" ] || fail "OBJECT_STORAGE_ACCESS_KEY is empty"
    [ -n "${OBJECT_STORAGE_SECRET_KEY:-}" ] || fail "OBJECT_STORAGE_SECRET_KEY is empty"
fi
printf '%s' "${OBJECT_STORAGE_BUCKET:-}" | grep -Eq '^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$' \
    || fail "OBJECT_STORAGE_BUCKET must be 3-63 lower-case letters, digits, dots or hyphens"

# --- Payments --------------------------------------------------------
# A rail with a key but no webhook secret takes money and never credits it.
# A short secret can be guessed, and the webhook credits whatever a validly
# signed message says. Empty is safe: every webhook is then refused.
for rail in STRIPE:STRIPE_SECRET_KEY:STRIPE_WEBHOOK_SECRET NOWPAYMENTS:NOWPAYMENTS_API_KEY:NOWPAYMENTS_IPN_SECRET; do
    key_name=$(echo "$rail" | cut -d: -f2)
    secret_name=$(echo "$rail" | cut -d: -f3)
    eval "key=\${$key_name:-}"
    eval "secret=\${$secret_name:-}"
    if [ -n "$key" ] && [ -z "$secret" ]; then
        fail "$key_name is set but $secret_name is empty: payments would never be credited"
    fi
    if [ -n "$secret" ] && [ "${#secret}" -lt 24 ]; then
        fail "$secret_name is shorter than 24 characters: use the secret your payment provider issued"
    fi
done

if [ "$problems" -gt 0 ]; then
    echo "preflight: $problems problem(s) in .env; the API will not start. See docs/self-hosting.md." >&2
    exit 1
fi
echo "preflight: .env looks usable"
