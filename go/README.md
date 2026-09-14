# Go SDK installation

Go imports the base SDK directly; no CGo wrapper is necessary. The existing versioned Go module is published. Socket examples require a release containing the new Device dialer API.

## Install with the native package manager

```sh
go get github.com/urnetwork/sdk/v2026@latest
```

Pin a release with `go get github.com/urnetwork/sdk/v2026@v2026.9.14-1046068620`. Keep the import's major version aligned with its module tag.

## Supported platforms

Use the platforms supported by the Go SDK and connect transport. A local Device owns the userspace stack; a remote Device uses a running app's RPC connection.

## GitHub and local builds

Go modules resolve Git tags and commits. `GOPROXY=direct go get github.com/urnetwork/sdk/v2026@<commit>` bypasses the module proxy. A main-branch workspace can instead use the root module and its development dependencies.

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [Go module publishing](https://go.dev/doc/modules/publishing) — official package-manager documentation, checked September 14, 2026.
