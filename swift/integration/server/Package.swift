// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "ServerAllocator", platforms: [.macOS(.v13)], products: [.executable(name: "Allocator", targets: ["Allocator"])], targets: [.executableTarget(name: "Allocator")])
