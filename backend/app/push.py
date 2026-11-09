"""FCM HTTP v1 'data-only' sync pings.

Privacy/security: payload carries NO health data and NO message text, only a
'sync' hint. The app then fetches /v1/today over authenticated TLS.
"""
from __future__ import annotations

import json
import logging

import anyio
import httpx

log = logging.getLogger(__name__)


class Pusher:
    def __init__(self, project_id: str | None, credentials_file: str | None,
                 credentials_json: str | None = None):
        self.project_id = project_id
        self._creds = None
        scopes = ["https://www.googleapis.com/auth/firebase.messaging"]
        if project_id and (credentials_file or credentials_json):
            from google.oauth2 import service_account  # lazy import

            if credentials_json:  # injected from a secret manager as an env var
                self._creds = service_account.Credentials.from_service_account_info(
                    json.loads(credentials_json), scopes=scopes
                )
            else:
                self._creds = service_account.Credentials.from_service_account_file(
                    credentials_file, scopes=scopes
                )

    @property
    def enabled(self) -> bool:
        return self._creds is not None

    def _bearer(self) -> str:
        from google.auth.transport.requests import Request

        if not self._creds.valid:
            self._creds.refresh(Request())
        return self._creds.token

    async def send_sync(self, http: httpx.AsyncClient, fcm_tokens: list[str], reason: str) -> None:
        if not self.enabled:
            log.info("push disabled; skipping sync ping")
            return
        if reason not in {"workout", "sleep", "recovery", "target"}:
            reason = "target"
        url = f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send"
        # google-auth refresh is blocking; keep it off the event loop.
        bearer = await anyio.to_thread.run_sync(self._bearer)
        headers = {"Authorization": f"Bearer {bearer}"}
        for tok in fcm_tokens:
            body = {"message": {"token": tok, "data": {"type": "sync", "reason": reason},
                                "android": {"priority": "high", "ttl": "3600s"}}}
            r = await http.post(url, json=body, headers=headers)
            if r.status_code >= 400:
                # Never log the token itself.
                log.warning("FCM send failed: HTTP %s", r.status_code)
