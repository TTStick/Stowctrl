"""Optional server-side background removal (rembg).

Clients cut out photos on-device first; this endpoint only exists for
devices where that fails. It is enabled when the image is built with
WITH_CUTOUT=true, which installs rembg and onnxruntime.
"""
from __future__ import annotations

import importlib.util
import io
import threading

from . import config

_lock = threading.Lock()
_session = None


def available() -> bool:
    return importlib.util.find_spec("rembg") is not None


def remove_background(data: bytes) -> bytes:
    from PIL import Image
    from rembg import new_session, remove

    # Opening only reads the header: refuse decompression bombs up front.
    with Image.open(io.BytesIO(data)) as probe:
        if probe.width * probe.height > config.MAX_IMAGE_PIXELS:
            raise ValueError("image too large")

    global _session
    with _lock:
        if _session is None:
            # First call downloads the model into U2NET_HOME (a volume in Docker).
            _session = new_session(config.CUTOUT_MODEL)
        session = _session
    result = remove(data, session=session, post_process_mask=True)
    if not isinstance(result, (bytes, bytearray)):
        raise RuntimeError("unexpected rembg output")
    return bytes(result)
