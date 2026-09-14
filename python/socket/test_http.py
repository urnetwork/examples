"""Local adapter integration tests; no account, host DNS, or public server."""
from contextlib import contextmanager
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
import httpcore
import httpx
import requests
from ur_http import UrHttpxTransport, UrRequestsAdapter, UrNetworkStream


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_):
        pass
    def do_GET(self):
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/cookie")
            self.send_header("Set-Cookie", "example=yes; Path=/")
            data = b""
        else:
            self.send_response(200)
            data = self.headers.get("Cookie", "").encode() if self.path == "/cookie" else "héllo".encode()
        if self.path == "/gzip":
            data = gzip.compress(data)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_POST(self):
        data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class FixtureConn:
    # Exposes only the SDK methods. The adapters cannot access a file descriptor.
    def __init__(self, endpoint):
        self._socket = socket.create_connection(endpoint, timeout=5)
        self.closed = False
    def set_read_deadline(self, millis):
        self._socket.settimeout(None if not millis else max(.001, millis / 1000 - time.time()))
    set_write_deadline = set_read_deadline
    def read(self, size):
        data = self._socket.recv(size)
        return SimpleNamespace(data=data, eof=not data)
    def write(self, data):
        # Force partial native writes; the adapter must finish the byte sequence.
        return self._socket.send(data[:7])
    def close(self):
        self.closed = True
        self._socket.close()


class FixtureDevice:
    def __init__(self, endpoint):
        self.endpoint, self.names, self.conns = endpoint, [], []
    def dial(self, network, address, **_):
        assert network == "tcp"
        self.names.append(address)
        conn = FixtureConn(self.endpoint)
        self.conns.append(conn)
        return conn


@contextmanager
def server(tls=False):
    with tempfile.TemporaryDirectory() as temp:
        http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cert = Path(temp) / "cert.pem"
        if tls:
            key = Path(temp) / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-keyout", str(key), "-out", str(cert), "-days", "1",
                            "-subj", "/CN=socket.test", "-addext", "subjectAltName=DNS:socket.test"],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(cert, key)
            http.socket = context.wrap_socket(http.socket, server_side=True)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            yield FixtureDevice(http.server_address), ("https" if tls else "http") + "://socket.test:" + str(http.server_port), cert
        finally:
            http.shutdown()
            http.server_close()
            thread.join(5)


class AdapterTests(unittest.TestCase):
    def test_httpx_plain_and_verified_tls(self):
        for tls in (False, True):
            with self.subTest(tls=tls), server(tls) as (device, url, cert):
                context = ssl.create_default_context(cafile=str(cert)) if tls else None
                with httpx.Client(transport=UrHttpxTransport(device, context), trust_env=False, timeout=5) as client:
                    self.assertEqual(client.get(url).text, "héllo")
                    self.assertEqual(client.post(url, content="data").text, "data")
                    self.assertEqual(client.get(url + "/gzip").text, "héllo")
                self.assertTrue(all(c.closed for c in device.conns))
                self.assertTrue(all(name.startswith("socket.test:") for name in device.names))

    def test_requests_plain_and_verified_tls_redirect_cookie_and_compression(self):
        for tls in (False, True):
            with self.subTest(tls=tls), server(tls) as (device, url, cert):
                with requests.Session() as client:
                    client.trust_env = False
                    client.verify = str(cert) if tls else True
                    adapter = UrRequestsAdapter(device)
                    client.mount("http://", adapter)
                    client.mount("https://", adapter)
                    self.assertEqual(client.get(url, timeout=5).text, "héllo")
                    self.assertEqual(client.post(url, data="data", timeout=5).text, "data")
                    self.assertEqual(client.get(url + "/gzip", timeout=5).text, "héllo")
                    self.assertIn("example=yes", client.get(url + "/redirect", timeout=5).text)
                self.assertTrue(all(c.closed for c in device.conns))

    def test_untrusted_certificate_is_rejected(self):
        with server(True) as (device, url, _):
            with httpx.Client(transport=UrHttpxTransport(device), trust_env=False, timeout=5) as client:
                with self.assertRaises(httpx.NetworkError):
                    client.get(url)
            self.assertTrue(all(c.closed for c in device.conns))

    def test_partial_read_error_is_not_silently_lost(self):
        class Partial(FixtureConn):
            def __init__(self):
                pass
            def set_read_deadline(self, _):
                pass
            def read(self, _):
                error = OSError("read failed")
                error.data = b"prefix"
                raise error
        stream = UrNetworkStream(Partial())
        self.assertEqual(stream.read(20), b"prefix")
        with self.assertRaises(httpcore.ReadError):
            stream.read(20)


if __name__ == "__main__":
    unittest.main()
