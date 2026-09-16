// swift-tools-version: 5.9
import Foundation
import PackageDescription

let local = ProcessInfo.processInfo.environment["URNETWORK_XCFRAMEWORK"]
var dependencies: [Package.Dependency] = []
var targets: [Target] = []
let sdk: Target.Dependency
if let path = local {
  // SwiftPM requires a relative binary path, even when a caller supplies an absolute one.
  let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().standardized.pathComponents
  let target = URL(
    fileURLWithPath: path, relativeTo: URL(fileURLWithPath: #filePath).deletingLastPathComponent()
  ).standardized.pathComponents
  var common = 0
  while common < min(root.count, target.count) && root[common] == target[common] { common += 1 }
  let relative = Array(repeating: "..", count: root.count - common) + target.dropFirst(common)
  targets.append(.binaryTarget(name: "URnetworkSdk", path: relative.joined(separator: "/")))
  sdk = .target(name: "URnetworkSdk")
} else {
  dependencies.append(
    .package(
      url: "https://github.com/urnetwork/sdk-swift",
      exact: Version(ProcessInfo.processInfo.environment["URNETWORK_SDK_VERSION"] ?? "0.0.1-dev.0")!
    ))
  sdk = .product(name: "URnetworkSdk", package: "sdk-swift")
}
targets.append(
  .target(
    name: "URExampleIntegration", dependencies: [sdk], linkerSettings: [.linkedLibrary("resolv")]))
let package = Package(
  name: "URExampleIntegration", platforms: [.macOS(.v14), .iOS(.v16)],
  products: [.library(name: "URExampleIntegration", targets: ["URExampleIntegration"])],
  dependencies: dependencies, targets: targets)
