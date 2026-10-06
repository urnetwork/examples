# TypeScript SDK installation

TypeScript uses sdk/js. Conn provides typed async reads/writes and Web Streams adapters; the Device exposes dial, dialTls and Direct Sockets constructors through directSockets.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
npm install @urnetwork/sdk
```

Uses the same package as JavaScript, including TypeScript declarations. No separate @types package is needed. pnpm, Yarn and Bun are alternative installers. The canonical package's first publication is pending.

For preview builds, use `npm install @urnetwork/sdk@nightly`. The unqualified command selects the `latest` tag once a release is published there.

## Supported platforms

The typed Node programs use Node 24+. Sockets use a hosted `DeviceRemote`; messages use a provider-capable native companion through extension RPC. The same SDK supplies browser types; the adjacent JavaScript socket directory includes the executable browser program.

## GitHub and local builds

Use a packaged npm tarball, or clone and build sdk/js. See the JavaScript guide for Git package layout and compatibility migration details.

The new messaging RPC capability requires the current SDK source in the [sibling-checkout layout](../javascript/integration/companion/README.md#build-from-sibling-checkouts). The message package uses `file:../../../sdk/js`. `npm run build` type-checks without emitting files; Node 24 runs `main.ts` directly with type stripping.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peers, targeted URMS text/ACK messages and offline codec tests through a native companion.
- [Provider](provider/README.md): a provider client of your network through the native companion, with the consent disclaimer, status, payout wallet display and background run.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [npm install](https://docs.npmjs.com/cli/v11/commands/npm-install/) — official package-manager documentation, checked September 14, 2026.
