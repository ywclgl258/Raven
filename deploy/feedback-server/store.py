"""SQLite store for Raven feedback tickets, events, and admin sessions.

The database lives outside the web root. Writes use WAL and a process lock so the
single-worker service and the local export script can share the file safely.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
STATUSES = ("new", "triaged", "in_progress", "resolved", "wontfix", "duplicate")
CATEGORIES = ("bug", "suggestion", "playback", "download", "account", "other")
SOURCES = ("app", "website")
# Client-supplied integers are bound as SQLite INTEGER values; keep them inside the real Android
# ranges so a crafted payload cannot overflow the column and turn into a 500.
MAX_VERSION_CODE = 2_100_000_000
MAX_SDK_INT = 999
MAX_SAFE_INT = 2**63 - 1
_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
    id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    source TEXT NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    contact TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'new',
    priority TEXT NOT NULL DEFAULT 'normal',
    app_version TEXT NOT NULL DEFAULT '',
    version_code INTEGER NOT NULL DEFAULT 0,
    package_name TEXT NOT NULL DEFAULT '',
    device_model TEXT NOT NULL DEFAULT '',
    android_release TEXT NOT NULL DEFAULT '',
    sdk_int INTEGER NOT NULL DEFAULT 0,
    locale TEXT NOT NULL DEFAULT '',
    install_id TEXT NOT NULL DEFAULT '',
    client_ip TEXT NOT NULL DEFAULT '',
    user_agent TEXT NOT NULL DEFAULT '',
    log_name TEXT NOT NULL DEFAULT '',
    log_bytes INTEGER NOT NULL DEFAULT 0,
    log_sha256 TEXT NOT NULL DEFAULT '',
    admin_note TEXT NOT NULL DEFAULT '',
    exported_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    actor TEXT NOT NULL,
    kind TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tickets_status_updated ON tickets(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_tickets_created ON tickets(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_ticket ON events(ticket_id, id);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


def new_ticket_id(clock: int | None = None) -> str:
    stamp = time.strftime("%Y%m%d", time.gmtime((clock or now_ms()) / 1000))
    return f"RV-{stamp}-{secrets.token_hex(3).upper()}"


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return f"scrypt${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, salt_hex, digest_hex = stored.split("$", 2)
    except ValueError:
        return False
    if scheme != "scrypt":
        return False
    candidate = hash_password(password, bytes.fromhex(salt_hex))
    return hmac.compare_digest(candidate, f"scrypt${salt_hex}${digest_hex}")


class FeedbackStore:
    def __init__(self, db_path: Path, admin_password: str | None = None, read_only: bool = False) -> None:
        self.db_path = Path(db_path)
        self.read_only = read_only
        self._lock = threading.Lock()
        if read_only:
            # URI mode=ro guarantees this handle cannot create the schema, change the journal
            # mode, or checkpoint WAL: exporting is a pure read even when run by another user.
            if not self.db_path.is_file():
                raise FileNotFoundError(f"反馈数据库不存在：{self.db_path}")
            self._conn = sqlite3.connect(
                self.db_path.resolve().as_uri() + "?mode=ro",
                uri=True,
                check_same_thread=False,
            )
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA query_only=ON")
            self._conn.execute("PRAGMA busy_timeout=5000")
            return
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._conn.executescript(_SCHEMA)
        self._conn.execute(
            "INSERT OR IGNORE INTO meta(key, value) VALUES ('schema', ?)",
            (str(SCHEMA_VERSION),),
        )
        if admin_password:
            self.set_admin_password(admin_password, only_if_missing=True)
        self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def set_admin_password(self, password: str, only_if_missing: bool = False) -> bool:
        if len(password) < 12:
            raise ValueError("admin password must be at least 12 characters")
        with self._lock:
            existing = self._meta("admin_password_hash")
            if existing and only_if_missing:
                return False
            self._conn.execute(
                "INSERT INTO meta(key, value) VALUES ('admin_password_hash', ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (hash_password(password),),
            )
            self._conn.commit()
            return True

    def check_admin_password(self, password: str) -> bool:
        stored = self._meta("admin_password_hash")
        return bool(stored) and verify_password(password, stored)

    def create_session(self, ttl_seconds: int = 12 * 60 * 60) -> str:
        token = secrets.token_urlsafe(32)
        created = now_ms()
        with self._lock:
            self._conn.execute(
                "INSERT INTO sessions(token_hash, created_at, expires_at) VALUES (?, ?, ?)",
                (hash_token(token), created, created + ttl_seconds * 1000),
            )
            self._purge_sessions_locked(created)
            self._conn.commit()
        return token

    def session_valid(self, token: str | None) -> bool:
        if not token:
            return False
        created = now_ms()
        with self._lock:
            row = self._conn.execute(
                "SELECT expires_at FROM sessions WHERE token_hash = ?",
                (hash_token(token),),
            ).fetchone()
            if row is None or int(row["expires_at"]) < created:
                return False
            return True

    def revoke_session(self, token: str | None) -> None:
        if not token:
            return
        with self._lock:
            self._conn.execute("DELETE FROM sessions WHERE token_hash = ?", (hash_token(token),))
            self._conn.commit()

    def insert_ticket(self, fields: dict[str, Any], log_name: str = "", log_bytes: int = 0, log_sha256: str = "") -> dict[str, Any]:
        created = now_ms()
        ticket_id = new_ticket_id(created)
        row = {
            "id": ticket_id,
            "created_at": created,
            "updated_at": created,
            "source": _one_of(fields.get("source"), SOURCES, "app"),
            "category": _one_of(fields.get("category"), CATEGORIES, "bug"),
            "title": _clip(fields.get("title"), 120),
            "body": _clip(fields.get("body"), 8000),
            "contact": _clip(fields.get("contact"), 160),
            "status": "new",
            "priority": "normal",
            "app_version": _clip(fields.get("appVersion") or fields.get("app_version"), 40),
            "version_code": _int(fields.get("versionCode") or fields.get("version_code"), MAX_VERSION_CODE, "versionCode"),
            "package_name": _clip(fields.get("packageName") or fields.get("package_name"), 80),
            "device_model": _clip(fields.get("deviceModel") or fields.get("device_model"), 80),
            "android_release": _clip(fields.get("androidRelease") or fields.get("android_release"), 40),
            "sdk_int": _int(fields.get("sdkInt") or fields.get("sdk_int"), MAX_SDK_INT, "sdkInt"),
            "locale": _clip(fields.get("locale"), 40),
            "install_id": _clip(fields.get("installId") or fields.get("install_id"), 64),
            "client_ip": _clip(fields.get("client_ip"), 64),
            "user_agent": _clip(fields.get("user_agent"), 240),
            "log_name": log_name,
            "log_bytes": log_bytes,
            "log_sha256": log_sha256,
            "admin_note": "",
            "exported_at": 0,
        }
        if not row["title"] or not row["body"]:
            raise ValueError("title and body are required")
        columns = ", ".join(row)
        placeholders = ", ".join("?" for _ in row)
        with self._lock:
            self._conn.execute(
                f"INSERT INTO tickets({columns}) VALUES ({placeholders})",
                tuple(row.values()),
            )
            self._add_event_locked(ticket_id, "public", "created", row["source"])
            self._conn.commit()
        return self.get_ticket(ticket_id) or row

    def get_ticket(self, ticket_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM tickets WHERE id = ?", (ticket_id,)).fetchone()
        return _public_ticket(row) if row else None

    def attach_log(self, ticket_id: str, name: str, size: int, digest: str) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE tickets SET log_name = ?, log_bytes = ?, log_sha256 = ? WHERE id = ?",
                (name, size, digest, ticket_id),
            )
            self._conn.commit()

    def list_tickets(
        self,
        status: str | None = None,
        source: str | None = None,
        query: str | None = None,
        limit: int = 100,
        offset: int = 0,
    ) -> dict[str, Any]:
        clauses: list[str] = []
        args: list[Any] = []
        if status and status != "all":
            clauses.append("status = ?")
            args.append(status)
        if source and source != "all":
            clauses.append("source = ?")
            args.append(source)
        if query:
            clauses.append("(id LIKE ? OR title LIKE ? OR body LIKE ? OR app_version LIKE ? OR device_model LIKE ?)")
            needle = f"%{query.strip()}%"
            args.extend([needle, needle, needle, needle, needle])
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        limit = max(1, min(int(limit), 200))
        offset = max(0, int(offset))
        with self._lock:
            total = self._conn.execute(f"SELECT COUNT(*) AS n FROM tickets {where}", args).fetchone()["n"]
            rows = self._conn.execute(
                f"SELECT * FROM tickets {where} ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                [*args, limit, offset],
            ).fetchall()
            counts = {
                item["status"]: item["n"]
                for item in self._conn.execute("SELECT status, COUNT(*) AS n FROM tickets GROUP BY status")
            }
        return {
            "total": total,
            "counts": {name: int(counts.get(name, 0)) for name in STATUSES},
            "items": [_public_ticket(row) for row in rows],
        }

    def update_ticket(
        self,
        ticket_id: str,
        status: str | None = None,
        priority: str | None = None,
        admin_note: str | None = None,
        actor: str = "admin",
    ) -> dict[str, Any] | None:
        sets: list[str] = []
        args: list[Any] = []
        detail: list[str] = []
        if status is not None:
            if status not in STATUSES:
                raise ValueError("unknown status")
            sets.append("status = ?")
            args.append(status)
            detail.append(f"status={status}")
        if priority is not None:
            if priority not in ("low", "normal", "high"):
                raise ValueError("unknown priority")
            sets.append("priority = ?")
            args.append(priority)
            detail.append(f"priority={priority}")
        if admin_note is not None:
            sets.append("admin_note = ?")
            args.append(_clip(admin_note, 4000))
            detail.append("note")
        if not sets:
            return self.get_ticket(ticket_id)
        sets.append("updated_at = ?")
        updated = now_ms()
        args.append(updated)
        args.append(ticket_id)
        with self._lock:
            cursor = self._conn.execute(
                f"UPDATE tickets SET {', '.join(sets)} WHERE id = ?",
                args,
            )
            if cursor.rowcount == 0:
                return None
            self._add_event_locked(ticket_id, actor, "updated", ", ".join(detail))
            self._conn.commit()
        return self.get_ticket(ticket_id)

    def events(self, ticket_id: str) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT created_at, actor, kind, detail FROM events WHERE ticket_id = ? ORDER BY id",
                (ticket_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def stats(self) -> dict[str, Any]:
        listing = self.list_tickets(limit=1)
        with self._lock:
            open_count = self._conn.execute(
                "SELECT COUNT(*) AS n FROM tickets WHERE status IN ('new', 'triaged', 'in_progress')"
            ).fetchone()["n"]
            with_logs = self._conn.execute(
                "SELECT COUNT(*) AS n FROM tickets WHERE log_bytes > 0"
            ).fetchone()["n"]
        return {"open": int(open_count), "withLogs": int(with_logs), "counts": listing["counts"], "total": listing["total"]}

    def _meta(self, key: str) -> str | None:
        row = self._conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return None if row is None else str(row["value"])

    def _add_event_locked(self, ticket_id: str, actor: str, kind: str, detail: str) -> None:
        self._conn.execute(
            "INSERT INTO events(ticket_id, created_at, actor, kind, detail) VALUES (?, ?, ?, ?, ?)",
            (ticket_id, now_ms(), actor, kind, detail),
        )

    def _purge_sessions_locked(self, now: int) -> None:
        self._conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))


def mark_exported(db_path: Path, ticket_ids: list[str], timeout_seconds: float = 10.0) -> None:
    """Mark tickets exported through a short, explicit write transaction.

    Opened with ``mode=rw`` (never ``rwc``) so a mistyped path cannot create a new database. The
    caller is expected to run as the service user; this helper only guarantees that the write is
    atomic, bounded, and never leaves a partially updated batch behind.
    """
    if not ticket_ids:
        return
    path = Path(db_path)
    if not path.is_file():
        raise FileNotFoundError(f"反馈数据库不存在：{path}")
    conn = sqlite3.connect(path.resolve().as_uri() + "?mode=rw", uri=True, timeout=timeout_seconds)
    try:
        conn.execute(f"PRAGMA busy_timeout={int(timeout_seconds * 1000)}")
        conn.execute("BEGIN IMMEDIATE")
        conn.executemany(
            "UPDATE tickets SET exported_at = ? WHERE id = ?",
            [(now_ms(), ticket_id) for ticket_id in ticket_ids],
        )
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def _public_ticket(row: sqlite3.Row) -> dict[str, Any]:
    item = dict(row)
    item["hasLog"] = int(item.get("log_bytes") or 0) > 0
    return item


def _clip(value: Any, limit: int) -> str:
    text = "" if value is None else str(value)
    text = text.replace("\x00", "").strip()
    return text[:limit]


def _int(value: Any, maximum: int, label: str = "整数") -> int:
    try:
        parsed = int(value or 0)
    except (TypeError, ValueError, OverflowError):
        return 0
    if parsed > MAX_SAFE_INT:
        raise ValueError(f"{label} 超出允许范围")
    return max(0, min(parsed, maximum))


def _one_of(value: Any, allowed: tuple[str, ...], default: str) -> str:
    text = str(value or "").strip().lower()
    return text if text in allowed else default


def dumps(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def env_flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}
