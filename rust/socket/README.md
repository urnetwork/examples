# Rust sockets, ureq and reqwest

[Integration](../integration/README.md) · [Sockets](README.md) · [Messages](../messages/README.md) · [Official networking research](../../NETWORK_EXAMPLES.md)

[src/main.rs](src/main.rs) runs the examples. [session.rs](src/session.rs) owns a local Device, and [ur_http.rs](src/ur_http.rs) supplies a ureq connector, resolver and transport over `urnetwork_sdk::Conn`.

## Install and run

Use Rust 1.85+ for this edition-2024 example:

```sh
cargo add urnetwork-sdk@<socket-release-version>
cargo run -- --version
cargo run -- --new-id
cargo run -- ureq https://example.com/
cargo run -- reqwest https://example.com/
cargo run -- tls example.com:443
cargo run -- udp echo.example:9000
cargo run -- dtls dtls.example:9001
```

Before first publication, `make -C sdk/rust package check-package` produces a staged Cargo project. Use a local Cargo patch, for example `cargo run --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust/dist/project"' -- --version`. If testing the release-download variant before GitHub assets are public, also set `SDK_RUST_NATIVE_CACHE` to the directory of checked native gzip assets.

The public crate downloads a version-pinned, checksum-verified native asset at **build time** and embeds it in the resulting binary. It never downloads “latest” at runtime. Go is not needed by registry consumers. Offline/restricted builds can prepopulate the documented checksum-checked cache. Set the Device variables below for direct modes.

## Replace HTTP connection creation

| Library | Integration |
| --- | --- |
| ureq **3.4.2, exact pin** | `Agent::with_parts(config, UrConnector(device), DeferredDNS)`. |
| reqwest 0.13 blocking | Supply the Go proxy with `ClientBuilder::proxy`. |
| `std::io` clients | Conn implements `Read` and `Write` for TCP; use `recv` / `send` for datagrams to retain empty-datagram semantics. |

ureq's connector/transport API is explicitly **unversioned**, so do not loosen its exact dependency pin without retesting. The custom resolver returns a placeholder; the connector passes the URI hostname directly to the SDK and performs verified SDK TLS for HTTPS. It supplies the buffer/deadline contract expected by ureq. This HTTP/1.1 example disables idle pooling because Conn has no nonblocking peek for a safe reusable-connection probe.

reqwest's public `connector_layer` wraps its existing connector; it does not accept an arbitrary SDK I/O object. The proxy example preserves its normal TLS and HTTP behavior while routing the upstream through the Go Device. Raw sockets expose no Unix `AsRawFd` or Windows socket handle.

Sources checked September 14, 2026: [ureq Connector](https://docs.rs/ureq/latest/ureq/unversioned/transport/trait.Connector.html), [ureq Transport](https://docs.rs/ureq/latest/ureq/unversioned/transport/trait.Transport.html), [ureq Resolver](https://docs.rs/ureq/latest/ureq/unversioned/resolver/trait.Resolver.html), [reqwest ClientBuilder](https://docs.rs/reqwest/latest/reqwest/struct.ClientBuilder.html).

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
