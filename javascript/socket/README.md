# JavaScript sockets: Node and browser

This directory contains **two runnable programs**, both using `@urnetwork/sdk` and an initialized hosted `DeviceRemote`. [Node](node/main.mjs) demonstrates UR sockets in Undici and Axios. [Browser](browser/main.mjs) demonstrates raw socket I/O, an Axios request adapter, and the SDK WebTransport API.

## Install and check

Use Node 24+ for the command-line program and the Vite development server.

```sh
npm install
npm test
npm run self-test
```

The canonical npm name is `@urnetwork/sdk`; its first socket release must be published before the registry install works. To test this checkout first, build `sdk/js` with `make package check-package`, then run `npm install /absolute/path/to/sdk/js/release/artifacts/urnetwork-sdk-<version>.tgz` here. The self-test loads real Go/WASM in Node, verifies initialization, and closes the runtime without an account.

## Hosted Device configuration

Provision a hosted Device running the socket-capable SDK. Obtain its actual instance ID and its WebSocket RPC connection configuration from your hosting service. An arbitrary generated UUID does not identify that hosted Device. Create a local JSON file:

```json
{
  "apiUrl": "api.bringyour.com",
  "platformUrl": "connect.bringyour.com",
  "byJwt": "your-platform-jwt",
  "proxyUrl": "your-hosted-device-websocket-url",
  "signedProxyId": "your-hosted-device-HMAC-auth-token",
  "instanceId": "the-hosted-device-instance-uuid"
}
```

The signed proxy ID and JWT are different credentials. The sample validates all six fields, waits for the Device RPC connection, then chooses a location. Keep the configuration file out of source control. WASM supplies the API in the browser and Node; the hosted Device carries the actual connection path.

## Run Node

```sh
export URNETWORK_DEVICE_CONFIG='/absolute/path/to/device.json'
export URNETWORK_HTTP_URL='https://example.com/'
npm run node
```

Optional `URNETWORK_UDP_ECHO=echo.example:9000` sends a UDP datagram. Optional `URNETWORK_WEBTRANSPORT_URL=https://transport.example/echo` opens a WebTransport bidirectional echo stream.

[ur_node_socket.mjs](ur_node_socket.mjs) adapts async `Conn` I/O into a Node `Duplex`. The connector retains the hostname, uses verified `dialTls` for HTTPS with HTTP/1.1 ALPN, and closes the SDK connection when the Node stream is destroyed.

| HTTP library | Replacement used here |
| --- | --- |
| Node `http` / `https` | Assign `agent.createConnection = connector(device, secure)`. |
| Undici / Node-style fetch | Pass an Undici `Agent({connect: connector(device)})` as the dispatcher. |
| Axios in Node | Pass the configured `httpAgent` and `httpsAgent`; disable environment proxy handling with `proxy: false`. |

The example supports HTTP/1.1. Kernel socket options are not available. It rejects local binding, socket upgrades, custom Node TLS keys/CAs, and disabling verification; configure a dedicated SDK connector if your application needs supported SDK TLS options. It does not replace every Node TLS socket introspection method.

## Run the browser

```sh
npm run browser
```

Open the printed localhost URL, paste the same hosted Device configuration into the form, and select **GET through UR socket** or **WebTransport echo**. `npm run build` emits a static site including the matching WASM and Go runtime glue. Deploy those assets together on an HTTPS origin.

Browsers' native `fetch` and `XMLHttpRequest` expose no raw socket factory. [browser/http.mjs](browser/http.mjs) therefore supplies a small HTTP/1.1 **GET-only** engine over `Conn`, and Axios uses it through its public `adapter` option. It handles length-delimited, chunked, and connection-close response bodies, caps responses at 1 MiB, and uses a 30-second deadline. It is an executable integration example, not a general HTTP client: pooling, redirects, cookies, streaming uploads, and compression are outside this adapter's scope.

WebTransport is an HTTPS/HTTP3 session protocol over QUIC, with streams and datagrams. It is **not** the proposed JavaScript Direct Sockets raw TCP/UDP API. `device.webTransport(url)` uses the SDK's QUIC implementation over a UR UDP socket; the endpoint must implement WebTransport and permit your browser origin. `device.dial` is the raw socket interface. See [the WebTransport support profile](https://github.com/urnetwork/sdk/blob/main/SOCKET.md) for supported options and deliberate API limitations. The echo endpoint must reply to the opened bidirectional stream.

## Research and verification

The Node adapter tests cover stream bytes, EOF, close/half-close, original-host TLS dialing, and the browser HTTP parser. SDK tests cover WebTransport streams/datagrams and session cleanup; the Node self-test uses real WASM. Browser packaging is checked by Vite. A real-browser runtime test loads and closes WASM without an account: run `npx playwright install chromium`, then `npm run test:browser` (or set `BROWSER_EXECUTABLE` to an installed Chrome executable).

Sources checked September 14, 2026: [Node Agent.createConnection](https://nodejs.org/api/http.html#agentcreateconnectionoptions-callback), [Undici connectors](https://github.com/nodejs/undici/blob/main/docs/docs/api/Connector.md), [Axios configuration](https://axios-http.com/docs/req_config), [WHATWG Fetch](https://fetch.spec.whatwg.org/), [W3C WebTransport](https://www.w3.org/TR/webtransport/), [Direct Sockets proposal](https://wicg.github.io/direct-sockets/).

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
