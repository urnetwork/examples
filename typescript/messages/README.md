# TypeScript peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

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
