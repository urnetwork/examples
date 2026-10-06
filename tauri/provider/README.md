# Tauri provider

[Rust installation](../../rust/README.md) · [Rust provider](../../rust/provider/README.md) · [Provider](README.md)

This desktop app runs a URnetwork provider on Windows, macOS and Linux inside a Tauri 2 app: the provider runs in the app's Rust core, in process, through the SDK's C ABI with the `urnetwork-sdk` crate, with no sidecar. It provides publicly as a provider client of your network and shows the providing status, clients served, data provided and the payout wallet, read only. The window shows the consent disclaimer next to the start control; closing it keeps providing in the system tray, and start at login uses the official autostart plugin. The provider core (status rules, state files and the provider session) is the library of the [Rust provider](../../rust/provider/README.md), so the console app and this app share one tested core. Both follow the [provider contract](../../PROVIDER_CONTRACT.md#tauri).

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [src-tauri/src/main.rs](src-tauri/src/main.rs) | App setup: the commands, the tray icon, hiding the window on close, start at login and the single instance. |
| [src-tauri/src/provider.rs](src-tauri/src/provider.rs) | The provider thread, which runs a session of the shared core, and the view that the window shows. |
| [src-tauri/build.rs](src-tauri/build.rs) | Placeholder icons on the first build, then tauri-build. |
| [src-tauri/tauri.conf.json](src-tauri/tauri.conf.json), [src-tauri/capabilities/default.json](src-tauri/capabilities/default.json) | The app's configuration, its window, content security policy and permissions. |
| [src-tauri/Cargo.toml](src-tauri/Cargo.toml) | The app crate, with the shared core as a path dependency on `rust/provider`. |
| [ui/index.html](ui/index.html), [ui/main.js](ui/main.js), [ui/style.css](ui/style.css) | The window: plain HTML and JavaScript, no bundler. |
| [rust/provider/src](../../rust/provider/src/lib.rs) | The shared provider core and its self-test. |

## Build and test

Install Rust 1.85 or later and the [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/) for your OS: the Xcode command line tools on macOS; the Microsoft C++ build tools and WebView2 (part of Windows 11) on Windows; on Linux the WebKitGTK 4.1 development package and an AppIndicator library for the tray, for example `libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev` on Debian and Ubuntu. The SDK crate is the same as the Rust provider's, `urnetwork-sdk = "0.0.1-dev.0"` in the core's [Cargo.toml](../../rust/provider/Cargo.toml): a release with the provider intent, client limit and extender APIs embeds the native runtime when it builds. From `tauri/provider/src-tauri`, on every OS (PowerShell on Windows):

```sh
cargo test
cargo build
cargo run
```

`cargo test` runs the shared core's self-test (the contract's vectors and the state files) and the app's own tests (the window's disclaimer and fields, the views, the start-at-login launch) without the native runtime. The first build writes placeholder icons into `src-tauri/icons/` (not committed); replace them with your own, for example with `cargo tauri icon`, and the build keeps them.

Before the SDK crate is published, add the local development patch to every `cargo` command, as for the [Rust provider](../../rust/provider/README.md#build-and-self-test): the staged crate `sdk/rust/dist/project` (from `make -C sdk/rust`) embeds the runtime, while the source crate `sdk/rust` loads the C ABI library named by `URNETWORK_SDK_LIBRARY` when the app runs:

```sh
cargo run --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust/dist/project"'
```

To bundle the app for an OS, build on that OS with the Tauri CLI (`cargo install tauri-cli --version '^2' --locked`, then `cargo tauri build` in `tauri/provider`), with an SDK crate that embeds the runtime: a bundle cannot rely on `URNETWORK_SDK_LIBRARY`. Before publication, put the patch in a `src-tauri/.cargo/config.toml` (`[patch.crates-io]`, `urnetwork-sdk = { path = "/absolute/path/to/sdk/rust/dist/project" }`) so the CLI's build uses it, and keep that file out of your repository. Sign the bundle as each OS requires.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**, a top-level client created with `"provide_intent": true`, and maps your network's payout wallet; the [Rust provider](../../rust/provider/README.md#backend-provision-and-map-the-payout-wallet) shows both with the [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) and with curl. In short:

```sh
cd ../../go/provider/server
go build -o wallet .
umask 077
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
```

With curl, post `{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/tauri-provider", "provide_intent": true}` to `POST /network/auth-client` with the root JWT and keep `client_id` and `by_client_jwt` ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The **network consent** covers every provider client of your network, including later ones, with one signature of the coldkey owner; a **per-provider consent** (`./wallet challenge <client-id>`, `./wallet accept <client-id> <signature>`) covers one client, for example one run by an independent signer, and takes precedence for it ([contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping)). The app never maps the wallet and never holds a key; it only shows the wallet.

## Configure the installation

The app keeps the installation state in a private `provider` directory in its local app data directory, named after the `identifier` in [tauri.conf.json](src-tauri/tauri.conf.json) (use your own):

| OS | State directory |
| --- | --- |
| macOS | `~/Library/Application Support/com.example.urnetwork-provider/provider` |
| Windows | `%LOCALAPPDATA%\com.example.urnetwork-provider\provider` |
| Linux | `$XDG_DATA_HOME/com.example.urnetwork-provider/provider`, by default under `~/.local/share` |

The local, not roaming, app data keeps the identity on this machine. The app creates the directory (owner-only on macOS and Linux) when providing starts, and the window shows its path. Write the scoped client JWT from your backend into `client.jwt` there, private to the user:

```sh
# macOS; on Linux use "${XDG_DATA_HOME:-$HOME/.local/share}/com.example.urnetwork-provider/provider"
STATE_DIR="$HOME/Library/Application Support/com.example.urnetwork-provider/provider"
umask 077
mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
printf '%s\n' 'scoped-client-jwt-from-your-backend' > "$STATE_DIR/client.jwt"
```

```powershell
# Windows
$StateDir = "$env:LOCALAPPDATA\com.example.urnetwork-provider\provider"
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
Set-Content -Path "$StateDir\client.jwt" -Value 'scoped-client-jwt-from-your-backend'
```

In your app, your sign-in flow writes `client.jwt` the same way once your backend has provisioned the installation; never ship a token in the app bundle. On first start the app creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. The app rewrites `client.jwt` whenever the SDK refreshes the token.

## Run

Start the app (`cargo run`, or the bundle) and choose **Start providing**, next to the consent disclaimer. The window shows the four fields as labeled values and updates them every second:

| Field | Meaning |
| --- | --- |
| Status | `starting` until the provider is enabled and connected to the platform, then `providing`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` and `stopped` otherwise. |
| Clients served | Distinct clients that opened a contract with this provider since the app started, up to `100000+`. |
| Data provided | Bytes relayed for clients, both directions, since providing started. |
| Payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping, `(network)` for the network's wallet, `(hotkey)` for the network's hotkey delegation (a later server and SDK change) or `(another provider)`; `checking`, `not set` or `unavailable` otherwise. |

The console examples print the same values as `status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)`.

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit and this installation has not qualified as a provider. The SDK retries by itself after about 15 to 20 minutes, at the time the status shows. A provider install that qualifies as a provider (providing publicly, passing its egress probe and reliable) is exempt from the limit and does not get this status.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443 and UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it logs those listeners. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind these ports, providing continues without the role. The shared core creates the device with `urnet_new_device_local_with_provide_extender` and both extender settings on; passing `false` for `default_provide_extender` (`DEFAULT_PROVIDE_EXTENDER` in [rust/provider/src/session.rs](../../rust/provider/src/session.rs)) turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

**Stop providing** stops the device. Closing the window keeps providing in the tray; **Show window** in the tray menu brings it back and **Quit** stops providing and exits. The app does not use the console exit codes: a missing or invalid `client.jwt`, a credential the server rejected (issue a new scoped JWT from your backend) or any other failure stops providing and shows its message in the window.

## Run in the background

Tauri apps run in the background in the tray rather than as a service. **Start at login and keep providing in the background** registers the app with the [autostart plugin](https://v2.tauri.app/plugin/autostart/): a launch agent in `~/Library/LaunchAgents` on macOS, a `Run` entry under `HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion` on Windows and a `.desktop` file in `~/.config/autostart` on Linux. The entry starts the app with `--background`: it opens no window, starts providing at once and stays in the tray. Register it from the installed app, not from a `cargo run` build. The app runs as a single instance ([single-instance plugin](https://v2.tauri.app/plugin/single-instance/)), so opening it again shows the running window instead of starting a second provider with the same identity.
