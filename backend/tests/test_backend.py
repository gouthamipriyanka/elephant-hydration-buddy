import base64
import hashlib
import hmac
import json
import time
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.messages import MessageValidationError, load_messages
from app.security import verify_whoop_signature
from app.targets import TargetConfig, Workout, daily_target_ml, expected_by_now_ml, mood

SECRET = "test-client-secret"


# ---------------- targets ----------------
def test_base_target_from_weight():
    assert daily_target_ml(80, []) == 2800  # 80 * 35


def test_fallback_weight_when_missing_or_absurd():
    assert daily_target_ml(None, []) == daily_target_ml(75, [])
    assert daily_target_ml(999, []) == daily_target_ml(75, [])


def test_workout_adds_and_high_strain_multiplies():
    t0 = datetime(2026, 1, 1, 10, tzinfo=UTC)
    easy = Workout(t0, t0 + timedelta(hours=1), strain=8)
    hard = Workout(t0, t0 + timedelta(hours=1), strain=16)
    assert daily_target_ml(80, [easy]) == 3300
    assert daily_target_ml(80, [hard]) == 3450  # 2800 + 650


def test_target_is_capped_for_safety():
    t0 = datetime(2026, 1, 1, tzinfo=UTC)
    marathon = [Workout(t0, t0 + timedelta(hours=5), strain=20)] * 3
    assert daily_target_ml(120, marathon) == TargetConfig().max_target_ml


def test_negative_duration_ignored():
    t0 = datetime(2026, 1, 1, 10, tzinfo=UTC)
    assert daily_target_ml(80, [Workout(t0, t0 - timedelta(hours=2))]) == 2800


def test_prorating_and_mood():
    noon = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
    exp = expected_by_now_ml(3000, noon)
    assert 0 < exp < 3000
    assert expected_by_now_ml(3000, noon.replace(hour=5)) == 0
    assert expected_by_now_ml(3000, noon.replace(hour=23)) == 3000
    assert mood(exp, exp) == "happy"
    assert mood(int(exp * 0.8), exp) == "neutral"
    assert mood(int(exp * 0.5), exp) == "thirsty"


# ---------------- messages ----------------
def test_shipped_messages_are_valid():
    m = load_messages()
    assert sum(len(m[p]) for p in ("reminder", "behind", "kudos", "goal_met", "bottle")) >= 70
    assert len(m["facts"]) >= 8


@pytest.mark.parametrize("bad", [
    "Click https://evil.example to claim",
    "visit www.evil.com",
    "go to evil.com now",
    "<script>alert(1)</script>",
    "x" * 201,
])
def test_messages_reject_links_and_markup(tmp_path, bad):
    p = tmp_path / "m.yaml"
    pools = {k: ["ok"] for k in ("reminder", "behind", "kudos", "goal_met", "bottle")}
    pools["kudos"] = [bad]
    p.write_text(json.dumps(pools))
    with pytest.raises(MessageValidationError):
        load_messages(p)


# ---------------- webhook signature ----------------
def _sign(body: bytes, ts: str) -> str:
    return base64.b64encode(hmac.new(SECRET.encode(), ts.encode() + body, hashlib.sha256).digest()).decode()


def test_signature_valid_and_tampered():
    body = b'{"a":1}'
    ts = str(int(time.time() * 1000))
    assert verify_whoop_signature(SECRET, body, _sign(body, ts), ts)
    assert not verify_whoop_signature(SECRET, b'{"a":2}', _sign(body, ts), ts)
    assert not verify_whoop_signature(SECRET, body, None, ts)


def test_signature_replay_rejected():
    body = b"{}"
    old = str(int((time.time() - 3600) * 1000))
    assert not verify_whoop_signature(SECRET, body, _sign(body, old), old)


# ---------------- API ----------------
WHOOP_CALLS: list[str] = []


def whoop_mock(request: httpx.Request) -> httpx.Response:
    WHOOP_CALLS.append(request.url.path)
    p = request.url.path
    if p.endswith("/oauth2/token"):
        return httpx.Response(200, json={"access_token": "at", "refresh_token": "rt", "expires_in": 3600})
    if p.endswith("/user/profile/basic"):
        return httpx.Response(200, json={"user_id": 42})
    if p.endswith("/user/measurement/body"):
        return httpx.Response(200, json={"weight_kilogram": 80})
    if p.endswith("/activity/workout"):
        now = datetime.now(UTC)
        return httpx.Response(200, json={"records": [
            {"start": (now - timedelta(hours=1)).isoformat(), "end": now.isoformat(), "score": {"strain": 8}}
        ]})
    return httpx.Response(404)


@pytest.fixture
def client(tmp_path):
    s = Settings(
        env="qa", public_base_url="https://hydrate.test", whoop_client_id="cid",
        whoop_client_secret=SECRET, token_encryption_key=Fernet.generate_key().decode(),
        database_path=str(tmp_path / "db.sqlite"), fcm_project_id=None, fcm_credentials_file=None,
    )
    http = httpx.AsyncClient(transport=httpx.MockTransport(whoop_mock))
    app = create_app(s, http)
    with TestClient(app) as c:
        c.store = app.state.store
        yield c


def _paired(client) -> dict:
    code = client.store.create_pairing_code()
    tok = client.post("/v1/pair", json={"code": code}).json()["device_token"]
    return {"Authorization": f"Bearer {tok}"}


def test_requires_auth(client):
    assert client.get("/v1/messages").status_code == 401
    assert client.get("/v1/messages", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_pairing_code_single_use(client):
    code = client.store.create_pairing_code()
    assert client.post("/v1/pair", json={"code": code}).status_code == 200
    assert client.post("/v1/pair", json={"code": code}).status_code == 400


def test_docs_disabled_outside_dev(client):
    assert client.get("/docs").status_code == 404


def test_security_headers(client):
    r = client.get("/healthz")
    assert r.headers["X-Content-Type-Options"] == "nosniff"
    assert "Strict-Transport-Security" in r.headers


def test_today_fallback_then_whoop(client):
    h = _paired(client)
    r = client.get("/v1/today", params={"tz": "Asia/Kolkata"}, headers=h).json()
    assert r["source"] == "fallback" and r["target_ml"] == 2600  # 75kg fallback, rounded

    url = client.get("/v1/whoop/connect", headers=h).json()["authorize_url"]
    state = httpx.URL(url).params["state"]
    assert client.get("/whoop/callback", params={"code": "c", "state": "forged"}).status_code == 400
    assert client.get("/whoop/callback", params={"code": "c", "state": state}).status_code == 200
    # state is single-use
    assert client.get("/whoop/callback", params={"code": "c", "state": state}).status_code == 400

    r = client.get("/v1/today", params={"tz": "Asia/Kolkata"}, headers=h).json()
    assert r["source"] == "whoop" and r["target_ml"] >= 2800


def test_today_rejects_bad_tz(client):
    h = _paired(client)
    assert client.get("/v1/today", params={"tz": "../../etc/passwd"}, headers=h).status_code == 400


def test_webhook_auth_and_dedupe(client):
    body = json.dumps({"user_id": 42, "id": "abc", "type": "workout.updated", "trace_id": "t"}).encode()
    ts = str(int(time.time() * 1000))
    assert client.post("/webhooks/whoop", content=body).status_code == 401
    hdr = {"X-WHOOP-Signature": _sign(body, ts), "X-WHOOP-Signature-Timestamp": ts}
    assert client.post("/webhooks/whoop", content=body, headers=hdr).status_code == 204
    assert client.post("/webhooks/whoop", content=body, headers=hdr).status_code == 204  # idempotent


def test_delete_me_wipes_everything(client):
    h = _paired(client)
    client.store.save_whoop_tokens({"access_token": "x", "expires_at": time.time() + 999})
    assert client.delete("/v1/me", headers=h).status_code == 204
    assert client.store.load_whoop_tokens() is None
    assert client.get("/v1/messages", headers=h).status_code == 401
