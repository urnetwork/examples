"""HTTPX and Requests transports over UR Conn. Sync HTTP/1.1; TLS uses MemoryBIO.

Install httpcore, httpx, requests and urllib3 alongside urnetwork-sdk.
No monkeypatching, OS socket descriptor, or native DNS resolution is used.
"""
import io
import ssl
import time
from email.message import Message
from types import SimpleNamespace
from urllib.parse import urlsplit
import httpcore
import httpx
import requests
import urllib3


def deadline(timeout):
    return 0 if timeout is None else int(time.time() * 1000 + timeout * 1000)


class UrNetworkStream(httpcore.NetworkStream):
    def __init__(self, conn):
        self.conn = conn
        self.pending = None

    def read(self, max_bytes, timeout=None):
        if self.pending:
            error, self.pending = self.pending, None
            raise httpcore.ReadError(str(error))
        self.conn.set_read_deadline(deadline(timeout))
        try:
            return self.conn.read(max_bytes).data
        except OSError as error:
            if getattr(error, "data", b""):
                self.pending = error
                return error.data
            if "timeout" in str(error).lower():
                raise httpcore.ReadTimeout(str(error)) from error
            raise httpcore.ReadError(str(error)) from error

    def write(self, buffer, timeout=None):
        self.conn.set_write_deadline(deadline(timeout))
        offset = 0
        try:
            while offset < len(buffer):
                n = self.conn.write(buffer[offset:offset + 65535])
                if n <= 0:
                    raise OSError("socket write made no progress")
                offset += n
        except OSError as error:
            cls = httpcore.WriteTimeout if "timeout" in str(error).lower() else httpcore.WriteError
            raise cls(str(error)) from error

    def close(self):
        self.conn.close()

    def start_tls(self, ssl_context, server_hostname=None, timeout=None):
        stream = UrTLSStream(self, ssl_context, server_hostname)
        try:
            stream.perform(stream.ssl.do_handshake, timeout)
        except Exception:
            self.close()
            raise
        return stream

    def get_extra_info(self, info):
        return None


class UrTLSStream(httpcore.NetworkStream):
    def __init__(self, transport, context, hostname):
        self.transport = transport
        self.incoming, self.outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.ssl = context.wrap_bio(self.incoming, self.outgoing, server_side=False, server_hostname=hostname)

    def flush(self, timeout):
        while self.outgoing.pending:
            self.transport.write(self.outgoing.read(), timeout)

    def perform(self, operation, timeout):
        end = None if timeout is None else time.monotonic() + timeout
        def remaining():
            if end is None:
                return None
            value = end - time.monotonic()
            if value <= 0:
                raise httpcore.ReadTimeout("TLS operation timed out")
            return value
        while True:
            try:
                result = operation()
                self.flush(remaining())
                return result
            except ssl.SSLWantWriteError:
                self.flush(remaining())
            except ssl.SSLWantReadError:
                self.flush(remaining())
                chunk = self.transport.read(65535, remaining())
                if chunk:
                    self.incoming.write(chunk)
                else:
                    self.incoming.write_eof()
            except ssl.SSLZeroReturnError:
                return b""
            except ssl.SSLError as error:
                raise httpcore.ConnectError(str(error)) from error

    def read(self, max_bytes, timeout=None):
        return self.perform(lambda: self.ssl.read(max_bytes), timeout)

    def write(self, buffer, timeout=None):
        offset = 0
        end = None if timeout is None else time.monotonic() + timeout
        while offset < len(buffer):
            remaining = None if end is None else max(0, end - time.monotonic())
            n = self.perform(lambda: self.ssl.write(buffer[offset:]), remaining)
            if not isinstance(n, int) or n <= 0:
                raise httpcore.WriteError("TLS write made no progress")
            offset += n

    def close(self):
        self.transport.close()

    def get_extra_info(self, info):
        return self.ssl if info == "ssl_object" else None


class UrBackend(httpcore.NetworkBackend):
    def __init__(self, device):
        self.device = device

    def connect_tcp(self, host, port, timeout=None, local_address=None, socket_options=None):
        if local_address or socket_options:
            raise ValueError("Local bind and kernel socket options are unsupported")
        if isinstance(host, bytes):
            host = host.decode("ascii")
        address = ("[" + host + "]" if ":" in host else host) + ":" + str(port)
        millis = 30000 if timeout is None else max(1, int(timeout * 1000))
        try:
            return UrNetworkStream(self.device.dial("tcp", address, timeout_millis=millis))
        except OSError as error:
            raise httpcore.ConnectError(str(error)) from error

    def connect_unix_socket(self, *args, **kwargs):
        raise ValueError("UR sockets do not expose Unix sockets")


class HttpxBody(httpx.SyncByteStream):
    def __init__(self, stream):
        self.stream = stream
    def __iter__(self):
        yield from self.stream
    def close(self):
        self.stream.close()


class UrHttpxTransport(httpx.BaseTransport):
    def __init__(self, device, ssl_context=None):
        self.pool = httpcore.ConnectionPool(network_backend=UrBackend(device),
                                            ssl_context=ssl_context or ssl.create_default_context(),
                                            http1=True, http2=False)

    def handle_request(self, request):
        try:
            response = self.pool.handle_request(httpcore.Request(
                method=request.method, url=str(request.url), headers=request.headers.raw,
                content=request.stream, extensions=request.extensions))
        except httpcore.TimeoutException as error:
            raise httpx.TimeoutException(str(error), request=request) from error
        except httpcore.NetworkError as error:
            raise httpx.NetworkError(str(error), request=request) from error
        return httpx.Response(response.status, headers=response.headers,
                              stream=HttpxBody(response.stream), extensions=response.extensions)

    def close(self):
        self.pool.close()


class CoreBody(io.RawIOBase):
    def __init__(self, stream, pool):
        self.stream, self.iterator, self.pending = stream, iter(stream), b""
        self.pool = pool
    def readable(self):
        return True
    def readinto(self, buffer):
        if len(buffer) == 0:
            return 0
        while not self.pending:
            self.pending = next(self.iterator, None)
            if self.pending is None:
                return 0
        n = min(len(buffer), len(self.pending))
        buffer[:n], self.pending = self.pending[:n], self.pending[n:]
        return n
    def close(self):
        if self.closed:
            return
        try:
            self.stream.close()
        finally:
            self.pool.close()
            super().close()


class UrRequestsAdapter(requests.adapters.BaseAdapter):
    def __init__(self, device):
        self.backend = UrBackend(device)

    def send(self, request, stream=False, timeout=None, verify=True, cert=None, proxies=None):
        if proxies:
            raise ValueError("Use session.trust_env=False; UR supplies the connection path")
        if verify is False:
            raise ValueError("Certificate verification is required")
        context = ssl.create_default_context(cafile=verify if isinstance(verify, str) else requests.certs.where())
        if cert:
            context.load_cert_chain(*(cert if isinstance(cert, tuple) else (cert,)))
        if isinstance(timeout, tuple):
            connect_timeout, read_timeout = timeout
        else:
            connect_timeout = read_timeout = timeout
        # One pool per response keeps per-request trust/client certificates isolated.
        pool = httpcore.ConnectionPool(network_backend=self.backend, ssl_context=context, http2=False)
        content = request.body or b""
        headers = dict(request.headers)
        if not any(name.lower() == "host" for name in headers):
            url = urlsplit(request.url)
            host = "[" + url.hostname + "]" if ":" in url.hostname else url.hostname
            headers["Host"] = host + (":" + str(url.port) if url.port else "")
        if isinstance(content, str):
            content = content.encode()
        try:
            core = pool.handle_request(httpcore.Request(
                request.method, request.url, headers=list(headers.items()),
                content=content, extensions={"timeout": {
                    "connect": connect_timeout, "read": read_timeout, "write": read_timeout, "pool": connect_timeout}}))
        except httpcore.TimeoutException as error:
            pool.close()
            raise requests.Timeout(str(error), request=request) from error
        except (httpcore.NetworkError, httpcore.ProtocolError) as error:
            pool.close()
            raise requests.ConnectionError(str(error), request=request) from error
        except Exception:
            pool.close()
            raise
        body = CoreBody(core.stream, pool)
        headers = urllib3.HTTPHeaderDict()
        message = Message()
        for name, value in core.headers:
            key, val = name.decode("ascii"), value.decode("latin-1")
            message.add_header(key, val)
            if key.lower() != "transfer-encoding":  # httpcore already removed chunk framing.
                headers.add(key, val)
        raw = urllib3.response.HTTPResponse(body=io.BufferedReader(body), headers=headers,
                                            status=core.status, preload_content=False,
                                            original_response=SimpleNamespace(msg=message, isclosed=lambda: body.closed))
        response = requests.Response()
        response.status_code, response.headers = core.status, requests.structures.CaseInsensitiveDict(headers)
        response.raw, response.url, response.request = raw, request.url, request
        response.encoding = requests.utils.get_encoding_from_headers(response.headers)
        requests.cookies.extract_cookies_to_jar(response.cookies, request, raw)
        return response

    def close(self):
        pass
