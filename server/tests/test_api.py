"""End-to-end API tests. A tiny fake Anthropic endpoint stands in for the
real API so the icon route is exercised without network or cost."""
from __future__ import annotations

import json
import os
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

FAKE_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" onload="alert(1)">'
    '<script>alert(1)</script>'
    '<path d="M10 10 L38 38" stroke="#ff0000" stroke-width="2.5" fill="none" style="x"/>'
    '<circle cx="24" cy="24" r="6" fill="red"/>'
    '<image href="http://evil"/>'
    "</svg>"
)
captured: dict = {}
INVITE = "family-invite-2026"


class _FakeAnthropic(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("content-length", 0))
        captured["body"] = json.loads(self.rfile.read(length))
        captured["beta"] = self.headers.get("anthropic-beta")
        payload = {
            "id": "msg_test", "type": "message", "role": "assistant",
            "model": captured["body"]["model"],
            "content": [{"type": "text", "text": "Here you go:\n" + FAKE_SVG}],
            "stop_reason": "end_turn", "stop_sequence": None,
            "usage": {"input_tokens": 10, "output_tokens": 20},
        }
        raw = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


_server = HTTPServer(("127.0.0.1", 0), _FakeAnthropic)
threading.Thread(target=_server.serve_forever, daemon=True).start()

os.environ["NOOK_DATA_DIR"] = tempfile.mkdtemp()
os.environ["NOOK_INVITE_CODE"] = INVITE
os.environ["NOOK_MAX_USERS"] = "100"
os.environ["NOOK_USER_QUOTA_MB"] = "2"
os.environ["ANTHROPIC_API_KEY"] = "test-key"
os.environ["ANTHROPIC_BASE_URL"] = f"http://127.0.0.1:{_server.server_port}"

from fastapi.testclient import TestClient  # noqa: E402

from app import auth, config, main  # noqa: E402
from app.main import app  # noqa: E402
from app.security import RateLimiter  # noqa: E402

client = TestClient(app)
JPEG = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00"


def register(name: str, password: str = "secret123") -> dict:
    r = client.post("/api/auth/register", json={"username": name, "password": password, "deviceName": "t",
                                                "inviteCode": INVITE})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}"}


def test_info_and_health():
    assert client.get("/api/health").json() == {"ok": True}
    info = client.get("/api/info").json()
    assert info["features"]["icons"] is True
    assert info["registrationOpen"] is True and info["registration"] == "code"
    assert info["passwordMin"] == 8


def test_register_login_errors():
    register("alice")
    reg = {"password": "secret123", "inviteCode": INVITE}
    assert client.post("/api/auth/register", json={**reg, "username": "alice"}).status_code == 409
    assert client.post("/api/auth/register", json={**reg, "username": "a"}).status_code == 400
    assert client.post("/api/auth/register", json={**reg, "username": "bobby", "password": "1234567"}).status_code == 400
    assert client.post("/api/auth/login", json={"username": "alice", "password": "wrong"}).status_code == 401
    ok = client.post("/api/auth/login", json={"username": "ALICE", "password": "secret123"})
    assert ok.status_code == 200
    assert client.get("/api/me", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_sync_roundtrip_and_conflicts():
    h = register("syncer")
    room = {"entity": "room", "id": "r1", "updatedAt": 1000, "data": {"name": "客厅"}}
    r = client.post("/api/sync", headers=h, json={"cursor": 0, "changes": [room]}).json()
    assert r["accepted"] == 1 and r["cursor"] == 1
    assert r["changes"][0]["data"]["name"] == "客厅"

    # A second device starting from zero sees the room.
    pulled = client.post("/api/sync", headers=h, json={"cursor": 0}).json()
    assert [c["id"] for c in pulled["changes"]] == ["r1"]

    # Older write loses, newer write wins.
    stale = {**room, "updatedAt": 900, "data": {"name": "旧"}}
    r = client.post("/api/sync", headers=h, json={"cursor": 1, "changes": [stale]}).json()
    assert r["accepted"] == 0 and r["changes"] == []
    newer = {**room, "updatedAt": 2000, "data": {"name": "新客厅"}}
    r = client.post("/api/sync", headers=h, json={"cursor": 1, "changes": [newer]}).json()
    assert r["accepted"] == 1 and r["changes"][0]["data"]["name"] == "新客厅"

    # Deletion travels as a tombstone.
    tomb = {"entity": "room", "id": "r1", "updatedAt": 3000, "deleted": True}
    r = client.post("/api/sync", headers=h, json={"cursor": 2, "changes": [tomb]}).json()
    assert r["changes"][0]["deleted"] is True and r["changes"][0]["data"] is None

    # A cursor from the future (server restored from backup) asks for a reset.
    assert client.post("/api/sync", headers=h, json={"cursor": 99}).json().get("reset") is True

    # Other users never see this data.
    other = register("stranger")
    assert client.post("/api/sync", headers=other, json={"cursor": 0}).json()["changes"] == []


def test_sync_pagination():
    h = register("pager")
    changes = [{"entity": "item", "id": f"i{n}", "updatedAt": 1000 + n, "data": {"n": n}} for n in range(25)]
    client.post("/api/sync", headers=h, json={"cursor": 0, "changes": changes, "limit": 1})
    seen, cursor, more = [], 0, True
    while more:
        r = client.post("/api/sync", headers=h, json={"cursor": cursor, "limit": 10}).json()
        seen += [c["id"] for c in r["changes"]]
        cursor, more = r["cursor"], r["hasMore"]
    assert len(seen) == 25 and len(set(seen)) == 25


def test_sync_rejects_bad_ids():
    h = register("badids")
    bad = {"entity": "Room!", "id": "x", "updatedAt": 1}
    assert client.post("/api/sync", headers=h, json={"changes": [bad]}).status_code == 400
    bad = {"entity": "room", "id": "../etc", "updatedAt": 1}
    assert client.post("/api/sync", headers=h, json={"changes": [bad]}).status_code == 400


def test_files_roundtrip_and_isolation():
    h = register("filer")
    png = b"\x89PNG\r\n\x1a\nfake"
    r = client.put("/api/files/abc-123.png", headers={**h, "Content-Type": "image/png"}, content=png)
    assert r.status_code == 200, r.text
    got = client.get("/api/files/abc-123.png", headers=h)
    assert got.status_code == 200 and got.content == png and got.headers["content-type"] == "image/png"
    assert client.head("/api/files/abc-123.png", headers=h).status_code == 200
    assert client.get("/api/files/missing.png", headers=h).status_code == 404
    assert client.put("/api/files/..%2Fx", headers={**h, "Content-Type": "image/png"}, content=png).status_code in (400, 404)
    assert client.put("/api/files/a.txt", headers={**h, "Content-Type": "text/html"}, content=b"x").status_code == 415
    other = register("snooper")
    assert client.get("/api/files/abc-123.png", headers=other).status_code == 404


def test_icon_generation_sanitises_svg():
    h = register("artist")
    r = client.post("/api/icons/generate", headers=h, json={"prompt": "汤勺"})
    assert r.status_code == 200, r.text
    svg = r.json()["svg"]
    assert "<script" not in svg and "onload" not in svg and "<image" not in svg and "style" not in svg
    assert 'stroke="currentColor"' in svg and 'fill="currentColor"' in svg
    assert captured["body"]["model"] == "claude-opus-5-5"
    assert captured["body"]["fallbacks"] == "default"
    assert captured["body"]["output_config"] == {"effort": "medium"}
    assert "server-side-fallback-2026-07-01" in captured["beta"]
    assert "汤勺" in captured["body"]["messages"][0]["content"]
    assert client.post("/api/icons/generate", headers=h, json={"prompt": "  "}).status_code == 400


def test_cutout_disabled_without_rembg():
    h = register("cutter")
    r = client.post("/api/cutout", headers=h, content=b"img")
    assert r.status_code in (200, 500, 501)


def test_password_change_and_account_deletion():
    h = register("leaver", "secret123")
    second = client.post("/api/auth/login", json={"username": "leaver", "password": "secret123"}).json()
    h2 = {"Authorization": f"Bearer {second['token']}"}
    assert client.post("/api/auth/password", headers=h, json={"oldPassword": "bad", "newPassword": "newsecret"}).status_code == 401
    assert client.post("/api/auth/password", headers=h, json={"oldPassword": "secret123", "newPassword": "short"}).status_code == 400
    assert client.post("/api/auth/password", headers=h, json={"oldPassword": "secret123", "newPassword": "newsecret"}).status_code == 200
    assert client.get("/api/me", headers=h2).status_code == 401  # other device signed out
    assert client.get("/api/me", headers=h).status_code == 200
    client.post("/api/sync", headers=h, json={"changes": [{"entity": "room", "id": "z", "updatedAt": 1, "data": {}}]})
    assert client.post("/api/me/delete", headers=h, json={"password": "wrong"}).status_code == 401
    assert client.post("/api/me/delete", headers=h, json={"password": "newsecret"}).status_code == 200
    assert client.get("/api/me", headers=h).status_code == 401
    assert client.post("/api/auth/login", json={"username": "leaver", "password": "newsecret"}).status_code == 401


# ---------------------------------------------------------------- hardening

def _forget_failures():
    for limiter in (main.fails_account_ip, main.fails_ip, main.fails_account, main.code_fails):
        limiter._hits.clear()


def test_no_docs_and_security_headers():
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404
    r = client.get("/api/info")
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-frame-options"] == "DENY"
    assert "sandbox" in r.headers["content-security-policy"]
    assert r.headers["cache-control"] == "no-store"
    assert "strict-transport-security" not in r.headers  # plain HTTP here


def test_registration_needs_the_invite_code():
    body = {"username": "gatecrasher", "password": "secret123"}
    r = client.post("/api/auth/register", json=body)
    assert r.status_code == 403 and "注册码" in r.json()["detail"]
    for _ in range(config.CODE_FAILS_PER_IP - 1):
        assert client.post("/api/auth/register", json={**body, "inviteCode": "guess"}).status_code == 403
    # Too many wrong codes from this address: even the right one waits.
    r = client.post("/api/auth/register", json={**body, "inviteCode": INVITE})
    assert r.status_code == 429 and "retry-after" in r.headers
    _forget_failures()
    assert client.post("/api/auth/register", json={**body, "inviteCode": INVITE}).status_code == 200


def test_login_throttling():
    register("victim")
    wrong = {"username": "victim", "password": "wrong-password"}
    for _ in range(config.LOGIN_FAILS_PER_ACCOUNT_IP):
        assert client.post("/api/auth/login", json=wrong).status_code == 401
    # Locked for this account from this address, even with the right password.
    r = client.post("/api/auth/login", json={"username": "VICTIM", "password": "secret123"})
    assert r.status_code == 429
    _forget_failures()
    # Spraying many usernames from one address trips the per-address limit.
    for n in range(config.LOGIN_FAILS_PER_IP):
        client.post("/api/auth/login", json={"username": f"nobody{n}", "password": "x"})
    assert client.post("/api/auth/login", json={"username": "victim", "password": "secret123"}).status_code == 429
    _forget_failures()
    assert client.post("/api/auth/login", json={"username": "victim", "password": "secret123"}).status_code == 200


def test_unknown_user_still_hashes(monkeypatch):
    seen = []
    real = auth.verify_password
    monkeypatch.setattr(auth, "verify_password", lambda p, h: seen.append(h) or real(p, h))
    assert client.post("/api/auth/login", json={"username": "ghost", "password": "secret123"}).status_code == 401
    assert seen == [auth.DUMMY_HASH]
    _forget_failures()


def test_password_rechecks_are_throttled(monkeypatch):
    h = register("guarded")
    monkeypatch.setattr(main, "fails_account", RateLimiter(2, 3600))
    bad = {"password": "nope-nope"}
    assert client.post("/api/me/delete", headers=h, json=bad).status_code == 401
    assert client.post("/api/me/delete", headers=h, json=bad).status_code == 401
    assert client.post("/api/me/delete", headers=h, json={"password": "secret123"}).status_code == 429
    assert client.get("/api/me", headers=h).status_code == 200


def test_request_body_limits(monkeypatch):
    h = register("bulky")
    big = {"username": "x" * 70_000, "password": "y"}
    assert client.post("/api/auth/login", json=big).status_code == 413
    monkeypatch.setattr(config, "MAX_SYNC_BYTES", 2000)
    changes = [{"entity": "item", "id": f"b{n}", "updatedAt": 1, "data": {"pad": "z" * 100}} for n in range(30)]
    assert client.post("/api/sync", headers=h, json={"changes": changes}).status_code == 413
    # A streamed body without Content-Length is cut off the same way.
    stream = iter([b'{"changes": [', b" " * 3000, b"]}"])
    r = client.post("/api/sync", headers={**h, "Content-Type": "application/json"}, content=stream)
    assert r.status_code == 413
    assert client.post("/api/sync", headers=h, json={"changes": [{"entity": "room", "id": "t", "updatedAt": 2**60}]}).status_code == 400


def test_uploads_are_sniffed():
    h = register("uploader")
    html = b"<html><script>alert(1)</script></html>"
    r = client.put("/api/files/evil.png", headers={**h, "Content-Type": "image/png"}, content=html)
    assert r.status_code == 415
    assert client.get("/api/files/evil.png", headers=h).status_code == 404
    # Served as what it really is, not what the uploader said.
    assert client.put("/api/files/photo.png", headers={**h, "Content-Type": "image/png"}, content=JPEG).status_code == 200
    got = client.get("/api/files/photo.png", headers=h)
    assert got.headers["content-type"] == "image/jpeg"
    assert got.headers["x-content-type-options"] == "nosniff" and "sandbox" in got.headers["content-security-policy"]


def test_storage_quota():
    h = register("hoarder")
    chunk = JPEG + b"\0" * (1_200_000)
    assert client.put("/api/files/one.jpg", headers={**h, "Content-Type": "image/jpeg"}, content=chunk).status_code == 200
    r = client.put("/api/files/two.jpg", headers={**h, "Content-Type": "image/jpeg"}, content=chunk)
    assert r.status_code == 413 and "存储空间" in r.json()["detail"]
    # Replacing a file only counts the new size.
    assert client.put("/api/files/one.jpg", headers={**h, "Content-Type": "image/jpeg"}, content=chunk).status_code == 200
    # Records count too, and an over-quota sync is rolled back entirely.
    changes = [{"entity": "item", "id": f"q{n}", "updatedAt": 5, "data": {"pad": "z" * 100_000}} for n in range(10)]
    assert client.post("/api/sync", headers=h, json={"changes": changes}).status_code == 413
    assert client.post("/api/sync", headers=h, json={"cursor": 0}).json()["changes"] == []
    # Tombstones are tiny and still go through.
    tomb = [{"entity": "item", "id": "q0", "updatedAt": 6, "deleted": True}]
    assert client.post("/api/sync", headers=h, json={"changes": tomb}).status_code == 200


def test_idle_sessions_expire_and_are_capped():
    h = register("sleeper")
    token_hash = auth.hash_token(h["Authorization"][7:])
    main.db.execute("UPDATE sessions SET last_seen = 0 WHERE token_hash = ?", (token_hash,))
    r = client.get("/api/me", headers=h)
    assert r.status_code == 401
    assert main.db.one("SELECT 1 FROM sessions WHERE token_hash = ?", (token_hash,)) is None
    first = client.post("/api/auth/login", json={"username": "sleeper", "password": "secret123"}).json()["token"]
    main.db.execute("UPDATE sessions SET last_seen = last_seen - 1000 WHERE token_hash = ?", (auth.hash_token(first),))
    for _ in range(config.MAX_SESSIONS_PER_USER):
        assert client.post("/api/auth/login", json={"username": "sleeper", "password": "secret123"}).status_code == 200
    count = main.db.one("SELECT COUNT(*) FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.username = 'sleeper'")[0]
    assert count == config.MAX_SESSIONS_PER_USER
    assert client.get("/api/me", headers={"Authorization": f"Bearer {first}"}).status_code == 401


def test_rate_limiter_memory_is_bounded(monkeypatch):
    monkeypatch.setattr(RateLimiter, "MAX_KEYS", 100)
    limiter = RateLimiter(3, 60)
    for n in range(1000):
        limiter.hit(f"k{n}")
    assert len(limiter._hits) <= 100
    for _ in range(5):
        limiter.hit("busy")
    assert len(limiter._hits["busy"]) == 3 and not limiter.allowed("busy")


@pytest.fixture(scope="session", autouse=True)
def _shutdown():
    yield
    _server.shutdown()
