# Java SDK installation

Desktop Java uses the generated JNA wrapper over sdk/cgo (Java 17+). Android uses the existing gomobile AAR; it is a different artifact.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```kotlin
implementation("io.ur:urnetwork-sdk:<version>")
```

Maven: add `io.ur:urnetwork-sdk:<version>` as a dependency. Android uses `io.ur:urnetwork-sdk-android:<version>` instead. Gradle repositories must include `mavenCentral()`.

## Supported platforms

Desktop packages bundle native resources for the supported 64-bit platforms. Android currently builds API 24+ for arm64, arm, and amd64. Keep one Go runtime binding per process.

## GitHub and local builds

Maven and Gradle do not natively consume arbitrary Git dependencies. Clone sdk and run `make -C sdk/java`; install the resulting JAR and POM in Maven Local. JitPack support requires a tested root build and is not currently promised.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.
- [Embed](embed/README.md): URnetwork inside your own app: one scoped client per installation from your backend, the in-app Device, and the installation's data caps; with the backend tool that provisions clients and sets caps.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [Gradle Maven repositories](https://docs.gradle.org/current/userguide/declaring_repositories.html) — official package-manager documentation, checked September 14, 2026.
