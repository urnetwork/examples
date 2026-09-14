# C++ sockets, Boost.Beast and CPR

[main.cpp](main.cpp) demonstrates a local Device with verified TLS, UDP and DTLS. [ur_stream.hpp](ur_stream.hpp) implements Beast's synchronous read/write stream contract over the SDK's C ABI, preserving partial I/O, EOF and close.

## Build and run

With a local checkout on an Apple Silicon Mac and Boost headers installed:

```sh
make -C ../../../sdk/cgo package check-package
make SDK_INCLUDE=../../../sdk/cgo/dist/runtime/darwin-arm64/include SDK_LIBDIR=../../../sdk/cgo/dist/runtime/darwin-arm64/lib
./socket-example --version
./socket-example --new-id
./socket-example beast example.com:443 /
./socket-example udp echo.example:9000
./socket-example dtls dtls.example:9001
```

Set `BOOST_INCLUDE` when Boost is outside `/opt/homebrew/include`. On Linux, point the SDK include/library variables at its platform archive. The [CMake project](CMakeLists.txt) supports normal package-manager integration:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH=/path/to/sdk/runtime
cmake --build build
```

Use your Conan/vcpkg toolchain to resolve the SDK and Boost when installed that way. The generated general C++ SDK header additionally needs nlohmann/json; select Conan's `cpp=True` option or install that header dependency. This socket example wraps the C ABI itself.

## Replace HTTP transport creation

| Library | Integration |
| --- | --- |
| Boost.Beast synchronous HTTP | Pass `UrStream` to `http::write` and `http::read`; it opens `urnet_device_dial_tls`. |
| CPR | [cpr_example.cpp](cpr_example.cpp) supplies `cpr::Proxies` pointing to the Go proxy. |
| libcurl | Its callback requires a real OS socket; use the C example's proxy integration. |

The Beast stream leaves HTTP parsing, headers and the 1 MiB response-body limit to Beast. SDK TLS verifies the certificate and selects HTTP/1.1. The adapter is synchronous; it does not implement Asio's async operations or expose an OS native handle.

To build CPR, install CPR through your C++ package manager and enable it with `cmake -S . -B build -DENABLE_CPR=ON ...`. Then run `build/cpr-example https://example.com/` with the proxy environment variable below. CPR is built on libcurl; replacing an integer socket handle cannot connect it directly to a UR Conn.

Sources checked September 14, 2026: [Beast stream requirements](https://www.boost.org/doc/libs/latest/libs/beast/doc/html/beast/using_io/writing_composed_operations.html), [CPR source](https://github.com/libcpr/cpr), [CPR proxy configuration](https://github.com/libcpr/cpr/blob/master/include/cpr/proxies.h), [libcurl open-socket callback](https://curl.se/libcurl/c/CURLOPT_OPENSOCKETFUNCTION.html).

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
