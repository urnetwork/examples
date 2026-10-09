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

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, versioning and acknowledgements. In JavaScript the whole API is the subscription this example already uses: `device.enableSubprotocol(id, listener)` resolves to a `SubprotocolSubscription` with `send(clientId, bytes)`, `querySubprotocols(clientId, timeoutMillis)`, `close()` and `closed`. The JS binding has no stats, enabled list, received count or disable-all; close each subscription instead.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with Protobuf-ES. Install `@bufbuild/protobuf` and `@bufbuild/protoc-gen-es` at the same version, then generate `gen/notes_pb.js` and its declarations with `mkdir -p gen && protoc --plugin=protoc-gen-es=node_modules/.bin/protoc-gen-es --es_out=gen --es_opt=target=js+dts -I ../../go/messages/subprotocol notes.proto`:

```js
import {randomBytes} from "node:crypto";
import {create, fromBinary, toBinary} from "@bufbuild/protobuf";
import {AckSchema, EnvelopeSchema, NoteSchema} from "./gen/notes_pb.js";

const NOTES = 4097;
const MAX_MESSAGE_BYTES = 4608;

function encodeEnvelope(envelope) {
  const bytes = toBinary(EnvelopeSchema, envelope);
  if (bytes.length > MAX_MESSAGE_BYTES) throw new RangeError("Envelope exceeds 4608 bytes");
  return bytes;
}

// The listener gets an owned copy of each message. An exception in it closes
// the subscription, so it drops bad input instead of throwing.
let subscription;
subscription = await device.enableSubprotocol(NOTES, async ({sourceClientId, bytes}) => {
  if (bytes.length > MAX_MESSAGE_BYTES) return;
  let envelope;
  try {
    envelope = fromBinary(EnvelopeSchema, bytes);
  } catch {
    return; // malformed: drop and count, never acknowledge
  }
  if (envelope.kind.case === "note") {
    const note = envelope.kind.value;
    if (note.messageId !== 0n && new TextEncoder().encode(note.text).length <= 4096) {
      const ack = create(EnvelopeSchema, {kind: {case: "ack", value: create(AckSchema, {messageId: note.messageId})}});
      await subscription.send(sourceClientId, encodeEnvelope(ack));
    }
  } else if (envelope.kind.case === "ack") {
    // match an outstanding note on (sourceClientId, envelope.kind.value.messageId)
  } // undefined: no kind this version knows
});

const protocols = await subscription.querySubprotocols(peerClientId, 10000);
if (protocols === null || !protocols.includes(NOTES)) throw new Error("Peer support for 4097 is unknown or absent");
const messageId = randomBytes(8).readBigUInt64BE() || 1n;
const note = create(EnvelopeSchema, {kind: {case: "note", value: create(NoteSchema, {messageId, text: "hello"})}});
if (!await subscription.send(peerClientId, encodeEnvelope(note))) throw new Error("SDK did not enqueue note");
```

`device` is the companion Device from [companion.mjs](../integration/companion.mjs); install the mirrored peer/state listeners before its initial sync and open the subscription after it, as [application.mjs](application.mjs) does. Protobuf-ES represents `uint64` as `BigInt`, which keeps the full 64-bit `message_id`.
