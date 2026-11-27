#!/usr/bin/env bash
# Installs the Raven feedback service beside the existing website vhost.
# Does not print or store the admin password in the repository.
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
    printf 'Run as root.\n' >&2
    exit 1
fi

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
APP_DIR=/opt/raven-feedback
DATA_DIR=/var/lib/raven-feedback
ENV_FILE=/etc/raven-feedback.env
NGINX_CONF=/etc/nginx-ai/nginx.conf
NGINX_PID=/run/nginx-ai.pid
SITE_CONF=/etc/nginx-ai/sites/raven-website.conf

id raven-feedback >/dev/null 2>&1 || useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin raven-feedback

install -d -o raven-feedback -g raven-feedback -m 0750 "$DATA_DIR" "$DATA_DIR/logs"
install -d -m 0755 "$APP_DIR"
install -m 0644 "$SCRIPT_DIR/app.py" "$SCRIPT_DIR/store.py" "$SCRIPT_DIR/export_feedback.py" "$SCRIPT_DIR/requirements.txt" "$APP_DIR/"
install -d -m 0755 "$APP_DIR/static"
install -m 0644 "$SCRIPT_DIR/static/admin.html" "$SCRIPT_DIR/static/admin.js" "$APP_DIR/static/"

if [[ ! -x "$APP_DIR/.venv/bin/python" ]]; then
    python3 -m venv "$APP_DIR/.venv"
fi
"$APP_DIR/.venv/bin/pip" install --disable-pip-version-check -r "$APP_DIR/requirements.txt"
chown -R raven-feedback:raven-feedback "$APP_DIR" "$DATA_DIR"

if [[ ! -s "$ENV_FILE" ]]; then
    if [[ -z "${RAVEN_FEEDBACK_ADMIN_PASSWORD:-}" ]]; then
        printf 'Set RAVEN_FEEDBACK_ADMIN_PASSWORD before the first install.\n' >&2
        exit 1
    fi
    install -m 0640 /dev/null "$ENV_FILE"
    chown root:raven-feedback "$ENV_FILE"
    printf 'RAVEN_FEEDBACK_ADMIN_PASSWORD=%s\n' "$RAVEN_FEEDBACK_ADMIN_PASSWORD" > "$ENV_FILE"
    chmod 0640 "$ENV_FILE"
fi

wait_for_health() {
    for _ in $(seq 1 30); do
        if "$APP_DIR/.venv/bin/python" - <<'PY' >/dev/null 2>&1
import json, urllib.request
with urllib.request.urlopen("http://127.0.0.1:8765/api/feedback/health", timeout=2) as response:
    payload = json.load(response)
if payload.get("status") != "ok":
    raise SystemExit(1)
PY
        then
            return 0
        fi
        sleep 1
    done
    return 1
}

install -m 0644 "$SCRIPT_DIR/raven-feedback.service" /etc/systemd/system/raven-feedback.service
systemctl daemon-reload
# Pick up new files and the environment file immediately; a failed restart must abort the install
# instead of leaving a half-updated service behind.
systemctl enable raven-feedback.service
if ! systemctl restart raven-feedback.service || ! systemctl is-active --quiet raven-feedback.service; then
    printf 'raven-feedback.service failed to start; see: journalctl -u raven-feedback -n 50\n' >&2
    exit 1
fi
if ! wait_for_health; then
    printf 'Feedback health check failed; see: journalctl -u raven-feedback -n 50\n' >&2
    exit 1
fi

SNIPPET=/etc/nginx-ai/snippets/raven-feedback.conf
install -d -m 0755 /etc/nginx-ai/snippets
install -m 0644 "$SCRIPT_DIR/nginx-raven-feedback.conf" "$SNIPPET"
# The repo vhost may already declare these locations inline; do not double-include them.
if grep -q "location = /api/feedback" "$SITE_CONF"; then
    printf 'Website vhost already routes the feedback API; skipping snippet include.\n'
elif ! grep -q "snippets/raven-feedback.conf" "$SITE_CONF"; then
    python3 - "$SITE_CONF" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
needle = "    location / {\n        return 404;\n    }"
insert = "    include /etc/nginx-ai/snippets/raven-feedback.conf;\n\n" + needle
if needle not in text:
    raise SystemExit("website vhost has no terminal location / block; add the snippet manually")
path.write_text(text.replace(needle, insert, 1))
PY
fi

/usr/sbin/nginx -t -c "$NGINX_CONF" -p /etc/nginx-ai
kill -HUP "$(cat "$NGINX_PID")"
printf 'Raven feedback installed: https://ywclgl258.us.ci/admin\n'
