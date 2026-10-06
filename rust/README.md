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

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [Cargo dependencies](https://doc.rust-lang.org/cargo/reference/specifying-dependencies.html) — official package-manager documentation, checked September 14, 2026.
