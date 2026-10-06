# TypeScript sockets

[Integration](../integration/README.md) · [Sockets](README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Official networking research](../../NETWORK_EXAMPLES.md)

[main.ts](main.ts) is a runnable, typed Node 24+ application. It imports `Conn` and `DeviceRemote` from `@urnetwork/sdk`, sends optional TCP/UDP echoes through Direct Sockets, and configures Undici and Axios to open UR SDK connections.

## Install and run

```sh
npm install
npm run check
npm test
npm run self-test
export URNETWORK_DEVICE_CONFIG='/absolute/path/to/device.json'
export URNETWORK_CLIENT_JWT='your-scoped-client-jwt'
export URNETWORK_INSTANCE_ID='the-actual-hosted-device-instance-uuid'
npm start
```

Use a socket-capable package release. Before its first publication, install the tarball built by `make -C sdk/js package check-package` with `npm install /absolute/path/to/urnetwork-sdk-<version>.tgz`. No separate `@types` SDK package is required.

Create the hosted Device JSON described in the [JavaScript guide](../../javascript/socket/README.md#hosted-device-configuration). The shared [client bootstrap](../integration/client.mjs) requires all three environment variables above and overrides the JSON's `byJwt` and `instanceId` from the environment; the file may contain only `apiUrl`, `platformUrl`, `proxyUrl` and `signedProxyId`. Use the actual instance ID supplied by the hosting service. The service allocator issues client credentials; it does not create the hosted proxy or its RPC settings.

This directory includes a [Device adapter](device.mjs), [Node socket adapter](ur_node_socket.mjs), and [typed Direct Sockets example](direct-sockets.ts); retain the adjacent integration directory for its shared bootstrap. `URNETWORK_HTTP_URL` selects the HTTP URL. `URNETWORK_TCP_ECHO=echo.example:9000` and `URNETWORK_UDP_ECHO=echo.example:9001` enable optional Direct Sockets echo requests to ordinary TCP/UDP servers. Bracket IPv6 endpoints.

The self-test initializes and closes the real Go/WASM runtime without an account. `npm run check` validates the SDK's exported socket types under NodeNext module resolution.

Hosted proxy devices are not visible peers and reject subprotocol messaging. [Messages](../messages/README.md) uses the SDK's typed subprotocol API through a separate provider-capable native companion. The hosted socket setup here is unchanged.

## Replace HTTP socket creation

| Library | Hook |
| --- | --- |
| Node HTTP/HTTPS | `Agent.createConnection` returns the `UrNodeSocket` Duplex adapter. |
| Undici | A custom `Agent` connector opens `device.dial` / `device.dialTls`. |
| Axios | Custom HTTP/HTTPS agents use that connector; `proxy: false` keeps destination resolution on the SDK path. |

HTTPS uses verified SDK TLS with HTTP/1.1 ALPN. The small casts in `main.ts` connect Node's overloaded callback declaration to the adapter's runtime callback contract; the SDK's `Conn` calls themselves are typed. Native descriptors, Node-specific TLS introspection, and HTTP/2 are outside this adapter.

For an executable browser program, use the adjacent [JavaScript browser example](../../javascript/socket/README.md#run-the-browser). The same package and typed Conn/Direct Sockets declarations apply to browser TypeScript projects. Native browser fetch/XHR have no socket factory; Axios needs a request adapter there.

## Direct Sockets

`direct-sockets.ts` uses `const {TCPSocket, UDPSocket} = device.directSockets`, then the standard constructor signatures. TCP has byte streams and BYOB readers; connected UDP carries `UDPMessage` objects containing `data`. Both provide `opened`, `closed`, and asynchronous `close()`. The example applies a 30-second I/O timeout, cancels/aborts pending operations, and releases stream locks before closing.

This works over the UR Device in Node and ordinary browsers; it does not require Chrome's native Isolated Web App packaging. `dnsQueryType` optionally forces IPv4 or IPv6. Bound UDP, multicast, listeners, and per-socket buffer/no-delay/keep-alive tuning are not implemented. TLS/DTLS remain available through `dialTls`. The UDP Happy Eyeballs policy below applies, including possible initial duplicate delivery and address fields that update after the first reply is consumed. [SDK support profile](https://github.com/urnetwork/sdk/blob/main/SOCKET.md), [WICG API](https://wicg.github.io/direct-sockets/).

Sources checked September 14, 2026: [Node HTTP agents](https://nodejs.org/api/http.html#agentcreateconnectionoptions-callback), [Undici connectors](https://github.com/nodejs/undici/blob/main/docs/docs/api/Connector.md), [Axios adapters and agents](https://axios-http.com/docs/req_config), [TypeScript Node module resolution](https://www.typescriptlang.org/docs/handbook/modules/reference.html#node16-nodenext).

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
