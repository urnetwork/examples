# C# sockets, HttpClient and RestSharp

[Program.cs](Program.cs) uses `URnetwork.SDK` with a local Device. [UrStream.cs](UrStream.cs) adapts its Conn to a .NET Stream and [UrSession.cs](UrSession.cs) owns the native SDK handles.

## Install and run

Use .NET 8+:

```sh
dotnet add package URnetwork.SDK --version <socket-release-version>
dotnet run -- --version
dotnet run -- --new-id
dotnet run -- https://example.com/
dotnet run -- restsharp https://example.com/
dotnet run -- udp echo.example:9000
dotnet run -- dtls dtls.example:9001
```

Before first publication, build `sdk/csharp`, then restore with `dotnet restore --source /path/to/sdk/csharp/dist/artifacts --source https://api.nuget.org/v3/index.json`. The project accepts `-p:UrSdkVersion=<version>` and defaults to the local development package. Set the Device variables below before live requests.

## Replace the HTTP socket factory

| Library | Hook |
| --- | --- |
| .NET HttpClient | `SocketsHttpHandler.ConnectCallback` returns a `UrStream` around `Device.Dial("tcp", host_port)`. |
| RestSharp 112.1 | Construct it with the configured HttpClient. |
| Raw socket-like I/O | Use `Device.Dial` / `Device.DialTls` and Conn directly; the numeric native handle is not `System.Net.Sockets.Socket.Handle`. |

The HTTP handler receives the unresolved `DnsEndPoint` and passes its hostname to the Device. TLS, SNI, certificate checks and HTTP negotiation remain in HttpClient, layered on that stream. HTTP/2-or-lower is selected: HTTP/3 uses QUIC and cannot be intercepted through this stream callback.

The async facade offloads the blocking C ABI call, owns the read buffer until it completes, and closes the connection on cancellation. A connection returned after canceled establishment is also disposed. This is suitable for the example; very large numbers of concurrent blocking calls need worker-pool sizing or a dedicated async bridge. Partial data is delivered before a deferred read error.

Sources checked September 14, 2026: [.NET ConnectCallback](https://learn.microsoft.com/en-us/dotnet/api/system.net.http.socketshttphandler.connectcallback), [HttpVersionPolicy](https://learn.microsoft.com/en-us/dotnet/api/system.net.http.httpversionpolicy), [RestSharp custom HttpClient](https://restsharp.dev/docs/advanced/configuration/).

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
