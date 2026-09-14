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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [Gradle Maven repositories](https://docs.gradle.org/current/userguide/declaring_repositories.html) — official package-manager documentation, checked September 14, 2026.
