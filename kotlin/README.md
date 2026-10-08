# Kotlin SDK installation

Kotlin/JVM consumes sdk/java; sdk/kotlin/Makefile delegates to it. Android uses gomobile. There is no separate Kotlin/Native or Kotlin Multiplatform runtime.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```kotlin
implementation("io.ur:urnetwork-sdk:<version>")
```

For Android use `implementation("io.ur:urnetwork-sdk-android:<version>")`. Add `mavenCentral()` to Gradle repositories. Desktop Java and Kotlin share one JAR.

## Supported platforms

JVM and Android support follows the Java guide. Run blocking socket calls on Dispatchers.IO, and close the socket to cancel an in-progress native read.

## GitHub and local builds

Clone sdk and run `make -C sdk/kotlin` (desktop) or `make -C sdk/kotlin package-android` (already-built AAR). Consume the staged Maven artifacts locally.

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
