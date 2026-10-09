# Swift SDK installation

Swift uses the existing gomobile Apple XCFramework. sdk/swift/Makefile packages that binary; it does not create a new networking implementation.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```swift
.package(url: "https://github.com/urnetwork/sdk-swift", exact: "<version>")
```

In Xcode, use File → Add Package Dependencies and the same distribution repository URL. The generated SwiftPM distribution is awaiting publication. CocoaPods and Carthage metadata are staged from the same XCFramework.

## Supported platforms

iOS 16+ and macOS 13.5+ in the current mobile build: iOS arm64, simulator arm64, macOS arm64/amd64. This package does not provide Linux Swift.

## GitHub and local builds

SwiftPM requires Package.swift at the repository root. The proposed sdk-swift repository is the small binary distribution. For development run `make -C sdk/swift` and use sdk/swift/dist/package; the referenced immutable XCFramework URL must exist before publishing that package.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run, on the SDK's C ABI for Windows, macOS and Linux.
- [Embed](embed/README.md): URnetwork inside your own app, for its own traffic: your backend provisions one client per installation, puts it in its ACL group and sets its data caps; the app runs a local Device and shows its status.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [Swift package manifests](https://docs.swift.org/package-manager/PackageDescription/PackageDescription.html) — official package-manager documentation, checked September 14, 2026.
