"""Raven feedback service.

Public:
  POST /api/feedback
  GET  /api/feedback/health

Admin (session cookie, HttpOnly):
  POST /api/admin/login
  POST /api/admin/logout
  GET  /api/admin/tickets
  GET  /api/admin/tickets/<id>
  POST /api/admin/tickets/<id>
  GET  /api/admin/tickets/<id>/log
  GET  /admin

The process binds to loopback. nginx terminates TLS and forwards /api/feedback
and /admin to this service.
"""

from __future__ import annotations

import hashlib
import os
import re
import threading
import time
from collections import deque
from pathlib import Path

from flask import Flask, abort, jsonify, request, send_file, send_from_directory

from store import CATEGORIES, SOURCES, STATUSES, FeedbackStore

MAX_BODY_BYTES = 64 * 1024
MAX_LOG_BYTES = 1_500_000


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


PUBLIC_RATE_LIMIT = _env_int("RAVEN_FEEDBACK_PUBLIC_RATE_LIMIT", 30)
PUBLIC_RATE_WINDOW = _env_int("RAVEN_FEEDBACK_PUBLIC_RATE_WINDOW", 60)
LOGIN_RATE_LIMIT = _env_int("RAVEN_FEEDBACK_LOGIN_RATE_LIMIT", 10)
LOGIN_RATE_WINDOW = _env_int("RAVEN_FEEDBACK_LOGIN_RATE_WINDOW", 300)
RATE_LIMIT_MAX_CLIENTS = 4096

TICKET_ID = re.compile(r"^RV-\d{8}-[0-9A-F]{6}$")
SECRET_HEADER = re.compile(
    r"(?im)\b((?:set-cookie|cookie|authorization|proxy-authorization)\s*[:=]\s*)[^\n]*"
)
SECRET_KEY_VALUE = re.compile(
    r"(?i)\b(password|passwd|pwd|cookie|set-cookie|authorization|auth|token|access_token|refresh_token"
    r"|secret|api[_-]?key|signature|sig)\b(\s*[:=]\s*)(\"?)([^\s\"&,;]+)"
)
BEARER_TOKEN = re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}")
URL_SECRET_QUERY = re.compile(
    r"(?i)([?&](?:token|key|sig|signature|password|passwd|pwd|auth|session|code)=)[^&#\s]+"
)
COOKIE_PAIR = re.compile(r"(?i)\b([A-Za-z0-9_-]{2,24})=(?:[A-Za-z0-9%._-]{20,})")
PRIVATE_KEY_BLOCK = re.compile(
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.S
)


class RateLimiter:
    """Bounded, thread-safe fixed-window limiter keyed by client address.

    The service is one process behind nginx, so an in-process counter is enough. Memory is capped:
    when the table is full, expired entries are dropped and then the oldest key is evicted.
    """

    def __init__(self, limit: int, window_seconds: int, max_clients: int = RATE_LIMIT_MAX_CLIENTS) -> None:
        self.limit = max(1, limit)
        self.window_seconds = max(1, window_seconds)
        self.max_clients = max(1, max_clients)
        self._lock = threading.Lock()
        self._hits: dict[str, deque[float]] = {}

    def allow(self, key: str) -> tuple[bool, int]:
        now = time.monotonic()
        cutoff = now - self.window_seconds
        with self._lock:
            if key not in self._hits and len(self._hits) >= self.max_clients:
                self._prune_locked(cutoff)
                while self._hits and len(self._hits) >= self.max_clients:
                    self._hits.pop(next(iter(self._hits)))
            bucket = self._hits.setdefault(key, deque())
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()
            if len(bucket) >= self.limit:
                retry_after = max(1, int(self.window_seconds - (now - bucket[0])) + 1)
                return False, retry_after
            bucket.append(now)
            return True, 0

    def _prune_locked(self, cutoff: float) -> None:
        stale = [key for key, bucket in self._hits.items() if not bucket or bucket[-1] <= cutoff]
        for key in stale:
            del self._hits[key]


def _rate_limited(limiter: RateLimiter, key: str):
    allowed, retry_after = limiter.allow(key)
    if allowed:
        return None
    response = jsonify(ok=False, error="请求过于频繁，请稍后再试")
    response.status_code = 429
    response.headers["Retry-After"] = str(retry_after)
    return response


def create_app(
    store: FeedbackStore | None = None,
    log_dir: Path | None = None,
    static_dir: Path | None = None,
) -> Flask:
    root = Path(__file__).resolve().parent
    data_root = Path(os.environ.get("RAVEN_FEEDBACK_DATA", "/var/lib/raven-feedback"))
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = MAX_BODY_BYTES + MAX_LOG_BYTES + 16_384
    app.store = store or FeedbackStore(
        data_root / "feedback.sqlite3",
        admin_password=os.environ.get("RAVEN_FEEDBACK_ADMIN_PASSWORD"),
    )
    app.log_dir = Path(log_dir or data_root / "logs")
    app.log_dir.mkdir(parents=True, exist_ok=True)
    app.static_dir = Path(static_dir or root / "static")
    app.secure_cookie = os.environ.get("RAVEN_FEEDBACK_SECURE_COOKIE", "1") != "0"
    app.public_limiter = RateLimiter(PUBLIC_RATE_LIMIT, PUBLIC_RATE_WINDOW)
    app.login_limiter = RateLimiter(LOGIN_RATE_LIMIT, LOGIN_RATE_WINDOW)

    @app.get("/api/feedback/health")
    def health():
        return jsonify(status="ok", service="raven-feedback")

    @app.post("/api/feedback")
    def submit():
        limited = _rate_limited(app.public_limiter, _client_ip())
        if limited:
            return limited
        _limit_public_request()
        payload, log_bytes = _read_submission()
        log_name = ""
        log_sha = ""
        if log_bytes:
            log_sha = hashlib.sha256(log_bytes).hexdigest()
        try:
            ticket = app.store.insert_ticket(
                payload,
                log_bytes=len(log_bytes),
                log_sha256=log_sha,
            )
        except ValueError as error:
            return jsonify(ok=False, error=str(error)), 422
        if log_bytes:
            log_name = f"{ticket['id']}.log"
            target = app.log_dir / log_name
            temporary = target.with_suffix(".log.part")
            temporary.write_bytes(log_bytes)
            os.replace(temporary, target)
            os.chmod(target, 0o640)
            _attach_log(app.store, ticket["id"], log_name, len(log_bytes), log_sha)
            ticket = app.store.get_ticket(ticket["id"]) or ticket
        return jsonify(ok=True, id=ticket["id"], status=ticket["status"]), 201

    @app.post("/api/admin/login")
    def login():
        limited = _rate_limited(app.login_limiter, _client_ip())
        if limited:
            return limited
        body = request.get_json(silent=True) or {}
        password = str(body.get("password") or "")
        if not app.store.check_admin_password(password):
            time.sleep(0.4)
            return jsonify(ok=False, error="账号或口令不正确"), 401
        token = app.store.create_session()
        response = jsonify(ok=True)
        response.set_cookie(
            "raven_feedback_session",
            token,
            httponly=True,
            secure=app.secure_cookie,
            samesite="Strict",
            max_age=12 * 60 * 60,
            path="/",
        )
        return response

    @app.post("/api/admin/logout")
    def logout():
        app.store.revoke_session(request.cookies.get("raven_feedback_session"))
        response = jsonify(ok=True)
        response.delete_cookie("raven_feedback_session", path="/")
        return response

    @app.get("/api/admin/session")
    def session():
        _require_admin()
        return jsonify(ok=True, stats=app.store.stats())

    @app.get("/api/admin/tickets")
    def tickets():
        _require_admin()
        return jsonify(app.store.list_tickets(
            status=request.args.get("status"),
            source=request.args.get("source"),
            query=request.args.get("q"),
            limit=_bounded(request.args.get("limit"), 80, 200),
            offset=_bounded(request.args.get("offset"), 0, 10_000),
        ))

    @app.get("/api/admin/tickets/<ticket_id>")
    def ticket(ticket_id: str):
        _require_admin()
        _check_id(ticket_id)
        item = app.store.get_ticket(ticket_id)
        if item is None:
            abort(404)
        return jsonify(ticket=item, events=app.store.events(ticket_id))

    @app.post("/api/admin/tickets/<ticket_id>")
    def update(ticket_id: str):
        _require_admin()
        _check_id(ticket_id)
        body = request.get_json(silent=True) or {}
        try:
            item = app.store.update_ticket(
                ticket_id,
                status=body.get("status"),
                priority=body.get("priority"),
                admin_note=body.get("adminNote", body.get("admin_note")),
            )
        except ValueError as error:
            return jsonify(ok=False, error=str(error)), 400
        if item is None:
            abort(404)
        return jsonify(ok=True, ticket=item)

    @app.get("/api/admin/tickets/<ticket_id>/log")
    def download_log(ticket_id: str):
        _require_admin()
        _check_id(ticket_id)
        path = app.log_dir / f"{ticket_id}.log"
        if not path.is_file():
            abort(404)
        return send_file(path, mimetype="text/plain", as_attachment=True, download_name=path.name)

    @app.get("/admin")
    @app.get("/admin/")
    def admin_page():
        return send_from_directory(app.static_dir, "admin.html")

    @app.get("/admin/app.js")
    def admin_script():
        return send_from_directory(app.static_dir, "admin.js", mimetype="text/javascript")

    @app.get("/api/feedback/meta")
    def meta():
        return jsonify(categories=list(CATEGORIES), sources=list(SOURCES), statuses=list(STATUSES), maxLogBytes=MAX_LOG_BYTES)

    return app


def _require_admin() -> None:
    from flask import current_app

    if not current_app.store.session_valid(request.cookies.get("raven_feedback_session")):
        abort(401)


def _check_id(ticket_id: str) -> None:
    if not TICKET_ID.fullmatch(ticket_id):
        abort(404)


def _bounded(value: str | None, default: int, maximum: int) -> int:
    try:
        parsed = int(value if value is not None else default)
    except ValueError:
        return default
    return max(0, min(parsed, maximum))


def _limit_public_request() -> None:
    length = request.content_length or 0
    if length > MAX_BODY_BYTES + MAX_LOG_BYTES + 16_384:
        abort(413)


def _read_submission() -> tuple[dict, bytes]:
    if request.content_type and "multipart/form-data" in request.content_type:
        raw = request.form.get("payload") or "{}"
        try:
            import json
            payload = json.loads(raw)
        except json.JSONDecodeError:
            abort(400, "payload 不是合法 JSON")
        upload = request.files.get("log")
        log_bytes = upload.read(MAX_LOG_BYTES + 1) if upload else b""
    else:
        payload = request.get_json(silent=True)
        log_bytes = b""
        if not isinstance(payload, dict):
            abort(400, "需要 JSON 对象")
        inline = payload.pop("logText", None)
        if inline:
            log_bytes = str(inline).encode("utf-8")
    if not isinstance(payload, dict):
        abort(400, "payload 不是对象")
    if len(log_bytes) > MAX_LOG_BYTES:
        abort(413, "日志超过 1.5 MB")
    payload["client_ip"] = _client_ip()
    payload["user_agent"] = request.headers.get("User-Agent", "")
    return payload, _redact_log(log_bytes)


def _client_ip() -> str:
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64]
    return (request.remote_addr or "")[:64]


def _redact_log(raw: bytes) -> bytes:
    if not raw:
        return b""
    text = raw.decode("utf-8", errors="replace")
    # Same intent as core:common LogRedactor: headers, key/value pairs, bearer/basic credentials,
    # signed query strings, cookie pairs and private keys are replaced before the log is stored.
    text = PRIVATE_KEY_BLOCK.sub("[redacted](private key)", text)
    text = SECRET_HEADER.sub(lambda match: match.group(1) + "[redacted]", text)
    text = SECRET_KEY_VALUE.sub(
        lambda match: f"{match.group(1)}{match.group(2)}{match.group(3)}[redacted]", text
    )
    text = BEARER_TOKEN.sub(lambda match: f"{match.group(1)} [redacted]", text)
    text = URL_SECRET_QUERY.sub(lambda match: match.group(1) + "[redacted]", text)
    text = COOKIE_PAIR.sub(lambda match: f"{match.group(1)}=[redacted]", text)
    return text.encode("utf-8")


def _attach_log(store: FeedbackStore, ticket_id: str, name: str, size: int, digest: str) -> None:
    with store._lock:
        store._conn.execute(
            "UPDATE tickets SET log_name = ?, log_bytes = ?, log_sha256 = ? WHERE id = ?",
            (name, size, digest, ticket_id),
        )
        store._conn.commit()


def main() -> None:
    port = int(os.environ.get("RAVEN_FEEDBACK_PORT", "8765"))
    create_app().run(host="127.0.0.1", port=port, debug=False, threaded=True)


if __name__ == "__main__":
    main()

