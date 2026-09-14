# Rust SDK installation

Rust exposes the raw C ABI and owned Handle/Device/Conn wrappers over sdk/cgo. The package embeds compressed native runtime bytes, verifies their digest, and loads them without requiring Go in the consumer.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
cargo add urnetwork-sdk
```

Pin a registry version with `cargo add urnetwork-sdk@<version>`. This is a library: cargo install is not the installation command.

## Supported platforms

Rust 1.85+ and a platform included in the release manifest. The small public crate downloads a version-pinned, checksum-verified native gzip at build time and embeds the runtime in the executable. `SDK_RUST_NATIVE_CACHE` supports a prepopulated offline cache. Source checkout users run the native preparation target.

## GitHub and local builds

Cargo supports Git dependencies, but the source checkout must first have its runtime prepared with `make -C sdk/rust native` (or explicitly use URNETWORK_SDK_LIBRARY). For an install independent of the checkout, use the .crate produced by `make -C sdk/rust`.

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [Cargo dependencies](https://doc.rust-lang.org/cargo/reference/specifying-dependencies.html) — official package-manager documentation, checked September 14, 2026.
