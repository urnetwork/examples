# Swift provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md) · [Embed](../embed/README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux. It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md), with the [Go provider](../../go/provider/README.md) as the reference. The SDK's Swift package is a gomobile build for Apple platforms, so this example calls the SDK's C ABI (`urnetwork_sdk.h`) through a SwiftPM C module, with one code path on all three systems.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [Package.swift](Package.swift) | The package, and where the SDK's header and native library are. |
| [module.modulemap](Sources/CURnetworkSdk/module.modulemap), [shim.h](Sources/CURnetworkSdk/shim.h) | The `CURnetworkSdk` C module over `urnetwork_sdk.h`, linking `URnetworkSdk`. |
| [main.swift](Sources/Provider/main.swift) | Commands, exit codes and the SDK's log directory. |
| [Session.swift](Sources/Provider/Session.swift) | Provider device lifecycle on the C ABI: identity, public provide mode, listeners, status, stop. |
| [Inbox.swift](Sources/Provider/Inbox.swift), [Signals.swift](Sources/Provider/Signals.swift) | Hand-off from the SDK's callbacks and the stop signals to the status loop. |
| [Status.swift](Sources/ProviderCore/Status.swift) | Status fields, status line text and the clients-served count. |
| [SdkJson.swift](Sources/ProviderCore/SdkJson.swift) | The C ABI's JSON values: client limit status, packet stats, contract details and the wallet. |
| [State.swift](Sources/ProviderCore/State.swift) | The private installation state directory. |
| [Command.swift](Sources/ProviderCore/Command.swift) | The command line and the exit codes. |
| [SelfTest.swift](Sources/ProviderCore/SelfTest.swift), [Sha256.swift](Sources/ProviderCore/Sha256.swift), [ProviderCoreTests.swift](Tests/ProviderCoreTests/ProviderCoreTests.swift) | Credential-free self-test, also run by `swift test`. |

`ProviderCore` is plain Swift: the status rules, the state files and the self-test need neither the SDK nor a network. The `provider` executable adds the SDK calls.

## Build and self-test

Use Swift 5.9 or later: Xcode or the swift.org toolchain on macOS 13.5 or later, the swift.org toolchain on Linux and Windows. The example needs the C ABI of an SDK release after sdk `c638dfa8`, the first with `urnet_new_device_local_with_provide_extender` and `urnet_device_get_client_limit_status`. Take it from the release's C runtime archive (`URnetworkSdk-C-<version>-<platform>.zip`, with `include/`, `lib/` and, for Windows, `bin/`), or from a local build of the SDK's C ABI (`make -C sdk/cgo build_darwin_arm64`, `build_linux_amd64` or `build_windows_amd64` on a macOS build host, written to `sdk/cgo/build/<os>/<arch>/`).

Two environment variables tell `Package.swift` where the SDK is: `URNETWORK_SDK_INCLUDE` is the directory with `urnetwork_sdk.h`, and `URNETWORK_SDK_LIBDIR` is the directory with the native library (`libURnetworkSdk.dylib`, `libURnetworkSdk.so`, or on Windows the import library `URnetworkSdk.lib`). Both default to `../../../sdk/cgo/build/<os>/<arch>`, a local build in an sdk checkout beside this repository. From `swift/provider`:

```sh
# macOS and Linux
export URNETWORK_SDK_INCLUDE=/absolute/path/to/urnetwork-sdk/include
export URNETWORK_SDK_LIBDIR=/absolute/path/to/urnetwork-sdk/lib
swift build -c release
swift test
.build/release/provider --self-test
.build/release/provider --version
```

The executable finds the library in `URNETWORK_SDK_LIBDIR` or beside itself. On Linux, `swift build -c release --static-swift-stdlib` makes a binary that needs no Swift runtime on the machine that runs it, only `libURnetworkSdk.so` beside it.

```powershell
# Windows, in PowerShell; the Swift toolchain uses the Visual Studio C++ tools
$env:URNETWORK_SDK_INCLUDE = 'C:\path\to\urnetwork-sdk\include'
$env:URNETWORK_SDK_LIBDIR = 'C:\path\to\urnetwork-sdk\lib'
swift build -c release
swift test
Copy-Item C:\path\to\urnetwork-sdk\bin\URnetworkSdk.dll .build\release\
.build\release\provider.exe --self-test
.build\release\provider.exe --version
```

Windows loads `URnetworkSdk.dll` from beside `provider.exe`. A local SDK build has the DLL and `urnetwork_sdk.def` but no import library; make one in a Developer PowerShell with `lib /def:urnetwork_sdk.def /machine:x64 /out:URnetworkSdk.lib`.

`--self-test` needs no credentials and no network and creates no device. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the C ABI's JSON values, the JWT claim and the state-file handling (private permissions, atomic replacement, instance ID and identity reuse), and that the SDK values the status rules use match `urnetwork_sdk.h`. `swift test` runs the same checks without loading the native library: its test target does not link it.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations and maps the payout wallet. It uses only the Go standard library, so it fits next to any backend:

```sh
cd ../../go/provider/server
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` keeps your installations in a private `providers.json` in the wallet directory. It reissues an installation it already maps and otherwise creates a provider install with `provide_intent`, and writes the client JWT to the file you name with owner-only permissions, without printing it. Your backend delivers that token to the installation as its `client.jwt`. The [contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients) shows the same request with curl.

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later, so no key ships in any app:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message as `network-consent.txt` in the wallet directory, after checking that it names your network and the coldkey. The coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature; `network-accept` submits it. A **per-provider consent** covers one client and takes precedence over the network consent for it, for an installation that must earn to a different coldkey, such as one run by an independent signer: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl, the signing format and the precedence rules.

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

On a test machine that also runs the backend tool, `./wallet provision user:alice:laptop-1 "$URNETWORK_PROVIDER_STATE_DIR/client.jwt"` writes the file directly. On first run the app creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. Never put the root JWT on an installation.

## Run

```sh
.build/release/provider
```

On Windows run `.build\release\provider.exe`. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

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

The SDK copies its log lines to stderr and keeps its full log in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443, which the role needs, and UDP 443 and 4053 when it can bind them, so that clients that cannot reach the platform directly can connect through this provider, and it logs those listeners on stderr. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind TCP 443, providing continues with the role off, and the SDK tries TCP 443 again every few minutes. Only the subnet's command-line provider also listens on UDP 53; clients of the role try both UDP 53 and 4053. The app creates its device with `urnet_new_device_local_with_provide_extender` and both extender settings on (`provideExtenderEnabled` and `defaultProvideExtender` in [Session.swift](Sources/Provider/Session.swift)); setting `defaultProvideExtender` to `false` turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C (and SIGTERM on macOS and Linux) stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the built `provider` (or `provider.exe`), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`.

The service must find the SDK's library as the terminal did: keep `libURnetworkSdk.dylib` or `libURnetworkSdk.so` in `URNETWORK_SDK_LIBDIR` or beside the executable, and `URnetworkSdk.dll` beside `provider.exe`. On Linux, build with `--static-swift-stdlib` so the service needs no Swift runtime. On Windows, the task's user needs the Swift runtime on its `PATH`, which the Swift installer sets up.
