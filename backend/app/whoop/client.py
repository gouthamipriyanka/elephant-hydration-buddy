"""Minimal WHOOP API v2 client (read-only).

VERIFY against https://developer.whoop.com before production: endpoint paths
and field names below follow the v2 API as publicly documented at the time of
writing, but could not be re-checked live from the build environment.
"""
from __future__ import annotations

import logging
import time
import urllib.parse
from datetime import datetime

import httpx

from ..config import Settings
from ..store import Store
from ..targets import Workout

log = logging.getLogger(__name__)


class WhoopNotConnected(Exception):
    pass


class WhoopAuthError(Exception):
    pass


def authorization_url(settings: Settings, state: str) -> str:
    q = {
        "response_type": "code",
        "client_id": settings.whoop_client_id,
        "redirect_uri": settings.redirect_uri,
        "scope": " ".join(settings.whoop_scopes),
        "state": state,
    }
    return f"{settings.whoop_auth_url}?{urllib.parse.urlencode(q)}"


def _with_expiry(tokens: dict) -> dict:
    tokens = dict(tokens)
    tokens["expires_at"] = int(time.time()) + int(tokens.get("expires_in", 3600)) - 60
    return tokens


async def exchange_code(settings: Settings, http: httpx.AsyncClient, code: str) -> dict:
    r = await http.post(
        settings.whoop_token_url,
        data={
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": settings.redirect_uri,
            "client_id": settings.whoop_client_id,
            "client_secret": settings.whoop_client_secret,
        },
    )
    if r.status_code != 200:
        raise WhoopAuthError(f"token exchange failed: HTTP {r.status_code}")
    return _with_expiry(r.json())


class WhoopClient:
    def __init__(self, settings: Settings, store: Store, http: httpx.AsyncClient):
        self.s = settings
        self.store = store
        self.http = http

    async def _access_token(self) -> str:
        tokens = self.store.load_whoop_tokens()
        if not tokens:
            raise WhoopNotConnected()
        if tokens.get("expires_at", 0) > time.time():
            return tokens["access_token"]
        if not tokens.get("refresh_token"):
            raise WhoopAuthError("no refresh token; reconnect required")
        r = await self.http.post(
            self.s.whoop_token_url,
            data={
                "grant_type": "refresh_token",
                "refresh_token": tokens["refresh_token"],
                "client_id": self.s.whoop_client_id,
                "client_secret": self.s.whoop_client_secret,
                "scope": "offline",
            },
        )
        if r.status_code != 200:
            raise WhoopAuthError(f"refresh failed: HTTP {r.status_code}")
        new = _with_expiry(r.json())
        # WHOOP rotates refresh tokens; keep the old one only if none is returned.
        new.setdefault("refresh_token", tokens["refresh_token"])
        self.store.save_whoop_tokens(new)
        return new["access_token"]

    async def _get(self, path: str, params: dict | None = None) -> dict:
        token = await self._access_token()
        r = await self.http.get(
            f"{self.s.whoop_api_base}{path}",
            params=params,
            headers={"Authorization": f"Bearer {token}"},
        )
        if r.status_code == 401:
            raise WhoopAuthError("unauthorized; reconnect required")
        r.raise_for_status()
        return r.json()

    async def profile_user_id(self) -> int | None:
        data = await self._get("/v2/user/profile/basic")
        return data.get("user_id")

    async def body_weight_kg(self) -> float | None:
        data = await self._get("/v2/user/measurement/body")
        w = data.get("weight_kilogram")
        return float(w) if w is not None else None

    async def workouts_between(self, start: datetime, end: datetime) -> list[Workout]:
        out: list[Workout] = []
        params: dict = {"start": start.isoformat(), "end": end.isoformat(), "limit": 25}
        for _ in range(10):  # hard page cap: defensive against pagination loops
            data = await self._get("/v2/activity/workout", params)
            for rec in data.get("records", []):
                try:
                    out.append(Workout(
                        start=datetime.fromisoformat(rec["start"].replace("Z", "+00:00")),
                        end=datetime.fromisoformat(rec["end"].replace("Z", "+00:00")),
                        strain=(rec.get("score") or {}).get("strain"),
                    ))
                except (KeyError, ValueError, AttributeError):
                    continue  # skip malformed record rather than failing the day
            nxt = data.get("next_token")
            if not nxt:
                break
            params["nextToken"] = nxt
        return out

    async def revoke(self) -> None:
        """Best-effort revoke of the user's grant (VERIFY endpoint in WHOOP docs)."""
        try:
            token = await self._access_token()
            await self.http.delete(
                f"{self.s.whoop_api_base}/v2/user/access",
                headers={"Authorization": f"Bearer {token}"},
            )
        except Exception:  # noqa: BLE001 - deletion must proceed even if revoke fails
            log.warning("WHOOP revoke failed; local data is still deleted. Revoke manually in WHOOP app.")
