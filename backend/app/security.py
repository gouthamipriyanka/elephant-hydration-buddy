"""Webhook signature verification and simple rate limiting."""
from __future__ import annotations

import base64
import hashlib
import hmac
import threading
import time


def verify_whoop_signature(
    secret: str,
    raw_body: bytes,
    signature_b64: str | None,
    timestamp_ms: str | None,
    max_skew_seconds: int = 300,
    now: float | None = None,
) -> bool:
    """WHOOP signs webhooks as base64(HMAC-SHA256(client_secret, timestamp + body)).

    Headers: X-WHOOP-Signature and X-WHOOP-Signature-Timestamp (ms since epoch).
    VERIFY the exact scheme in WHOOP's webhook docs before go-live.
    Replay protection: reject timestamps outside +/- max_skew_seconds.
    """
    if not signature_b64 or not timestamp_ms:
        return False
    try:
        ts = int(timestamp_ms) / 1000.0
    except ValueError:
        return False
    now = time.time() if now is None else now
    if abs(now - ts) > max_skew_seconds:
        return False
    expected = base64.b64encode(
        hmac.new(secret.encode(), timestamp_ms.encode() + raw_body, hashlib.sha256).digest()
    ).decode()
    return hmac.compare_digest(expected, signature_b64)


class RateLimiter:
    """In-process token bucket. Fine for a single instance (see store.py note)."""

    def __init__(self, rate_per_minute: int = 60, burst: int = 20):
        self.rate = rate_per_minute / 60.0
        self.burst = burst
        self._buckets: dict[str, tuple[float, float]] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            tokens, last = self._buckets.get(key, (float(self.burst), now))
            tokens = min(self.burst, tokens + (now - last) * self.rate)
            if tokens < 1:
                self._buckets[key] = (tokens, now)
                return False
            self._buckets[key] = (tokens - 1, now)
            if len(self._buckets) > 10_000:  # bound memory under abuse
                self._buckets.clear()
            return True
