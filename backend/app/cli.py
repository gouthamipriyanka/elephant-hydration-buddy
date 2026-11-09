"""Admin CLI. Run inside the deployed container (not exposed over HTTP).

    python -m app.cli pair      -> prints a one-time pairing code (valid 10 min)
    python -m app.cli genkey    -> prints a new Fernet key for TOKEN_ENCRYPTION_KEY
    python -m app.cli check-messages
    python -m app.cli export-messages [path]  -> bundles validated messages into the Android app
"""
from __future__ import annotations

import sys

from cryptography.fernet import Fernet


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "genkey":
        print(Fernet.generate_key().decode())
        return 0
    if cmd == "export-messages":
        import json

        from .messages import load_messages

        out = argv[2] if len(argv) > 2 else "../android/app/src/main/assets/messages.json"
        with open(out, "w", encoding="utf-8") as f:
            json.dump(load_messages(), f, ensure_ascii=False, indent=1)
            f.write("\n")
        print(f"wrote {out}")
        return 0
    if cmd == "check-messages":
        from .messages import load_messages

        m = load_messages()
        print({k: len(v) for k, v in m.items()})
        return 0
    if cmd == "pair":
        from .config import load_settings
        from .store import Store

        s = load_settings()
        print(Store(s.database_path, s.token_encryption_key).create_pairing_code())
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
