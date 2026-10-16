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
    GET  /devices             when each device last checked in (X-Device), for the status page
    POST /events              {"events": [{"t": "blocked"|"habit"|"unlock", "at": ms, ...}]} from a
                              device's local log (X-Device); duplicates are dropped. 120 days kept
    GET  /events?since=MS     those events, for the weekly review in the habits web app
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
FAIL_LIMIT = 10  # wrong logins from one address within FAIL_WINDOW_S, then 429 until they age out
FAIL_WINDOW_S = 15 * 60
SEEN_WRITE_S = 60  # devices.json is rewritten at most this often per user
EVENTS_KEEP_DAYS = 120
EVENT_TYPES = {"blocked", "habit", "unlock"}
PREFIX = "/xrnhabit"

args = None
lock = threading.Lock()
changed = threading.Condition(lock)
auth_cache = {}  # sha256(user:password) -> (user, expires, users-file mtime)
fail_lock = threading.Lock()
failures = {}  # client address -> monotonic times of its recent wrong logins
seen_lock = threading.Lock()
seen = {}  # user -> {device: epoch ms of its last request}, mirrored to devices.json
seen_written = {}  # user -> time.monotonic() of the last devices.json write
events_lock = threading.Lock()
events = {}  # user -> list of event dicts, oldest first, mirrored to events.jsonl


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


def client_address(handler):
    """The caller's address. Behind the local proxy (Funnel) that's the first X-Forwarded-For hop;
    the header is only trusted from a loopback peer, since anyone else could make it up."""
    peer = handler.client_address[0]
    forwarded = handler.headers.get("X-Forwarded-For")
    if forwarded and peer in ("127.0.0.1", "::1"):
        return forwarded.split(",")[0].strip()
    return peer


def locked_out(addr):
    """Whether this address has used up its wrong logins for now."""
    now = time.monotonic()
    with fail_lock:
        recent = [t for t in failures.get(addr, ()) if now - t < FAIL_WINDOW_S]
        if recent:
            failures[addr] = recent
        else:
            failures.pop(addr, None)
        return len(recent) >= FAIL_LIMIT


def note_failure(addr):
    now = time.monotonic()
    with fail_lock:
        if len(failures) > 10000:  # a flood from many addresses: forget the stale ones
            for a in [a for a, ts in failures.items() if now - ts[-1] >= FAIL_WINDOW_S]:
                del failures[a]
        failures.setdefault(addr, []).append(now)


def devices_path(user):
    return os.path.join(args.data, "users", user, "devices.json")


def note_device(user, device):
    """Remember when [device] (the phone, the laptop) last checked in."""
    device = (device or "")[:64]
    if not device:
        return
    with seen_lock:
        devices = seen.get(user)
        if devices is None:
            try:
                with open(devices_path(user), encoding="utf-8") as f:
                    devices = json.load(f)
            except (OSError, ValueError):
                devices = {}
            seen[user] = devices
        devices[device] = int(time.time() * 1000)
        if time.monotonic() - seen_written.get(user, -SEEN_WRITE_S) >= SEEN_WRITE_S:
            seen_written[user] = time.monotonic()
            path = devices_path(user)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path + ".tmp", "w", encoding="utf-8") as f:
                json.dump(devices, f)
            os.replace(path + ".tmp", path)


def devices_of(user):
    with seen_lock:
        if user in seen:
            return dict(seen[user])
    try:
        with open(devices_path(user), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def events_path(user):
    return os.path.join(args.data, "users", user, "events.jsonl")


def event_key(e):
    return (e.get("d"), e.get("t"), e.get("at"), e.get("block"), e.get("target"), e.get("ms"))


def clean_event(raw, device):
    """A device's event as stored, or None if it isn't one we know."""
    if not isinstance(raw, dict) or raw.get("t") not in EVENT_TYPES or not isinstance(raw.get("at"), int):
        return None
    e = {"d": device, "t": raw["t"], "at": raw["at"]}
    for k in ("block", "target", "name"):
        if isinstance(raw.get(k), str) and raw[k]:
            e[k] = raw[k][:200]
    if isinstance(raw.get("ms"), int) and raw["ms"] >= 0:
        e["ms"] = raw["ms"]
    return e


def load_events(user):
    """The user's events (cached), dropping any past EVENTS_KEEP_DAYS. Call under events_lock."""
    if user not in events:
        kept = []
        try:
            with open(events_path(user), encoding="utf-8") as f:
                for line in f:
                    try:
                        kept.append(json.loads(line))
                    except ValueError:
                        pass
        except OSError:
            pass
        events[user] = kept
    cutoff = int((time.time() - EVENTS_KEEP_DAYS * 86400) * 1000)
    if events[user] and events[user][0].get("at", 0) < cutoff:
        events[user] = [e for e in events[user] if e.get("at", 0) >= cutoff]
        rewrite_events(user)
    return events[user]


def rewrite_events(user):
    path = events_path(user)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        for e in events[user]:
            f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
    os.replace(path + ".tmp", path)


def add_events(user, device, raw_events):
    """Stores the new ones; returns how many were new."""
    with events_lock:
        stored = load_events(user)
        known = {event_key(e) for e in stored}
        new = []
        for raw in raw_events:
            e = clean_event(raw, device)
            if e and event_key(e) not in known:
                known.add(event_key(e))
                new.append(e)
        if new:
            path = events_path(user)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "a", encoding="utf-8") as f:
                for e in new:
                    f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
            stored.extend(new)
            stored.sort(key=lambda e: e.get("at", 0))
        return len(new)


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
        addr = client_address(self)
        if locked_out(addr):
            time.sleep(FAIL_DELAY_S)
            self.send(429, "too many wrong passwords; try again later", headers={"Retry-After": str(FAIL_WINDOW_S)})
            return None
        user = check_login(self.headers.get("Authorization"))
        if not user:
            if self.headers.get("Authorization"):
                note_failure(addr)
            time.sleep(FAIL_DELAY_S)
            # The status page logs in with its own form: a challenge would pop the browser's dialog.
            challenge = {} if self.headers.get("X-Xrn-Client") else {"WWW-Authenticate": 'Basic realm="xrnhabit"'}
            self.send(401, "login required", headers=challenge)
            return None
        with fail_lock:
            failures.pop(addr, None)
        note_device(user, self.headers.get("X-Device"))
        return user

    def do_GET(self):
        path, query = self.route()
        if path == "/health":
            return self.send(200, "ok")
        if path == "/devices":
            user = self.login()
            if user:
                self.send(200, json.dumps(devices_of(user)), "application/json; charset=utf-8")
            return
        if path == "/events":
            user = self.login()
            if not user:
                return
            try:
                since = int(query.get("since", 0))
            except ValueError:
                return self.send(400, "bad since")
            with events_lock:
                found = [e for e in load_events(user) if e.get("at", 0) >= since]
            return self.send(200, json.dumps({"events": found}, ensure_ascii=False, separators=(",", ":")),
                             "application/json; charset=utf-8")
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

    def do_POST(self):
        path, _ = self.route()
        user = self.login()
        if not user:
            return
        if path != "/events":
            return self.send(404, "not found")
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self.send(413, "body missing or too large")
        try:
            body = json.loads(self.rfile.read(length))
            raw = body["events"]
            if not isinstance(raw, list):
                raise ValueError
        except (ValueError, KeyError, TypeError):
            return self.send(400, 'body must be {"events": [...]}')
        device = (self.headers.get("X-Device") or "")[:64]
        if not device:
            return self.send(400, "X-Device required")
        added = add_events(user, device, raw)
        self.send(200, json.dumps({"ok": True, "added": added}), "application/json")

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
