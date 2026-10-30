"""The server as deployed: a real uvicorn process, so the setup code, proxy
headers (X-Forwarded-For / -Proto) and HTTPS enforcement are exercised the
way they run in Docker."""
from __future__ import annotations

import os
import socket
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx
import pytest

ROOT = Path(__file__).resolve().parent.parent


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Server:
    def __init__(self, **env: str) -> None:
        self.data = Path(tempfile.mkdtemp())
        self.port = _free_port()
        full = {k: v for k, v in os.environ.items() if not k.startswith(("NOOK_", "ANTHROPIC_", "FORWARDED_"))}
        full.update({"NOOK_DATA_DIR": str(self.data), **env})
        self.proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
             "--port", str(self.port), "--no-server-header"],
            cwd=ROOT, env=full, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        self.url = f"http://127.0.0.1:{self.port}"
        for _ in range(100):
            if self.proc.poll() is not None:
                break
            try:
                httpx.get(self.url + "/api/health", timeout=1)
                return
            except httpx.HTTPError:
                time.sleep(0.1)
        self.stop()

    def stop(self) -> str:
        if self.proc.poll() is None:
            self.proc.terminate()
        out, _ = self.proc.communicate(timeout=10)
        return out


def test_fresh_server_is_claimed_with_the_setup_code():
    srv = Server(FORWARDED_ALLOW_IPS="10.9.9.9")
    try:
        assert httpx.get(srv.url + "/api/info").json()["registration"] == "code"
        code_file = srv.data / "setup-code"
        code = code_file.read_text().strip()
        assert stat.S_IMODE(code_file.stat().st_mode) == 0o600
        body = {"username": "owner", "password": "owner-pass-1"}
        r = httpx.post(srv.url + "/api/auth/register", json=body)
        assert r.status_code == 403
        assert httpx.post(srv.url + "/api/auth/register", json={**body, "inviteCode": "AAAA-BBBB-CCCC-DDDD"}).status_code == 403
        # Typed loosely (lower case, no dashes) it still matches.
        r = httpx.post(srv.url + "/api/auth/register", json={**body, "inviteCode": code.replace("-", "").lower()})
        assert r.status_code == 200, r.text
        assert not code_file.exists()
        assert httpx.get(srv.url + "/api/info").json()["registration"] == "closed"
        r = httpx.post(srv.url + "/api/auth/register", json={**body, "username": "second", "inviteCode": code})
        assert r.status_code == 403

        # The proxy isn't trusted here, so a forged X-Forwarded-For changes
        # nothing: the failures all count against 127.0.0.1.
        for n in range(5):
            r = httpx.post(srv.url + "/api/auth/login", json={"username": "owner", "password": "wrong"},
                           headers={"X-Forwarded-For": f"203.0.113.{n}"})
            assert r.status_code == 401
        r = httpx.post(srv.url + "/api/auth/login", json={"username": "owner", "password": "owner-pass-1"},
                       headers={"X-Forwarded-For": "203.0.113.99"})
        assert r.status_code == 429
        assert "server" not in r.headers
    finally:
        log = srv.stop()
    assert code in log  # the operator finds the code in `docker logs`
    assert "login failed user=owner ip=127.0.0.1" in log


def test_behind_https_proxy():
    srv = Server(FORWARDED_ALLOW_IPS="127.0.0.1", NOOK_REQUIRE_HTTPS="true", NOOK_INVITE_CODE="invite-for-family")
    try:
        assert httpx.get(srv.url + "/api/health").status_code == 200
        r = httpx.get(srv.url + "/api/info")
        assert r.status_code == 403 and "HTTPS" in r.json()["detail"]
        proxied = {"X-Forwarded-Proto": "https"}
        r = httpx.get(srv.url + "/api/info", headers=proxied)
        assert r.status_code == 200 and r.headers["strict-transport-security"].startswith("max-age=")
        body = {"username": "fam", "password": "family-pass", "inviteCode": "invite-for-family"}
        assert httpx.post(srv.url + "/api/auth/register", json=body, headers=proxied).status_code == 200
        # Behind the trusted proxy each real client is limited on its own.
        attacker = {**proxied, "X-Forwarded-For": "198.51.100.7"}
        for _ in range(5):
            httpx.post(srv.url + "/api/auth/login", json={"username": "fam", "password": "bad"}, headers=attacker)
        assert httpx.post(srv.url + "/api/auth/login", json=body, headers=attacker).status_code == 429
        family = {**proxied, "X-Forwarded-For": "192.0.2.10"}
        assert httpx.post(srv.url + "/api/auth/login", json=body, headers=family).status_code == 200
    finally:
        log = srv.stop()
    assert "ip=198.51.100.7" in log


def test_refuses_to_start_with_a_weak_invite_code():
    srv = Server(NOOK_INVITE_CODE="1234")
    out = srv.stop()
    assert srv.proc.returncode not in (0, None)
    assert "NOOK_INVITE_CODE" in out
