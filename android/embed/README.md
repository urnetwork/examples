# Android embed

[Installation](../README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This Android app embeds the URnetwork SDK inside your own product. It obtains the installation's scoped client JWT from your backend's token server, starts a local device with it, and shows the device's status and the installation's data caps: the status, the data used this month and the running total, each against its cap, with the client and installation IDs. The device carries only the app's own traffic: it is not a device VPN, so the app needs no `VpnService`, no foreground service and only the `INTERNET` permission. It follows the [embed contract](../../EMBED_CONTRACT.md) and its [Android notes](../../EMBED_CONTRACT.md#android); the [Go embed app](../../go/embed/README.md) is the reference.

## Files

| File | Purpose |
| --- | --- |
| [build.gradle.kts](build.gradle.kts), [settings.gradle.kts](settings.gradle.kts), [gradle.properties](gradle.properties) | Gradle build: Android Gradle plugin 8.11.1, Kotlin 2.3.21, the SDK AAR. |
| [AndroidManifest.xml](src/main/AndroidManifest.xml) | `INTERNET` only, the app-scoped holder, the backup exclusion and the HTTPS-only network security configuration. |
| [MainActivity.kt](src/main/kotlin/com/example/urnetwork/embed/MainActivity.kt) | The one screen: Start, the status fields with the client and installation IDs, Stop, the licenses and the next step. |
| [EmbedApplication.kt](src/main/kotlin/com/example/urnetwork/embed/EmbedApplication.kt), [EmbedController.kt](src/main/kotlin/com/example/urnetwork/embed/EmbedController.kt) | The application-scoped holder: start and stop, the token fetch, the status every second, the cap reads every 5 minutes and after contract changes, sign-out. |
| [EmbedSession.kt](src/main/kotlin/com/example/urnetwork/embed/EmbedSession.kt) | Device lifecycle: the network space, the local device, the listeners, the best-available location, close. |
| [TokenFetch.kt](src/main/kotlin/com/example/urnetwork/embed/TokenFetch.kt) | `fetchClientJwt`: the one function to replace with your own sign-in. |
| [DataCap.kt](src/main/kotlin/com/example/urnetwork/embed/DataCap.kt) | The cap object, the cap readings, and the cap read with the client JWT. |
| [EmbedStatus.kt](src/main/kotlin/com/example/urnetwork/embed/EmbedStatus.kt) | The status rules and texts: decimal data amounts, the reset time, the client limit, the status line. |
| [EmbedState.kt](src/main/kotlin/com/example/urnetwork/embed/EmbedState.kt) | The private installation state directory and the token server settings. |
| [DebugEmbedImportReceiver.kt](src/debug/kotlin/com/example/urnetwork/embed/DebugEmbedImportReceiver.kt), [debug manifest](src/debug/AndroidManifest.xml), [debug network security configuration](src/debug/res/xml/network_security_config.xml) | Debug builds only: imports the token server settings or a `client.jwt` over adb, and allows plain HTTP to the loopback token server. |
| [EmbedStatusTest.kt](src/test/kotlin/com/example/urnetwork/embed/EmbedStatusTest.kt), [DataCapTest.kt](src/test/kotlin/com/example/urnetwork/embed/DataCapTest.kt), [EmbedStateTest.kt](src/test/kotlin/com/example/urnetwork/embed/EmbedStateTest.kt), [TokenFetchTest.kt](src/test/kotlin/com/example/urnetwork/embed/TokenFetchTest.kt) | The credential-free self-test, as JVM unit tests. |
| [res/](src/main/res) | Layout, strings, themes, icon, data extraction rules and the release network security configuration. |

The backend is the [Go token server](../../go/embed/server) in `go/embed/server`; Android has no backend tool of its own.

## Build and self-test

Use JDK 21, Gradle 9.5 (the Android Gradle plugin 8.11 does not run on Gradle 9.6; `gradle wrapper --gradle-version 9.5.1` adds a wrapper if you want one) and the Android SDK with platform 36. Point `ANDROID_HOME` at the Android SDK, or put `sdk.dir=/absolute/path/to/Android/sdk` in `local.properties`. The first build downloads its declared dependencies.

The app needs the gomobile AAR of the SDK (package `com.bringyour.sdk`) from the first SDK release after sdk `c638dfa8`, which adds the client limit status. It uses no SDK API beyond that release: it reads the caps over HTTP. When the published package `io.ur:urnetwork-sdk-android` has that release, pass `-PurnetworkSdkVersion=<version>` to every Gradle command. Until then, build the AAR from sdk main on macOS or Linux, with `connect`, `glog`, `goidenticons` and `gvisor` checked out next to `sdk`, Go, the Android NDK, and gomobile and checksec (`make -C sdk/build init_tools` installs the pinned versions):

```sh
cd sdk/build
ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/29.0.14206865" WARP_VERSION=2026.10.8-local make build_android
```

This writes `sdk/build/android/URnetworkSdk.aar`. On Linux, use your NDK path, for example `$HOME/Android/Sdk/ndk/<version>`. Copy the AAR to `libs/URnetworkSdk.aar` (ignored by git) or pass its path with `-PurnetworkSdkAar=/absolute/path/to/URnetworkSdk.aar`. From `android/embed` on macOS and Linux:

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

`assembleDebug` builds `build/outputs/apk/debug/urnetwork-android-embed-debug.apk`.

`testDebugUnitTest` is the self-test. It runs on the JVM with no credentials, no network, no device and without loading the native SDK runtime, and checks the byte, reset time, client limit text and status line vectors; the status rules with the rule vectors; the data fields (checking, unavailable, a later failure keeping the last value, the Embed-not-enabled refusal clearing it, no cap); the cap object parsing; the JWT `client_id` claim; the token fetch against a stand-in token server (the bearer session, the `installation_id`, saving `client.jwt` atomically, a mismatched client refused, and the answers mapped to the states below); the state directory (private permissions, atomic replacement, `instance-id` created once, a symlinked file refused); the token server URL rules; and the configuration errors. The app's screen shows the SDK version at the bottom.

## Backend: provision clients and set data caps

Only your backend holds the root credential. For production, make it an **API key**: create one with `POST /account/api-key` and a network JWT, keep it in your secret store, and rotate it by creating a new key, deploying it, then removing the old one with `POST /account/api-key/remove`. An API key has the same full-network scope as a network JWT: anyone holding it can provision clients, change caps and remove clients for your whole network ([contract](../../EMBED_CONTRACT.md#the-root-credential)). Never put it in an app.

Each installation gets **its own client**: the platform keeps one live connection per client, so two installations sharing one client would keep displacing each other. The token server keys each client by `user:<service-user-id>:<installation-id>`, where the installation ID is the app's `instance-id`, and it allows 5 installations per user by default. Data caps belong to a client, so they apply per installation.

The [Go token server](../../EMBED_CONTRACT.md#the-token-server) is the backend this app calls. It authenticates the app's demo session, provisions or reissues the installation's client with `POST /network/auth-client`, puts new clients in their ACL group (`isolated` unless `URNETWORK_DEFAULT_ACL_GROUP` is `default`), applies optional default caps to them and answers with the client JWT, never the root credential. From `android/embed`, on macOS and Linux:

```sh
cd ../../go/embed/server
go build -o token-server .
./token-server --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state
export URNETWORK_ROOT_JWT='urn_your-api-key-from-your-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/token-server-map.json'
export URNETWORK_DEMO_SESSIONS='/absolute/path/to/private-service-state/demo-sessions.json'
export URNETWORK_DEFAULT_ACL_GROUP=isolated
export URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT=10000000000
./token-server
```

The demo session file stands in for your service's sign-in: `{"version": 1, "sessions": {"<random token, at least 32 characters>": "alice"}}`, private like the map. The server listens on `127.0.0.1:8790`; serve the internet through your own HTTPS front end.

**ACL groups.** The token server puts each new client in `URNETWORK_DEFAULT_ACL_GROUP`, `isolated` unless you set `default`, before it answers the client JWT, so your users never see each other in your network's peer list; an app that uses Messages keeps `default`, and the token server's `acl <key> default|isolated` command moves a client later. On a server without ACL groups the token server logs that once and still answers the token ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)).

Caps are set with the root credential and **merge**: omit a field to keep it, send `null` to clear that cap, or a byte count to set it ([contract](../../EMBED_CONTRACT.md#backend-per-user-data-caps)). The monthly cap resets at 00:00 UTC on the first of the month; the running-total cap resets only when the backend sends `reset_total`. With curl, where `C` is the installation's client ID (the app shows it):

```sh
API=https://api.bringyour.com
# 10 GB a month for this installation
curl -fsS -X POST "$API/network/client-acl-group" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C", "acl_group": "isolated"}'
curl -fsS -X POST "$API/network/client-data-cap" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C", "monthly_byte_limit": 10000000000}'
# pause it at once, then resume by setting the cap back
curl -fsS -X POST "$API/network/client-data-cap" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C", "monthly_byte_limit": 0}'
# read every capped client of your network, 1000 at a time
curl -fsS "$API/network/client-data-caps?limit=1000" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
# remove the installation's client: it loses access at its next reconnect
curl -fsS -X POST "$API/network/remove-client" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C"}'
```

Removing a client does not keep the user out: if the installation signs in again, the token server provisions a new client, so your service refuses that user's sign-in. Usage is accounted when transfer contracts settle, so the used counts lag live traffic and a client can pass a cap by up to the size of its open contracts.

**The client limit.** A network can have 100 top-level clients by default: its active clients that have no parent and are not provider installs. A client stops counting when it is removed or after 30 days without connecting. Past the limit, provisioning answers `Client limit exceeded.` and the token server answers 409 `client_limit`. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

The AAR carries the arm64-v8a, armeabi-v7a and x86_64 libraries; for a release, publish an App Bundle or split the APK per ABI. The SDK (urnetwork/sdk) is licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/): your app's own code can stay proprietary, and changes to the SDK's own source files are shared under the MPL. Publish the SDK's licenses and data attributions with your app: **Licenses** shows what `Sdk.getLicenses("android")` returns, with no network.

The installation state lives in a private `embed` directory in the no-backup files directory (`/data/user/0/com.example.urnetwork.embed/no_backup/embed`), mode 0700 with 0600 files, which backups and device transfers leave out. `client.jwt` is a bearer secret: the app never prints or logs it, and a production app may also encrypt it with a key in the Android Keystore.

Because the device routes some of your users' traffic through URnetwork, your privacy policy and your Play data safety form must say so. The app uses no `VpnService`, so Google Play's VpnService declaration does not apply; the disclosures do.

## Configure the installation

Your app's sign-in provides the session. For local testing, debug builds import the token server URL and the demo session over adb; release builds have no import path and allow only HTTPS. From the emulator, the host's loopback token server is `http://10.0.2.2:8790`; from a device, run `adb reverse tcp:8790 tcp:8790` and use `http://127.0.0.1:8790`. On macOS and Linux:

```sh
adb install build/outputs/apk/debug/urnetwork-android-embed-debug.apk
adb shell am broadcast -n com.example.urnetwork.embed/.DebugEmbedImportReceiver \
  --es token_server_url http://10.0.2.2:8790 --es demo_session "$DEMO_SESSION"
```

On Windows, in PowerShell:

```powershell
adb install build\outputs\apk\debug\urnetwork-android-embed-debug.apk
adb shell am broadcast -n com.example.urnetwork.embed/.DebugEmbedImportReceiver --es token_server_url http://10.0.2.2:8790 --es demo_session $env:DEMO_SESSION
```

The broadcast answers `Broadcast completed: result=-1, data="imported token-server.json for http://10.0.2.2:8790"`, or names the problem. The session passes through the adb command line, so import it this way only on a development machine. Without a token server, the app starts with the `client.jwt` in its state: import one that a backend tool's `provision` wrote with `--es client_jwt "$(cat client.jwt)" --ez clear_token_server true`. In a debug build, `adb shell run-as com.example.urnetwork.embed ls -l no_backup/embed` lists the files.

## Run

Open **URnetwork embed** and tap **Start**. The app creates `instance-id` on first run, posts it to the token server, saves `client.jwt`, starts the device for its own traffic and shows the fields every second while the screen is open; the device keeps running in the app's process until **Stop**. `adb logcat -s EmbedController` shows the status line when it changes:

```text
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| Status | `stopped` before Start and after Stop; `signed out` after the server rejects the client credential or the token server answers 401 or 409; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` while a cap of 0 is reached; `data cap reached, resets YYYY-MM-DD HH:MM UTC` at the monthly cap, `data cap reached` at the running total; `connected` once the device has a provider; `connecting` otherwise. |
| Data this month | `checking` until the first cap read, `unavailable` if it fails, `no cap` without a monthly cap, otherwise the used amount of the cap in decimal units. While Embed isn't enabled for your network, the cap read answers `Embed isn't enabled for this network.` and both data fields read `unavailable` ([contract](../../EMBED_CONTRACT.md#embed-enablement)). |
| Data total | The same for the running total. |
| Client ID, Installation ID | The installation's client in your network, and its `instance-id`. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit; the SDK retries by itself at the time shown, after about 15 to 20 minutes. `data cap reached` is a hard stop: the installation gets no new data until the month rolls over or your backend raises, clears or resets the cap.

Android apps have no exit codes. What the console examples report with exit code 78 — a 401 or 409 from the token server, or the server rejecting the credential — shows `signed out` with the message; a missing configuration, an unreachable token server, a 5xx or an invalid answer shows `stopped` with the message. Tap Start again after fixing the cause. The SDK's log is in `logs/` in the state directory.

## Next

The embed example ends where your app's own traffic begins. Use the same device: `DeviceLocal` is a dialer, so your app opens its TCP, UDP and TLS connections on it, as the [Kotlin Sockets](../../kotlin/socket/README.md) example does on the desktop SDK; the [networking matrix](../../NETWORK_EXAMPLES.md) maps HTTP stacks to adapters. The [Kotlin Messages](../../kotlin/messages/README.md) example exchanges [URMS](../../MESSAGES_PROTOCOL.md) messages with other clients of your network on a provider-capable device of its own, not the embed device, which does not provide: that device provides to your network, so ask your users first, and the client must be in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups) rather than the token server's default `isolated`. To run those desktop examples with this installation's identity, use the content of `client.jwt` as `URNETWORK_CLIENT_JWT` and of `instance-id` as `URNETWORK_INSTANCE_ID`, one program at a time.
