"""AI icon drawing: ask Claude for a single-colour line-art SVG, then
rebuild it from a strict whitelist so clients only ever receive plain
geometry (no scripts, styles, links or external references)."""
from __future__ import annotations

import os
import re
import xml.etree.ElementTree as ET
from typing import Optional

import anthropic
import defusedxml.ElementTree as SafeET

from . import config

SYSTEM_PROMPT = """You draw small icons for a home-inventory app. The user names a household \
object (usually in Chinese); you reply with exactly one SVG document and nothing else.

Style:
- Simple, instantly recognisable outline icon in the spirit of SF Symbols or Lucide.
- Monochrome. Every shape uses stroke="currentColor" and fill="none". fill="currentColor" is \
allowed only for tiny solid details such as dots or buttons.
- stroke-width="2.5", stroke-linecap="round", stroke-linejoin="round".
- Show the object alone from its most recognisable angle, centred, with about 4 units of padding.
- At most 14 shapes. No background, no frame, no shadow, no text or letters.

Format:
- <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">.
- Only these elements: g, path, circle, ellipse, rect, line, polyline, polygon.
- No transform, style, class, id, defs, gradients, filters, masks, clipPath, image, text or script.
- Absolute coordinates inside the 48x48 viewBox."""

_SVG_RE = re.compile(r"<svg\b[\s\S]*?</svg>", re.IGNORECASE)
_NUMBER_RE = re.compile(r"^-?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?(px)?$")
_PATH_RE = re.compile(r"^[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\-\s]*$")
_POINTS_RE = re.compile(r"^[0-9eE.,+\-\s]*$")

_DRAWABLE = {"path", "circle", "ellipse", "rect", "line", "polyline", "polygon"}
_NUMERIC_ATTRS = {"cx", "cy", "r", "rx", "ry", "x", "y", "width", "height", "x1", "y1", "x2", "y2"}
_PAINT_ATTRS = {"fill", "stroke"}
_STYLE_NUMERIC = {"stroke-width", "opacity", "stroke-opacity", "fill-opacity"}
_LINECAPS = {"butt", "round", "square"}
_LINEJOINS = {"miter", "round", "bevel"}
_FILL_RULES = {"nonzero", "evenodd"}
_MAX_ELEMENTS = 200


class IconError(Exception):
    def __init__(self, message: str, status: int = 502) -> None:
        super().__init__(message)
        self.message = message
        self.status = status


_client: Optional[anthropic.AsyncAnthropic] = None


def available() -> bool:
    return bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"))


def _get_client() -> anthropic.AsyncAnthropic:
    global _client
    if _client is None:
        _client = anthropic.AsyncAnthropic(timeout=120.0, max_retries=2)
    return _client


async def generate_svg(prompt: str) -> str:
    kwargs: dict = {
        "model": config.ICON_MODEL,
        "max_tokens": 16000,
        "system": SYSTEM_PROMPT,
        "messages": [{"role": "user", "content": f"要画的物品：{prompt}"}],
    }
    if config.ICON_EFFORT:
        kwargs["output_config"] = {"effort": config.ICON_EFFORT}

    client = _get_client()
    try:
        if config.ICON_FALLBACKS:
            # "default" lets the API pick a fallback model by refusal category.
            response = await client.beta.messages.create(
                betas=["server-side-fallback-2026-07-01"],
                extra_body={"fallbacks": "default"},
                **kwargs,
            )
        else:
            response = await client.messages.create(**kwargs)
    except anthropic.AuthenticationError:
        raise IconError("服务器配置的大模型 API Key 无效")
    except anthropic.PermissionDeniedError:
        raise IconError("服务器的大模型 API Key 没有调用权限")
    except anthropic.NotFoundError:
        raise IconError(f"服务器配置的模型 {config.ICON_MODEL} 不存在")
    except anthropic.RateLimitError:
        raise IconError("大模型调用太频繁，请稍后再试", status=429)
    except anthropic.APIStatusError as exc:
        raise IconError(f"大模型服务出错（{exc.status_code}），请稍后再试")
    except anthropic.APIConnectionError:
        raise IconError("服务器连接不上大模型服务，请检查网络或 ANTHROPIC_BASE_URL")

    if response.stop_reason == "refusal":
        raise IconError("大模型拒绝绘制这个图标，换个描述试试", status=422)
    text = "".join(block.text for block in response.content if block.type == "text")
    return sanitize_svg(text)


def _local(name: str) -> str:
    return name.rsplit("}", 1)[-1]


def _clean_attrs(tag: str, attrib: dict) -> dict:
    out: dict = {}
    for raw_key, raw_value in attrib.items():
        key = _local(raw_key)
        value = str(raw_value).strip()
        if len(value) > 20000:
            continue
        if key == "d" and tag == "path":
            if _PATH_RE.match(value):
                out[key] = value
        elif key == "points" and tag in ("polyline", "polygon"):
            if _POINTS_RE.match(value):
                out[key] = value
        elif key in _NUMERIC_ATTRS or key in _STYLE_NUMERIC:
            if _NUMBER_RE.match(value):
                out[key] = value.removesuffix("px")
        elif key in _PAINT_ATTRS:
            out[key] = "none" if value.lower() in ("none", "transparent") else "currentColor"
        elif key == "stroke-linecap" and value in _LINECAPS:
            out[key] = value
        elif key == "stroke-linejoin" and value in _LINEJOINS:
            out[key] = value
        elif key == "fill-rule" and value in _FILL_RULES:
            out[key] = value
    return out


def _view_box(root: ET.Element) -> str:
    raw = (root.get("viewBox") or "").replace(",", " ").split()
    if len(raw) == 4 and all(_NUMBER_RE.match(v) for v in raw):
        x, y, w, h = (float(v.removesuffix("px")) for v in raw)
        if w > 0 and h > 0:
            return " ".join(f"{v:g}" for v in (x, y, w, h))
    width, height = root.get("width"), root.get("height")
    if width and height and _NUMBER_RE.match(width) and _NUMBER_RE.match(height):
        return f"0 0 {float(width.removesuffix('px')):g} {float(height.removesuffix('px')):g}"
    return "0 0 48 48"


def sanitize_svg(text: str) -> str:
    match = _SVG_RE.search(text or "")
    if not match:
        raise IconError("大模型没有返回有效的图标，请重试")
    try:
        root = SafeET.fromstring(match.group(0))
    except Exception:
        raise IconError("大模型返回的图标格式有误，请重试")

    out = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "viewBox": _view_box(root)})
    root_style = {k: v for k, v in _clean_attrs("svg", root.attrib).items()
                  if k not in _NUMERIC_ATTRS}
    out.attrib.update(root_style)

    count = 0

    def copy(src: ET.Element, dst: ET.Element, depth: int) -> None:
        nonlocal count
        for child in list(src):
            tag = _local(child.tag)
            if count >= _MAX_ELEMENTS:
                return
            if tag == "g" and depth < 4:
                group = ET.SubElement(dst, "g", {k: v for k, v in _clean_attrs("g", child.attrib).items()
                                                 if k not in _NUMERIC_ATTRS})
                copy(child, group, depth + 1)
                if len(group) == 0:
                    dst.remove(group)
            elif tag in _DRAWABLE:
                attrs = _clean_attrs(tag, child.attrib)
                if tag == "path" and "d" not in attrs:
                    continue
                if tag in ("polyline", "polygon") and "points" not in attrs:
                    continue
                ET.SubElement(dst, tag, attrs)
                count += 1

    copy(root, out, 0)
    if count == 0:
        raise IconError("大模型返回的图标是空的，请重试")
    return ET.tostring(out, encoding="unicode")
