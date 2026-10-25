#!/usr/bin/env python3
"""Minimal stand-in for Home Assistant's REST API (GET /api/, /api/states, POST /api/services)."""
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
TOKEN = "test-token"
states = {
    "light.living_room": {"state": "off", "attributes": {"friendly_name": "客厅灯", "brightness": 0}},
    "switch.washer_power": {"state": "on", "attributes": {"friendly_name": "洗衣机插座"}},
    "sensor.fridge_temp": {"state": "4.5", "attributes": {"friendly_name": "冰箱温度", "unit_of_measurement": "°C"}},
    "climate.bedroom_ac": {"state": "cool", "attributes": {"friendly_name": "卧室空调", "current_temperature": 27, "temperature": 26}},
    "cover.living_curtain": {"state": "closed", "attributes": {"friendly_name": "客厅窗帘"}},
}
calls = []
class H(BaseHTTPRequestHandler):
    def _auth(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_response(401); self.end_headers(); return False
        return True
    def _json(self, obj, code=200):
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        if not self._auth(): return
        if self.path == "/api/": return self._json({"message": "API running."})
        if self.path == "/api/states": return self._json([{"entity_id": k, **v, "last_changed": "2026-10-25T00:00:00Z"} for k, v in states.items()])
        if self.path == "/calls": return self._json(calls)
        self._json({"message": "not found"}, 404)
    def do_POST(self):
        if not self._auth(): return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        parts = self.path.strip("/").split("/")
        if len(parts) == 4 and parts[:2] == ["api", "services"]:
            domain, service = parts[2], parts[3]
            eid = body.get("entity_id"); calls.append([domain, service, body])
            s = states.get(eid)
            if s:
                if service == "turn_on": s["state"] = "on" if domain != "climate" else "cool"
                elif service == "turn_off": s["state"] = "off"
                elif service == "open_cover": s["state"] = "open"
                elif service == "close_cover": s["state"] = "closed"
                elif service == "set_temperature": s["attributes"]["temperature"] = body.get("temperature")
            return self._json([])
        self._json({"message": "not found"}, 404)
    def log_message(self, *a): pass
ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), H).serve_forever()
