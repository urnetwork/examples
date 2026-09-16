# JavaScript message codec and capability gate

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md)

Real peer messaging is currently unavailable in this runtime. The hosted `DeviceRemote` has no subprotocol API, and hosted proxy devices are excluded from the visible peer list. Socket/Direct Sockets support does not provide Device subprotocol messaging. This program implements the common codec and reports that capability limit explicitly.

## Build and self-test

Use Node 22+ and run from `javascript/messages`. The sources are [main.mjs](main.mjs) and [codec.mjs](codec.mjs), with scripts in [package.json](package.json). This package has no dependencies or compilation step; Node runs the `.mjs` files directly.

```sh
npm run self-test
node main.mjs --version
```

The self-test needs no credentials or live networking. It checks [URMS v1](../../MESSAGES_PROTOCOL.md) golden vectors, full-width nonzero `BigInt` IDs, exact lengths, the 4096-byte UTF-8 limit, malformed frames and empty ACK payloads. Package installation/TypeScript compilation may download build tools. `--version` identifies the codec and states that hosted messaging is unavailable.

## Runtime capability check

```sh
npm start
```

This prints the missing subprotocol/peer capability and exits with status **2**. There is no working live `watch` or `send` command here. Run a supported local Device [messages example](../../README.md) for the two-client demo; both peers must advertise subprotocol `4096` and query capabilities before sending.

[Integration](../integration/README.md) contains the runnable service allocator and hosted client bootstrap. Client apps use `URNETWORK_CLIENT_JWT`; only the authenticated backend holds `URNETWORK_ROOT_JWT`. Two visible messaging peers need distinct top-level client IDs and installation IDs, plus a runtime that exposes peer discovery and subprotocol callbacks. The current hosted configuration cannot satisfy that runtime requirement.
