# Android SDK installation

Android apps use the gomobile AAR of the SDK: Java and Kotlin classes in the package `com.bringyour.sdk` with the native library for each ABI. Kotlin calls the Go API directly, for example `Sdk.newDeviceLocalWithProvideExtender` and `DeviceLocal.getClientLimitStatus()`; Go errors arrive as exceptions. Desktop Java and Kotlin use the C ABI package instead (see [Kotlin](../kotlin/README.md)).

**Publication status:** the package coordinate below describes the intended public release and is not yet published. Build the AAR locally while publication is being prepared.

## Install with the native package manager

```kotlin
implementation("io.ur:urnetwork-sdk-android:<version>")
```

Add `mavenCentral()` to the Gradle repositories.

## Supported platforms

The AAR carries arm64-v8a, armeabi-v7a and x86_64 libraries and supports Android 7.0 (API 24) and later; the examples here need Android 8.0 (API 26). Android builds of the SDK do not include the provider extender role of desktop builds.

## GitHub and local builds

Clone sdk with `connect`, `glog`, `goidenticons` and `gvisor` next to it, install the pinned gomobile and checksec with `make -C sdk/build init_tools`, then run `make -C sdk/build build_android` with `ANDROID_NDK_HOME` set to an Android NDK. The build writes `sdk/build/android/URnetworkSdk.aar`; reference it from Gradle with `implementation(files("/absolute/path/to/URnetworkSdk.aar"))`.

## Examples

- [Provider](provider/README.md): a provider client of your network in a foreground service, with the consent disclaimer, status, payout wallet display and background run.
- [Embed](embed/README.md): URnetwork inside your own app, for its own traffic: the client JWT from your backend's token server, the device, and the installation's status and data caps.

Apps get a scoped client JWT from their backend; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md), the [provider contract](../PROVIDER_CONTRACT.md) and the [embed contract](../EMBED_CONTRACT.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.
