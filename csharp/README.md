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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [NuGet package installation](https://learn.microsoft.com/en-us/nuget/consume-packages/install-use-packages-dotnet-cli) — official package-manager documentation, checked September 14, 2026.
