"""Defences for running on the public internet: rate limits, the one-time
setup code, upload sniffing and an ASGI guard for HTTPS, body sizes and
response headers."""
from __future__ import annotations

import json
import logging
import os
import secrets
import sys
import threading
import time
from collections import deque
from pathlib import Path
from typing import Optional

from starlette.exceptions import HTTPException

from . import config
from .auth import same_secret

audit = logging.getLogger("nook.audit")
if not audit.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(logging.Formatter("%(asctime)s nook %(levelname)s %(message)s"))
    audit.addHandler(_handler)
    audit.setLevel(logging.INFO)
    audit.propagate = False


class RateLimiter:
    """Sliding-window counters kept in memory (one server process).

    Each key keeps at most `limit` timestamps, and keys are pruned so a
    flood of distinct keys can't grow memory without bound.
    """

    MAX_KEYS = 50_000

    def __init__(self, limit: int, window: float) -> None:
        self.limit, self.window = max(1, limit), window
        self._hits: dict[str, deque] = {}
        self._lock = threading.Lock()

    def allowed(self, key: str) -> bool:
        return self.retry_after(key) == 0

    def retry_after(self, key: str) -> int:
        """Seconds until `key` may try again; 0 when it may now."""
        with self._lock:
            q = self._hits.get(key)
            if q is None or len(q) < self.limit:
                return 0
            wait = self.window - (time.monotonic() - q[0])
            return max(0, int(wait) + 1) if wait > 0 else 0

    def hit(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            q = self._hits.get(key)
            if q is None:
                if len(self._hits) >= self.MAX_KEYS:
                    self._prune(now)
                q = self._hits[key] = deque(maxlen=self.limit)
            q.append(now)

    def reset(self, key: str) -> None:
        with self._lock:
            self._hits.pop(key, None)

    def _prune(self, now: float) -> None:
        stale = [k for k, q in self._hits.items() if now - q[-1] > self.window]
        for k in stale:
            del self._hits[k]
        if len(self._hits) >= self.MAX_KEYS:
            # Still full: forget the keys that have been quiet the longest.
            by_age = sorted(self._hits, key=lambda k: self._hits[k][-1])
            for k in by_age[: len(by_age) // 2]:
                del self._hits[k]


def too_many(seconds: int) -> HTTPException:
    minutes = max(1, (seconds + 59) // 60)
    return HTTPException(429, f"尝试次数过多，请 {minutes} 分钟后再试", headers={"Retry-After": str(seconds)})


def safe_name(name: str) -> str:
    """A username as it may appear in the log (no control characters)."""
    return name if name.isprintable() and len(name) <= 64 else repr(name[:64])


# ---------------------------------------------------------------- setup code

_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


class SetupCode:
    """One-time code that claims a fresh server.

    While the server has no accounts (and registration is neither open nor
    invite-only) the first account needs this code, so whoever finds a new
    deployment first can't take it over. It is printed to the log and kept
    in DATA_DIR/setup-code until the first account exists.
    """

    def __init__(self, path: Path) -> None:
        self.path = path
        self._code: Optional[str] = None

    def prepare(self, has_users: bool) -> None:
        if has_users or config.ALLOW_REGISTRATION or config.INVITE_CODE:
            self.consume()
            return
        try:
            code = self.path.read_text().strip()
        except OSError:
            code = ""
        if len(normalize_code(code)) != 16:
            code = "-".join("".join(secrets.choice(_ALPHABET) for _ in range(4)) for _ in range(4))
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as fh:
                fh.write(code + "\n")
        self._code = code
        audit.warning("首次使用：注册第一个账号时填写注册码 %s（也保存在 %s）", code, self.path)

    @property
    def active(self) -> bool:
        return self._code is not None

    def matches(self, given: str) -> bool:
        if self._code is None:
            return False
        return same_secret(normalize_code(given), normalize_code(self._code))

    def consume(self) -> None:
        self._code = None
        self.path.unlink(missing_ok=True)


def normalize_code(code: str) -> str:
    return "".join(ch for ch in code.upper() if ch.isalnum())


# ---------------------------------------------------------------- uploads

_HEIF_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis", b"mif1", b"msf1"}


def sniff_image(head: bytes) -> Optional[str]:
    """The real type of an upload from its first bytes; None if not a photo."""
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    if head[4:8] == b"ftyp" and head[8:12] in _HEIF_BRANDS:
        return "image/heic"
    return None


# ---------------------------------------------------------------- ASGI guard

SECURITY_HEADERS = [
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"no-referrer"),
    (b"content-security-policy", b"default-src 'none'; frame-ancestors 'none'; sandbox"),
    (b"cross-origin-resource-policy", b"same-origin"),
]
HSTS = (b"strict-transport-security", b"max-age=31536000; includeSubDomains")


def body_limit(path: str) -> int:
    if path.startswith("/api/files/") or path == "/api/cutout":
        return config.MAX_FILE_BYTES
    if path == "/api/sync":
        return config.MAX_SYNC_BYTES
    return config.MAX_JSON_BYTES


class Guard:
    """Wraps every HTTP request: refuses plain HTTP when HTTPS is required,
    caps the request body (declared or streamed) per route, and adds
    security headers to every response."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        https = scope.get("scheme") == "https"
        path = scope.get("path", "")

        async def send_secured(message):
            if message["type"] == "http.response.start":
                headers = [(k, v) for k, v in message.get("headers", []) if k.lower() != b"server"]
                present = {k.lower() for k, _ in headers}
                headers += [h for h in SECURITY_HEADERS if h[0] not in present]
                if b"cache-control" not in present:
                    headers.append((b"cache-control", b"no-store"))
                if https:
                    headers.append(HSTS)
                message = {**message, "headers": headers}
            await send(message)

        if config.REQUIRE_HTTPS and not https and path != "/api/health":
            return await _reply(send_secured, 403, "请通过 HTTPS 访问")

        limit = body_limit(path)
        declared = dict(scope.get("headers", [])).get(b"content-length")
        if declared is not None:
            if not declared.isdigit():
                return await _reply(send_secured, 400, "请求格式不正确")
            if int(declared) > limit:
                return await _reply(send_secured, 413, "请求内容过大")

        received = 0

        async def receive_capped():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise HTTPException(413, "请求内容过大")
            return message

        await self.app(scope, receive_capped, send_secured)


async def _reply(send, status: int, detail: str) -> None:
    body = json.dumps({"detail": detail}, ensure_ascii=False).encode()
    await send({"type": "http.response.start", "status": status, "headers": [
        (b"content-type", b"application/json"), (b"content-length", str(len(body)).encode()),
        (b"connection", b"close"),
    ]})
    await send({"type": "http.response.body", "body": body})
