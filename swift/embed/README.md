# Swift embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This console app embeds the URnetwork SDK in your own product on Windows, macOS and Linux. It obtains its installation's scoped client JWT from your backend, starts a local device with it, and shows the status and the data caps your backend set for the installation. The device carries only the app's own traffic: it is not a device VPN and it does not provide. What the app sends through it continues in the [Sockets](../socket/README.md) examples; the [Messages](../messages/README.md) examples run on a provider-capable device of their own ([Next](#next-traffic-through-the-device)). It follows the [embed contract](../../EMBED_CONTRACT.md) and the [Go reference](../../go/embed/README.md). Like the [Swift provider](../provider/README.md), it calls the SDK's C ABI (`urnetwork_sdk.h`) through a SwiftPM C module, with one code path on all three systems; an app for Apple platforms only can use the gomobile XCFramework instead, which exposes the same Go API (`SdkNewDeviceLocalWithDefaults`).

## Files

| File | Purpose |
| --- | --- |
| [Package.swift](Package.swift) | The package, and where the SDK's header and native library are. |
| [module.modulemap](Sources/CURnetworkSdk/module.modulemap), [shim.h](Sources/CURnetworkSdk/shim.h) | The `CURnetworkSdk` C module over `urnetwork_sdk.h`, linking `URnetworkSdk`. |
| [main.swift](Sources/Embed/main.swift) | Commands, exit codes, the SDK's logs and `--licenses`. |
| [Session.swift](Sources/Embed/Session.swift) | Device lifecycle on the C ABI: creation with the client JWT and instance ID, listeners, the best available destination, cap reads, status and handle release. |
| [Inbox.swift](Sources/Embed/Inbox.swift), [Signals.swift](Sources/Embed/Signals.swift) | Hand-off from the SDK's callbacks, the cap reads and the stop signals to the run loop. |
| [Fetch.swift](Sources/EmbedCore/Fetch.swift) | `fetchClientJwt`, the token fetch to replace with your own sign-in, plus the cap read and the configuration, over a replaceable HTTP function (`urlSessionHttp` in the app). |
| [Status.swift](Sources/EmbedCore/Status.swift) | Status rules, data fields, decimal byte amounts, reset and retry times, and the cap object. |
| [SdkJson.swift](Sources/EmbedCore/SdkJson.swift) | The C ABI's JSON values: client limit status and window status. |
| [State.swift](Sources/EmbedCore/State.swift) | The private installation state directory and the JWT claim. |
| [Command.swift](Sources/EmbedCore/Command.swift) | The command line and the exit codes. |
| [SelfTest.swift](Sources/EmbedCore/SelfTest.swift), [EmbedCoreTests.swift](Tests/EmbedCoreTests/EmbedCoreTests.swift) | Credential-free self-test: `embed --self-test`, and `swift test` without the native library. |
| [server/](server/Sources/EmbedServer/main.swift) | The backend tool: `provision`, `cap`, `usage`, `usage-all`, `remove` and `acl`. |

`EmbedCore` is plain Swift: the status rules, the token fetch, the state files and the self-test need neither the SDK nor a network. The `embed` executable adds the SDK calls.

## Build and self-test

Use Swift 5.9 or later: Xcode or the swift.org toolchain on macOS 13.5 or later, the swift.org toolchain on Linux and Windows. The example needs the C ABI of an SDK release after sdk `c638dfa8`, the first with `urnet_device_get_client_limit_status` and `urnet_get_licenses`. Take it from the release's C runtime archive (`URnetworkSdk-C-<version>-<platform>.zip`, with `include/`, `lib/` and, for Windows, `bin/`), or from a local build of the SDK's C ABI (`make -C sdk/cgo build_darwin_arm64`, `build_linux_amd64` or `build_windows_amd64` on a macOS build host, written to `sdk/cgo/build/<os>/<arch>/`).

`URNETWORK_SDK_INCLUDE` is the directory with `urnetwork_sdk.h`, and `URNETWORK_SDK_LIBDIR` the directory with the native library (`libURnetworkSdk.dylib`, `libURnetworkSdk.so`, or on Windows the import library `URnetworkSdk.lib`). Both default to `../../../sdk/cgo/build/<os>/<arch>`, a local build in an sdk checkout beside this repository. From `swift/embed`:

```sh
# macOS and Linux
export URNETWORK_SDK_INCLUDE=/absolute/path/to/urnetwork-sdk/include
export URNETWORK_SDK_LIBDIR=/absolute/path/to/urnetwork-sdk/lib
swift build -c release
swift test
.build/release/embed --self-test
.build/release/embed --licenses > licenses.json
.build/release/embed --version
```

The executable finds the library in `URNETWORK_SDK_LIBDIR` or beside itself. On Linux, `swift build -c release --static-swift-stdlib` makes a binary that needs no Swift runtime on the machine that runs it, only `libURnetworkSdk.so` beside it.

```powershell
# Windows, in PowerShell; the Swift toolchain uses the Visual Studio C++ tools
$env:URNETWORK_SDK_INCLUDE = 'C:\path\to\urnetwork-sdk\include'
$env:URNETWORK_SDK_LIBDIR = 'C:\path\to\urnetwork-sdk\lib'
swift build -c release
swift test
Copy-Item C:\path\to\urnetwork-sdk\bin\URnetworkSdk.dll .build\release\
.build\release\embed.exe --self-test
.build\release\embed.exe --licenses > licenses.json
.build\release\embed.exe --version
```

Windows loads `URnetworkSdk.dll` from beside `embed.exe`. A local SDK build has the DLL and `urnetwork_sdk.def` but no import library; make one in a Developer PowerShell with `lib /def:urnetwork_sdk.def /machine:x64 /out:URnetworkSdk.lib`.

`--self-test` needs no credentials and no network and creates no device. It checks the contract's byte, reset time, client limit and status line vectors and the order of the status rules; the data fields (`checking`, `unavailable`, a later failure keeping the last reading, the Embed-not-enabled refusal clearing it, `no cap` for a null limit); the cap object (null and absent limits, `capped` and `capped_reason`, an unknown reason, refused answers); the C ABI's JSON values; the JWT `client_id` claim; the origin rules; the token fetch against a stand-in server (the request, saving `client.jwt` atomically, a mismatched client refused, and each answer mapped to its exit code); the cap read; the state files (private permissions, atomic replacement, `instance-id` created once, a symlink refused); the configuration errors; the start line; and that the SDK values the status rules use match `urnetwork_sdk.h`. `swift test` runs the same checks without loading the native library. This example was built and its tests run on macOS arm64 against a local build of sdk main; the Linux and Windows builds were not run.

The backend tool in [server](server/Sources/EmbedServer/main.swift) is a server-only Foundation command-line program for macOS and Linux, like the [integration allocator](../integration/server/Sources/Allocator/main.swift) it extends. It needs no SDK:

```sh
cd server
swift build -c release
.build/release/embed-server --self-test
```

## Backend: provision clients and set data caps

Only your backend holds the root credential. Use an **API key** in production: create one with `POST /account/api-key` (called with a network JWT); it authenticates as your network exactly like the network JWT and is just as powerful, so keep it in your secret store, never on an installation. To rotate it, create a new key, deploy it, then remove the old one with `POST /account/api-key/remove`; remove a leaked key at once ([contract](../../EMBED_CONTRACT.md#the-root-credential)).

Your backend provisions **one client per running installation**: the platform keeps one connection per client, so two installations that share a client keep displacing each other. The key is `user:<service-user-id>:<installation-id>`, where the installation ID is the app's `instance-id`. Your apps can get their token from the [Go token server](../../go/embed/README.md), the reference backend they call over HTTP, or your backend can run this language's tool:

```sh
cd server
umask 077
mkdir -p /absolute/path/to/private-service-state
export URNETWORK_ROOT_JWT='api-key-or-network-jwt-from-your-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/embed-clients.json'
export URNETWORK_DEFAULT_ACL_GROUP=isolated
KEY='user:alice:22222222-2222-2222-2222-222222222222'
.build/release/embed-server provision "$KEY" /absolute/path/to/private-service-state/alice.jwt
.build/release/embed-server cap "$KEY" --monthly 10000000000
.build/release/embed-server usage "$KEY"
.build/release/embed-server usage-all
.build/release/embed-server acl "$KEY" default
.build/release/embed-server status
```

`provision` reissues the key's client or provisions a new one, provisions again when the old client was deactivated after 30 days without connecting, writes the client JWT to the file you name with owner-only permissions, never prints it, and prints `{"client_id": "..."}`. Your backend delivers that token to the installation as its `client.jwt`. `cap` posts only the options you give: `--monthly` and `--total` take a byte count or `null` (clear the cap), and `--reset-total` starts a new running total. The monthly cap resets at 00:00 UTC on the first of the month; a cap of `0` pauses the installation. `usage` prints the key's cap object and `usage-all` prints every capped client of your network, one per line. New clients go into the ACL group that `URNETWORK_DEFAULT_ACL_GROUP` names: `isolated` when it is unset, so your users never see each other in your network's peer list, or `default` for an app that uses Messages. `provision` saves a new client's mapping with a `pending_acl` record, applies the group with `POST /network/client-acl-group`, and only then writes the client JWT; after a failure the record stays and the next `provision` applies the group. `acl <key> default` or `acl <key> isolated` moves a client later and prints `{"client_id": "...", "acl_group": "..."}`. `cap`, `usage`, `remove` and `acl` exit 78 with `no client is mapped for that key; run provision first` for a key without a client, and a held map lock exits 1. On a server that predates a route the tool exits 1 with `/network/client-acl-group answered 404: the server predates ACL groups`, or `<path> answered 404: the server predates the data-cap routes` for the cap routes; set `URNETWORK_DEFAULT_ACL_GROUP=default` to provision on a server without ACL groups ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)). `status` prints your network's Embed state from `GET /network/embed` as one line, `embed enabled: yes | client limit: 5000 | active clients: 1234`. Until the URnetwork team enables Embed for your network (an [Embed plan](https://ur.io/services)), `cap`, `usage`, `usage-all` and `acl` exit 78 with `embed not enabled: Embed isn't enabled for this network; see https://ur.io/services`, and `provision` still writes the client JWT but keeps the default ACL group in `pending_acl` and prints `embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services` on stderr; the next `provision` after Embed is enabled applies it ([contract](../../EMBED_CONTRACT.md#embed-enablement)). Without the tool, the backend sends the requests itself:

```sh
API=https://api.bringyour.com
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "embed client", "device_spec": "urnetwork-examples/curl"}'
curl -fsS -X POST "$API/network/client-acl-group" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"client_id": "11111111-1111-1111-1111-111111111111", "acl_group": "isolated"}'
curl -fsS -X POST "$API/network/client-data-cap" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"client_id": "11111111-1111-1111-1111-111111111111", "monthly_byte_limit": 10000000000}'
curl -fsS "$API/network/client-data-cap?client_id=11111111-1111-1111-1111-111111111111" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
```

To **pause** an installation, set a cap to `0` (`embed-server cap "$KEY" --monthly 0`); set it back to resume. To **remove** one, `embed-server remove "$KEY"` removes its client and its mapping; the installation loses access at its next connection, and your service must also refuse that user's sign-in, or the next sign-in provisions a new client.

A network can have **100 top-level clients** by default: the active clients that are not provider installs, so each running installation counts until it is removed or deactivated after 30 days without connecting. Past the limit, provisioning is refused and the tool prints `client limit reached: your network is at its client limit; see https://ur.io/services` with exit code 78. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

- **The SDK runtime** ships beside the app: `libURnetworkSdk.dylib` on macOS (in `URNETWORK_SDK_LIBDIR`, beside the executable, or in a bundle's `Frameworks` with a matching run path), `libURnetworkSdk.so` on Linux, `URnetworkSdk.dll` on Windows. On Linux, build with `--static-swift-stdlib` so the app needs no Swift runtime; on Windows, ship the Swift runtime DLLs with the app or have the user install them.
- **License:** the SDK and connect are under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), a file-level copyleft: your app's code can stay proprietary, and changes to the SDK's own files must be shared under the MPL. Publish the SDK's licenses and data attributions with your app: `embed --licenses` prints them as JSON from `urnet_get_licenses` for this operating system's app kind (`apple`, `windows` or `linux`), with no network.
- **In-app traffic only:** the device routes only what the app sends through it, so the app needs no VPN entitlement. Your privacy policy and store disclosures must still say that the app routes some of its users' traffic through URnetwork; the SDK ships no Apple privacy manifest.
- **`client.jwt`** lives in the private state directory below and is a bearer secret: never print, log or back it up. A production app may also encrypt it with the Keychain, DPAPI or the Secret Service.

## Configure the installation

The app keeps its state in one private directory named by `URNETWORK_EMBED_STATE_DIR`: `client.jwt`, `instance-id` (created on first run) and the SDK's bounded `logs/`. With the token server, the app fetches its client JWT on every start:

```sh
# Linux; on macOS use "$HOME/Library/Application Support/urnetwork-embed"
umask 077
export URNETWORK_EMBED_STATE_DIR="$HOME/.local/state/urnetwork-embed"
mkdir -p "$URNETWORK_EMBED_STATE_DIR"
chmod 700 "$URNETWORK_EMBED_STATE_DIR"
export URNETWORK_TOKEN_SERVER_URL='http://127.0.0.1:8790'
export URNETWORK_DEMO_SESSION='demo-session-token-from-the-token-server'
```

```powershell
# Windows: the profile directory keeps the files private to the user
$env:URNETWORK_EMBED_STATE_DIR = "$env:LOCALAPPDATA\urnetwork-embed"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_EMBED_STATE_DIR | Out-Null
$env:URNETWORK_TOKEN_SERVER_URL = 'http://127.0.0.1:8790'
$env:URNETWORK_DEMO_SESSION = 'demo-session-token-from-the-token-server'
```

The token server URL is an HTTPS origin, or loopback HTTP (`localhost`, `127.0.0.1`, `[::1]`) for local testing; set it together with the demo session, or neither. Without a token server, write `client.jwt` with the backend tool: run the app once to create `instance-id` (it exits with code 78 because there is no `client.jwt` yet), then provision with that installation ID:

```sh
server/.build/release/embed-server provision "user:alice:$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")" "$URNETWORK_EMBED_STATE_DIR/client.jwt"
```

`URNETWORK_API_URL` (default `https://api.bringyour.com`) is where the app reads its caps. Never put the root credential on an installation.

## Run

```sh
.build/release/embed
```

On Windows run `.build\release\embed.exe` with `URnetworkSdk.dll` beside it. The app prints its client and installation IDs, then a status line on stdout whenever a field changes, and at least once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222
status: connecting | data this month: checking | data total: checking
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| status | `connecting` until the device has a provider for the app's traffic, then `connected`; `client limit, retry at HH:MM UTC`; `paused` while a cap your backend set is `0`; `data cap reached, resets YYYY-MM-DD HH:MM UTC` for the monthly cap, or `data cap reached` for the running total. |
| data this month | `checking` until the first cap read, `unavailable` if it failed, `no cap` without a monthly cap, otherwise the bytes used of the cap in decimal units. While Embed isn't enabled for your network, the cap read answers `Embed isn't enabled for this network.` and both data fields read `unavailable` ([contract](../../EMBED_CONTRACT.md#embed-enablement)). |
| data total | The same for the running-total cap. |

`client limit` means the platform disconnected this client because your network reached its plan's limit on concurrently connected clients; the SDK reconnects by itself at the time shown. At a data cap the device gets no new transfer contracts until the month rolls over at 00:00 UTC on the first, or your backend raises, clears or resets the cap; usage can pass a cap by up to the size of the contracts still open. The app reads its caps with its own client JWT at start, every 5 minutes and within 5 seconds of a contract change; a server without the cap routes shows `unavailable`.

SDK log lines also appear on stderr, and the full log is in `logs/`. Ctrl-C (and SIGTERM on macOS and Linux) stops the device and exits with code 0. Exit code 78 is a configuration or credential problem that a restart does not fix: missing or invalid state, a network JWT, a credential the server rejected, or a token server answer of 401 or 409. Exit code 1 is any other failure.

## Next: traffic through the device

The embed example ends where the app's own traffic begins. The [Sockets](../socket/README.md) and [Messages](../messages/README.md) examples use the same identity from the state directory:

```sh
export URNETWORK_CLIENT_JWT="$(cat "$URNETWORK_EMBED_STATE_DIR/client.jwt")"
export URNETWORK_INSTANCE_ID="$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")"
```

Run one program at a time with one identity. Sockets route TCP, UDP and HTTP clients through the device.

Messages exchange the [URMS](../../MESSAGES_PROTOCOL.md) text and ACK protocol with other clients of your network, but not on the embed device, which does not provide. A Messages program starts its own provider-capable device for the installation, and that device provides to your network: your network's other clients can route traffic through the installation, so ask your users first. Messages also need the client in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups): provision with `URNETWORK_DEFAULT_ACL_GROUP=default`, or move the client with the backend tool's `acl <key> default`. An `isolated` client, the examples' default, never appears in your network's peer list; a `default` client appears there while the network has 100 or fewer recently active top-level clients that are not isolated, and your app decides what of it to show.
