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

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [Go module publishing](https://go.dev/doc/modules/publishing) — official package-manager documentation, checked September 14, 2026.
