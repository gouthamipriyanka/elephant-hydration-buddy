"""Loads and validates the message pools.

Validation is a security control: messages reach his lock screen, so they must
be short plain text with no links (a tampered pool must not become a phishing
vector). The Android app re-validates on its side as defence in depth.
"""
from __future__ import annotations

import re
from pathlib import Path

import yaml

POOLS = ("reminder", "behind", "kudos", "goal_met", "bottle")
MAX_LEN = 200
_URL_RE = re.compile(r"(https?://|www\.|\b[a-z0-9-]+\.(com|net|org|io|ly|me|app|link)\b)", re.I)
_CONTROL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f<>]")
_ALLOWED_FACT_HOSTS = ("www.cdc.gov", "www.mayoclinic.org", "www.nhs.uk", "www.canr.msu.edu")

DEFAULT_PATH = Path(__file__).with_name("messages.yaml")


class MessageValidationError(ValueError):
    pass


def _check_text(text: object, where: str) -> str:
    if not isinstance(text, str) or not text.strip():
        raise MessageValidationError(f"{where}: must be non-empty text")
    if len(text) > MAX_LEN:
        raise MessageValidationError(f"{where}: longer than {MAX_LEN} chars")
    if _URL_RE.search(text):
        raise MessageValidationError(f"{where}: links are not allowed in messages")
    if _CONTROL_RE.search(text):
        raise MessageValidationError(f"{where}: control characters / angle brackets not allowed")
    return text.strip()


def load_messages(path: Path = DEFAULT_PATH) -> dict:
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise MessageValidationError("messages file must be a mapping")
    out: dict = {}
    for pool in POOLS:
        items = raw.get(pool)
        if not isinstance(items, list) or not items:
            raise MessageValidationError(f"pool '{pool}' must be a non-empty list")
        out[pool] = [_check_text(t, f"{pool}[{i}]") for i, t in enumerate(items)]

    facts = []
    for i, f in enumerate(raw.get("facts") or []):
        if not isinstance(f, dict):
            raise MessageValidationError(f"facts[{i}] must be a mapping")
        url = f.get("source_url", "")
        if not isinstance(url, str) or not url.startswith("https://") or \
                url.split("/")[2] not in _ALLOWED_FACT_HOSTS:
            raise MessageValidationError(f"facts[{i}]: source_url must be https on an allowed host")
        facts.append({
            "text": _check_text(f.get("text"), f"facts[{i}].text"),
            "source": _check_text(f.get("source"), f"facts[{i}].source"),
            # Source URL is shown in-app (About screen) as plain text, never as a tappable link
            # inside a notification.
            "source_url": url,
        })
    out["facts"] = facts

    unknown = set(raw) - set(POOLS) - {"facts"}
    if unknown:
        raise MessageValidationError(f"unknown pools: {sorted(unknown)}")
    return out
