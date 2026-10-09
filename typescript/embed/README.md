# TypeScript embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This Node 24 console app, written in TypeScript and run with Node's type stripping, embeds URnetwork in your product on Windows, macOS and Linux. It obtains this installation's scoped client JWT from your backend, starts the embedded device and shows its status and its own data caps, following the [embed contract](../../EMBED_CONTRACT.md). The device carries only the traffic the app sends through it: it uses no VPN API. The JavaScript SDK cannot run a local Device, so the app starts the [native companion](../../javascript/integration/companion/README.md#embed-mode) in embed mode as its child process: the companion owns the device and the installation state and serves the status on a loopback route.

## Files

| File | Purpose |
| --- | --- |
| [main.ts](main.ts), [command.ts](command.ts) | Commands and exit codes. |
| [token.ts](token.ts) | `fetchClientJwt`: the one place the app obtains its client JWT. Replace it with your own sign-in. |
| [session.ts](session.ts) | The run: settings, the installation's identity, the companion child process, the status loop and the cap reads. |
| [companion.ts](companion.ts) | Starting, reading and stopping the native companion in embed mode. |
| [caps.ts](caps.ts) | The app's own data caps, read with its client JWT. |
| [status.ts](status.ts) | The status rules, the data fields and the status line. |
| [state.ts](state.ts) | The private installation state directory. |
| [selftest.ts](selftest.ts), [embed.test.ts](embed.test.ts) | The credential-free self-test; `npm test` type-checks, runs it and also tests the exit codes, the companion child process and whole runs against stand-ins. |
| [package.json](package.json), [tsconfig.json](tsconfig.json) | Scripts and the type check; the only packages are TypeScript and the Node types. |
| [server/embed-server.ts](server/embed-server.ts) | The [backend tool](../../EMBED_CONTRACT.md#backend-tools): provision, caps, usage, removal. Server only. |
| [../../javascript/integration/companion/](../../javascript/integration/companion/README.md#embed-mode) | The native companion (Go) that owns the embedded device. |

## Build and self-test

Use Node 24 or later and Go 1.26.7 or later. The app runs its `.ts` files directly with Node's type stripping; `npm ci` installs only the type-check packages (TypeScript and the Node types), in the app and in `server/`. The app needs the native companion built. The companion's `go.mod` requires the SDK as `github.com/urnetwork/sdk/v2026 v2026`, a version query: `go mod tidy` resolves it to the latest 2026 SDK release, which needs network access. The embed app needs an SDK release from the first release after sdk `c638dfa8`. From `examples` on macOS or Linux:

```sh
cd javascript/integration/companion
go mod tidy
go test .
go build -o bin/ur-companion .
cd ../../../typescript/embed
npm ci
npm test
node main.ts --self-test
node main.ts --licenses > licenses.json
node main.ts --version
cd server
npm ci
npm run self-test
```

On Windows, in PowerShell from `examples`:

```powershell
cd javascript\integration\companion
go mod tidy
go test .
go build -o bin\ur-companion.exe .
cd ..\..\..\typescript\embed
npm ci
npm test
node main.ts --self-test
node main.ts --licenses > licenses.json
node main.ts --version
cd server
npm ci
npm run self-test
```

The app runs the companion from `../../javascript/integration/companion/bin/ur-companion` (`ur-companion.exe` on Windows); set `URNETWORK_COMPANION_PATH` to its absolute path to use another one. The companion is pure Go, so any host builds every desktop target, for example `GOOS=windows GOARCH=amd64 go build -o bin/ur-companion.exe .`.

`node main.ts --self-test` needs no credentials, no network, no companion and no installed packages. It checks the contract's byte, reset time, client limit and status line vectors, the status rules and their order, the data fields, the cap object parsing, the JWT `client_id` claim, the token fetch against a stand-in token server on loopback, the state directory, the configuration errors and the start line. `--licenses` prints the SDK's licenses and data attributions as a JSON array, from the companion's `--licenses`, to publish with your app. `npm test` type-checks, runs the same checks, plus the exit codes, the companion child process and two whole runs against a stand-in companion script, token server and cap API (macOS and Linux). The backend tool's self-test (`node server/embed-server.ts --self-test`, after `npm run build` type-checks it) checks the [contract's backend rules](../../EMBED_CONTRACT.md#backend-tools) against a scripted API and a loopback stand-in.

## Backend

Only your backend holds the root credential, `URNETWORK_ROOT_JWT`. For production make it an [API key](../../EMBED_CONTRACT.md#the-root-credential) (`POST /account/api-key`): it authenticates as your network exactly like a network JWT, with the same full scope, and you rotate it by creating a new key, deploying it and removing the old one. Treat it like a production database password. Your backend provisions **one client per running installation**, keyed `user:<service-user-id>:<installation-id>`, because the platform keeps one resident connection per client ([contract](../../EMBED_CONTRACT.md#one-client-per-running-installation)).

**With the token server.** The [Go token server](../../go/embed/README.md) is the reference backend: the app posts its `instance-id` with a demo session token, and the server provisions or reissues the installation's client, puts new clients in their ACL group (`isolated` unless `URNETWORK_DEFAULT_ACL_GROUP` is `default`), applies default caps to them and answers the client JWT ([HTTP contract](../../EMBED_CONTRACT.md#the-token-server)). The app fetches on every start.

**With the backend tool.** `server/embed-server.ts` runs the same steps from your own backend, with the [integration allocator's](../integration/README.md#backend-allocator) settings, map and lock (`URNETWORK_ROOT_JWT`, `URNETWORK_CLIENT_MAP`, optional `URNETWORK_API_URL`). Keep its map separate from the token server's. On macOS or Linux:

```sh
export URNETWORK_ROOT_JWT='urn_...'            # from your secret store
export URNETWORK_CLIENT_MAP=/srv/myservice/urnetwork/clients.json
export URNETWORK_DEFAULT_ACL_GROUP=isolated           # the default; default for an app that uses Messages
KEY=user:alice:22222222-2222-2222-2222-222222222222
node server/embed-server.ts provision "$KEY" /path/to/state/client.jwt   # prints {"client_id": "..."}
node server/embed-server.ts cap "$KEY" --monthly 10000000000              # 10 GB a month
node server/embed-server.ts cap "$KEY" --total null --reset-total         # clear the total cap
node server/embed-server.ts usage "$KEY"
node server/embed-server.ts usage-all                                     # one cap object per line
node server/embed-server.ts acl "$KEY" default                       # move it to the default ACL group
node server/embed-server.ts status                                   # the network's Embed state
node server/embed-server.ts cap "$KEY" --monthly 0                        # pause the installation
node server/embed-server.ts remove "$KEY"
```

The curl equivalents are in the contract: [provision](../../EMBED_CONTRACT.md#backend-provision-clients), [caps](../../EMBED_CONTRACT.md#backend-per-user-data-caps). Caps merge: an omitted option keeps its cap, `null` clears it. The monthly cap resets at 00:00 UTC on the first of the month; the running total only when you reset it. Usage is accounted when contracts settle, so a client can pass a cap by up to its open contracts. A cap of `0` pauses the installation until that cap changes; `remove` deactivates its client, which loses access at its next reconnect. New clients go into the ACL group that `URNETWORK_DEFAULT_ACL_GROUP` names: `isolated` when it is unset, so your users never see each other in your network's peer list, or `default` for an app that uses Messages. `provision` saves a new client's mapping with a `pending_acl` record, applies the group with `POST /network/client-acl-group`, and only then writes the client JWT; after a failure the record stays and the next `provision` applies the group. `acl <key> default` or `acl <key> isolated` moves a client later and prints `{"client_id": "...", "acl_group": "..."}`. `cap`, `usage`, `remove` and `acl` exit 78 with `no client is mapped for that key; run provision first` for a key without a client, and a held map lock exits 1. On a server that predates a route the tool exits 1 with `/network/client-acl-group answered 404: the server predates ACL groups`, or `<path> answered 404: the server predates the data-cap routes` for the cap routes; set `URNETWORK_DEFAULT_ACL_GROUP=default` to provision on a server without ACL groups ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)). `status` prints your network's Embed state from `GET /network/embed` as one line, `embed enabled: yes | client limit: 5000 | active clients: 1234`. Until the URnetwork team enables Embed for your network (an [Embed plan](https://ur.io/services)), `cap`, `usage`, `usage-all` and `acl` exit 78 with `embed not enabled: Embed isn't enabled for this network; see https://ur.io/services`, and `provision` still writes the client JWT but keeps the default ACL group in `pending_acl` and prints `embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services` on stderr; the next `provision` after Embed is enabled applies it ([contract](../../EMBED_CONTRACT.md#embed-enablement)).

**The client limit.** A network can have 100 active top-level clients by default; provider installs and child clients never count, and a client stops counting when it is removed or after 30 days without connecting. Provisioning past the limit is refused, and the tool prints `client limit reached: your network is at its client limit; see https://ur.io/services` and exits with 78. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

Ship the native companion with your app, one binary per OS and architecture, for example `bin/darwin-arm64/ur-companion`, `bin/linux-x64/ur-companion` and `bin/win32-x64/ur-companion.exe`, and point `URNETWORK_COMPANION_PATH` at the right one. The app itself needs no URnetwork JavaScript package. The SDK and connect are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/): your app's own code can stay proprietary, and changes to the SDK's own files are shared under the MPL. Publish the SDK's licenses and data attributions with your app: the companion's `/embed-status` returns them for the host OS (`GetLicenses`). The embedded device needs no VPN entitlement, but your privacy policy and store privacy details must say that the app routes some of its users' traffic through URnetwork.

`client.jwt` lives in the installation's private state directory, protected by the operating system's user isolation. A production app may also encrypt it with the platform's secret store (the Keychain, DPAPI or libsecret). Either way it is a bearer secret: never print, log or back it up.

## Configure the installation

Create a private state directory once, and keep it for the life of the installation:

```sh
mkdir -p ~/.local/state/my-app/embed && chmod 700 ~/.local/state/my-app/embed   # Linux
mkdir -p ~/Library/Application\ Support/MyApp/embed && chmod 700 ~/Library/Application\ Support/MyApp/embed   # macOS
```

```powershell
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\MyApp\embed"   # Windows
```

Then either configure the token server, which the app calls on every start:

```sh
export URNETWORK_EMBED_STATE_DIR="$HOME/.local/state/my-app/embed"
export URNETWORK_TOKEN_SERVER_URL=http://127.0.0.1:8790     # an HTTPS origin in production
export URNETWORK_DEMO_SESSION='a-demo-session-token-from-the-token-servers-session-file'
```

or write `client.jwt` into the state directory with the backend tool's `provision` and set only `URNETWORK_EMBED_STATE_DIR`. The app creates `instance-id` on first run; with the token server, that is the installation ID it sends. `URNETWORK_API_URL` (default `https://api.bringyour.com`) is the API of the app's own cap reads.

## Run

```sh
node main.ts
```

```powershell
node main.ts
```

The app prints the client ID and installation ID, then a status line when any field changes, and otherwise once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222
status: connecting | data this month: checking | data total: checking
status: connected | data this month: 1.2 GB of 10.0 GB | data total: no cap
```

| Field | Shows |
| --- | --- |
| Status | `client limit, retry at HH:MM UTC`, `paused`, `data cap reached, resets YYYY-MM-DD HH:MM UTC`, `data cap reached`, `connected` or `connecting`: the first that applies ([rules](../../EMBED_CONTRACT.md#status)). |
| Data this month | `checking`, `unavailable`, `no cap`, or the bytes used this UTC month of the monthly cap. While Embed isn't enabled for your network, the cap read answers `Embed isn't enabled for this network.` and both data fields read `unavailable` ([contract](../../EMBED_CONTRACT.md#embed-enablement)). |
| Data total | The same for the running-total cap. |

`client limit` means the platform holds this client off for your network's concurrent client limit; the SDK retries by itself at the time shown. `data cap reached` means a cap is reached: the device gets no new data until the month rolls over, or until your backend raises, clears or resets the cap. Ctrl-C stops the app. Exit codes: **0** after a requested stop, **78** for a configuration or credential problem that a restart does not fix (missing or invalid state, a network JWT, an auth logout, or a token server answer of 401 or 409), and **1** for any other failure, including any other token server answer. The commands are `run` (the default), `--self-test`, `--licenses` and `--version`; any other argument is a usage error (78).

## Next

The embed example ends where the app's own traffic begins ([contract](../../EMBED_CONTRACT.md#next-traffic-through-the-device)). The installation's state works with the other examples: export `URNETWORK_CLIENT_JWT` as the content of `client.jwt` and `URNETWORK_INSTANCE_ID` as the content of `instance-id`, and run one program at a time with one identity.

- [Sockets](../socket/README.md): the TypeScript Sockets examples use a hosted Device today.
- [Messages](../messages/README.md): the Messages examples run the native companion in its messaging mode with that identity. That companion's Device provides to your network, unlike the embed Device, so ask your users first, and the client must be in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups) (provision with `URNETWORK_DEFAULT_ACL_GROUP=default`, or move it with the backend tool's `acl <key> default`). An `isolated` client, the examples' default, never appears in your network's peer list.
