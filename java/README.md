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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [Gradle Maven repositories](https://docs.gradle.org/current/userguide/declaring_repositories.html) — official package-manager documentation, checked September 14, 2026.
