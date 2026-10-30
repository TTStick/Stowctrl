"""Runtime configuration, read once from environment variables.

Every knob has a default that is safe to put on the public internet behind
the bundled HTTPS proxy; see `.env.example` for the full list.
"""
from __future__ import annotations

import os
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def _int(name: str, default: int) -> int:
    value = os.environ.get(name, "").strip()
    return int(value) if value else default


VERSION = "1.1.0"

DATA_DIR = Path(os.environ.get("NOOK_DATA_DIR", "/data"))

# Who may create accounts:
# - NOOK_ALLOW_REGISTRATION=true: anyone who can reach the server.
# - NOOK_INVITE_CODE set: anyone who knows the code (share it with family).
# - neither: nobody, except the very first account, which needs the one-time
#   setup code the server prints to its log (and writes to DATA_DIR/setup-code).
ALLOW_REGISTRATION = _bool("NOOK_ALLOW_REGISTRATION", False)
INVITE_CODE = os.environ.get("NOOK_INVITE_CODE", "").strip()
MAX_USERS = _int("NOOK_MAX_USERS", 20)

PASSWORD_MIN = 8
PASSWORD_MAX = 128

# Refuse plain-HTTP requests (except the health check). Behind the bundled
# Caddy proxy every request arrives with X-Forwarded-Proto: https; uvicorn
# only believes that header from FORWARDED_ALLOW_IPS (default 127.0.0.1).
REQUIRE_HTTPS = _bool("NOOK_REQUIRE_HTTPS", False)

# Sessions not used for this long are signed out.
SESSION_IDLE_MS = _int("NOOK_SESSION_IDLE_DAYS", 90) * 86_400_000
MAX_SESSIONS_PER_USER = 30

# Request and storage limits.
MAX_FILE_BYTES = _int("NOOK_MAX_FILE_MB", 20) * 1024 * 1024
MAX_SYNC_BYTES = _int("NOOK_MAX_SYNC_MB", 32) * 1024 * 1024
MAX_JSON_BYTES = 64 * 1024
MAX_RECORD_BYTES = 512 * 1024
MAX_CHANGES_PER_SYNC = 1000
USER_QUOTA_BYTES = _int("NOOK_USER_QUOTA_MB", 4096) * 1024 * 1024
MAX_RECORDS_PER_USER = _int("NOOK_MAX_RECORDS", 200_000)

# Brute-force protection for logins and anything else that checks a secret.
LOGIN_FAILS_PER_ACCOUNT_IP = 5     # per 15 minutes
LOGIN_FAILS_PER_IP = 20            # per 15 minutes, across usernames
LOGIN_FAILS_PER_ACCOUNT = 50       # per hour, across IPs
CODE_FAILS_PER_IP = 10             # wrong setup/invite codes per hour

# AI icon drawing (Claude). The Anthropic SDK reads ANTHROPIC_API_KEY and
# ANTHROPIC_BASE_URL itself; these only tune the request.
ICON_MODEL = os.environ.get("NOOK_ICON_MODEL", "claude-opus-5-5")
ICON_EFFORT = os.environ.get("NOOK_ICON_EFFORT", "medium").strip()
ICON_FALLBACKS = _bool("NOOK_ICON_FALLBACKS", True)
ICON_RATE_PER_HOUR = _int("NOOK_ICON_RATE_PER_HOUR", 60)
ICON_RATE_PER_DAY_TOTAL = _int("NOOK_ICON_RATE_PER_DAY", 300)

# Server-side background removal fallback (only when rembg is installed).
CUTOUT_MODEL = os.environ.get("NOOK_CUTOUT_MODEL", "isnet-general-use")
CUTOUT_RATE_PER_HOUR = _int("NOOK_CUTOUT_RATE_PER_HOUR", 120)
MAX_IMAGE_PIXELS = 40_000_000


def problems() -> list[str]:
    """Settings that would leave a public server open; checked at startup."""
    out = []
    if INVITE_CODE and len(INVITE_CODE) < 8:
        out.append("NOOK_INVITE_CODE 至少需要 8 个字符")
    return out
