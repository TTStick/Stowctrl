"""Nook sync server: accounts, record sync, photo storage, AI icons."""
from __future__ import annotations

import json
import re
import secrets
import shutil
import time
import uuid
from collections import defaultdict, deque
from typing import Any, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from . import auth, config, cutout, icons
from .db import Database

db = Database(config.DATA_DIR / "nook.db")
FILES_DIR = config.DATA_DIR / "files"
FILES_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="Nook Server", version=config.VERSION)

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.@-]{3,32}$")
ENTITY_RE = re.compile(r"^[a-z_]{1,32}$")
RECORD_ID_RE = re.compile(r"^[A-Za-z0-9-]{1,64}$")
FILE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,90}(\.[A-Za-z0-9]{1,8})?$")
FILE_TYPES = {"image/jpeg", "image/png", "image/heic", "image/webp", "application/octet-stream"}


def now_ms() -> int:
    return int(time.time() * 1000)


@app.exception_handler(RequestValidationError)
async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": "请求格式不正确"})


class _RateLimiter:
    """Sliding-window counter kept in memory; good enough for one process."""

    def __init__(self, limit: int, window: float) -> None:
        self.limit, self.window = limit, window
        self.hits: dict[str, deque] = defaultdict(deque)

    def check(self, key: str) -> bool:
        now = time.monotonic()
        q = self.hits[key]
        while q and now - q[0] > self.window:
            q.popleft()
        return len(q) < self.limit

    def hit(self, key: str) -> None:
        self.hits[key].append(time.monotonic())


login_failures = _RateLimiter(limit=10, window=300)
icon_calls = _RateLimiter(limit=max(1, config.ICON_RATE_PER_HOUR), window=3600)


# ---------------------------------------------------------------- auth

def current_user(authorization: Optional[str] = Header(default=None)) -> dict:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(401, "未登录")
    token_hash = auth.hash_token(authorization[7:].strip())
    row = db.one(
        "SELECT u.id, u.username, u.created_at, u.password_hash, s.last_seen FROM sessions s "
        "JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
        (token_hash,),
    )
    if row is None:
        raise HTTPException(401, "登录已失效，请重新登录")
    if now_ms() - row["last_seen"] > 3_600_000:
        db.execute("UPDATE sessions SET last_seen = ? WHERE token_hash = ?", (now_ms(), token_hash))
    return {**dict(row), "token_hash": token_hash}


class Credentials(BaseModel):
    username: str
    password: str
    deviceName: str = ""


class PasswordChange(BaseModel):
    oldPassword: str
    newPassword: str


class PasswordConfirm(BaseModel):
    password: str


def _user_payload(row: Any) -> dict:
    return {"id": row["id"], "username": row["username"], "createdAt": row["created_at"]}


def _issue_session(user_id: str, device: str) -> str:
    token = auth.new_token()
    ts = now_ms()
    db.execute(
        "INSERT INTO sessions(token_hash, user_id, device_name, created_at, last_seen) VALUES (?,?,?,?,?)",
        (auth.hash_token(token), user_id, device[:64], ts, ts),
    )
    return token


def _registration_open() -> bool:
    if config.ALLOW_REGISTRATION:
        return True
    return db.one("SELECT 1 FROM users LIMIT 1") is None


@app.get("/api/health")
def health() -> dict:
    return {"ok": True}


@app.get("/api/info")
def info() -> dict:
    return {
        "name": "Nook",
        "version": config.VERSION,
        "registrationOpen": _registration_open(),
        "features": {"icons": icons.available(), "cutout": cutout.available()},
    }


@app.post("/api/auth/register")
def register(body: Credentials) -> dict:
    if not _registration_open():
        raise HTTPException(403, "该服务器已关闭注册")
    username = body.username.strip()
    if not USERNAME_RE.match(username):
        raise HTTPException(400, "用户名需为 3-32 位字母、数字或 _ . @ -")
    if not 6 <= len(body.password) <= 128:
        raise HTTPException(400, "密码长度需为 6-128 位")
    if db.one("SELECT 1 FROM users WHERE username = ?", (username,)):
        raise HTTPException(409, "用户名已被占用")
    user_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO users(id, username, password_hash, created_at) VALUES (?,?,?,?)",
        (user_id, username, auth.hash_password(body.password), now_ms()),
    )
    token = _issue_session(user_id, body.deviceName)
    row = db.one("SELECT * FROM users WHERE id = ?", (user_id,))
    return {"token": token, "user": _user_payload(row)}


@app.post("/api/auth/login")
def login(body: Credentials, request: Request) -> dict:
    ip = request.client.host if request.client else "?"
    key = f"{ip}:{body.username.strip().lower()}"
    if not login_failures.check(key):
        raise HTTPException(429, "尝试次数过多，请 5 分钟后再试")
    row = db.one("SELECT * FROM users WHERE username = ?", (body.username.strip(),))
    if row is None or not auth.verify_password(body.password, row["password_hash"]):
        login_failures.hit(key)
        raise HTTPException(401, "用户名或密码错误")
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
def change_password(body: PasswordChange, user: dict = Depends(current_user)) -> dict:
    if not auth.verify_password(body.oldPassword, user["password_hash"]):
        raise HTTPException(401, "原密码不正确")
    if not 6 <= len(body.newPassword) <= 128:
        raise HTTPException(400, "新密码长度需为 6-128 位")
    db.execute("UPDATE users SET password_hash = ? WHERE id = ?",
               (auth.hash_password(body.newPassword), user["id"]))
    # Sign out every other device; this one keeps its session.
    db.execute("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?",
               (user["id"], user["token_hash"]))
    return {"ok": True}


@app.post("/api/me/delete")
def delete_account(body: PasswordConfirm, user: dict = Depends(current_user)) -> dict:
    if not auth.verify_password(body.password, user["password_hash"]):
        raise HTTPException(401, "密码不正确")
    with db.tx() as c:
        for table in ("records", "files", "counters", "sessions"):
            c.execute(f"DELETE FROM {table} WHERE user_id = ?", (user["id"],))
        c.execute("DELETE FROM users WHERE id = ?", (user["id"],))
    shutil.rmtree(FILES_DIR / user["id"], ignore_errors=True)
    return {"ok": True}


# ---------------------------------------------------------------- sync

class Change(BaseModel):
    entity: str
    id: str
    updatedAt: int
    deleted: bool = False
    data: Optional[dict[str, Any]] = None


class SyncRequest(BaseModel):
    cursor: int = 0
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

        for ch in body.changes:
            if not ENTITY_RE.match(ch.entity) or not RECORD_ID_RE.match(ch.id):
                raise HTTPException(400, "同步数据格式不正确")
            data_text = "{}" if ch.deleted else json.dumps(ch.data or {}, ensure_ascii=False,
                                                           separators=(",", ":"))
            if len(data_text.encode()) > config.MAX_RECORD_BYTES:
                raise HTTPException(413, "单条记录过大")
            existing = c.execute(
                "SELECT updated_at FROM records WHERE user_id = ? AND entity = ? AND id = ?",
                (uid, ch.entity, ch.id),
            ).fetchone()
            if existing is not None and ch.updatedAt <= existing[0]:
                continue
            seq += 1
            c.execute(
                "INSERT INTO records(user_id, entity, id, data, updated_at, deleted, seq) "
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(user_id, entity, id) DO UPDATE SET "
                "data = excluded.data, updated_at = excluded.updated_at, "
                "deleted = excluded.deleted, seq = excluded.seq",
                (uid, ch.entity, ch.id, data_text, ch.updatedAt, int(ch.deleted), seq),
            )
            accepted += 1

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
    content_type = request.headers.get("content-type", "application/octet-stream").split(";")[0].strip()
    if content_type not in FILE_TYPES:
        raise HTTPException(415, "不支持的文件类型")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{file_id}.{secrets.token_hex(4)}.part")
    size = 0
    try:
        with open(tmp, "wb") as fh:
            async for chunk in request.stream():
                size += len(chunk)
                if size > config.MAX_FILE_BYTES:
                    raise HTTPException(413, "文件过大")
                fh.write(chunk)
        if size == 0:
            raise HTTPException(400, "文件为空")
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
    prompt: str


@app.post("/api/icons/generate")
async def generate_icon(body: IconRequest, user: dict = Depends(current_user)) -> dict:
    prompt = body.prompt.strip()
    if not prompt:
        raise HTTPException(400, "请描述要画的物品")
    if len(prompt) > 200:
        raise HTTPException(400, "描述太长了，200 字以内")
    if not icons.available():
        raise HTTPException(503, "服务器没有配置大模型（ANTHROPIC_API_KEY）")
    if not icon_calls.check(user["id"]):
        raise HTTPException(429, "AI 绘制次数已达上限，请稍后再试")
    icon_calls.hit(user["id"])
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
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > config.MAX_FILE_BYTES:
            raise HTTPException(413, "图片过大")
    if not data:
        raise HTTPException(400, "图片为空")
    try:
        png = await run_in_threadpool(cutout.remove_background, bytes(data))
    except Exception:
        raise HTTPException(500, "服务器抠图失败")
    return Response(content=png, media_type="image/png")
