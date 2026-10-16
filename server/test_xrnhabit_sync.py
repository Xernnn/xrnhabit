"""Tests for xrnhabit-sync.py: optimistic concurrency, the cheap 304 check, the long-poll.

    python3 -m unittest server/test_xrnhabit_sync.py     (from the repo root)
"""
import base64
import importlib.util
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("xrnhabit_sync", os.path.join(HERE, "xrnhabit-sync.py"))
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)


class SyncServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import bcrypt

        cls.tmp = tempfile.TemporaryDirectory()
        users = os.path.join(cls.tmp.name, "htpasswd")
        with open(users, "w") as f:
            f.write("sonn:" + bcrypt.hashpw(b"secret", bcrypt.gensalt(4)).decode() + "\n")
        web = os.path.join(cls.tmp.name, "web")
        os.makedirs(web)
        with open(os.path.join(web, "index.html"), "w") as f:
            f.write("<!doctype html>status")
        srv.args = type("Args", (), {"data": os.path.join(cls.tmp.name, "data"), "users": users, "web": web})()
        srv.FAIL_DELAY_S = 0
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
        cls.httpd.daemon_threads = True
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.tmp.cleanup()

    def call(self, method, path, body=None, headers=None, password="secret"):
        req = urllib.request.Request(self.base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", "Basic " + base64.b64encode(f"sonn:{password}".encode()).decode())
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(), e.headers

    def test_write_needs_the_version_it_read(self):
        status, body, headers = self.call("GET", "/xrnhabit/state")
        self.assertEqual(200, status)
        v0 = headers["ETag"]
        self.assertEqual(428, self.call("PUT", "/state", {"a": 1})[0])
        status, _, headers = self.call("PUT", "/state", {"a": 1}, {"If-Match": v0})
        self.assertEqual(200, status)
        v1 = headers["ETag"]
        self.assertNotEqual(v0, v1)
        status, _, headers = self.call("PUT", "/state", {"a": 2}, {"If-Match": v0})  # stale
        self.assertEqual(412, status)
        self.assertEqual(v1, headers["ETag"])
        _, body, _ = self.call("GET", "/state")
        self.assertEqual({"a": 1}, json.loads(body))

    def test_unchanged_answers_304(self):
        _, _, headers = self.call("GET", "/state")
        status, body, _ = self.call("GET", "/state", headers={"If-None-Match": headers["ETag"]})
        self.assertEqual((304, ""), (status, body))

    def test_long_poll_wakes_on_a_write(self):
        _, _, headers = self.call("GET", "/state")
        etag = headers["ETag"]
        threading.Timer(0.3, lambda: self.call("PUT", "/state", {"b": 1}, {"If-Match": etag})).start()
        start = time.monotonic()
        status, body, headers2 = self.call("GET", "/state?wait=20", headers={"If-None-Match": etag})
        self.assertLess(time.monotonic() - start, 5)
        self.assertEqual(200, status)
        self.assertEqual({"b": 1}, json.loads(body))
        self.assertNotEqual(etag, headers2["ETag"])

    def test_long_poll_times_out_with_304(self):
        _, _, headers = self.call("GET", "/state")
        start = time.monotonic()
        status, _, _ = self.call("GET", "/state?wait=0.5", headers={"If-None-Match": headers["ETag"]})
        self.assertEqual(304, status)
        self.assertGreaterEqual(time.monotonic() - start, 0.4)

    def test_login_and_open_paths(self):
        self.assertEqual(401, self.call("GET", "/state", password="nope")[0])
        status, _, headers = self.call("GET", "/state", password="nope", headers={"X-Xrn-Client": "web"})
        self.assertEqual(401, status)
        self.assertIsNone(headers.get("WWW-Authenticate"))
        with urllib.request.urlopen(self.base + "/xrnhabit/health") as r:
            self.assertEqual("ok", r.read().decode())
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("status", r.read().decode())


if __name__ == "__main__":
    unittest.main()
