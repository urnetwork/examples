# TypeScript peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md)

This Node 24 program uses the SDK's typed subprotocol API to discover live peers and exchange [URMS v1](../../MESSAGES_PROTOCOL.md) TEXT/ACK frames. It supplies its own typed codec to the shared command controller. The [native companion](../../javascript/integration/companion/README.md) owns a full provider-capable `DeviceLocal`; TypeScript owns application message processing through extension RPC. Hosted socket proxies remain ineligible for messaging.

## Build and offline checks

Follow the [current SDK and companion build](../../javascript/integration/companion/README.md#build-from-sibling-checkouts) first. The package links `../../../sdk/js` from the sibling checkout. Run from `typescript/messages`:

```sh
npm ci
npm run build
npm run self-test
node main.ts --version
```

`npm run build` runs TypeScript checking with no output files. Node 24 runs [main.ts](main.ts) directly using type stripping; there is no `dist/main.js` entry point. `npm run self-test` type-checks and runs the credential-free [codec.ts](codec.ts) checks. Once dependencies are installed, these checks need no companion or live connection. The direct `node main.ts --self-test` codec check also needs no SDK installation.

The tests cover exact golden frames, full-width nonzero `BigInt` IDs, strict UTF-8, byte limits, malformed frames and empty ACKs. To run the shared controller and transport tests against both language codecs, run `npm test` from `javascript/messages`. Scripts and dependencies are in [package.json](package.json); [application.mjs](../../javascript/messages/application.mjs) and its [declarations](../../javascript/messages/application.d.mts) are shared with JavaScript.

## Configure and run

Start the [companion](../../javascript/integration/companion/README.md#configure-one-installation), then load the same scoped client JWT, persisted instance UUID and random companion token into this Node process. Its absolute-path JSON configuration is:

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

Keep the receiver running:

```sh
npm start -- watch
```

From another installation with its own client, instance and companion:

```sh
npm start -- send PEER_CLIENT_UUID 'hello from TypeScript'
```

Use the peer's client ID, not its name or instance UUID. `peers` waits for a real snapshot; `watch` reports metadata changes and accepts messages. The displayed fields include provide status, principal, roles, device name/specification, stable color and disconnected count. Unavailable state is distinct from an empty peer list.

`send` waits for a live snapshot and rejects destinations absent from its connected peers. It then queries the selected peer for subprotocol `4096` and waits up to ten seconds for an ACK matching both the source client ID and message ID. Valid TEXT is acknowledged after decoding; malformed frames and ACKs are never ACKed. Ctrl-C stops the watcher. Follow the [two-client setup](../../javascript/integration/companion/README.md#two-clients-on-one-machine) for a single-machine demo. Other native language examples use the same wire bytes.

## Lifecycle and credentials

The [companion helper](../integration/companion.mjs) shares JavaScript's transport. It returns before initial sync so the controller can install mirrored peer/state listeners first. After sync, the controller opens the typed subscription. Later mirrored-listener changes can reconnect RPC and invalidate existing subscriptions.

Watch the subscription's `closed` Promise. A reconnect or bounded-queue failure requires a fresh subscription and another support query; the CLI reports the error and can be restarted. Cleanup closes the native registration, waits for receive processing, removes peer listeners and closes the Device/WASM runtime. Incoming and outgoing bytes are copied, with one complete frame per callback.

The companion accepts one controller at a time and authenticates it with the local token. Both app-side processes use only `URNETWORK_CLIENT_JWT`; the [service backend](../integration/README.md#backend-allocator) alone retains `URNETWORK_ROOT_JWT`.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, versioning and acknowledgements. The SDK's typed API is the one this example uses: `device.enableSubprotocol(id, listener)` resolves to a `SubprotocolSubscription` with `send(clientId, bytes)`, `querySubprotocols(clientId, timeoutMillis)`, `close()` and `closed`, and `@urnetwork/sdk` exports the `SubprotocolMessage` and `SubprotocolSubscription` types. There are no stats, enabled list, received count or disable-all in JS/TS; close each subscription instead.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with Protobuf-ES. Install `@bufbuild/protobuf` and `@bufbuild/protoc-gen-es` at the same version, then generate `gen/notes_pb.ts` with `mkdir -p gen && protoc --plugin=protoc-gen-es=node_modules/.bin/protoc-gen-es --es_out=gen --es_opt=target=ts -I ../../go/messages/subprotocol notes.proto`. The schema has no enums, so the generated file runs under Node's type stripping:

```ts
import {create, fromBinary, toBinary} from "@bufbuild/protobuf";
import type {SubprotocolMessage, SubprotocolSubscription} from "@urnetwork/sdk";
import {AckSchema, EnvelopeSchema, NoteSchema, type Envelope} from "./gen/notes_pb.ts";

const NOTES = 4097;
const MAX_MESSAGE_BYTES = 4608;

function encodeEnvelope(envelope: Envelope): Uint8Array {
  const bytes = toBinary(EnvelopeSchema, envelope);
  if (bytes.length > MAX_MESSAGE_BYTES) throw new RangeError("Envelope exceeds 4608 bytes");
  return bytes;
}

// The listener gets an owned copy of each message. An exception in it closes
// the subscription, so it drops bad input instead of throwing.
async function receiveEnvelope(subscription: SubprotocolSubscription, {sourceClientId, bytes}: SubprotocolMessage): Promise<void> {
  if (bytes.length > MAX_MESSAGE_BYTES) return;
  let envelope: Envelope;
  try {
    envelope = fromBinary(EnvelopeSchema, bytes);
  } catch {
    return; // malformed: drop and count, never acknowledge
  }
  switch (envelope.kind.case) {
    case "note": {
      const note = envelope.kind.value;
      if (note.messageId !== 0n && new TextEncoder().encode(note.text).length <= 4096) {
        const ack = create(EnvelopeSchema, {kind: {case: "ack", value: create(AckSchema, {messageId: note.messageId})}});
        await subscription.send(sourceClientId, encodeEnvelope(ack));
      }
      break;
    }
    case "ack":
      // match an outstanding note on (sourceClientId, envelope.kind.value.messageId)
      break;
    default: // undefined: no kind this version knows
  }
}

async function sendNote(subscription: SubprotocolSubscription, peerClientId: string, messageId: bigint, text: string): Promise<void> {
  const protocols = await subscription.querySubprotocols(peerClientId, 10000);
  if (protocols === null || !protocols.includes(NOTES)) throw new Error("Peer support for 4097 is unknown or absent");
  const note = create(EnvelopeSchema, {kind: {case: "note", value: create(NoteSchema, {messageId, text})}});
  if (!await subscription.send(peerClientId, encodeEnvelope(note))) throw new Error("SDK did not enqueue note");
}

let subscription: SubprotocolSubscription;
subscription = await device.enableSubprotocol(NOTES, (message: SubprotocolMessage) => receiveEnvelope(subscription, message));
```

`device` is the companion Device from the [integration helper](../integration/companion.mjs); install the mirrored peer/state listeners before its initial sync and open the subscription after it, as the shared [application.mjs](../../javascript/messages/application.mjs) does. Protobuf-ES represents `uint64` as `bigint`, which keeps the full 64-bit `message_id`.
