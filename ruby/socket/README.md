# Ruby sockets, Net::HTTP and Faraday

[Integration](../integration/README.md) · [Sockets](README.md) · [Messages](../messages/README.md) · [Official networking research](../../NETWORK_EXAMPLES.md)

[main.rb](main.rb) is a complete program using the `urnetwork-sdk` gem's FFI wrapper. Direct modes create a local Device and use its Conn for TLS, UDP or DTLS.

## Install and run

Use a current supported CRuby with Bundler (the example was checked with CRuby 4):

```sh
bundle install
bundle exec ruby main.rb --version
bundle exec ruby main.rb --new-id
bundle exec ruby main.rb tls example.com:443
bundle exec ruby main.rb udp echo.example:9000
bundle exec ruby main.rb dtls dtls.example:9001
bundle exec ruby main.rb net-http https://example.com/
bundle exec ruby main.rb faraday https://example.com/
```

Before the first socket release, build `sdk/ruby` and install its matching platform gem with `gem install /path/to/urnetwork-sdk-<version>-<platform>.gem`, then run `bundle install`. Set the Device variables below for direct modes. RubyGems platform selection must match the included native runtime.

## HTTP integration

| Library | Executable integration |
| --- | --- |
| Net::HTTP | `Net::HTTP::Proxy(host, port)` uses the Go proxy. |
| Faraday 2.14 with Net::HTTP adapter | Set the proxy on `Faraday.new` and explicitly choose `:net_http`. |
| HTTP.rb | Its socket-class hook alone is insufficient for HTTPS: the TLS path uses OpenSSL's descriptor-backed SSLSocket. Use a proxy, or implement a complete TLS-capable transport separately. |

The FFI Conn is not a Ruby `IO` with `fileno`. Net::HTTP builds an OS TCP socket and layers `OpenSSL::SSL::SSLSocket` over it, so supplying a Ruby object with only `read` and `write` cannot safely replace that connection path. The proxy keeps certificate/hostname verification in the HTTP library and SDK DNS on the upstream connection.

The raw `tls` mode is a small HTTP/1.1 GET byte exchange over `device.dial_tls`, not a general HTTP parser. The wrapper raises `SocketError` with any partial bytes/count still attached; applications must handle both. Native callbacks and handles must remain alive until their operations finish.

Sources checked September 14, 2026: [Net::HTTP implementation](https://github.com/ruby/net-http/blob/master/lib/net/http.rb), [Faraday Net::HTTP adapter](https://github.com/lostisland/faraday-net_http/blob/main/lib/faraday/adapter/net_http.rb), [HTTP.rb connection implementation](https://github.com/httprb/http/blob/main/lib/http/connection.rb).

## Device setup

The executable creates a local Device with the scoped client JWT issued by your service backend and chooses the best available location. Follow the [integration guide](../integration/README.md): the JWT must contain its assigned `client_id`; only the backend holds the root JWT. Generate an instance ID once with this program's `--new-id` mode, save it, and reuse it for this installation.

```sh
export URNETWORK_CLIENT_JWT='your-scoped-client-jwt'
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
