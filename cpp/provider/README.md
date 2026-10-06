# C++ provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux, on the SDK's C++ header `urnetwork_sdk.hpp`: a header-only C++17 layer over the C ABI with handle-owning classes, `std::function` listeners and nlohmann/json data types. It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md) and the [Go reference](../../go/provider/README.md).

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [main.cpp](main.cpp) | Commands, exit codes and stop signals. |
| [session.hpp](session.hpp) | Provider device lifecycle: identity, public provide mode, listeners, status and stop. |
| [status.hpp](status.hpp) | Status fields, status line text and the clients-served count. |
| [state.hpp](state.hpp) | The private installation state directory, with base64, UUID and JWT payload decoding. |
| [command.hpp](command.hpp) | The command line and the exit codes. |
| [selftest.hpp](selftest.hpp), [selftest_main.cpp](selftest_main.cpp) | Credential-free self-test: `./provider --self-test`, and `make self-test` without the SDK library. |
| [Makefile](Makefile), [CMakeLists.txt](CMakeLists.txt) | Builds for Make and for CMake with the SDK package. |

## Build and self-test

Use a C++17 compiler, nlohmann/json 3.11 or later (`brew install nlohmann-json`, `apt install nlohmann-json3-dev`, or `vcpkg install nlohmann-json`) and a native SDK build with `urnet_new_device_local_with_provide_extender` and `urnet_device_get_client_limit_status`: the first SDK release after sdk `c638dfa8`, or a local build of sdk main. Like the other C++ examples, the Makefile defaults use `../../../sdk/cgo/include` and `../../../sdk/cgo/build/packages/darwin-arm64` (after `make -C sdk/cgo package` in an adjacent sdk checkout) and Homebrew's nlohmann/json; pass other directories for installed packages. From `cpp/provider` on macOS or Linux:

```sh
make SDK_INCLUDE=/absolute/path/to/include SDK_LIBDIR=/absolute/path/to/lib JSON_INCLUDE=/absolute/path/to/json/include
make self-test SDK_INCLUDE=/absolute/path/to/include JSON_INCLUDE=/absolute/path/to/json/include
./provider --self-test
./provider --version
```

The Makefile records `SDK_LIBDIR` as the run path. To ship the library beside the app on Linux, build with `SDK_RPATH='$$ORIGIN'` and copy `libURnetworkSdk.so` next to `provider`.

The [CMake build](CMakeLists.txt) uses the package's `urnetwork::sdk` target and `nlohmann_json::nlohmann_json`, and registers the self-test with CTest:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH="/absolute/path/to/sdk-prefix;/absolute/path/to/nlohmann-json-prefix"
cmake --build build
ctest --test-dir build
./build/provider --self-test
```

On Windows, in PowerShell, with Visual Studio and vcpkg's nlohmann/json:

```powershell
cmake -S . -B build -DCMAKE_PREFIX_PATH=C:\path\to\urnetwork-sdk -DCMAKE_TOOLCHAIN_FILE=C:\path\to\vcpkg\scripts\buildsystems\vcpkg.cmake
cmake --build build --config Release
ctest --test-dir build -C Release
.\build\Release\provider.exe --self-test
.\build\Release\provider.exe --version
```

The CMake build copies `URnetworkSdk.dll` beside `provider.exe`; keep it there. From macOS or Linux, MinGW-w64 cross-builds `provider.exe`: make an import library from the runtime's `urnetwork_sdk.def`, then link the C++ runtime statically so that the app needs only `URnetworkSdk.dll` beside it:

```sh
mkdir -p build
x86_64-w64-mingw32-dlltool -d /path/to/windows/amd64/urnetwork_sdk.def -D URnetworkSdk.dll -l build/libURnetworkSdk.a
make CXX=x86_64-w64-mingw32-g++ SDK_INCLUDE=/path/to/windows/amd64 SDK_LIBDIR=build JSON_INCLUDE=/path/to/json/include LDLIBS=-static
```

`--self-test` needs no credentials and no network and creates no device. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the SDK's data types read from the contract's JSON shapes (including `null` and missing keys), base64, UUIDs, the JWT `client_id` claim and the state-file handling (private permissions, atomic replacement, instance ID and identity reuse). `make self-test` and the CMake `provider-self-test` target run the same checks in a binary that does not link the SDK library: they use only the data types of `urnetwork_sdk.hpp`, which make no SDK calls. This example was built and its self-tests run on macOS arm64 and on Linux arm64 (glibc); the Windows build was cross-compiled and linked with MinGW-w64, not run.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The Go backend tool in [go/provider/server](../../go/provider/server/main.go) provisions installations and maps the payout wallet, and uses only the Go standard library:

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

`provision` keeps your installations in `providers.json` in the wallet directory, reissues an installation it already maps (without `provide_intent`, which the server ignores on a reissue) and otherwise creates a provider install; it writes the client JWT to the file you name, with owner-only permissions, and never prints it. Your backend delivers that token to the installation as its `client.jwt`. Without the tool, the backend sends the request itself:

```sh
API=https://api.bringyour.com
umask 077
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/cpp-provider", "provide_intent": true}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json    # a refusal answers 200 with error.message
jq -r .client_id auth-client.json                   # keep it in the installation map
jq -r .by_client_jwt auth-client.json > client.jwt  # deliver it to the installation
rm auth-client.json
```

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message as `network-consent.txt` in the wallet directory, after checking that it names your network and the coldkey. The coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature; `network-accept` submits it and saves a receipt. A **per-provider consent** covers one client and takes precedence over the network consent for it, for a client that must earn to a different coldkey, such as one run by an independent signer: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl, the signing format and the precedence rules.

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

On first run the app creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. The files have the same format in every provider example, so a state directory can move between them. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. Never put the root JWT on an installation.

## Run

```sh
./provider
```

On Windows run `.\provider.exe` with `URnetworkSdk.dll` beside it. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

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

SDK log lines also appear on stderr; the SDK's full log is in `logs/` in the state directory. The SDK calls the app's listeners on its own threads: the listeners copy what they carry into state they share by `std::shared_ptr`, and the app saves refreshed tokens and applies wallet reads on its own thread ([session.hpp](session.hpp)).

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443 and UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind these ports, providing continues without the role. The app creates its device with `urnet::newDeviceLocalWithProvideExtender` and both extender settings on; passing `false` for `defaultProvideExtender` (in [session.hpp](session.hpp)) turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C (or SIGTERM) stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the built `provider` (or `provider.exe`), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`. The service must find the SDK library as your terminal does: keep the library at the run path the build recorded (macOS, Linux), or `URnetworkSdk.dll` beside `provider.exe` (Windows).
