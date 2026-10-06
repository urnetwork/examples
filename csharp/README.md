# C# SDK installation

The managed wrapper uses P/Invoke and SafeHandle over sdk/cgo. The NuGet package carries native runtime assets; consumers do not need Go.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
dotnet add package URnetwork.SDK
```

Visual Studio's NuGet UI and Paket use the same public NuGet package. Pin with `dotnet add package URnetwork.SDK --version <version>`.

Use `dotnet add package URnetwork.SDK --prerelease` for preview releases.

## Supported platforms

The binding targets .NET 8+ on desktop. Only OS/architecture RIDs present in the package are supported. Native AOT, trimming, single-file publishing and mobile require separate validation.

## GitHub and local builds

NuGet cannot use a Git URL as a package reference. Clone sdk, run `make -C sdk/csharp`, then install the .nupkg from sdk/csharp/dist/artifacts as a local NuGet source.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [NuGet package installation](https://learn.microsoft.com/en-us/nuget/consume-packages/install-use-packages-dotnet-cli) — official package-manager documentation, checked September 14, 2026.
