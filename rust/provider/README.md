# Rust provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux. It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md) and the [Go provider](../../go/provider/README.md), the reference implementation, over the SDK's C ABI. Its provider core is a library, so other Rust apps can share it.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [src/main.rs](src/main.rs) | Commands, stop signals and exit codes. |
| [src/session.rs](src/session.rs) | Provider device lifecycle over the C ABI: identity, public provide mode, listeners, status, stop. |
| [src/status.rs](src/status.rs) | Status fields, status line text and the clients-served count. |
| [src/sdk_json.rs](src/sdk_json.rs) | The C ABI's JSON values that the status reads. |
| [src/state.rs](src/state.rs) | The private installation state directory. |
| [src/id.rs](src/id.rs) | Ids in canonical form, without the native runtime. |
| [src/self_test.rs](src/self_test.rs), [tests/self_test.rs](tests/self_test.rs) | Credential-free self-test, also run by `cargo test`. |
| [src/lib.rs](src/lib.rs), [Cargo.toml](Cargo.toml) | The provider core library (`urnetwork_provider`) and the `provider` binary. |

## Build and self-test

Use Rust 1.85 or later (edition 2024). [Cargo.toml](Cargo.toml) requires the SDK crate as `urnetwork-sdk = "0.0.1-dev.0"`; select a release that has the provider intent, client limit and extender APIs (the first SDK release after sdk `c638dfa8`), or use the local development patch below. The published crate embeds the native runtime for each supported platform when it builds, so the built `provider` needs nothing else at run time. From `rust/provider`:

```sh
cargo build --release
cargo test
./target/release/provider --self-test
./target/release/provider --version
```

On Windows, in PowerShell, the same `cargo` commands build `target\release\provider.exe`; run `.\target\release\provider.exe --self-test`. Linux builds need a glibc target: the SDK has no musl runtime.

Before the crate is published, build against a local SDK checkout. `make -C sdk/rust` stages a crate with the native runtime embedded in `sdk/rust/dist/project`; pass it as a Cargo patch to every `cargo` command:

```sh
cargo build --release --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust/dist/project"'
```

Or patch in the source crate `sdk/rust` and point `URNETWORK_SDK_LIBRARY` at a C ABI library built in `sdk/cgo` (`libURnetworkSdk.dylib`, `libURnetworkSdk.so` or `URnetworkSdk.dll`) whenever the app runs:

```sh
cargo build --release --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust"'
export URNETWORK_SDK_LIBRARY=/absolute/path/to/sdk/cgo/build/darwin/arm64/libURnetworkSdk.dylib
./target/release/provider --version
```

`--self-test` needs no credentials, no network and no native runtime. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the C ABI JSON that the status reads, the state-file handling (private permissions, atomic replacement, instance ID and identity reuse) and the usage exit code. `cargo test` runs the same checks one by one, plus the C ABI callback tests (a callback that arrives after the provider stopped is ignored). `--version` loads the native runtime and prints the SDK version.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations and maps the payout wallet for every provider example:

```sh
cd ../../go/provider/server
go build -o wallet .
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` reissues an installation it already maps and otherwise creates a provider install; it writes the scoped client JWT to the file you name, with owner-only permissions. Your backend delivers that token to the installation as its `client.jwt`. Without the tool, create the provider install with curl and jq:

```sh
API=https://api.bringyour.com
umask 077
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/rust-provider", "provide_intent": true}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json    # a refusal answers 200 with error.message
jq -r .client_id auth-client.json                   # keep it in your installation map
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

For an installation that must earn to another coldkey, for example one run by an independent signer, a **per-provider consent** covers that one client and takes precedence over the network consent for it: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives both flows with curl, the signing format and the precedence rules. The app never maps the wallet; it only shows it.

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

On POSIX the app refuses a state directory or file that group or others can access, and a symlinked file. On first run it creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. Never put the root JWT on an installation.

## Run

```sh
./target/release/provider
```

On Windows run `.\target\release\provider.exe`. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

```text
status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)
```

| Field | Meaning |
| --- | --- |
| status | `starting` until the provider is enabled and connected to the platform, then `providing`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` and `stopped` otherwise. |
| clients served | Distinct clients that opened a contract with this provider since the app started, up to `100000+`. |
| data provided | Bytes relayed for clients, both directions, since the app started. |
| payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping, `(network)` for the network's wallet, `(hotkey)` for the network's hotkey delegation (a later server and SDK change) or `(another provider)`; `checking`, `not set` or `unavailable` otherwise. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit and this installation has not qualified as a provider. The SDK retries by itself after about 15 to 20 minutes, at the time the status shows. A provider install that qualifies as a provider (providing publicly, passing its egress probe and reliable) is exempt from the limit and does not get this status.

SDK log lines also appear on stderr; the SDK's full log is in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443 and UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind these ports, providing continues without the role. The app creates its device with `urnet_new_device_local_with_provide_extender` and both extender settings on ([src/session.rs](src/session.rs)); passing `false` for `default_provide_extender` (`DEFAULT_PROVIDE_EXTENDER`) turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C (or SIGTERM) stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the built `target/release/provider` (or `provider.exe`), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`. A build that loads its runtime from `URNETWORK_SDK_LIBRARY` needs that variable in the service too: an `Environment=` line for systemd, an entry in the plist's `EnvironmentVariables`, or `$env:URNETWORK_SDK_LIBRARY` in the Windows launcher.
