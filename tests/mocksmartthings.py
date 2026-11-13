#!/usr/bin/env python3
# Copyright 2026 hubitrep
# SPDX-License-Identifier: Apache-2.0
"""Mock of the SmartThings OAuth and device-list endpoints, for testing Samsung TV SmartThings Link
without a SmartThings API plan.

Serves what the app calls, with SmartThings' documented rules:
  GET  /v1/oauth/authorize  checks client_id and redirect_uri, then redirects to redirect_uri with
                            code and state (or error=access_denied with --deny)
  POST /v1/oauth/token      HTTP Basic client auth; authorization_code (single-use code, same
                            redirect_uri) or refresh_token (single-use; the old access token stops
                            working at once); otherwise 400 {"error": "invalid_grant"}
  GET  /v1/devices          Bearer token; one TV (OCF, "Samsung OCF TV") and one non-TV device
Control:
  GET  /mock/state          counters and the current tokens' ages (no token values)
  POST /mock/revoke         makes the current refresh token invalid, as if SmartThings ended the session

Usage: mocksmartthings.py --client-id ID --client-secret SECRET [--port 8099] [--expires-in 86399] [--deny] [--second-tv]
"""

import argparse
import base64
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlparse

LOCK = threading.Lock()
S = {"codes": {}, "access": {}, "refresh": {}, "counts": {}, "events": []}
ARGS = None

TV_ID = "11111111-2222-3333-4444-555555555555"
DEVICES = [
    {"deviceId": TV_ID, "name": "[TV] Mock", "label": "Living Room TV", "type": "OCF",
     "deviceTypeName": "Samsung OCF TV", "components": [{"id": "main", "categories": [{"name": "Television"}]}]},
    {"deviceId": "99999999-0000-0000-0000-000000000000", "name": "Mock bulb", "label": "Mock bulb", "type": "ZIGBEE",
     "deviceTypeName": "ZigBee RGBW Bulb", "components": [{"id": "main", "categories": [{"name": "Light"}]}]},
]
SECOND_TV = {"deviceId": "66666666-7777-8888-9999-000000000000", "name": "[TV] Mock 2", "label": "Bedroom TV",
             "type": "OCF", "deviceTypeName": "Samsung OCF TV",
             "components": [{"id": "main", "categories": [{"name": "Television"}]}]}


def note(what):
    with LOCK:
        S["counts"][what] = S["counts"].get(what, 0) + 1
        S["events"].append([round(time.time(), 1), what])
        del S["events"][:-50]
    print(f"{time.strftime('%H:%M:%S')} {what}", flush=True)


def new_tokens():
    a, r = secrets.token_urlsafe(24), secrets.token_urlsafe(32)
    now = time.time()
    S["access"] = {a: now + ARGS.expires_in}      # one session: a new pair replaces the old one
    S["refresh"] = {r: now}
    return {"access_token": a, "token_type": "bearer", "refresh_token": r, "expires_in": ARGS.expires_in,
            "scope": "r:devices:* x:devices:*", "access_tier": 0, "installed_app_id": "mock-installed-app"}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body=None, headers=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if body is not None:
            self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/v1/oauth/authorize":
            if q.get("client_id") != ARGS.client_id:
                note("authorize: unknown client_id")
                return self.send(400, {"error": "invalid_client"})
            if q.get("response_type") != "code" or not q.get("redirect_uri"):
                note("authorize: bad request")
                return self.send(400, {"error": "invalid_request"})
            if ARGS.deny:
                note("authorize: denied")
                loc = q["redirect_uri"] + "?" + urlencode({"error": "access_denied", "state": q.get("state", "")})
                return self.send(302, None, {"Location": loc})
            code = secrets.token_urlsafe(16)
            with LOCK:
                S["codes"][code] = q["redirect_uri"]
            note(f"authorize: code issued, scope={q.get('scope')}")
            loc = q["redirect_uri"] + "?" + urlencode({"code": code, "state": q.get("state", "")})
            return self.send(302, None, {"Location": loc})
        if u.path == "/v1/devices":
            tok = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
            with LOCK:
                exp = S["access"].get(tok)
            if not exp or exp < time.time():
                note("devices: 401")
                return self.send(401, {"error": "invalid_token"})
            note("devices: 200")
            items = DEVICES + ([SECOND_TV] if ARGS.second_tv else [])
            return self.send(200, {"items": items, "_links": {}})
        if u.path == "/mock/state":
            with LOCK:
                now = time.time()
                return self.send(200, {"counts": S["counts"], "events": S["events"][-20:],
                                       "accessExpiresIn": [round(e - now) for e in S["access"].values()],
                                       "refreshAge": [round(now - t) for t in S["refresh"].values()]})
        return self.send(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode() if n else ""
        if u.path == "/mock/revoke":
            with LOCK:
                S["refresh"] = {}
            note("mock: refresh token revoked")
            return self.send(200, {"ok": True})
        if u.path != "/v1/oauth/token":
            return self.send(404, {"error": "not found"})
        auth = self.headers.get("Authorization") or ""
        try:
            cid, csec = base64.b64decode(auth.removeprefix("Basic ").strip()).decode().split(":", 1)
        except Exception:
            cid, csec = None, None
        if cid != ARGS.client_id or csec != ARGS.client_secret:
            note("token: 401 bad client credentials")
            return self.send(401, {"error": "invalid_client"})
        f = {k: v[0] for k, v in parse_qs(raw).items()}
        with LOCK:
            if f.get("grant_type") == "authorization_code":
                want = S["codes"].pop(f.get("code"), None)
                if not want or want != f.get("redirect_uri"):
                    ok = None
                else:
                    ok = new_tokens()
                what = "token: authorization_code"
            elif f.get("grant_type") == "refresh_token":
                ok = new_tokens() if f.get("refresh_token") in S["refresh"] else None
                what = "token: refresh_token"
            else:
                ok, what = None, f"token: grant_type {f.get('grant_type')}"
        if not ok:
            note(f"{what} -> 400 invalid_grant")
            return self.send(400, {"error": "invalid_grant", "error_description": "Invalid grant"})
        note(f"{what} -> 200")
        return self.send(200, ok)


def main():
    global ARGS
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--client-id", required=True)
    p.add_argument("--client-secret", required=True)
    p.add_argument("--port", type=int, default=8099)
    p.add_argument("--expires-in", type=int, default=86399, help="access token lifetime, seconds")
    p.add_argument("--deny", action="store_true", help="authorize answers error=access_denied")
    p.add_argument("--second-tv", action="store_true", help="the account holds a second TV")
    ARGS = p.parse_args()
    print(f"mock SmartThings on port {ARGS.port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", ARGS.port), H).serve_forever()


if __name__ == "__main__":
    main()
