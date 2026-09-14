// swift-tools-version: 5.9
import PackageDescription
import Foundation

// Published installs use the small SwiftPM distribution repository. A local
// XCFramework is an explicit development option, not a public registry path.
let local = ProcessInfo.processInfo.environment["URNETWORK_XCFRAMEWORK"]
var dependencies: [Package.Dependency] = [
    .package(url: "https://github.com/Alamofire/Alamofire.git", from: "5.10.2")
]
var targets: [Target] = []
var sdk: Target.Dependency
if let path = local {
    targets.append(.binaryTarget(name: "URnetworkSdk", path: path))
    sdk = .target(name: "URnetworkSdk")
} else {
    dependencies.append(.package(url: "https://github.com/urnetwork/sdk-swift", exact: Version(ProcessInfo.processInfo.environment["URNETWORK_SDK_VERSION"] ?? "0.0.1-dev.0")!))
    sdk = .product(name: "URnetworkSdk", package: "sdk-swift")
}
targets.append(.executableTarget(name: "SocketExample", dependencies: [sdk, .product(name: "Alamofire", package: "Alamofire")], linkerSettings: [.linkedLibrary("resolv")]))
let package = Package(name: "SocketExample", platforms: [.macOS(.v14)], dependencies: dependencies, targets: targets)
