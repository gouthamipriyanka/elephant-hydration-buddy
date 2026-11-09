"""Configuration from environment variables.

Secrets (WHOOP_CLIENT_SECRET, TOKEN_ENCRYPTION_KEY, FCM credentials) must be
injected by the platform's secret manager at runtime. Never commit them.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _req(name: str) -> str:
    val = os.environ.get(name)
    if not val:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return val


@dataclass(frozen=True)
class Settings:
    env: str  # dev | qa | uat | prod
    public_base_url: str  # e.g. https://hydrate.example.com (no trailing slash)
    whoop_client_id: str
    whoop_client_secret: str
    token_encryption_key: str  # Fernet key (urlsafe base64, 32 bytes)
    database_path: str
    fcm_project_id: str | None
    fcm_credentials_file: str | None
    fcm_credentials_json: str | None = None
    whoop_api_base: str = "https://api.prod.whoop.com/developer"
    whoop_auth_url: str = "https://api.prod.whoop.com/oauth/oauth2/auth"
    whoop_token_url: str = "https://api.prod.whoop.com/oauth/oauth2/token"  # noqa: S105 - URL, not a secret
    # Minimum scopes needed. 'offline' grants a refresh token.
    whoop_scopes: tuple[str, ...] = field(
        default=("offline", "read:workout", "read:sleep", "read:recovery", "read:body_measurement")
    )
    webhook_max_skew_seconds: int = 300
    data_retention_days: int = 90

    @property
    def redirect_uri(self) -> str:
        return f"{self.public_base_url}/whoop/callback"


def load_settings() -> Settings:
    env = os.environ.get("APP_ENV", "dev")
    base = _req("PUBLIC_BASE_URL").rstrip("/")
    if env != "dev" and not base.startswith("https://"):
        raise RuntimeError("PUBLIC_BASE_URL must be https outside dev")
    return Settings(
        env=env,
        public_base_url=base,
        whoop_client_id=_req("WHOOP_CLIENT_ID"),
        whoop_client_secret=_req("WHOOP_CLIENT_SECRET"),
        token_encryption_key=_req("TOKEN_ENCRYPTION_KEY"),
        database_path=os.environ.get("DATABASE_PATH", "./data/hydration.db"),
        fcm_project_id=os.environ.get("FCM_PROJECT_ID"),
        fcm_credentials_file=os.environ.get("FCM_CREDENTIALS_FILE"),
        fcm_credentials_json=os.environ.get("FCM_CREDENTIALS_JSON"),
    )
