# Tauri embed

[Rust installation](../../rust/README.md) · [Rust embed](../../rust/embed/README.md) · [Embed](README.md)

This desktop app embeds a URnetwork Device inside a Tauri 2 app on Windows, macOS and Linux: the Device runs in the app's Rust core, in process, through the SDK's C ABI with the `urnetwork-sdk` crate, with no sidecar. The app obtains its installation's scoped client JWT from your backend's token server, starts the Device, connects to the best available location and shows the status, the data used this month and the running total, with the client and installation IDs. The Device carries **only the app's own traffic**: it uses no VPN APIs, and what the app sends through it continues in the [Sockets](../../rust/socket/README.md) examples; the [Messages](../../rust/messages/README.md) examples run on a provider-capable Device of their own ([Next](#next)). The embed core (state, token fetch, status rules and the session) is the library of the [Rust embed example](../../rust/embed/README.md), so the console app and this app share one tested core. Both follow the [embed contract](../../EMBED_CONTRACT.md#tauri).

## Files

| File | Purpose |
| --- | --- |
| [src-tauri/src/main.rs](src-tauri/src/main.rs) | App setup: the commands, the single instance, closing the Device on exit. |
| [src-tauri/src/embed.rs](src-tauri/src/embed.rs) | The device thread, which runs a session of the shared core, and publishes the view every second. |
| [src-tauri/src/embed_state.rs](src-tauri/src/embed_state.rs) | The view and its state changes, the token server settings and the start's preparation, without Tauri. |
| [src-tauri/build.rs](src-tauri/build.rs) | Placeholder icons on the first build, then tauri-build. |
| [src-tauri/tauri.conf.json](src-tauri/tauri.conf.json), [src-tauri/capabilities/default.json](src-tauri/capabilities/default.json) | The app's configuration, its window, content security policy and permissions. |
| [src-tauri/Cargo.toml](src-tauri/Cargo.toml) | The app crate, with the shared core as a path dependency on `rust/embed`. |
| [ui/index.html](ui/index.html), [ui/main.js](ui/main.js), [ui/style.css](ui/style.css) | The window: plain HTML and JavaScript, no bundler. |
| [rust/embed/src](../../rust/embed/src/lib.rs) | The shared embed core and its self-test. |
| [rust/embed/server](../../rust/embed/server/src/main.rs) | The Rust backend tool, server only. |

## Build and test

Install Rust 1.85 or later and the [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/) for your OS: the Xcode command line tools on macOS; the Microsoft C++ build tools and WebView2 (part of Windows 11) on Windows; on Linux the WebKitGTK 4.1 development package, for example `libwebkit2gtk-4.1-dev librsvg2-dev` on Debian and Ubuntu. The SDK crate is the same as the Rust embed example's, `urnetwork-sdk = "0.0.1-dev.0"` in the core's [Cargo.toml](../../rust/embed/Cargo.toml): a release from the first one after sdk `c638dfa8` embeds the native runtime when it builds. From `tauri/embed/src-tauri`, on every OS (PowerShell on Windows):

```sh
cargo test
cargo build
cargo run
```

`cargo test` runs the shared core's self-test (the contract's vectors, the state files, the token fetch against a loopback stand-in) and the app's own tests without the native runtime: the window's fields and commands, the view texts, sign-out and stop, the token server settings, and the start against a stand-in token server, where 401 and 409 answers show `signed out` and 5xx answers `stopped`. The first build writes placeholder icons into `src-tauri/icons/` (not committed); replace them with your own, for example with `cargo tauri icon`, and the build keeps them.

Before the SDK crate is published, add the local development patch to every `cargo` command, as for the [Rust embed example](../../rust/embed/README.md#build-and-self-test): the staged crate `sdk/rust/dist/project` (from `make -C sdk/rust`) embeds the runtime, while the source crate `sdk/rust` loads the C ABI library named by `URNETWORK_SDK_LIBRARY` when the app runs:

```sh
cargo run --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust/dist/project"'
```

To bundle the app for an OS, build on that OS with the Tauri CLI (`cargo install tauri-cli --version '^2' --locked`, then `cargo tauri build` in `tauri/embed`), with an SDK crate that embeds the runtime: a bundle cannot rely on `URNETWORK_SDK_LIBRARY`. Before publication, put the patch in a `src-tauri/.cargo/config.toml` (`[patch.crates-io]`, `urnetwork-sdk = { path = "/absolute/path/to/sdk/rust/dist/project" }`) so the CLI's build uses it, and keep that file out of your repository. Sign the bundle as each OS requires.

## Backend

Only your backend holds the root credential, an **API key** for production: it authenticates as your network with the same full-network scope as the root JWT, and you rotate it by creating a new key, deploying it and removing the old one ([contract](../../EMBED_CONTRACT.md#the-root-credential)). Each running installation gets its own client, keyed `user:<service-user-id>:<installation-id>`; data caps apply per installation.

This app gets its client JWT from the [Go token server](../../EMBED_CONTRACT.md#the-token-server) in `go/embed/server`: it posts its `instance-id` to `POST /urnetwork/client-token` with a demo session as the bearer token, and the server provisions or reissues the installation's client, applies your default caps to new clients and answers the client JWT. For local testing, run the token server on loopback (`127.0.0.1:8790` by default) with a private demo session file, and enter that origin and a session token in the window.

**ACL groups.** The token server puts each new client in `URNETWORK_DEFAULT_ACL_GROUP`, `isolated` unless you set `default`, before it answers the client JWT, so your users never see each other in your network's peer list; an app that uses Messages keeps `default`, and the token server's `acl <key> default|isolated` command moves a client later. On a server without ACL groups the token server logs that once and still answers the token ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)).

Set and read caps, pause, and remove clients with the token server's commands or the [Rust backend tool](../../rust/embed/README.md#backend), whose `cap`, `usage`, `usage-all`, `remove` and `acl` commands post the same requests as these:

```sh
API=https://api.bringyour.com
curl -fsS -X POST "$API/network/client-acl-group" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "<client id>", "acl_group": "isolated"}'
curl -fsS -X POST "$API/network/client-data-cap" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "<client id>", "monthly_byte_limit": 10000000000}'
curl -fsS "$API/network/client-data-cap?client_id=<client id>" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
curl -fsS -X POST "$API/network/remove-client" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "<client id>"}'
```

A cap of `0` pauses the installation until the cap changes. A network can have 100 top-level clients by default (active clients without a `source_client_id` that are not provider installs); past that, provisioning is refused with `Client limit exceeded.`, which the token server answers as `409 client_limit` and this app shows as `signed out` with the message. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

The `urnetwork-sdk` crate embeds a checksum-verified native runtime in the app's binary when it builds, so the Tauri bundle carries the Device with no separate library or sidecar on Windows, macOS and Linux (glibc). The SDK is licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/): your app's own code can stay proprietary, and changes to the SDK's own source files are shared under the MPL. The window's **Licenses and data attributions** section shows the SDK's licenses for this OS (`GetLicenses`, C ABI `urnet_get_licenses`); publish them with your app.

The app keeps `client.jwt` in its private state directory, protected by the operating system's user isolation; a production app may also encrypt it with the platform's secret store (the Keychain, DPAPI or the Secret Service). The window never shows the client JWT, and the demo session never goes back to the window after it is saved. The Device uses no VPN APIs, so no VPN entitlement applies, but your app's privacy disclosures still must say that it routes some of its users' traffic through URnetwork; the SDK ships no privacy manifest of its own.

## Configure the installation

The app keeps the installation state in a private `embed` directory in its local app data directory, named after the `identifier` in [tauri.conf.json](src-tauri/tauri.conf.json) (use your own):

| OS | State directory |
| --- | --- |
| macOS | `~/Library/Application Support/com.example.urnetwork-embed/embed` |
| Windows | `%LOCALAPPDATA%\com.example.urnetwork-embed\embed` |
| Linux | `$XDG_DATA_HOME/com.example.urnetwork-embed/embed`, by default under `~/.local/share` |

The app creates the directory (owner-only on macOS and Linux) when you start, and the window shows its path. The window's **Token server URL** (an HTTPS origin, or loopback HTTP for local testing) and **Demo session** fields are saved in `token-server.json` there when you start; leave the session empty to keep the saved one. With both fields empty, the app uses a `client.jwt` that the backend tool's `provision` wrote into the directory. In your app, your own sign-in replaces the demo session: change `fetch_client_jwt` in the [core](../../rust/embed/src/token.rs) and never ship a token in the app bundle. On first start the app creates `instance-id`, rewrites `client.jwt` whenever the SDK refreshes the token, and the SDK keeps its bounded log files in `logs/`.

## Run

Start the app (`cargo run`, or the bundle), enter the token server settings and choose **Start**. The window shows the fields as labeled values and updates them every second:

| Field | Meaning |
| --- | --- |
| Status | `stopped` before start and after stop; `signed out` after the server rejected the credential or the token server answered 401 or 409, until started again; `connecting`, then `connected` once the window has a provider added; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` at a cap of 0; `data cap reached, resets YYYY-MM-DD HH:MM UTC` at the monthly cap, or `data cap reached` at the running total. |
| Data this month | `<used> of <limit>` for the monthly cap, in decimal units; `no cap` without one; `checking` before the first read; `unavailable` if that read failed. |
| Data total | The same for the running-total cap. |
| Client ID, Installation ID | This installation's client and its `instance-id`. |

The console example prints the same values as `status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap`.

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit; the SDK retries by itself after about 15 to 20 minutes, at the time the status shows. At a data cap the client gets no new transfer contracts until the month rolls over (00:00 UTC on the first) or your backend raises, clears or resets the cap; usage lags live traffic and can pass a cap by up to the size of the contracts still open. An unreachable token server or a 5xx answer stops with its message; start again to retry.

**Stop** closes the Device. The Device lives with the app: closing the window stops it and exits. The app runs as a single instance ([single-instance plugin](https://v2.tauri.app/plugin/single-instance/)), so opening it again shows the running window instead of starting a second Device with the same installation ID, which would displace the first.

## Next

The Device carries your app's own traffic: route it with the [Sockets](../../rust/socket/README.md) examples, using the same identity, one program at a time. The state directory holds it: `URNETWORK_CLIENT_JWT` is the content of `client.jwt` and `URNETWORK_INSTANCE_ID` the content of `instance-id`. 

The [Messages](../../rust/messages/README.md) examples exchange the [URMS](../../MESSAGES_PROTOCOL.md) text and ACK protocol with other clients of your network, but not on the embed Device, which does not provide. A Messages program starts its own provider-capable Device for the installation, and that Device provides to your network: your network's other clients can route traffic through the installation, so ask your users first. Messages also need the client in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups): provision with `URNETWORK_DEFAULT_ACL_GROUP=default`, or move the client with the backend tool's `acl <key> default`. An `isolated` client, the examples' default, never appears in your network's peer list; a `default` client appears there while the network has 100 or fewer recently active top-level clients that are not isolated, and your app decides what of it to show.
