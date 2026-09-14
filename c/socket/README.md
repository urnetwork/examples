# C sockets and libcurl

[main.c](main.c) demonstrates the C ABI directly: create a Device, dial a verified TLS connection, exchange bytes, and release the connection handle. It also contains UDP, DTLS and libcurl modes. [ur_session.h](ur_session.h) handles Device bootstrap and ownership.

## Build and run

Install the SDK through the C/C++ package path in the [install guide](../README.md). With a local checkout on an Apple Silicon Mac:

```sh
make -C ../../../sdk/cgo package check-package
make SDK_INCLUDE=../../../sdk/cgo/dist/runtime/darwin-arm64/include SDK_LIBDIR=../../../sdk/cgo/dist/runtime/darwin-arm64/lib
./socket-example --version
./socket-example --new-id
./socket-example tls example.com:443
./socket-example udp echo.example:9000
./socket-example dtls dtls.example:9001
./socket-example curl https://example.com/
```

The Makefile uses `curl-config` for libcurl headers/libraries. On Linux, set `SDK_INCLUDE` and `SDK_LIBDIR` to the unpacked matching runtime; the same compiler command works with its `.so`. For Windows/MSVC or managed package installations, use [CMakeLists.txt](CMakeLists.txt) with the package manager's toolchain and `find_package(urnetwork-sdk CONFIG REQUIRED)`. Link `urnetwork::sdk` and deploy its DLL beside the executable.

Set the Device variables below for raw modes. The curl mode uses the configured Go proxy and does not create a second local Device.

## HTTP library integration

libcurl's `CURLOPT_OPENSOCKETFUNCTION` must return a real `curl_socket_t`. Returning a `uint64_t` SDK handle there is invalid. Its send/receive callbacks do not turn an arbitrary user-space connection into an OS descriptor either. [main.c](main.c) therefore configures `CURLOPT_PROXY` explicitly and clears `CURLOPT_NOPROXY` so the destination cannot bypass the selected proxy.

For direct HTTPS without libcurl, `tls` mode calls `urnet_device_dial_tls` and sends a bounded HTTP/1.1 GET request. This minimal raw example is not a replacement for libcurl's HTTP parser, redirects, compression or pooling. Read/write counts and returned error strings must both be handled; free every owned string with `urnet_free_string`.

Sources checked September 14, 2026: [libcurl open-socket callback](https://curl.se/libcurl/c/CURLOPT_OPENSOCKETFUNCTION.html), [libcurl proxy option](https://curl.se/libcurl/c/CURLOPT_PROXY.html), [libcurl TLS verification](https://curl.se/libcurl/c/CURLOPT_SSL_VERIFYPEER.html).

## Device setup

The executable creates a local Device using the SDK's existing network-space APIs, applies your JWT, and chooses the best available location. Set an account JWT as described in the [SDK setup guide](https://ur.io/docs/getting-started-sdk). Generate an instance ID once with this program's `--new-id` mode, save it, and reuse it for this installation.

```sh
export URNETWORK_JWT='your-account-jwt'
export URNETWORK_INSTANCE_ID='your-persisted-instance-uuid'
```

Keep the network-space manager alive until the Device closes. The sample owns and releases these objects in that order. HTTP proxy modes use the Go proxy's Device instead.

## Clients that need a kernel socket

Start the [Go example](../../go/socket/README.md) in a second terminal:

```sh
cd examples/go/socket
go run . -mode proxy
```

It prints `URNETWORK_HTTP_PROXY=http://127.0.0.1:<port>`. Export that exact value in the client terminal. This is a loopback HTTP/CONNECT proxy: the library uses a local OS connection to it, and its upstream dial calls `Device.DialContext`. HTTPS remains end-to-end encrypted and verified by the HTTP library. The original destination hostname reaches the SDK. The proxy listener is ordinary local application plumbing, not a new SDK listener API.

Only an application configured to use this proxy gets UR routing. Native socket factories, system-wide proxy settings, and unrelated traffic do not change.

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
