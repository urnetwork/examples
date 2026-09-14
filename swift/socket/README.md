# Swift sockets, URLSession and Alamofire

[main.swift](Sources/SocketExample/main.swift) is a runnable **macOS 14+ command-line example**. It uses the gomobile XCFramework's portable Socket API directly, and shows URLSession/Alamofire routing through an HTTP CONNECT proxy.

## Install and run

SwiftPM resolves the SDK from its distribution repository, `https://github.com/urnetwork/sdk-swift`. Select the first socket-capable version:

```sh
export URNETWORK_SDK_VERSION='<socket-release-version>'
swift run SocketExample --version
swift run SocketExample --new-id
swift run SocketExample tls example.com:443
swift run SocketExample udp echo.example:9000
swift run SocketExample dtls dtls.example:9001
swift run SocketExample urlsession https://example.com/
swift run SocketExample alamofire https://example.com/
```

Before the distribution repository's first publication, copy a freshly built SDK XCFramework into `.native/URnetworkSdk.xcframework` here and set `URNETWORK_XCFRAMEWORK=.native/URnetworkSdk.xcframework`. SwiftPM requires that local binary-target path to be relative to this package. The SDK's existing `sdk/build` Makefile builds the Apple artifact; an older framework without `openSocket` is insufficient.

Set the Device variables below for raw modes. The executable's minimum macOS version is 14 because the proxy example uses `ProxyConfiguration`. SDK sockets themselves retain the Apple SDK's iOS 16 / macOS 13.5 baseline. iOS apps can reuse the portable socket calls on a worker queue; they need their normal app lifecycle/bootstrap code.

## Replace HTTP connection creation

| API | Integration |
| --- | --- |
| Portable raw socket | `device.openSocket("tcp", address: ..., timeoutMillis: ..., tlsOptions: ...)`; nil TLS options mean plain transport. |
| TLS/DTLS | Supply `SdkSocketTLSOptions()`; TCP selects TLS, UDP selects DTLS. |
| URLSession | Set `URLSessionConfiguration.proxyConfigurations` to the Go loopback CONNECT proxy. |
| Alamofire | Construct `Alamofire.Session(configuration:)` with that URLSession configuration. |

URLSession has no public arbitrary byte-stream/socket factory. `URLProtocol` customizes request loading and is not a raw transport injection point; implementing it would also require implementing an HTTP client. Alamofire builds on URLSession, so the supported proxy configuration is the practical integration for both.

`ProxyConfiguration` requires iOS 17 / macOS 14. An iOS app needs a reachable proxy process/service or an in-app proxy implementation; the desktop companion is not automatically present on a phone. Direct SDK socket calls do not need that companion.

The static Go runtime links the system `resolv` library. The SwiftPM/CocoaPods package carries that dependency; direct XCFramework/Carthage consumers must link it too. Portable reads/writes block: keep them off a UI thread. `SocketRead.eof` is distinct from an empty data value.

Sources checked September 14, 2026: [URLSession proxyConfigurations](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/proxyconfigurations), [ProxyConfiguration](https://developer.apple.com/documentation/network/proxyconfiguration/init(httpconnectproxy:tlsoptions:)), [URLProtocol registration](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/protocolclasses), [Alamofire Session](https://alamofire.github.io/Alamofire/Classes/Session.html).

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
