"""SQLite persistence (single household deployment).

Production note: SQLite is appropriate for ONE instance with a persistent
volume (see infra/README.md). If you ever scale beyond one instance, move to
Postgres; the rate limiter and OAuth-state store would also need to be shared.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import threading
import time
from pathlib import Path

from cryptography.fernet import Fernet

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
  token_hash TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  fcm_token_enc BLOB,
  revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS pairing_codes (
  code_hash TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_states (
  state_hash TEXT PRIMARY KEY,
  device_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS whoop_tokens (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  blob_enc BLOB NOT NULL,
  whoop_user_id INTEGER,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS webhook_events (
  event_key TEXT PRIMARY KEY,
  received_at INTEGER NOT NULL
);
"""


def _h(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class Store:
    def __init__(self, db_path: str, encryption_key: str):
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA)
        self._lock = threading.Lock()
        self._fernet = Fernet(encryption_key.encode())

    # ---- pairing / devices -------------------------------------------------
    def create_pairing_code(self, ttl_seconds: int = 600) -> str:
        code = secrets.token_urlsafe(12)
        with self._lock:
            self._db.execute(
                "INSERT INTO pairing_codes VALUES (?, ?)", (_h(code), int(time.time()) + ttl_seconds)
            )
        return code

    def redeem_pairing_code(self, code: str) -> str | None:
        """Single-use: returns a new device token or None."""
        now = int(time.time())
        with self._lock:
            cur = self._db.execute(
                "DELETE FROM pairing_codes WHERE code_hash = ? AND expires_at > ?", (_h(code), now)
            )
            if cur.rowcount != 1:
                return None
            token = secrets.token_urlsafe(32)
            self._db.execute(
                "INSERT INTO devices (token_hash, created_at) VALUES (?, ?)", (_h(token), now)
            )
        return token

    def device_exists(self, token: str) -> str | None:
        th = _h(token)
        row = self._db.execute(
            "SELECT token_hash FROM devices WHERE token_hash = ? AND revoked = 0", (th,)
        ).fetchone()
        return row[0] if row else None

    def set_fcm_token(self, device_hash: str, fcm_token: str) -> None:
        with self._lock:
            self._db.execute(
                "UPDATE devices SET fcm_token_enc = ? WHERE token_hash = ?",
                (self._fernet.encrypt(fcm_token.encode()), device_hash),
            )

    def fcm_tokens(self) -> list[str]:
        rows = self._db.execute(
            "SELECT fcm_token_enc FROM devices WHERE revoked = 0 AND fcm_token_enc IS NOT NULL"
        ).fetchall()
        return [self._fernet.decrypt(r[0]).decode() for r in rows]

    # ---- OAuth state (CSRF) --------------------------------------------------
    def create_oauth_state(self, device_hash: str, ttl_seconds: int = 600) -> str:
        state = secrets.token_urlsafe(32)
        with self._lock:
            self._db.execute(
                "INSERT INTO oauth_states VALUES (?, ?, ?)",
                (_h(state), device_hash, int(time.time()) + ttl_seconds),
            )
        return state

    def consume_oauth_state(self, state: str) -> bool:
        with self._lock:
            cur = self._db.execute(
                "DELETE FROM oauth_states WHERE state_hash = ? AND expires_at > ?",
                (_h(state), int(time.time())),
            )
        return cur.rowcount == 1

    # ---- WHOOP tokens (encrypted at rest) ------------------------------------
    def save_whoop_tokens(self, tokens: dict, whoop_user_id: int | None = None) -> None:
        blob = self._fernet.encrypt(json.dumps(tokens).encode())
        with self._lock:
            self._db.execute(
                "INSERT INTO whoop_tokens (id, blob_enc, whoop_user_id, updated_at) VALUES (1, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET blob_enc=excluded.blob_enc, "
                "whoop_user_id=COALESCE(excluded.whoop_user_id, whoop_tokens.whoop_user_id), "
                "updated_at=excluded.updated_at",
                (blob, whoop_user_id, int(time.time())),
            )

    def load_whoop_tokens(self) -> dict | None:
        row = self._db.execute("SELECT blob_enc FROM whoop_tokens WHERE id = 1").fetchone()
        return json.loads(self._fernet.decrypt(row[0])) if row else None

    def whoop_user_id(self) -> int | None:
        row = self._db.execute("SELECT whoop_user_id FROM whoop_tokens WHERE id = 1").fetchone()
        return row[0] if row else None

    # ---- webhook idempotency -------------------------------------------------
    def mark_event_seen(self, key: str) -> bool:
        """Returns True if this is the first time we see the event."""
        with self._lock:
            try:
                self._db.execute("INSERT INTO webhook_events VALUES (?, ?)", (key, int(time.time())))
                return True
            except sqlite3.IntegrityError:
                return False

    # ---- retention / deletion ------------------------------------------------
    def purge_expired(self, retention_days: int) -> None:
        now = int(time.time())
        with self._lock:
            self._db.execute("DELETE FROM pairing_codes WHERE expires_at <= ?", (now,))
            self._db.execute("DELETE FROM oauth_states WHERE expires_at <= ?", (now,))
            self._db.execute(
                "DELETE FROM webhook_events WHERE received_at < ?", (now - retention_days * 86400,)
            )

    def delete_all(self) -> None:
        with self._lock:
            for t in ("devices", "pairing_codes", "oauth_states", "whoop_tokens", "webhook_events"):
                self._db.execute(f"DELETE FROM {t}")  # noqa: S608 - fixed table names
