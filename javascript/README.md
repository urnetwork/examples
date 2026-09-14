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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). Use the actual hosted Device instance ID and connection configuration.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [npm install](https://docs.npmjs.com/cli/v11/commands/npm-install/) — official package-manager documentation, checked September 14, 2026.
