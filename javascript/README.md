# JavaScript SDK installation

JavaScript wraps sdk/js (Go/WASM) and uses async Conn objects and Web Streams. It does not load the CGo native library.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
npm install @urnetwork/sdk
```

The canonical name is awaiting its first publication. Its preview builds use `npm install @urnetwork/sdk@nightly`; the unqualified command above requires a release on the `latest` tag. The existing package is `@urnetwork/sdk-js`. npm, pnpm, Yarn and Bun all consume the same package.

## Supported platforms

The package loader runs in current browsers and Node; the executable Node example uses Node 24+. Both examples use a configured hosted DeviceRemote. Bun is an alternative installer; Bun runtime behavior is not separately qualified.

## GitHub and local builds

npm supports Git dependencies, but this repository's package is under js/. Use a published .tgz release asset, or clone sdk and run `make -C sdk/js package check-package` and install its tarball from `sdk/js/release/artifacts`. repository.directory is not a Git subdirectory installer.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): URMS codec checks and the current hosted-runtime capability gate.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [npm install](https://docs.npmjs.com/cli/v11/commands/npm-install/) — official package-manager documentation, checked September 14, 2026.
