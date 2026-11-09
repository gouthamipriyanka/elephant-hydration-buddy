"""Elephant Hydration Buddy backend.

Responsibilities (deliberately small attack surface):
  * Pair the phone (one-time code -> device token)
  * WHOOP OAuth (keeps client secret off the phone) + encrypted token storage
  * Compute today's target from WHOOP data
  * Serve validated message pools
  * Receive WHOOP webhooks -> data-only FCM "sync" ping
  * Full data deletion
It never receives location data or intake logs (data minimisation).
"""
from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

from .config import Settings, load_settings
from .messages import load_messages
from .push import Pusher
from .security import RateLimiter, verify_whoop_signature
from .store import Store
from .targets import daily_target_ml
from .whoop.client import (
    WhoopAuthError,
    WhoopClient,
    WhoopNotConnected,
    authorization_url,
    exchange_code,
)

log = logging.getLogger("hydration")
MAX_WEBHOOK_BYTES = 64 * 1024


class PairIn(BaseModel):
    code: str = Field(min_length=8, max_length=64)


class FcmIn(BaseModel):
    token: str = Field(min_length=20, max_length=4096)


def create_app(settings: Settings | None = None, http: httpx.AsyncClient | None = None) -> FastAPI:
    settings = settings or load_settings()
    store = Store(settings.database_path, settings.token_encryption_key)
    messages = load_messages()  # fail fast at startup if the pool is invalid
    pusher = Pusher(settings.fcm_project_id, settings.fcm_credentials_file, settings.fcm_credentials_json)
    limiter = RateLimiter(rate_per_minute=60, burst=20)
    pair_limiter = RateLimiter(rate_per_minute=5, burst=5)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.http = http or httpx.AsyncClient(timeout=15.0)
        store.purge_expired(settings.data_retention_days)
        yield
        if http is None:
            await app.state.http.aclose()

    is_prod_like = settings.env != "dev"  # API docs only in local dev
    app = FastAPI(
        title="Elephant Hydration Buddy",
        lifespan=lifespan,
        docs_url=None if is_prod_like else "/docs",
        redoc_url=None,
        openapi_url=None if is_prod_like else "/openapi.json",
    )
    app.state.store = store

    # ---- middleware ---------------------------------------------------------
    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        client = request.client.host if request.client else "unknown"
        if not limiter.allow(client):
            return JSONResponse({"detail": "rate limited"}, status_code=429)
        resp = await call_next(request)
        resp.headers["Strict-Transport-Security"] = "max-age=63072000; includeSubDomains"
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["Referrer-Policy"] = "no-referrer"
        resp.headers["Cache-Control"] = "no-store"
        resp.headers["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'"
        resp.headers["X-Frame-Options"] = "DENY"
        return resp

    # ---- auth ---------------------------------------------------------------
    def device(authorization: str = Header(default="")) -> str:
        if not authorization.startswith("Bearer "):
            raise HTTPException(401, "missing token")
        dh = store.device_exists(authorization.removeprefix("Bearer ").strip())
        if not dh:
            raise HTTPException(401, "invalid token")
        return dh

    def whoop() -> WhoopClient:
        return WhoopClient(settings, store, app.state.http)

    # ---- routes -------------------------------------------------------------
    @app.get("/healthz")
    async def healthz():
        return {"ok": True}

    @app.post("/v1/pair")
    async def pair(body: PairIn, request: Request):
        ip = request.client.host if request.client else "unknown"
        if not pair_limiter.allow(ip):
            raise HTTPException(429, "too many attempts")
        token = store.redeem_pairing_code(body.code)
        if not token:
            raise HTTPException(400, "invalid or expired code")
        return {"device_token": token}

    @app.put("/v1/fcm-token", status_code=204)
    async def fcm_token(body: FcmIn, dh: str = Depends(device)):
        store.set_fcm_token(dh, body.token)

    @app.get("/v1/whoop/connect")
    async def whoop_connect(dh: str = Depends(device)):
        return {"authorize_url": authorization_url(settings, store.create_oauth_state(dh))}

    @app.get("/whoop/callback", response_class=HTMLResponse)
    async def whoop_callback(code: str = "", state: str = "", error: str = ""):
        # Never reflect query params into the page.
        if error or not code or not state or not store.consume_oauth_state(state):
            return HTMLResponse(_page("Connection was not completed. Please try again from the app."), 400)
        try:
            tokens = await exchange_code(settings, app.state.http, code)
        except WhoopAuthError:
            return HTMLResponse(_page("WHOOP connection failed. Please try again from the app."), 400)
        store.save_whoop_tokens(tokens)
        try:
            store.save_whoop_tokens(tokens, await whoop().profile_user_id())
        except Exception:  # noqa: BLE001 - user id is only used to match webhooks
            log.warning("could not fetch WHOOP profile id")
        return HTMLResponse(_page("WHOOP connected 🐘💙 You can close this page and return to the app."))

    @app.get("/v1/whoop/status")
    async def whoop_status(dh: str = Depends(device)):
        return {"connected": store.load_whoop_tokens() is not None}

    @app.get("/v1/today")
    async def today(tz: str = "UTC", manual_weight_kg: float | None = None,
                    dh: str = Depends(device), wc: WhoopClient = Depends(whoop)):
        try:
            zone = ZoneInfo(tz)
        except (ZoneInfoNotFoundError, ValueError):
            raise HTTPException(400, "invalid tz") from None
        if manual_weight_kg is not None and not 30 <= manual_weight_kg <= 250:
            raise HTTPException(400, "invalid weight")
        now = datetime.now(zone)
        start = datetime.combine(now.date(), time.min, tzinfo=zone)
        weight, workouts, source = manual_weight_kg, [], "fallback"
        try:
            weight = await wc.body_weight_kg() or weight
            workouts = await wc.workouts_between(start, start + timedelta(days=1))
            source = "whoop"
        except WhoopNotConnected:
            pass
        except (WhoopAuthError, httpx.HTTPError):
            source = "fallback_whoop_error"  # app shows a gentle "reconnect WHOOP" prompt
        return {
            "date": now.date().isoformat(),
            "target_ml": daily_target_ml(weight, workouts),
            "workout_minutes": int(sum(w.hours for w in workouts) * 60),
            "source": source,
        }

    @app.get("/v1/messages")
    async def get_messages(dh: str = Depends(device)):
        return messages

    @app.post("/webhooks/whoop", status_code=204)
    async def whoop_webhook(request: Request):
        raw = await request.body()
        if len(raw) > MAX_WEBHOOK_BYTES:
            raise HTTPException(413)
        if not verify_whoop_signature(
            settings.whoop_client_secret, raw,
            request.headers.get("X-WHOOP-Signature"),
            request.headers.get("X-WHOOP-Signature-Timestamp"),
            settings.webhook_max_skew_seconds,
        ):
            raise HTTPException(401)
        try:
            evt = json.loads(raw)
            etype, eid = str(evt["type"]), str(evt["id"])
        except (ValueError, KeyError, TypeError):
            raise HTTPException(400) from None
        expected_user = store.whoop_user_id()
        if expected_user is not None and evt.get("user_id") != expected_user:
            return None  # not our user: ignore silently
        if not store.mark_event_seen(f"{etype}:{eid}:{evt.get('trace_id', '')}"):
            return None  # duplicate delivery
        kind = etype.split(".")[0]
        await pusher.send_sync(app.state.http, store.fcm_tokens(), kind)
        return None

    @app.delete("/v1/me", status_code=204)
    async def delete_me(dh: str = Depends(device), wc: WhoopClient = Depends(whoop)):
        await wc.revoke()
        store.delete_all()

    return app


def _page(msg: str) -> str:
    # Static text only; msg is always a server-side constant.
    return ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
            "<title>Hydration Buddy</title><body style='font-family:sans-serif;padding:2em'>"
            f"<p>{msg}</p></body>")
