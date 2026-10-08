# Go sockets and HTTP clients

[Integration](../integration/README.md) · [Sockets](README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md) · [Official networking research](../../NETWORK_EXAMPLES.md)

[main.go](main.go) creates a local Device and demonstrates `net.Conn`-compatible sockets. [socket.go](socket.go) installs `Device.DialContext` in an `http.Transport`. [proxy.go](proxy.go) also provides a runnable loopback proxy for clients in other languages that require OS sockets.

## Install and run

Install a socket-capable release using Go modules. The checked-in calendar-version pin is a release baseline; update it to the first socket release before running from the public registry:

```sh
go get github.com/urnetwork/sdk/v2026@<socket-release-version>
go mod tidy
go run . -version
export URNETWORK_CLIENT_JWT='your-scoped-client-jwt'
export URNETWORK_INSTANCE_ID='your-persisted-instance-uuid'
go run . -mode http -target https://example.com/
go run . -mode resty -target https://example.com/
```

Go 1.26.7+ is used by this example. The shared [client bootstrap](../integration/client.go) requires both environment variables and does not generate or save an instance UUID. Generate one during installation (for example with `uuidgen` on macOS), persist it and export that same value on later runs. Device creation uses the scoped JWT issued by your backend; see the [integration contract](../../INTEGRATION_CONTRACT.md).

For an unversioned development checkout, update SDK imports and requirements in both this module and the shared integration module to `github.com/urnetwork/sdk v0.0.0`, then add the required local `replace` directives for `sdk`, `connect`, `glog` and `goidenticons`. Published calendar-major modules use their versioned import paths; a local replace alone cannot rewrite imports inside an unversioned source tree.

## Replace the socket factory

| Library | Integration |
| --- | --- |
| Go standard `net/http` | Clone `http.DefaultTransport`, clear its environment proxy, assign `DialContext = device.DialContext`. |
| Resty v2 | `resty.NewWithClient(HTTPClient(device))` retains the same transport. |
| Raw TCP/UDP | Replace `net.Dialer.DialContext` with `device.DialContext`. |
| TLS/DTLS | `device.DialTlsContext(ctx, network, address, &tls.Config{})`. |

The HTTP transport performs its usual verified TLS handshake over the returned UR TCP connection and can negotiate HTTP/2. Direct `DialTlsContext` performs TLS in the SDK and uses DTLS for UDP. The dial context bounds establishment; use connection deadlines for later I/O.

Raw `tls`, `udp` and `dtls` modes send `hello` and read a reply, so supply the matching echo service:

```sh
go run . -mode udp -target echo.example:9000
go run . -mode dtls -target dtls.example:9001
go run . -mode tls -target tls-echo.example:9443
go run . -mode proxy
```

The proxy binds an ephemeral loopback port and prints an HTTP proxy URL. Configure the other language client with that URL. Plain HTTP requests and HTTPS CONNECT upstreams call the supplied Device dialer, preserving the destination hostname. Interrupt the process to close the proxy and Device.

`go test ./...` exercises plain HTTP and verified HTTPS through the proxy using local fixtures; it asserts that the upstream factory receives the original unresolved hostname.

Sources checked September 14, 2026: [Go http.Transport](https://pkg.go.dev/net/http#Transport), [net.Dialer](https://pkg.go.dev/net#Dialer), [Resty NewWithClient](https://pkg.go.dev/github.com/go-resty/resty/v2#NewWithClient), [Go module publishing](https://go.dev/doc/modules/publishing).

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
