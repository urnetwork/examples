# Android provider

[Installation](../README.md) · [Provider](README.md)

This Android app runs a URnetwork provider inside your application. A foreground service provides publicly as a provider client of your network while the user has started providing, and the app shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md) and its [Android notes](../../PROVIDER_CONTRACT.md#android); the [Go provider](../../go/provider/README.md) is the reference.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

The app shows the disclaimer next to its Start control. Your app shows its own consent screen before it starts providing, and lets the user stop at any time.

## Files

| File | Purpose |
| --- | --- |
| [build.gradle.kts](build.gradle.kts), [settings.gradle.kts](settings.gradle.kts), [gradle.properties](gradle.properties) | Gradle build: Android Gradle plugin 8.11.1, Kotlin 2.3.21, the SDK AAR. |
| [AndroidManifest.xml](src/main/AndroidManifest.xml) | Permissions, the `specialUse` foreground service and the backup exclusion. |
| [MainActivity.kt](src/main/kotlin/com/example/urnetwork/provider/MainActivity.kt) | The one screen: the disclaimer next to Start, the four status fields, Stop. |
| [ProviderService.kt](src/main/kotlin/com/example/urnetwork/provider/ProviderService.kt) | The foreground service that owns the device: start and stop, the status every second, the notification with Stop, the pause on metered networks. |
| [ProviderSession.kt](src/main/kotlin/com/example/urnetwork/provider/ProviderSession.kt) | Provider device lifecycle: identity, public provide mode, listeners, status, stop. |
| [ProviderStatus.kt](src/main/kotlin/com/example/urnetwork/provider/ProviderStatus.kt) | Status fields, status text and the clients-served count. |
| [ProviderState.kt](src/main/kotlin/com/example/urnetwork/provider/ProviderState.kt) | The private installation state directory. |
| [DebugClientJwtReceiver.kt](src/debug/kotlin/com/example/urnetwork/provider/DebugClientJwtReceiver.kt), [debug manifest](src/debug/AndroidManifest.xml) | Debug builds only: imports `client.jwt` over adb. |
| [ProviderStatusTest.kt](src/test/kotlin/com/example/urnetwork/provider/ProviderStatusTest.kt), [ProviderStateTest.kt](src/test/kotlin/com/example/urnetwork/provider/ProviderStateTest.kt) | The credential-free self-test, as JVM unit tests. |
| [res/](src/main/res) | Layout, strings, themes, icons and the data extraction rules. |

## Build and self-test

Use JDK 21, Gradle 9.5 (the Android Gradle plugin 8.11 does not run on Gradle 9.6; `gradle wrapper --gradle-version 9.5.1` adds a wrapper if you want one) and the Android SDK with platform 36. Point `ANDROID_HOME` at the Android SDK, or put `sdk.dir=/absolute/path/to/Android/sdk` in `local.properties`. The first build downloads its declared dependencies.

The app needs the gomobile AAR of the SDK (package `com.bringyour.sdk`) from the first SDK release after sdk `c638dfa8`, which adds the client limit status and the extender-aware constructor. When the published package `io.ur:urnetwork-sdk-android` has that release, pass `-PurnetworkSdkVersion=<version>` to every Gradle command. Until then, build the AAR from sdk main on macOS or Linux, with `connect`, `glog`, `goidenticons` and `gvisor` checked out next to `sdk`, Go, the Android NDK, and gomobile and checksec (`make -C sdk/build init_tools` installs the pinned versions):

```sh
cd sdk/build
ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/29.0.14206865" WARP_VERSION=2026.10.6-local make build_android
```

This writes `sdk/build/android/URnetworkSdk.aar`. On Linux, use your NDK path, for example `$HOME/Android/Sdk/ndk/<version>`. Copy the AAR to `libs/URnetworkSdk.aar` (ignored by git) or pass its path with `-PurnetworkSdkAar=/absolute/path/to/URnetworkSdk.aar`. From `android/provider` on macOS and Linux:

```sh
mkdir -p libs
cp /absolute/path/to/sdk/build/android/URnetworkSdk.aar libs/
gradle assembleDebug testDebugUnitTest
```

On Windows, in PowerShell (build the AAR on macOS or Linux, or use the published package):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
New-Item -ItemType Directory -Force -Path libs | Out-Null
Copy-Item C:\path\to\URnetworkSdk.aar libs\
gradle assembleDebug testDebugUnitTest
```

`assembleDebug` builds `build/outputs/apk/debug/urnetwork-android-provider-debug.apk`. The AAR carries the arm64-v8a, armeabi-v7a and x86_64 libraries; for a release, split the APK per ABI or publish an App Bundle.

`testDebugUnitTest` is the self-test. It runs on the JVM with no credentials, no network, no device and without loading the native SDK runtime, and checks the disclaimer text, the byte and status text vectors, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the JWT `client_id` claim, the state-file handling (private permissions, atomic replacement, instance ID and identity reuse) and the configuration errors. The app's screen shows the SDK version at the bottom.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The Go backend tool in [go/provider/server](../../go/provider/server/main.go) provisions installations and maps the payout wallet; its [README section](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) explains each command. From `android/provider`:

```sh
cd ../../go/provider/server
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:phone-1 /absolute/path/to/private-service-state/alice-phone-1.jwt
```

`provision` creates a provider install, or reissues the installation's stored client, and writes the client JWT to the file you name with owner-only permissions. With curl, post `{"description": "provider user:alice:phone-1", "device_spec": "urnetwork-examples/android-provider", "provide_intent": true}` to `/network/auth-client` with the root JWT, as the [contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients) shows, and deliver `by_client_jwt` to the installation.

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

A **per-provider consent** covers one client and takes precedence over the network consent for it, for example for a client run by an independent signer: `./wallet challenge <client-id>`, then `./wallet accept <client-id> <signature>`. The [contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl, the signing format and the precedence rules.

## Configure the installation

The app keeps its state in a private `provider` directory in its no-backup files directory (`/data/user/0/com.example.urnetwork.provider/no_backup/provider`), which it creates with owner-only permissions; Android backups and device transfers leave it out. Your app's sign-in flow receives the scoped client JWT from your backend and saves it with `importClientJwt(File(context.noBackupFilesDir, stateDirName), clientJwt)`. Never put the root JWT on an installation.

For local testing, debug builds import `client.jwt` over adb; release builds have no import path. Install the debug APK and import the token that `provision` wrote, on macOS and Linux:

```sh
adb install build/outputs/apk/debug/urnetwork-android-provider-debug.apk
adb shell am broadcast -n com.example.urnetwork.provider/.DebugClientJwtReceiver \
  --es client_jwt "$(cat /absolute/path/to/private-service-state/alice-phone-1.jwt)"
```

On Windows, in PowerShell:

```powershell
adb install build\outputs\apk\debug\urnetwork-android-provider-debug.apk
$clientJwt = (Get-Content C:\path\to\private-service-state\alice-phone-1.jwt -Raw).Trim()
adb shell am broadcast -n com.example.urnetwork.provider/.DebugClientJwtReceiver --es client_jwt $clientJwt
```

The broadcast answers `Broadcast completed: result=-1, data="imported client.jwt for client <client-id>"`, or names the problem, for example a network JWT without a `client_id` claim. The token passes through the adb command line, so import it this way only on a development machine. On first start the app creates `instance-id` and `identity.json` next to `client.jwt`, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. In a debug build, `adb shell run-as com.example.urnetwork.provider ls -l no_backup/provider` lists the files.

## Run

Open **URnetwork provider** and tap **Start providing**. On Android 13 and later the app first asks to show notifications; it provides either way, but without the permission its notification stays hidden. The screen shows the four fields every second while it is open, and the notification shows the status line with a **Stop** action:

```text
status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)
```

| Field | Meaning |
| --- | --- |
| Status | `starting` until the provider is enabled and connected to the platform, then `providing`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` on a metered network and `stopped` otherwise. |
| Clients served | Distinct clients that opened a contract with this provider since providing started. |
| Data provided | Bytes relayed for clients, both directions, since providing started. |
| Payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping, `(network)` for the network's wallet, `(hotkey)` for the network's hotkey delegation (a later server and SDK change) or `(another provider)`; `checking`, `not set` or `unavailable` otherwise. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit and this installation has not qualified as a provider. The SDK retries by itself after about 15 to 20 minutes, at the time the status shows. A provider install that qualifies as a provider (providing publicly, passing its egress probe and reliable) is exempt from the limit and does not get this status.

Desktop SDK builds also run the provider extender role while providing, listening on TCP 443 and UDP 443, 53 and 4053 for clients that cannot reach the platform directly. Android builds of the SDK do not include the role, so this app opens none of these listeners. The app still creates its device with `Sdk.newDeviceLocalWithProvideExtender` and both extender settings on, as every provider example does; passing `false` for `defaultProvideExtender` turns the default off where the role exists (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Android apps have no exit codes. A configuration or credential problem that a restart does not fix, which the console examples report with exit code 78 (a missing or invalid state, a network JWT, or the server rejecting the client credential), stops providing and shows the problem above the status fields; import a new scoped JWT from your backend and start again. Any other start failure shows `could not start the provider:` with the cause. The SDK's log is in `logs/` in the state directory; `adb logcat` shows the app's own messages.

## Run in the background

The foreground service is Android's background pattern ([contract](../../PROVIDER_CONTRACT.md#android)). It keeps providing after the user leaves the app or turns off the screen, with its notification showing the status and **Stop**; Stop in the app or in the notification ends it. On Android 14 and later it runs as a `specialUse` foreground service, which has no daily time limit, with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` that explains providing. It starts only from the user's tap on Start: when the system ends the app's process, providing stays stopped until the user starts it again.

Providing pauses on metered networks, such as mobile data, and resumes on unmetered ones; the status shows `paused` meanwhile. Set `provideOnMeteredNetworks` in [ProviderService.kt](src/main/kotlin/com/example/urnetwork/provider/ProviderService.kt) to `true` to provide on metered networks too.
