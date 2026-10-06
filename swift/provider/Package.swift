// swift-tools-version: 5.9
// The Swift provider example (PROVIDER_CONTRACT.md): a console app for Windows,
// macOS and Linux on the SDK's C ABI. The SDK's Swift package (gomobile) has no
// Linux or Windows binary, so CURnetworkSdk is a C module over urnetwork_sdk.h
// that links the native library on all three systems.
//
// URNETWORK_SDK_INCLUDE names the directory with urnetwork_sdk.h, and
// URNETWORK_SDK_LIBDIR the directory with the native library (on Windows, the
// URnetworkSdk.lib import library). Both default to the host build of a sibling
// sdk checkout, sdk/cgo/build/<os>/<arch>. ProviderCore and its tests are plain
// Swift and use neither.
import Foundation
import PackageDescription

let environment = ProcessInfo.processInfo.environment
// the package directory: this manifest's path without its file name
let manifestPath = #filePath
let manifestNameStart =
  manifestPath.lastIndex(where: { $0 == "/" || $0 == "\\" }) ?? manifestPath.startIndex
let packageDirectory = String(manifestPath[..<manifestNameStart])
#if os(macOS)
  let hostSystem = "darwin"
#elseif os(Windows)
  let hostSystem = "windows"
#else
  let hostSystem = "linux"
#endif
#if arch(arm64)
  let hostArch = "arm64"
#else
  let hostArch = "amd64"
#endif
let sdkBuildDirectory = "\(packageDirectory)/../../../sdk/cgo/build/\(hostSystem)/\(hostArch)"
let sdkInclude = environment["URNETWORK_SDK_INCLUDE"] ?? sdkBuildDirectory
let sdkLibDir = environment["URNETWORK_SDK_LIBDIR"] ?? sdkBuildDirectory

/// The executable's library search path on macOS and Linux: the SDK library
/// directory, then the executable's own directory (origin), so a copy of the
/// library beside the executable works too. Windows looks beside the executable.
func sdkRpathFlags(_ origin: String) -> [String] {
  return ["-Xlinker", "-rpath", "-Xlinker", sdkLibDir, "-Xlinker", "-rpath", "-Xlinker", origin]
}

let package = Package(
  name: "ProviderExample",
  platforms: [.macOS("13.5")],
  products: [.executable(name: "provider", targets: ["Provider"])],
  targets: [
    // urnetwork_sdk.h, linking the native library
    .systemLibrary(name: "CURnetworkSdk", path: "Sources/CURnetworkSdk"),
    // status rules, installation state and the self-test, without the native sdk
    .target(name: "ProviderCore"),
    .executableTarget(
      name: "Provider",
      dependencies: ["CURnetworkSdk", "ProviderCore"],
      swiftSettings: [.unsafeFlags(["-Xcc", "-I\(sdkInclude)"])],
      linkerSettings: [
        .unsafeFlags(["-L\(sdkLibDir)"]),
        .unsafeFlags(sdkRpathFlags("@executable_path"), .when(platforms: [.macOS])),
        .unsafeFlags(sdkRpathFlags("$ORIGIN"), .when(platforms: [.linux])),
      ]),
    .testTarget(name: "ProviderCoreTests", dependencies: ["ProviderCore"]),
  ]
)
