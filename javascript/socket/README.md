# JavaScript sockets: Node and browser

This directory contains **two runnable programs**, both using `@urnetwork/sdk` and an initialized hosted `DeviceRemote`. [Node](node/main.mjs) demonstrates UR sockets in Undici and Axios. [Browser](browser/main.mjs) demonstrates an Axios request adapter. Both include Direct Sockets TCP/UDP echo through the Device.

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

Optional `URNETWORK_TCP_ECHO=echo.example:9000` runs a Direct Sockets TCP echo. `URNETWORK_UDP_ECHO=echo.example:9001` runs a Direct Sockets UDP echo. Use ordinary TCP/UDP echo servers you control. Bracket IPv6 endpoints, for example `[2001:db8::1]:9000`.

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

Open the printed localhost URL and paste the same hosted Device configuration into the form. Use **GET through an SDK TLS socket**, or choose TCP/UDP and a `host:port` echo endpoint for **Direct Sockets echo**. `npm run build` emits a static site including the matching WASM and Go runtime glue. Deploy those assets together on an HTTPS origin.

Browsers' native `fetch` and `XMLHttpRequest` expose no raw socket factory. [browser/http.mjs](browser/http.mjs) therefore supplies a small HTTP/1.1 **GET-only** engine over `Conn`, and Axios uses it through its public `adapter` option. It handles length-delimited, chunked, and connection-close response bodies, caps responses at 1 MiB, and uses a 30-second deadline. It is an executable integration example, not a general HTTP client: pooling, redirects, cookies, streaming uploads, and compression are outside this adapter's scope.

## Direct Sockets

[device.mjs](device.mjs) binds the standard constructor signatures to the Device:

```js
const {TCPSocket, UDPSocket} = device.directSockets;
const udp = new UDPSocket({remoteAddress: "echo.example", remotePort: 9001});
const {readable, writable} = await udp.opened;
const writer = writable.getWriter();
await writer.write({data: new TextEncoder().encode("hello")});
writer.releaseLock();
const reader = readable.getReader();
console.log((await reader.read()).value.data);
reader.releaseLock();
await udp.close(); await udp.closed;
```

For TCP, use `new TCPSocket("echo.example", 9000)` and write bytes directly. The executable helper handles split TCP replies, UDP message boundaries, a 30-second I/O timeout, and stream cleanup. TCP accepts `BufferSource` writes and default/BYOB readers. Connected UDP reads and writes `{data}` objects. Cancel or abort pending I/O, release reader/writer locks, then await `close()` and `closed`.

The SDK interface works in ordinary browsers and Node. Chrome's native [Direct Sockets API](https://developer.chrome.com/docs/iwa/direct-sockets) requires an Isolated Web App; this example uses the UR Device instead. It does not install browser globals. The client profile supports `dnsQueryType: "ipv4"` or `"ipv6"`; omission retains hostname Happy Eyeballs. Bound UDP, multicast, listeners, and per-socket buffer/no-delay/keep-alive tuning are unavailable. See the [SDK support profile](https://github.com/urnetwork/sdk/blob/main/SOCKET.md).

These constructors use plain TCP/UDP. The HTTP examples continue to use `dialTls` for HTTPS. UDP hostname races can duplicate the initial datagram; `opened` address fields initially show a candidate and update after consuming the first reply.

## Research and verification

The Node adapter tests cover stream bytes, EOF, close/half-close, original-host TLS dialing, and the browser HTTP parser. Direct Sockets tests cover TCP/UDP messages and timeout cleanup. The SDK's Go/WASM tests carry real TCP/UDP IPv4/IPv6 packets through the Direct Sockets streams; the Node self-test uses real WASM. Browser packaging is checked by Vite. A real-browser runtime test loads and closes WASM without an account: run `npx playwright install chromium`, then `npm run test:browser` (or set `BROWSER_EXECUTABLE` to an installed Chrome executable).

Direct Sockets sources checked September 15, 2026: [WICG proposal](https://wicg.github.io/direct-sockets/), [Chrome implementation](https://developer.chrome.com/docs/iwa/direct-sockets). HTTP integration sources: [Node Agent.createConnection](https://nodejs.org/api/http.html#agentcreateconnectionoptions-callback), [Undici connectors](https://github.com/nodejs/undici/blob/main/docs/docs/api/Connector.md), [Axios configuration](https://axios-http.com/docs/req_config), [WHATWG Fetch](https://fetch.spec.whatwg.org/).

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
