"""Runtime configuration, read once from environment variables.

Every knob has a default that works for a single-household deployment;
see `.env.example` for the full list.
"""
from __future__ import annotations

import os
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


VERSION = "1.0.0"

DATA_DIR = Path(os.environ.get("NOOK_DATA_DIR", "/data"))

# Registration stays open for the very first account even when this is off,
# so a fresh deployment can always be claimed by its owner.
ALLOW_REGISTRATION = _bool("NOOK_ALLOW_REGISTRATION", True)

MAX_FILE_BYTES = int(os.environ.get("NOOK_MAX_FILE_MB", "20")) * 1024 * 1024
MAX_RECORD_BYTES = 512 * 1024
MAX_CHANGES_PER_SYNC = 1000

# AI icon drawing (Claude). The Anthropic SDK reads ANTHROPIC_API_KEY and
# ANTHROPIC_BASE_URL itself; these only tune the request.
ICON_MODEL = os.environ.get("NOOK_ICON_MODEL", "claude-opus-5-5")
ICON_EFFORT = os.environ.get("NOOK_ICON_EFFORT", "medium").strip()
ICON_FALLBACKS = _bool("NOOK_ICON_FALLBACKS", True)
ICON_RATE_PER_HOUR = int(os.environ.get("NOOK_ICON_RATE_PER_HOUR", "60"))

# Server-side background removal fallback (only when rembg is installed).
CUTOUT_MODEL = os.environ.get("NOOK_CUTOUT_MODEL", "isnet-general-use")
