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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [Swift package manifests](https://docs.swift.org/package-manager/PackageDescription/PackageDescription.html) — official package-manager documentation, checked September 14, 2026.
