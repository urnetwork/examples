# JavaScript peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This Node 24 program discovers live network peers and exchanges [URMS v1](../../MESSAGES_PROTOCOL.md) text and ACK frames. A [native companion](../integration/companion/README.md) owns the provider-capable `DeviceLocal`; the JavaScript application owns the binary codec, selected destination, receive handling and acknowledgements through extension RPC. Hosted socket proxies remain ineligible for messaging.

## Build and offline checks

Follow the [current SDK and companion build](../integration/companion/README.md#build-from-sibling-checkouts) first. The package links `../../../sdk/js` from the sibling checkout; an older published WASM does not acquire these APIs automatically. Run from `javascript/messages`:

```sh
npm ci
npm run self-test
npm test
node main.mjs --version
```

`--self-test` needs no credentials, running companion or live connection. It checks the golden vectors, nonzero 64-bit `BigInt` IDs, exact lengths, strict UTF-8, the 4096-byte payload boundary and empty ACKs. It can also run before installation with `node main.mjs --self-test`. `npm test` checks both JS and TS codecs through the shared controller, ACK source/ID matching, callback copies and transport cleanup. Dependency installation may download build inputs.

The executable is [main.mjs](main.mjs), its codec is [codec.mjs](codec.mjs), and [application.mjs](application.mjs) contains the shared command controller. [package.json](package.json) defines the scripts.

## Configure and run

Start the [native companion](../integration/companion/README.md#configure-one-installation) with a scoped client JWT, persistent instance UUID and a random token. Load the **same saved values** into this Node process. Create an absolute-path configuration file:

```json
{
  "apiUrl": "https://api.bringyour.com",
  "platformUrl": "wss://connect.bringyour.com",
  "companionUrl": "ws://127.0.0.1:8787/device-rpc"
}
```

```sh
export URNETWORK_DEVICE_CONFIG='/absolute/path/to/message-device.json'
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='saved-installation-uuid'
export URNETWORK_COMPANION_TOKEN='saved-random-64-hex-character-token'
npm start -- self
npm start -- peers
```

Use one of these commands while the companion keeps running:

```sh
npm start -- watch
```

```sh
npm start -- send PEER_CLIENT_UUID 'hello from JavaScript'
```

`self` prints this client's ID. `peers` waits for a real snapshot; `watch` displays updates and received TEXT and sends ACKs. Peer output includes client ID, provide status, principal, roles, device name/specification, stable color and disconnected count. An unavailable snapshot is reported explicitly.

Select the receiving app's **client ID** from peer output. `send` waits for a live snapshot and rejects destinations absent from its connected peers. It then queries the selected peer's supported protocols, sends only when it advertises `4096`, and waits up to ten seconds for an ACK matching both the peer and message ID. The receiver must keep its message program running. ACKs are never ACKed, and malformed frames are rejected. Ctrl-C stops `watch`.

Each companion accepts one controller at a time. A two-peer JS/TS demo needs two distinct scoped clients, two saved instance IDs, two companions and two Node controllers; see [two clients on one machine](../integration/companion/README.md#two-clients-on-one-machine). A native language's messages program can be the other peer.

## RPC lifecycle

The [integration helper](../integration/companion.mjs) returns a connecting Device. The controller installs mirrored peer/state listeners before initial sync, then opens `device.enableSubprotocol(4096, listener)`. Registering those listeners after sync can reconnect the browser-style RPC and invalidate an existing subscription.

A subscription belongs to one RPC session. Transport, listener and queue failures reject its `closed` Promise; the CLI exits with an error. Restart the command to establish a new subscription and query support again. Cleanup closes the subscription and waits for its receive loop before removing peer listeners and closing the Device/WASM runtime. Frames are copied at the binding and application callback boundaries and never coalesced.

The local companion token only authorizes that installation's RPC connection. Neither this app nor the companion receives `URNETWORK_ROOT_JWT`; the [authenticated service backend](../integration/README.md#backend-allocator) retains it and returns only the scoped client credential.
