# Rust embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This console app embeds a URnetwork Device inside your own product on Windows, macOS and Linux. Your backend provisions a URnetwork client for each installation of your app and delivers its scoped client JWT; the app starts a Device with it, connects to the best available location and shows the status, the data used this month and the running total. The Device carries **only the app's own traffic**: it uses no VPN APIs, and what the app sends through it continues in the [Sockets](../socket/README.md) examples; the [Messages](../messages/README.md) examples run on a provider-capable Device of their own ([Next](#next)). It follows the [embed contract](../../EMBED_CONTRACT.md) over the SDK's C ABI. Its embed core is a library that the [Tauri embed app](../../tauri/embed/README.md) shares.

## Files

| File | Purpose |
| --- | --- |
| [src/main.rs](src/main.rs) | Commands, settings, stop signals and exit codes. |
| [src/token.rs](src/token.rs) | `fetch_client_jwt`: the token fetch, the one function to replace with your own sign-in. |
| [src/config.rs](src/config.rs) | Loading the installation and the client JWT at start. |
| [src/session.rs](src/session.rs) | The embedded Device over the C ABI: listeners, best available location, status, cap reads, stop. |
| [src/status.rs](src/status.rs) | The status fields and their exact text rules. |
| [src/caps.rs](src/caps.rs) | The cap object and the app's own cap read with its client JWT. |
| [src/state.rs](src/state.rs) | The private installation state directory. |
| [src/http.rs](src/http.rs), [src/sdk_json.rs](src/sdk_json.rs), [src/id.rs](src/id.rs) | HTTPS with origin rules, the C ABI's JSON values, canonical ids. |
| [src/self_test.rs](src/self_test.rs), [src/stand_in.rs](src/stand_in.rs), [tests/self_test.rs](tests/self_test.rs) | Credential-free self-test, with a loopback stand-in for the token server and the API; also run by `cargo test`. |
| [src/lib.rs](src/lib.rs), [Cargo.toml](Cargo.toml) | The embed core library (`urnetwork_embed`) and the `embed` binary. |
| [server/src/main.rs](server/src/main.rs), [server/Cargo.toml](server/Cargo.toml), [server/Cargo.lock](server/Cargo.lock) | The backend tool, server only: provision, caps, usage and removal. |

## Build and self-test

Use Rust 1.85 or later (edition 2024). [Cargo.toml](Cargo.toml) requires the SDK crate as `urnetwork-sdk = "0.0.1-dev.0"`; select a release from the first one after sdk `c638dfa8`, which adds the client limit status, or use the local development patch below. The published crate embeds a checksum-verified native runtime for each supported platform when it builds, so the built `embed` needs nothing else at run time. From `rust/embed`, on macOS and Linux:

```sh
cargo build --release
cargo test
./target/release/embed --self-test
./target/release/embed --licenses > licenses.json
./target/release/embed --version
```

On Windows, in PowerShell, the same `cargo` commands build `target\release\embed.exe`; run `.\target\release\embed.exe --self-test`. Linux builds need a glibc target: the SDK has no musl runtime.

Before the crate is published, build against a local SDK checkout. `make -C sdk/rust` stages a crate with the native runtime embedded in `sdk/rust/dist/project`; pass it as a Cargo patch to every `cargo` command:

```sh
cargo build --release --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust/dist/project"'
```

Or patch in the source crate `sdk/rust` and point `URNETWORK_SDK_LIBRARY` at a C ABI library built in `sdk/cgo` from the same checkout (`libURnetworkSdk.dylib`, `libURnetworkSdk.so` or `URnetworkSdk.dll`) whenever the app runs:

```sh
cargo build --release --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust"'
export URNETWORK_SDK_LIBRARY=/absolute/path/to/sdk/cgo/build/darwin/arm64/libURnetworkSdk.dylib
./target/release/embed --licenses > licenses.json
./target/release/embed --version
```

`--self-test` needs no credentials, no network and no native runtime. It checks the contract's byte, reset time, client limit and status line vectors and the order of the status rules, the data fields, the cap object, the C ABI JSON that the status reads, the client JWT's `client_id` claim, the token fetch and the cap read against a loopback stand-in (the request, saving `client.jwt` atomically, a mismatched answer refused, every answer mapped to its exit code), the cap read schedule, the state files (private permissions, atomic replacement, `instance-id` created once, a symlink refused), the configuration errors and the usage exit code. `cargo test` runs the same checks one by one, plus the C ABI callback tests. `--version` loads the native runtime and prints the SDK version.

## Backend

Only your backend holds the root credential, as `URNETWORK_ROOT_JWT`: an **API key** for production, or a network JWT. An API key authenticates as your network exactly like the root JWT, with the same full-network scope (it is not narrower): create one with `POST /account/api-key` (called with a network JWT; the key, which begins `urn_`, is shown only in that answer), list them with `GET /account/api-keys` and revoke one with `POST /account/api-key/remove`. Treat it like a production database password. To rotate, create a new key, deploy it, then remove the old one; remove a leaked key at once ([contract](../../EMBED_CONTRACT.md#the-root-credential)).

Each installation that can run at the same time as another needs its **own client**: the platform keeps one resident connection per client, so two installations sharing one would keep displacing each other. The backend keys each client by `user:<service-user-id>:<installation-id>`, where the installation ID is the app's `instance-id`. Data caps belong to a client, so they apply per installation ([contract](../../EMBED_CONTRACT.md#one-client-per-running-installation)).

**With the token server.** The [Go token server](../../EMBED_CONTRACT.md#the-token-server) in `go/embed/server` is the reference backend: the app posts its `instance-id` with a demo session, and the server provisions or reissues the installation's client, puts new clients in their ACL group (`isolated` unless `URNETWORK_DEFAULT_ACL_GROUP` is `default`), applies default caps to them and answers the client JWT. Run it with your root credential and point the app at it ([Configure the installation](#configure-the-installation)).

**With the Rust backend tool.** [server/src/main.rs](server/src/main.rs) extends the [integration allocator](../integration/README.md#backend-allocator) with the embed commands; it has the same settings, map format, key pattern, lock and response checks, and no SDK dependency. From `rust/embed/server`:

```sh
cargo build --locked
./target/debug/urnetwork-embed-server --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state
chmod 700 /absolute/path/to/private-service-state
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/clients.json'
export URNETWORK_DEFAULT_ACL_GROUP=isolated
export URNETWORK_ROOT_JWT='urn_...an-api-key-from-your-secret-store'
KEY='user:alice:22222222-2222-2222-2222-222222222222'   # the installation's instance-id
./target/debug/urnetwork-embed-server provision "$KEY" /absolute/path/to/private-service-state/alice.jwt
./target/debug/urnetwork-embed-server cap "$KEY" --monthly 10000000000
./target/debug/urnetwork-embed-server usage "$KEY"
./target/debug/urnetwork-embed-server usage-all
./target/debug/urnetwork-embed-server acl "$KEY" default
./target/debug/urnetwork-embed-server status
```

| Command | Does |
| --- | --- |
| `provision <key> <client-jwt-file>` | Reissues the key's client, or provisions a new one (and a new one when a reissue answers `Client does not exist.`, after 30 days without connecting). Writes the client JWT to the file, private, and prints `{"client_id": ...}`. |
| `cap <key> [--monthly <bytes>\|null] [--total <bytes>\|null] [--reset-total]` | Posts only the given fields: an omitted option keeps the cap, `null` clears it, a byte count sets it. `--reset-total` starts a new running total. Prints the cap object. |
| `usage <key>` | Prints the key's cap object. |
| `usage-all` | Pages through every capped client of the network, one cap object per line. |
| `remove <key>` | Removes the key's client and its mapping and prints `{"removed": ...}`. |
| `acl <key> default\|isolated` | Sets the key's client's ACL group with `POST /network/client-acl-group` and prints `{"client_id": ..., "acl_group": ...}`. |

On Windows, in PowerShell, set the variables with `$env:URNETWORK_CLIENT_MAP = '...'` and run `.\target\debug\urnetwork-embed-server.exe`. The tool exits 0 on success, 78 for a configuration or credential problem (missing settings, an invalid key or map, the root credential refused, the client limit) and 1 for any other failure, with one stderr line that never holds a secret. It refuses the token server's map, which has an extra `pending_caps` field, so the two never rewrite each other's map. New clients go into the ACL group that `URNETWORK_DEFAULT_ACL_GROUP` names: `isolated` when it is unset, so your users never see each other in your network's peer list, or `default` for an app that uses Messages. `provision` saves a new client's mapping with a `pending_acl` record, applies the group with `POST /network/client-acl-group`, and only then writes the client JWT; after a failure the record stays and the next `provision` applies the group. `acl <key> default` or `acl <key> isolated` moves a client later and prints `{"client_id": "...", "acl_group": "..."}`. `cap`, `usage`, `remove` and `acl` exit 78 with `no client is mapped for that key; run provision first` for a key without a client, and a held map lock exits 1. On a server that predates a route the tool exits 1 with `/network/client-acl-group answered 404: the server predates ACL groups`, or `<path> answered 404: the server predates the data-cap routes` for the cap routes; set `URNETWORK_DEFAULT_ACL_GROUP=default` to provision on a server without ACL groups ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)). `status` prints your network's Embed state from `GET /network/embed` as one line, `embed enabled: yes | client limit: 5000 | active clients: 1234`. Until the URnetwork team enables Embed for your network (an [Embed plan](https://ur.io/services)), `cap`, `usage`, `usage-all` and `acl` exit 78 with `embed not enabled: Embed isn't enabled for this network; see https://ur.io/services`, and `provision` still writes the client JWT but keeps the default ACL group in `pending_acl` and prints `embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services` on stderr; the next `provision` after Embed is enabled applies it ([contract](../../EMBED_CONTRACT.md#embed-enablement)).

The same requests with curl:

```sh
API=https://api.bringyour.com
curl -fsS -X POST "$API/network/auth-client" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"description": "embed client", "device_spec": "urnetwork-examples/curl"}'
curl -fsS -X POST "$API/network/client-acl-group" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "<client id>", "acl_group": "isolated"}'
curl -fsS -X POST "$API/network/client-data-cap" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "<client id>", "monthly_byte_limit": 10000000000}'
curl -fsS "$API/network/client-data-cap?client_id=<client id>" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
```

A reissue posts the stored `client_id` with the same description and device spec; the [contract](../../EMBED_CONTRACT.md#backend-provision-clients) covers the answers.

**Pause and remove.** A cap of `0` pauses an installation (`cap "$KEY" --monthly 0`; a monthly cap of 0 stays paused when the month rolls over); set the cap back to resume. `remove "$KEY"` removes the client: the platform refuses its client JWT at the next connection. To keep a user out, your service also refuses their sign-in, or the next sign-in provisions a new client.

**The client limit.** A network can have 100 top-level clients by default: active clients without a `source_client_id` that are not provider installs; a client stops counting when it is removed or after 30 days without connecting. Provisioning past the limit is refused with `Client limit exceeded.` (the tool prints `client limit reached: ...` and exits 78). An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

The `urnetwork-sdk` crate downloads a version-pinned, checksum-verified native runtime when it builds and embeds it in the executable, on Windows, macOS and Linux (glibc), so the app ships as one binary with no separate library. Pin the crate with `cargo add urnetwork-sdk@<version>`; `SDK_RUST_NATIVE_CACHE` names a prepopulated offline cache for builds without network access.

The SDK is licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/): your app's own code can stay proprietary, and changes to the SDK's own source files are shared under the MPL. Publish the SDK's licenses and data attributions with your app: `./target/release/embed --licenses` prints them as JSON for this OS (`GetLicenses`, C ABI `urnet_get_licenses`, with no network).

The app keeps `client.jwt` in its private state directory, protected by the operating system's user isolation; a production app may also encrypt it with the platform's secret store (the Keychain, DPAPI or the Secret Service). The client JWT is a bearer secret: never print, log or back it up. The Device uses no VPN APIs, so no VPN entitlement applies, but your app's privacy disclosures still must say that it routes some of its users' traffic through URnetwork; the SDK ships no privacy manifest of its own.

## Configure the installation

All installation state is in one private directory named by `URNETWORK_EMBED_STATE_DIR`, an absolute path, private to the user (0700 on macOS and Linux). Use a directory inside your app's data, for example `~/Library/Application Support/<your app>/embed` on macOS, `${XDG_DATA_HOME:-$HOME/.local/share}/<your app>/embed` on Linux and `%LOCALAPPDATA%\<your app>\embed` on Windows. The app creates `instance-id` there on first run and rewrites `client.jwt` whenever the SDK refreshes the token; the SDK keeps its bounded log files in `logs/`.

With a token server, set both `URNETWORK_TOKEN_SERVER_URL` (an HTTPS origin, or explicit loopback HTTP for local testing) and `URNETWORK_DEMO_SESSION` (the demo session from the token server's session file, standing in for your sign-in). The app fetches its client JWT on every start, which reissues the client:

```sh
umask 077
export URNETWORK_EMBED_STATE_DIR="$HOME/Library/Application Support/com.example.app/embed"
mkdir -p "$URNETWORK_EMBED_STATE_DIR" && chmod 700 "$URNETWORK_EMBED_STATE_DIR"
export URNETWORK_TOKEN_SERVER_URL='http://127.0.0.1:8790'
export URNETWORK_DEMO_SESSION='the-demo-session-token-from-your-session-file'
```

```powershell
$env:URNETWORK_EMBED_STATE_DIR = "$env:LOCALAPPDATA\com.example.app\embed"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_EMBED_STATE_DIR | Out-Null
$env:URNETWORK_TOKEN_SERVER_URL = 'http://127.0.0.1:8790'
$env:URNETWORK_DEMO_SESSION = 'the-demo-session-token-from-your-session-file'
```

Without a token server, the app uses the `client.jwt` already in the directory, as the backend tool's `provision` wrote it: run the app once to create `instance-id` (it then stops with exit 78, as there is no `client.jwt` yet), provision `user:<service-user-id>:<that instance-id>` into `"$URNETWORK_EMBED_STATE_DIR/client.jwt"`, and start it again. `URNETWORK_API_URL` (optional, an HTTPS origin) names the API for the cap reads.

## Run

```sh
./target/release/embed
```

On Windows, `.\target\release\embed.exe`. The app prints the client and installation IDs, then a status line whenever a field changes and otherwise once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| Status | `connecting` until the window has a provider added, then `connected`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` at a cap of 0; `data cap reached, resets YYYY-MM-DD HH:MM UTC` at the monthly cap, or `data cap reached` at the running total. |
| Data this month | `<used> of <limit>` for the monthly cap, in decimal units; `no cap` without one; `checking` before the first read; `unavailable` if that read failed. While Embed isn't enabled for your network, the cap read answers `Embed isn't enabled for this network.` and both data fields read `unavailable` ([contract](../../EMBED_CONTRACT.md#embed-enablement)). |
| Data total | The same for the running-total cap. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit; the SDK retries by itself after about 15 to 20 minutes, at the time the status shows. At a data cap the client gets no new transfer contracts: the monthly cap resets at 00:00 UTC on the first of the month, and the running total until your backend raises, clears or resets it. Usage lags live traffic and can pass a cap by up to the size of the contracts still open. The app reads its caps with its client JWT at start, every 5 minutes and within 5 seconds of a contract change.

Ctrl-C or SIGTERM stops the Device and exits 0. The app exits 78 for a configuration or credential problem that a restart does not fix (missing or invalid state, a network JWT, a credential the server rejected, or a token server answer of 401 or 409) and 1 for any other failure, including any other token server answer. The commands are `run` (the default), `--self-test`, `--licenses` and `--version`; any other argument is a usage error (78).

## Next

The Device carries your app's own traffic. Continue with the same identity, one program at a time:

```sh
export URNETWORK_CLIENT_JWT="$(cat "$URNETWORK_EMBED_STATE_DIR/client.jwt")"
export URNETWORK_INSTANCE_ID="$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")"
```

```powershell
$env:URNETWORK_CLIENT_JWT = (Get-Content "$env:URNETWORK_EMBED_STATE_DIR\client.jwt").Trim()
$env:URNETWORK_INSTANCE_ID = (Get-Content "$env:URNETWORK_EMBED_STATE_DIR\instance-id").Trim()
```

- [Sockets](../socket/README.md): TCP, UDP and HTTP clients through the Device; libraries that need an operating system socket use the loopback proxy ([networking matrix](../../NETWORK_EXAMPLES.md)).
- [Messages](../messages/README.md): Messages exchange the [URMS](../../MESSAGES_PROTOCOL.md) text and ACK protocol with other clients of your network, but not on the embed Device, which does not provide. A Messages program starts its own provider-capable Device for the installation, and that Device provides to your network: your network's other clients can route traffic through the installation, so ask your users first. Messages also need the client in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups): provision with `URNETWORK_DEFAULT_ACL_GROUP=default`, or move the client with the backend tool's `acl <key> default`. An `isolated` client, the examples' default, never appears in your network's peer list; a `default` client appears there while the network has 100 or fewer recently active top-level clients that are not isolated, and your app decides what of it to show.
