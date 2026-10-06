# Kotlin provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux, with the desktop JVM package `io.ur:urnetwork-sdk` (JNA over the SDK's C ABI; Kotlin/JVM and Java share it). It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md); the [Go provider](../../go/provider/README.md) is the reference implementation. Android apps use the gomobile AAR instead; see the contract's [Android notes](../../PROVIDER_CONTRACT.md#android).

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [Main.kt](src/main/kotlin/Main.kt) | Commands, exit codes and the shutdown hook for Ctrl-C and SIGTERM. |
| [ProviderSession.kt](src/main/kotlin/ProviderSession.kt) | Provider device lifecycle over the C ABI: identity, public provide mode, listeners, status, stop. |
| [ProviderStatus.kt](src/main/kotlin/ProviderStatus.kt) | Status fields, status line text, the clients-served count and the C ABI's JSON values, read with the JSON elements of kotlinx.serialization (no compiler plugin). |
| [InstallationState.kt](src/main/kotlin/InstallationState.kt) | The private installation state directory. |
| [SelfTest.kt](src/main/kotlin/SelfTest.kt) | Credential-free self-test. |
| [build.gradle.kts](build.gradle.kts), [settings.gradle.kts](settings.gradle.kts) | Gradle build: the application distribution and the `selfTest` task, which `gradle check` runs. |

## Build and self-test

Use Gradle and JDK 21; the build selects Kotlin 2.2.20 and kotlinx-serialization-json 1.9.0. It requires the desktop SDK package `io.ur:urnetwork-sdk` with the provider APIs this app calls: the extender-aware constructor `urnet_new_device_local_with_provide_extender` and the client limit status `urnet_device_get_client_limit_status`, in the SDK from the first release after sdk `c638dfa8`. The SDK version defaults to `0.0.1-dev.0`, the version of a local SDK build, which Gradle reads from Maven Local; add `-PsdkVersion=VERSION` to each Gradle command to select a release with these APIs. For a local build, clone sdk with its sibling repositories, then build the package (`sdk/kotlin` builds the shared Java package) and install it in Maven Local as the [Kotlin installation guide](../README.md#github-and-local-builds) describes:

```sh
make -C sdk/kotlin
mvn install:install-file -Dfile=sdk/java/dist/artifacts/urnetwork-sdk-0.0.1-dev.0.jar -DpomFile=sdk/java/dist/artifacts/urnetwork-sdk-0.0.1-dev.0.pom
```

That jar carries the native SDK runtime for the OS and architecture that built it. To load another native library, set `URNETWORK_SDK_LIBRARY` to the absolute path of `libURnetworkSdk.dylib`, `libURnetworkSdk.so` or `URnetworkSdk.dll`.

From `kotlin/provider` on macOS and Linux:

```sh
gradle --console=plain build installDist
build/install/urnetwork-provider/bin/urnetwork-provider --self-test
build/install/urnetwork-provider/bin/urnetwork-provider --version
```

On Windows, in PowerShell:

```powershell
gradle --console=plain build installDist
build\install\urnetwork-provider\bin\urnetwork-provider.bat --self-test
build\install\urnetwork-provider\bin\urnetwork-provider.bat --version
```

`gradle build` compiles the app and runs the self-test; `installDist` writes the start scripts and the jars to `build/install/urnetwork-provider`. The bytecode is the same on every OS; each OS needs its own native SDK runtime.

`--self-test` needs no credentials and no network, and does not load the native runtime. It checks the disclaimer, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the C ABI's JSON values and the state-file handling (private permissions, atomic replacement, instance ID and identity reuse). `--version` loads the native runtime and prints its SDK version.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true` on `POST /network/auth-client`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The app never calls auth-client and never sets the flag: the SDK declares provider intent on its connections by itself while it provides publicly.

The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations and maps the payout wallet, and uses only the Go standard library. From `go/provider/server`:

```sh
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` creates a provider install, or reissues the one it already maps for that installation key, and writes the client JWT to the file you name with owner-only permissions; your backend delivers that token to the installation as its `client.jwt`. Without the tool, post `{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/kotlin-provider", "provide_intent": true}` to `/network/auth-client` with the root JWT, as the [contract's curl example](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients) shows. The [Kotlin allocator](../integration/README.md#backend-allocator) provisions ordinary clients, without the flag.

The payout wallet is your fixed Bittensor coldkey. Map it with one **network consent**, which covers every provider client of your network, including the ones you provision later. The coldkey owner signs the consent message offline with their own wallet tool; the coldkey never touches the backend or the app:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message as `network-consent.txt` in the wallet directory, after checking that it names your network and the coldkey. The coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature for `network-accept`. A client that must earn to a different coldkey, for example one run by an independent signer, gets a **per-provider consent**, which covers that one client and takes precedence over the network consent: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives both flows with curl, the signing format and the precedence rules.

## Configure the installation

The app reads everything from one private directory named by `URNETWORK_PROVIDER_STATE_DIR`. Create it with owner-only permissions and write the scoped client JWT into `client.jwt`:

```sh
# Linux; on macOS use "$HOME/Library/Application Support/urnetwork-provider"
umask 077
export URNETWORK_PROVIDER_STATE_DIR="$HOME/.local/state/urnetwork-provider"
mkdir -p "$URNETWORK_PROVIDER_STATE_DIR"
chmod 700 "$URNETWORK_PROVIDER_STATE_DIR"
printf '%s\n' 'scoped-client-jwt-from-your-backend' > "$URNETWORK_PROVIDER_STATE_DIR/client.jwt"
```

```powershell
# Windows: the profile directory keeps the files private to the user
$env:URNETWORK_PROVIDER_STATE_DIR = "$env:LOCALAPPDATA\urnetwork-provider"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_PROVIDER_STATE_DIR | Out-Null
Set-Content -Path "$env:URNETWORK_PROVIDER_STATE_DIR\client.jwt" -Value 'scoped-client-jwt-from-your-backend'
```

On a test machine that also runs the backend tool, `./wallet provision user:alice:laptop-1 "$URNETWORK_PROVIDER_STATE_DIR/client.jwt"` writes the file directly. On first run the app creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. The state files are the same in every provider example. Never put the root JWT on an installation.

## Run

```sh
build/install/urnetwork-provider/bin/urnetwork-provider
```

On Windows run `build\install\urnetwork-provider\bin\urnetwork-provider.bat`. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

```text
status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)
```

| Field | Meaning |
| --- | --- |
| status | `starting` until the provider is enabled and connected to the platform, then `providing`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` and `stopped` otherwise. |
| clients served | Distinct clients that opened a contract with this provider since the app started. |
| data provided | Bytes relayed for clients, both directions, since the app started. |
| payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping, `(network)` for the network's wallet, `(hotkey)` for the network's hotkey delegation (a later server and SDK change) or `(another provider)`; `checking`, `not set` or `unavailable` otherwise. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit and this installation has not qualified as a provider. The SDK retries by itself after about 15 to 20 minutes, at the time the status shows. A provider install that qualifies as a provider (providing publicly, passing its egress probe and reliable) is exempt from the limit and does not get this status.

The SDK copies its log lines to stderr, and keeps its full log in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443, which the role needs, and UDP 443 and 4053 when it can bind them, so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections for the Java runtime the first time; on Linux, without the privilege to bind TCP 443, providing continues with the role off, and the SDK tries TCP 443 again every few minutes. Only the subnet's command-line provider also listens on UDP 53; clients of the role try both UDP 53 and 4053. The app creates its device with `urnet_new_device_local_with_provide_extender` and both extender settings on (`provideExtenderEnabled` and `defaultProvideExtender` in [ProviderSession.kt](src/main/kotlin/ProviderSession.kt)); setting `defaultProvideExtender` to `false` turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C or SIGTERM stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure, such as a native runtime that does not load.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of `build/install/urnetwork-provider/bin/urnetwork-provider` (on Windows, `urnetwork-provider.bat` in the same directory), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`. The start script finds Java through `JAVA_HOME` or the `PATH`; a service starts with a minimal environment, so set `JAVA_HOME` in the service when Java is not on its default path, and set `URNETWORK_SDK_LIBRARY` there too when the app loads its native runtime through it, next to `URNETWORK_PROVIDER_STATE_DIR` (`Environment=` for systemd, `EnvironmentVariables` for launchd, `$env:` in the Windows launcher).
