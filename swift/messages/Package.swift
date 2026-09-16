// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "MessagesExample", platforms: [.macOS(.v14)], dependencies: [.package(path: "../integration")], targets: [
    .executableTarget(name: "MessagesExample", dependencies: [.product(name: "URExampleIntegration", package: "integration")])
])
