# TypeScript SDK installation

TypeScript uses sdk/js. Conn provides typed async reads/writes and ReadableStream/WritableStream adapters; the Device exposes dial, dialTls and webTransport.

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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). Use the actual hosted Device instance ID and connection configuration.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [npm install](https://docs.npmjs.com/cli/v11/commands/npm-install/) — official package-manager documentation, checked September 14, 2026.
