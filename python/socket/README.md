# Python sockets, HTTPX and Requests

[main.py](main.py) is a complete local-Device program. [ur_http.py](ur_http.py) implements a synchronous HTTPcore network backend and adapters for HTTPX and Requests over `urnetwork.Conn`.

## Install and run

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python main.py --version
python main.py --new-id
python main.py --mode httpx --url https://example.com/
python main.py --mode requests --url https://example.com/
python main.py --mode tls --url https://example.com/
python main.py --mode udp --target echo.example:9000
python main.py --mode dtls --target dtls.example:9001
```

Set the Device environment variables below before live modes. The native package is `pip install urnetwork-sdk`. Before the socket package's first publication, build `make -C sdk/python package check-package`, then install its matching wheel before the remaining requirements. Python 3.10+ and a platform wheel for your OS/CPU are required. This example pins HTTPX 0.28.1 and HTTPcore 1.0.9 because their transport contracts are versioned independently.

## Replace HTTP connection creation

| Library | Integration in this directory |
| --- | --- |
| Python socket-like I/O | `device.dial("tcp", host_port)` returns Conn; its read result distinguishes EOF from empty UDP. |
| HTTPX | `httpx.Client(transport=UrHttpxTransport(device), trust_env=False)`. |
| HTTPcore | `NetworkBackend.connect_tcp` dials through the Device and returns the custom `NetworkStream`. |
| Requests | Mount `UrRequestsAdapter(device)` for both `http://` and `https://`. |

The HTTP backend uses Python `SSLContext.wrap_bio` and `MemoryBIO` for TLS over the UR byte stream; it does not pass a fake file descriptor into `SSLSocket`. Certificate and hostname verification stay enabled. This supports the synchronous HTTP/1.1 path; async clients and HTTP/2 require additional adapters. Deadlines, partial writes, partial-data errors, pool closure and response-body closure are handled explicitly. Requests retains its redirect/cookie handling and urllib3 response decoding.

## Tests

```sh
python -m unittest -v
```

Local fixtures exercise both clients over HTTP and verified HTTPS, a deliberately untrusted certificate, partial writes/reads, compressed content, redirects, and cookies. OpenSSL is used to create a temporary test certificate; no account is required.

Sources checked September 14, 2026: [HTTPX transports](https://www.python-httpx.org/advanced/transports/), [HTTPcore network backends](https://www.encode.io/httpcore/network-backends/), [Requests transport adapters](https://requests.readthedocs.io/en/latest/user/advanced/#transport-adapters), [Python MemoryBIO TLS](https://docs.python.org/3/library/ssl.html#ssl.SSLContext.wrap_bio).

## Device setup

The executable creates a local Device using the SDK's existing network-space APIs, applies your JWT, and chooses the best available location. Set an account JWT as described in the [SDK setup guide](https://ur.io/docs/getting-started-sdk). Generate an instance ID once with this program's `--new-id` mode, save it, and reuse it for this installation.

```sh
export URNETWORK_JWT='your-account-jwt'
export URNETWORK_INSTANCE_ID='your-persisted-instance-uuid'
```

Keep the network-space manager alive until the Device closes. The sample owns and releases these objects in that order. HTTP proxy modes use the Go proxy's Device instead.

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
