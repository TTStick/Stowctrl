"""Nook sync server: accounts, record sync, photo storage, AI icons."""
from __future__ import annotations

import asyncio
import json
import re
import secrets
import shutil
import sys
import time
import uuid
from typing import Any, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from . import auth, config, cutout, icons
from .db import Database
from .security import Guard, RateLimiter, SetupCode, audit, safe_name, sniff_image, too_many

for problem in config.problems():
    audit.error("配置不安全：%s", problem)
if config.problems():
    sys.exit(1)

db = Database(config.DATA_DIR / "nook.db")
FILES_DIR = config.DATA_DIR / "files"
FILES_DIR.mkdir(parents=True, exist_ok=True)

setup_code = SetupCode(config.DATA_DIR / "setup-code")
setup_code.prepare(has_users=db.one("SELECT 1 FROM users LIMIT 1") is not None)
if config.ALLOW_REGISTRATION:
    audit.warning("注册对所有人开放（NOOK_ALLOW_REGISTRATION=true）；公网部署请改用 NOOK_INVITE_CODE")

# No interactive docs: the API is for the apps, not for browsing.
app = FastAPI(title="Nook Server", version=config.VERSION, docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(Guard)

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.@-]{3,32}$")
ENTITY_RE = re.compile(r"^[a-z_]{1,32}$")
RECORD_ID_RE = re.compile(r"^[A-Za-z0-9-]{1,64}$")
FILE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,90}(\.[A-Za-z0-9]{1,8})?$")
FILE_TYPES = {"image/jpeg", "image/png", "image/heic", "image/webp", "application/octet-stream"}
MAX_MS = 2**53


def now_ms() -> int:
    return int(time.time() * 1000)


def client_ip(request: Request) -> str:
    # uvicorn has already replaced this with X-Forwarded-For, but only for
    # requests from FORWARDED_ALLOW_IPS (the reverse proxy).
    return request.client.host if request.client else "?"


@app.exception_handler(RequestValidationError)
async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": "请求格式不正确"})


WINDOW = 15 * 60
fails_account_ip = RateLimiter(config.LOGIN_FAILS_PER_ACCOUNT_IP, WINDOW)
fails_ip = RateLimiter(config.LOGIN_FAILS_PER_IP, WINDOW)
fails_account = RateLimiter(config.LOGIN_FAILS_PER_ACCOUNT, 3600)
code_fails = RateLimiter(config.CODE_FAILS_PER_IP, 3600)
icon_calls = RateLimiter(config.ICON_RATE_PER_HOUR, 3600)
icon_calls_total = RateLimiter(config.ICON_RATE_PER_DAY_TOTAL, 86400)
cutout_calls = RateLimiter(config.CUTOUT_RATE_PER_HOUR, 3600)
cutout_slot = asyncio.Semaphore(1)


# ---------------------------------------------------------------- auth

def current_user(authorization: Optional[str] = Header(default=None)) -> dict:
    if not authorization or not authorization.lower().startswith("bearer ") or len(authorization) > 256:
        raise HTTPException(401, "未登录")
    token_hash = auth.hash_token(authorization[7:].strip())
    row = db.one(
        "SELECT u.id, u.username, u.created_at, u.password_hash, s.last_seen FROM sessions s "
        "JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
        (token_hash,),
    )
    if row is None:
        raise HTTPException(401, "登录已失效，请重新登录")
    idle = now_ms() - row["last_seen"]
    if idle > config.SESSION_IDLE_MS:
        db.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash,))
        raise HTTPException(401, "太久没有使用，请重新登录")
    if idle > 3_600_000:
        db.execute("UPDATE sessions SET last_seen = ? WHERE token_hash = ?", (now_ms(), token_hash))
    return {**dict(row), "token_hash": token_hash}


class Credentials(BaseModel):
    username: str = Field(max_length=64)
    password: str = Field(max_length=config.PASSWORD_MAX)
    deviceName: str = Field("", max_length=128)


class Registration(Credentials):
    inviteCode: str = Field("", max_length=128)


class PasswordChange(BaseModel):
    oldPassword: str = Field(max_length=config.PASSWORD_MAX)
    newPassword: str = Field(max_length=config.PASSWORD_MAX)


class PasswordConfirm(BaseModel):
    password: str = Field(max_length=config.PASSWORD_MAX)


def _user_payload(row: Any) -> dict:
    return {"id": row["id"], "username": row["username"], "createdAt": row["created_at"]}


def _issue_session(user_id: str, device: str) -> str:
    token = auth.new_token()
    ts = now_ms()
    with db.tx() as c:
        c.execute("DELETE FROM sessions WHERE last_seen < ?", (ts - config.SESSION_IDLE_MS,))
        c.execute(
            "INSERT INTO sessions(token_hash, user_id, device_name, created_at, last_seen) VALUES (?,?,?,?,?)",
            (auth.hash_token(token), user_id, device[:64], ts, ts),
        )
        # Keep the most recently used sessions only.
        c.execute(
            "DELETE FROM sessions WHERE user_id = ? AND token_hash NOT IN ("
            "SELECT token_hash FROM sessions WHERE user_id = ? ORDER BY last_seen DESC LIMIT ?)",
            (user_id, user_id, config.MAX_SESSIONS_PER_USER),
        )
    return token


def registration_mode() -> str:
    """open: anyone may register; code: an invite or setup code is needed."""
    if config.ALLOW_REGISTRATION:
        return "open"
    if config.INVITE_CODE or setup_code.active:
        return "code"
    return "closed"


def _check_password(password: str, field: str = "密码") -> None:
    if not config.PASSWORD_MIN <= len(password) <= config.PASSWORD_MAX:
        raise HTTPException(400, f"{field}长度需为 {config.PASSWORD_MIN}-{config.PASSWORD_MAX} 位")


def _require_password(user: dict, password: str, ip: str) -> None:
    """Re-checks the password before a sensitive change; throttled like logins."""
    key = f"user:{user['id']}"
    wait = fails_account.retry_after(key)
    if wait:
        raise too_many(wait)
    if not auth.verify_password(password, user["password_hash"]):
        fails_account.hit(key)
        audit.warning("password check failed user=%s ip=%s", safe_name(user["username"]), ip)
        raise HTTPException(401, "密码不正确")


@app.get("/api/health")
def health() -> dict:
    return {"ok": True}


@app.get("/api/info")
def info() -> dict:
    mode = registration_mode()
    return {
        "name": "Nook",
        "version": config.VERSION,
        "registration": mode,
        "registrationOpen": mode != "closed",
        "passwordMin": config.PASSWORD_MIN,
        "features": {"icons": icons.available(), "cutout": cutout.available()},
    }


@app.post("/api/auth/register")
def register(body: Registration, request: Request) -> dict:
    ip = client_ip(request)
    mode = registration_mode()
    if mode == "closed":
        raise HTTPException(403, "该服务器已关闭注册")
    with_setup_code = False
    if mode == "code":
        wait = code_fails.retry_after(ip)
        if wait:
            raise too_many(wait)
        code = body.inviteCode.strip()
        with_setup_code = setup_code.matches(code)
        if not with_setup_code and not (config.INVITE_CODE and auth.same_secret(code, config.INVITE_CODE)):
            code_fails.hit(ip)
            audit.warning("register with wrong code ip=%s", ip)
            raise HTTPException(403, "请填写正确的注册码" if code else "这台服务器需要注册码")
    username = body.username.strip()
    if not USERNAME_RE.match(username):
        raise HTTPException(400, "用户名需为 3-32 位字母、数字或 _ . @ -")
    _check_password(body.password)
    password_hash = auth.hash_password(body.password)
    user_id = str(uuid.uuid4())
    with db.tx() as c:
        users = c.execute("SELECT COUNT(*) FROM users").fetchone()[0]
        if with_setup_code and users > 0:
            raise HTTPException(403, "这台服务器已经有主人了，注册码已失效")
        if users >= config.MAX_USERS:
            raise HTTPException(403, "账号数量已达上限")
        if c.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone():
            raise HTTPException(409, "用户名已被占用")
        c.execute(
            "INSERT INTO users(id, username, password_hash, created_at) VALUES (?,?,?,?)",
            (user_id, username, password_hash, now_ms()),
        )
    if with_setup_code:
        setup_code.consume()
    audit.info("registered user=%s ip=%s", username, ip)
    token = _issue_session(user_id, body.deviceName)
    row = db.one("SELECT * FROM users WHERE id = ?", (user_id,))
    return {"token": token, "user": _user_payload(row)}


@app.post("/api/auth/login")
def login(body: Credentials, request: Request) -> dict:
    ip = client_ip(request)
    name = body.username.strip().lower()
    limits = [(fails_account_ip, f"{ip}|{name}"), (fails_ip, ip), (fails_account, f"name:{name}")]
    wait = max(limiter.retry_after(key) for limiter, key in limits)
    if wait:
        audit.warning("login throttled user=%s ip=%s", safe_name(name), ip)
        raise too_many(wait)
    row = db.one("SELECT * FROM users WHERE username = ?", (body.username.strip(),))
    # Unknown names still pay for a hash, so timing doesn't reveal accounts.
    ok = auth.verify_password(body.password, row["password_hash"] if row else auth.DUMMY_HASH)
    if row is None or not ok:
        for limiter, key in limits:
            limiter.hit(key)
        audit.warning("login failed user=%s ip=%s", safe_name(name), ip)
        raise HTTPException(401, "用户名或密码错误")
    fails_account_ip.reset(f"{ip}|{name}")
    audit.info("login user=%s ip=%s", row["username"], ip)
    token = _issue_session(row["id"], body.deviceName)
    return {"token": token, "user": _user_payload(row)}


@app.post("/api/auth/logout")
def logout(user: dict = Depends(current_user)) -> dict:
    db.execute("DELETE FROM sessions WHERE token_hash = ?", (user["token_hash"],))
    return {"ok": True}


@app.get("/api/me")
def me(user: dict = Depends(current_user)) -> dict:
    return _user_payload(user)


@app.post("/api/auth/password")
def change_password(body: PasswordChange, request: Request, user: dict = Depends(current_user)) -> dict:
    try:
        _require_password(user, body.oldPassword, client_ip(request))
    except HTTPException as exc:
        if exc.status_code == 401:
            raise HTTPException(401, "原密码不正确")
        raise
    _check_password(body.newPassword, "新密码")
    db.execute("UPDATE users SET password_hash = ? WHERE id = ?",
               (auth.hash_password(body.newPassword), user["id"]))
    # Sign out every other device; this one keeps its session.
    db.execute("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?",
               (user["id"], user["token_hash"]))
    audit.info("password changed user=%s ip=%s", user["username"], client_ip(request))
    return {"ok": True}


@app.post("/api/me/delete")
def delete_account(body: PasswordConfirm, request: Request, user: dict = Depends(current_user)) -> dict:
    _require_password(user, body.password, client_ip(request))
    with db.tx() as c:
        for table in ("records", "files", "counters", "sessions"):
            c.execute(f"DELETE FROM {table} WHERE user_id = ?", (user["id"],))
        c.execute("DELETE FROM users WHERE id = ?", (user["id"],))
    shutil.rmtree(FILES_DIR / user["id"], ignore_errors=True)
    audit.info("account deleted user=%s ip=%s", user["username"], client_ip(request))
    return {"ok": True}


# ---------------------------------------------------------------- quota

def _files_bytes(c, uid: str, except_file: str = "") -> int:
    return c.execute("SELECT COALESCE(SUM(size), 0) FROM files WHERE user_id = ? AND id != ?",
                     (uid, except_file)).fetchone()[0]


def _records_usage(c, uid: str) -> tuple[int, int]:
    row = c.execute("SELECT COUNT(*), COALESCE(SUM(LENGTH(CAST(data AS BLOB))), 0) FROM records WHERE user_id = ?",
                    (uid,)).fetchone()
    return row[0], row[1]


# ---------------------------------------------------------------- sync

class Change(BaseModel):
    entity: str = Field(max_length=32)
    id: str = Field(max_length=64)
    updatedAt: int = Field(ge=0, le=MAX_MS)
    deleted: bool = False
    data: Optional[dict[str, Any]] = None


class SyncRequest(BaseModel):
    cursor: int = Field(0, ge=0, le=MAX_MS)
    changes: list[Change] = []
    limit: int = 500


@app.post("/api/sync")
def sync(body: SyncRequest, user: dict = Depends(current_user)) -> dict:
    """Push local changes, pull everything newer than `cursor`.

    Conflicts resolve last-writer-wins on the client's `updatedAt`; an
    equal or older write is ignored. Deletions are kept as tombstones so
    devices that were offline still learn about them.
    """
    if len(body.changes) > config.MAX_CHANGES_PER_SYNC:
        raise HTTPException(413, "单次同步的修改过多")
    limit = max(1, min(body.limit, 1000))
    uid = user["id"]
    accepted = 0

    with db.tx() as c:
        row = c.execute("SELECT seq FROM counters WHERE user_id = ?", (uid,)).fetchone()
        seq = row[0] if row else 0
        # The server was reset or restored from an older backup: the client
        # should forget its cursor and upload everything again.
        if body.cursor > seq:
            return {"cursor": seq, "changes": [], "hasMore": False, "accepted": 0, "reset": True}

        added_records = added_bytes = 0
        for ch in body.changes:
            if not ENTITY_RE.match(ch.entity) or not RECORD_ID_RE.match(ch.id):
                raise HTTPException(400, "同步数据格式不正确")
            data_text = "{}" if ch.deleted else json.dumps(ch.data or {}, ensure_ascii=False,
                                                           separators=(",", ":"))
            size = len(data_text.encode())
            if size > config.MAX_RECORD_BYTES:
                raise HTTPException(413, "单条记录过大")
            existing = c.execute(
                "SELECT updated_at, LENGTH(CAST(data AS BLOB)) FROM records "
                "WHERE user_id = ? AND entity = ? AND id = ?",
                (uid, ch.entity, ch.id),
            ).fetchone()
            if existing is not None and ch.updatedAt <= existing[0]:
                continue
            added_records += existing is None
            added_bytes += size - (existing[1] if existing else 0)
            seq += 1
            c.execute(
                "INSERT INTO records(user_id, entity, id, data, updated_at, deleted, seq) "
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id, entity, id) DO UPDATE SET "
                "data = excluded.data, updated_at = excluded.updated_at, "
                "deleted = excluded.deleted, seq = excluded.seq",
                (uid, ch.entity, ch.id, data_text, ch.updatedAt, int(ch.deleted), seq),
            )
            accepted += 1

        # Growth past the quota is refused (and rolled back); shrinking,
        # e.g. deleting things, is always allowed.
        if added_records > 0 or added_bytes > 0:
            count, used = _records_usage(c, uid)
            if added_records > 0 and count > config.MAX_RECORDS_PER_USER:
                raise HTTPException(413, "记录数量已达上限")
            if added_bytes > 0 and used + _files_bytes(c, uid) > config.USER_QUOTA_BYTES:
                raise HTTPException(413, "存储空间已满")

        c.execute(
            "INSERT INTO counters(user_id, seq) VALUES (?, ?) "
            "ON CONFLICT(user_id) DO UPDATE SET seq = excluded.seq",
            (uid, seq),
        )
        rows = c.execute(
            "SELECT entity, id, data, updated_at, deleted, seq FROM records "
            "WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?",
            (uid, body.cursor, limit + 1),
        ).fetchall()

    has_more = len(rows) > limit
    rows = rows[:limit]
    cursor = rows[-1]["seq"] if has_more else seq
    changes = [
        {
            "entity": r["entity"],
            "id": r["id"],
            "updatedAt": r["updated_at"],
            "deleted": bool(r["deleted"]),
            "data": None if r["deleted"] else json.loads(r["data"]),
        }
        for r in rows
    ]
    return {"cursor": cursor, "changes": changes, "hasMore": has_more, "accepted": accepted}


# ---------------------------------------------------------------- files

def _file_path(user_id: str, file_id: str):
    if not FILE_ID_RE.match(file_id):
        raise HTTPException(400, "文件名不正确")
    return FILES_DIR / user_id / file_id


@app.put("/api/files/{file_id}")
async def put_file(file_id: str, request: Request, user: dict = Depends(current_user)) -> dict:
    path = _file_path(user["id"], file_id)
    declared = request.headers.get("content-type", "application/octet-stream").split(";")[0].strip()
    if declared not in FILE_TYPES:
        raise HTTPException(415, "不支持的文件类型")
    with db.tx() as c:
        used = _files_bytes(c, user["id"], file_id) + _records_usage(c, user["id"])[1]
    allowed = min(config.MAX_FILE_BYTES, config.USER_QUOTA_BYTES - used)
    if allowed <= 0:
        raise HTTPException(413, "存储空间已满")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{file_id}.{secrets.token_hex(4)}.part")
    size = 0
    head = b""
    try:
        with open(tmp, "wb") as fh:
            async for chunk in request.stream():
                size += len(chunk)
                if size > allowed:
                    raise HTTPException(413, "文件过大" if size > config.MAX_FILE_BYTES else "存储空间已满")
                if len(head) < 16:
                    head += chunk[:16]
                fh.write(chunk)
        if size == 0:
            raise HTTPException(400, "文件为空")
        # Stored and served with the type the bytes really are, never the
        # one the uploader claimed.
        content_type = sniff_image(head)
        if content_type is None:
            raise HTTPException(415, "只能上传 JPEG、PNG、HEIC 或 WebP 图片")
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)
    db.execute(
        "INSERT INTO files(user_id, id, content_type, size, created_at) VALUES (?,?,?,?,?) "
        "ON CONFLICT(user_id, id) DO UPDATE SET content_type = excluded.content_type, size = excluded.size",
        (user["id"], file_id, content_type, size, now_ms()),
    )
    return {"id": file_id, "size": size}


@app.api_route("/api/files/{file_id}", methods=["GET", "HEAD"])
def get_file(file_id: str, user: dict = Depends(current_user)):
    path = _file_path(user["id"], file_id)
    row = db.one("SELECT content_type FROM files WHERE user_id = ? AND id = ?", (user["id"], file_id))
    if row is None or not path.exists():
        raise HTTPException(404, "文件不存在")
    return FileResponse(path, media_type=row["content_type"],
                        headers={"Cache-Control": "private, max-age=31536000, immutable"})


# ---------------------------------------------------------------- AI icons

class IconRequest(BaseModel):
    prompt: str = Field(max_length=1000)


@app.post("/api/icons/generate")
async def generate_icon(body: IconRequest, user: dict = Depends(current_user)) -> dict:
    prompt = body.prompt.strip()
    if not prompt:
        raise HTTPException(400, "请描述要画的物品")
    if len(prompt) > 200:
        raise HTTPException(400, "描述太长了，200 字以内")
    if not icons.available():
        raise HTTPException(503, "服务器没有配置大模型（ANTHROPIC_API_KEY）")
    if not icon_calls.allowed(user["id"]) or not icon_calls_total.allowed("all"):
        raise HTTPException(429, "AI 绘制次数已达上限，请稍后再试")
    icon_calls.hit(user["id"])
    icon_calls_total.hit("all")
    try:
        svg = await icons.generate_svg(prompt)
    except icons.IconError as exc:
        raise HTTPException(exc.status, exc.message)
    return {"svg": svg}


# ---------------------------------------------------------------- cutout fallback

@app.post("/api/cutout")
async def server_cutout(request: Request, user: dict = Depends(current_user)) -> Response:
    if not cutout.available():
        raise HTTPException(501, "服务器未启用抠图（构建时设置 WITH_CUTOUT=true）")
    if not cutout_calls.allowed(user["id"]):
        raise HTTPException(429, "抠图次数已达上限，请稍后再试")
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > config.MAX_FILE_BYTES:
            raise HTTPException(413, "图片过大")
    if not data:
        raise HTTPException(400, "图片为空")
    if sniff_image(bytes(data[:16])) is None:
        raise HTTPException(415, "只能处理 JPEG、PNG、HEIC 或 WebP 图片")
    cutout_calls.hit(user["id"])
    try:
        # One at a time: background removal needs a lot of memory.
        async with cutout_slot:
            png = await run_in_threadpool(cutout.remove_background, bytes(data))
    except Exception:
        raise HTTPException(500, "服务器抠图失败")
    return Response(content=png, media_type="image/png")
