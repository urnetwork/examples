# TypeScript SDK installation

TypeScript uses sdk/js. Conn provides typed async reads/writes and Web Streams adapters; the Device exposes dial, dialTls and Direct Sockets constructors through directSockets.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
npm install @urnetwork/sdk
```

Uses the same package as JavaScript, including TypeScript declarations. No separate @types package is needed. pnpm, Yarn and Bun are alternative installers. The canonical package's first publication is pending; @urnetwork/sdk-js is the existing name.

For preview builds, use `npm install @urnetwork/sdk@nightly`. The unqualified command selects the `latest` tag once a release is published there.

## Supported platforms

The typed Node program uses Node 24+ and a hosted DeviceRemote. The same package supplies browser types; the adjacent JavaScript directory includes the executable browser program.

## GitHub and local builds

Use a packaged npm tarball, or clone and build sdk/js. See the JavaScript guide for Git package layout and compatibility migration details.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): URMS codec checks and the current hosted-runtime capability gate.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [npm install](https://docs.npmjs.com/cli/v11/commands/npm-install/) — official package-manager documentation, checked September 14, 2026.
