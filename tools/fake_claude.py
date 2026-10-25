#!/usr/bin/env python3
"""Minimal stand-in for the Claude Messages API, for testing the apps'
direct (no server) AI icon mode without spending tokens.

  python3 tools/fake_claude.py 18200
Accepts API key "test-key". GET /calls returns what was received."""
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">'
       '<path d="M24 6 a8 10 0 1 0 0.1 0 Z M24 26 V44" stroke="currentColor" fill="none" stroke-width="2.5"/></svg>')
calls = []

class H(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authed(self):
        if self.headers.get("x-api-key") != "test-key":
            self._json({"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}, 401)
            return False
        return True

    def do_GET(self):
        if self.path == "/calls":
            return self._json(calls)
        if self.path.startswith("/v1/models"):
            if self._authed():
                self._json({"data": [{"id": "claude-opus-5-5", "type": "model"}], "has_more": False})
            return
        self._json({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        if self.path != "/v1/messages":
            return self._json({"error": {"message": "not found"}}, 404)
        if not self._authed():
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        calls.append({"headers": {k.lower(): v for k, v in self.headers.items()}, "body": body})
        self._json({
            "id": "msg_fake", "type": "message", "role": "assistant", "model": body.get("model"),
            "content": [{"type": "text", "text": "好的：\n" + SVG}],
            "stop_reason": "end_turn", "stop_sequence": None,
            "usage": {"input_tokens": 10, "output_tokens": 20},
        })

    def log_message(self, *a):
        pass

ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 18200), H).serve_forever()
