#!/usr/bin/env python3
"""xrnhabit sync server: the state the phone and the laptop share, behind HTTP Basic auth.

One JSON document per user, the `SharedState` of `:domain` (docs/domain-contract.md §2.4).
The server keeps no logic of its own: each device merges three ways on its side and writes back
with If-Match, so two devices can't overwrite each other. Made to run on a small home server
next to Radicale, published under a path (e.g. /xrnhabit) by a reverse proxy such as Tailscale
Funnel, which may or may not strip that prefix. Standard library only, plus `bcrypt` to read an
htpasswd file (Radicale's works as-is, so the calendar password is the sync password).

    GET  /state               200, the document; ETag: "<version>"
                              If-None-Match: "<version>" -> 304 when unchanged (a cheap check)
                              ?wait=S with If-None-Match long-polls up to S seconds (the laptop)
    PUT  /state               needs If-Match: "<version>" (or "*"); 200 with the new ETag, or
                              412 if someone wrote first: re-read, merge, try again
    GET  /health              200 "ok", no login (for monitoring)
    GET  /, /status.js, ...   a read-only status page (static files; the data needs a login)

    python3 xrnhabit-sync.py --data ~/xrnhabit-sync/data --users ~/.config/radicale/users \
        --web ~/xrnhabit-sync/web
"""
import argparse
import base64
import gzip
import hashlib
import json
import mimetypes
import os
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

MAX_BODY = 8 * 1024 * 1024
FAIL_DELAY_S = 2  # after a wrong password, as Radicale does
AUTH_CACHE_S = 600  # a bcrypt check costs ~0.3s on a phone; don't pay it per request
MAX_WAIT_S = 55
PREFIX = "/xrnhabit"

args = None
lock = threading.Lock()
changed = threading.Condition(lock)
auth_cache = {}  # sha256(user:password) -> (user, expires, users-file mtime)


def users_mtime():
    try:
        return os.stat(args.users).st_mtime
    except OSError:
        return 0


def check_login(header):
    """The user name for a valid `Authorization: Basic ...`, else None."""
    if not header or not header.startswith("Basic "):
        return None
    try:
        user, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
    except Exception:
        return None
    key = hashlib.sha256(f"{user}:{password}".encode()).hexdigest()
    mtime = users_mtime()
    hit = auth_cache.get(key)
    if hit and hit[1] > time.time() and hit[2] == mtime:
        return hit[0]
    import bcrypt  # only needed once someone logs in, so the tests can run without it

    try:
        with open(args.users, encoding="utf-8") as f:
            for line in f:
                name, _, hashed = line.strip().partition(":")
                if name == user and hashed and bcrypt.checkpw(password.encode(), hashed.encode()):
                    auth_cache[key] = (user, time.time() + AUTH_CACHE_S, mtime)
                    return user
    except (OSError, ValueError):
        pass
    return None


def state_path(user):
    return os.path.join(args.data, "users", user, "state.json")


def read_state(user):
    try:
        with open(state_path(user), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"version": 0, "data": {}}


def write_state(user, doc):
    path = state_path(user)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def etag_version(header):
    return (header or "").strip().removeprefix("W/").strip('"')


class Handler(BaseHTTPRequestHandler):
    server_version = "xrnhabit-sync/1"

    def log_message(self, fmt, *a):
        who = self.headers.get("X-Forwarded-For", self.client_address[0]) if self.headers else "-"
        sys.stderr.write(f"{who} {fmt % a}\n")

    def route(self):
        path, _, query = self.path.partition("?")
        if path == PREFIX or path.startswith(PREFIX + "/"):
            path = path[len(PREFIX):] or "/"
        return path, {k: v[-1] for k, v in parse_qs(query).items()}

    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None):
        if isinstance(body, str):
            body = body.encode()
        if len(body) > 1024 and "gzip" in (self.headers.get("Accept-Encoding") or ""):
            body = gzip.compress(body, 6)
            headers = {**(headers or {}), "Content-Encoding": "gzip"}
        self.send_response(code)
        self.send_header("Cache-Control", "no-store")
        if code != 304:
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD" and code != 304:
            self.wfile.write(body)

    def login(self):
        user = check_login(self.headers.get("Authorization"))
        if not user:
            time.sleep(FAIL_DELAY_S)
            # The status page logs in with its own form: a challenge would pop the browser's dialog.
            challenge = {} if self.headers.get("X-Xrn-Client") else {"WWW-Authenticate": 'Basic realm="xrnhabit"'}
            self.send(401, "login required", headers=challenge)
        return user

    def do_GET(self):
        path, query = self.route()
        if path == "/health":
            return self.send(200, "ok")
        if path != "/state":
            return self.static(path)
        user = self.login()
        if not user:
            return
        known = etag_version(self.headers.get("If-None-Match"))
        try:
            wait = min(max(float(query.get("wait", 0)), 0), MAX_WAIT_S)
        except ValueError:
            return self.send(400, "bad wait")
        with lock:
            doc = read_state(user)
            deadline = time.monotonic() + wait
            while known and known == str(doc["version"]):
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                changed.wait(left)
                doc = read_state(user)
        etag = {"ETag": f'"{doc["version"]}"'}
        if known and known == str(doc["version"]):
            return self.send(304, headers=etag)
        body = json.dumps(doc.get("data") or {}, ensure_ascii=False, separators=(",", ":"))
        self.send(200, body, "application/json; charset=utf-8",
                  {**etag, "X-Updated": doc.get("updated", ""), "X-Updated-By": doc.get("by", "")})

    def do_PUT(self):
        path, _ = self.route()
        user = self.login()
        if not user:
            return
        if path != "/state":
            return self.send(404, "not found")
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self.send(413, "body missing or too large")
        raw = self.rfile.read(length)
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip":
            raw = gzip.decompress(raw)
        try:
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise ValueError
        except ValueError:
            return self.send(400, "body must be a JSON object")
        want = (self.headers.get("If-Match") or "").strip()
        if not want:
            return self.send(428, "If-Match required")
        with lock:
            cur = read_state(user)
            if want != "*" and etag_version(want) != str(cur["version"]):
                return self.send(412, "changed since you read it", headers={"ETag": f'"{cur["version"]}"'})
            version = cur["version"] + 1
            by = (self.headers.get("X-Device") or "")[:64]
            write_state(user, {"version": version, "updated": datetime.now(timezone.utc).isoformat(),
                               "by": by, "data": data})
            changed.notify_all()
        self.send(200, '{"ok":true}', "application/json", {"ETag": f'"{version}"'})

    def static(self, path):
        if path in ("", "/"):
            path = "/index.html"
        name = os.path.normpath(path.lstrip("/"))
        file = os.path.join(args.web, name)
        if name.startswith("..") or not os.path.isfile(file):
            return self.send(404, "not found")
        with open(file, "rb") as f:
            data = f.read()
        ctype = mimetypes.guess_type(file)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "image/svg+xml"):
            ctype += "; charset=utf-8"
        self.send(200, data, ctype)


def main():
    global args
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5235)
    p.add_argument("--data", required=True, help="where the documents live")
    p.add_argument("--users", required=True, help="htpasswd file with bcrypt hashes")
    p.add_argument("--web", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "web"),
                   help="the status page's folder")
    args = p.parse_args()
    args.data = os.path.expanduser(args.data)
    args.users = os.path.expanduser(args.users)
    args.web = os.path.abspath(os.path.expanduser(args.web))
    mimetypes.add_type("text/javascript", ".js")
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    sys.stderr.write(f"xrnhabit-sync on http://{args.host}:{args.port}, data in {args.data}\n")
    srv.serve_forever()


if __name__ == "__main__":
    main()
