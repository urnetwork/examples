// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "SocketExample", platforms: [.macOS(.v14)], dependencies: [
    .package(path: "../integration"),
    .package(url: "https://github.com/Alamofire/Alamofire.git", from: "5.10.2")
], targets: [
    .executableTarget(name: "SocketExample", dependencies: [
        .product(name: "URExampleIntegration", package: "integration"),
        .product(name: "Alamofire", package: "Alamofire")
    ])
])
