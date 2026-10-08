// swift-tools-version: 5.9
// The Swift embed backend tool: a server-only Foundation command-line program,
// like the integration allocator it extends. It has no SDK dependency.
import PackageDescription

let package = Package(
  name: "EmbedServer",
  platforms: [.macOS(.v13)],
  products: [.executable(name: "embed-server", targets: ["EmbedServer"])],
  targets: [.executableTarget(name: "EmbedServer")]
)
