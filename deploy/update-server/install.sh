#!/usr/bin/env bash
# Installs the Raven fallback update channel into the *running* nginx instance
# (master pid file /run/nginx-ai.pid, config /etc/nginx-ai). Existing vhosts are untouched.
# TLS uses the domain's publicly trusted Let's Encrypt certificate, so clients need no pinning.
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
    printf 'Run as root.\n' >&2
    exit 1
fi

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
NGINX_CONF=/etc/nginx-ai/nginx.conf
NGINX_PID=/run/nginx-ai.pid
CERT_DIR=/etc/letsencrypt/live/ywclgl258.us.ci
WEB_ROOT=/var/www/raven-update/public

# Remove artifacts from earlier attempts (inert default-nginx conf and the private CA material).
rm -f /etc/nginx/conf.d/raven-update.conf
rm -rf /etc/nginx-ai/raven-update

[[ -s "$CERT_DIR/fullchain.pem" && -s "$CERT_DIR/privkey.pem" ]] || {
    printf 'Missing Let'"'"'s Encrypt certificate under %s\n' "$CERT_DIR" >&2
    exit 1
}

install -d -m 0755 "$WEB_ROOT/raven/releases/assets"
install -m 0644 "$SCRIPT_DIR/nginx-raven-update.conf" /etc/nginx-ai/sites/raven-update.conf

# Validate the full instance config before signalling it. Abort without reload on failure.
/usr/sbin/nginx -t -c "$NGINX_CONF" -p /etc/nginx-ai
kill -HUP "$(cat "$NGINX_PID")"
printf 'Raven update channel installed: https://ywclgl258.us.ci:8443/raven/releases/latest\n'
