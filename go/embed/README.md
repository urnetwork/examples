# Go embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This console app embeds URnetwork in one installation of your app on Windows, macOS and Linux. It gets the installation's scoped client JWT from your backend, starts a local Device with it, connects to the best available location and shows the status and the installation's data caps. The Device carries only the traffic your app sends through it, not the device's: there is no VPN. It is the reference for the [embed contract](../../EMBED_CONTRACT.md), which every embed example follows, and its backend, the [Go token server](server/README.md), is the reference backend.

## Files

| File | Purpose |
| --- | --- |
| [main.go](main.go) | Commands, the startup order and the exit codes. |
| [token.go](token.go) | `fetchClientJwt`: the token server request, the one place to replace with your own sign-in. |
| [session.go](session.go) | The Device lifecycle: the network space, the local Device, its listeners, the cap reads, the status loop and stop. |
| [status.go](status.go) | The status rules and the exact field texts. |
| [caps.go](caps.go) | The installation's own data caps, read with its client JWT. |
| [state.go](state.go) | The private installation state directory. |
| [selftest.go](selftest.go), [embed_test.go](embed_test.go) | Credential-free self-test, also run by `go test`. |
| [server/](server/README.md) | Backend only: the token server, and the commands that provision clients, set their ACL group, set and read data caps, and remove clients. |

## Build and self-test

Use Go 1.26.7 or later. `go.mod` pins the SDK release `github.com/urnetwork/sdk/v2026 v2026.10.8-1066946420`; the embed app needs a release after sdk `c638dfa8`, which adds the client limit status. Move to a newer release with `go get github.com/urnetwork/sdk/v2026@<version>`; the module tag is the pin. From `go/embed` on macOS or Linux:

```sh
go build -o embed .
go test ./...
./embed --self-test
./embed --licenses > licenses.json
```

On Windows, in PowerShell:

```powershell
go build -o embed.exe .
go test ./...
.\embed.exe --self-test
.\embed.exe --licenses > licenses.json
```

The SDK is pure Go, so any host builds every desktop target:

```sh
GOOS=windows GOARCH=amd64 go build -o embed-windows-amd64.exe .
GOOS=darwin GOARCH=arm64 go build -o embed-darwin-arm64 .
GOOS=linux GOARCH=amd64 go build -o embed-linux-amd64 .
```

`--self-test` needs no credentials and no network and creates no device. It checks the byte, reset time, client limit and status line vectors of the contract, the status rules and their order, the data fields, the cap object parsing, the client JWT claim, the token fetch against an in-process stand-in server, the state files, the configuration errors, the start line and the license kind. `--licenses` prints the SDK's licenses and data attributions as a JSON array, to publish with your app; it needs no state, credential or network.

The backend tool in [server/](server/README.md) is a separate module that uses only the standard library (Go 1.24 or later):

```sh
cd server
go build -o token-server .
./token-server --self-test
```

## Backend

Only your backend holds the root credential, and it provisions **one client per running installation**: the platform keeps one live connection per client, so two installations sharing a client keep displacing each other. The token server keys each client `user:<service-user-id>:<installation-id>`, where the installation ID is the installation's `instance-id`.

**The root credential.** Use an **API key** in production: `POST /account/api-key`, called with a network JWT, creates a long-lived key that begins `urn_` and is shown only once; `GET /account/api-keys` lists the keys' metadata; `POST /account/api-key/remove` revokes one. An API key authenticates as your network exactly like a network JWT, so the backend sends it as `Authorization: Bearer` to `POST /network/auth-client` and every data-cap route. Its scope is your whole network: it is not narrower than the root JWT. Keep it in your secret store, out of apps, logs and repositories, like a production database password. Rotate it by creating a new key, deploying it, then removing the old one; remove a leaked key at once. `URNETWORK_ROOT_JWT` holds either kind.

**Provision with the token server.** The [token server](server/README.md) is the shape of your service's sign-in endpoint: the app posts its `instance-id` to `POST /urnetwork/client-token` with its session, and the token server provisions or reissues the installation's client, puts a new client in its ACL group and applies your default caps to it, and returns the client JWT. Run it on the backend, with its [demo session file](server/README.md#configuration) standing in for your sign-in:

```sh
umask 077
mkdir -p /absolute/path/to/private-service-state
export URNETWORK_ROOT_JWT='urn_your-api-key-from-your-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/clients.json'
export URNETWORK_DEMO_SESSIONS='/absolute/path/to/private-service-state/sessions.json'
export URNETWORK_DEFAULT_ACL_GROUP=isolated
export URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT=10000000000
./token-server serve
```

**Provision with a command.** The same binary provisions one key at a time and writes the client JWT to a private file, never to its output:

```sh
./token-server provision user:alice:22222222-2222-2222-2222-222222222222 /absolute/path/to/client.jwt
```

The first `provision` of a key creates its client; later ones reissue it, and a reissue updates the client's description and device spec. A client that has not connected for 30 days is deactivated: its reissue answers `Client does not exist.`, and the token server and `provision` drop the mapping, provision a new client and apply the default ACL group and caps to it.

**ACL groups.** A new client is in the `default` ACL group, a peer of your network's other clients. The token server and `provision` move each new client to `URNETWORK_DEFAULT_ACL_GROUP`, `isolated` unless you set `default`, before they deliver its client JWT, so your users never see each other in your network's peer list. An app that uses Messages keeps `default`. `./token-server acl <key> default` or `isolated` moves a client later. A server without ACL groups answers their route with 404: `provision` and `acl` exit 1 with `/network/client-acl-group answered 404: the server predates ACL groups`, while the token server reports it once in its log and still answers the token, leaving the client `default`; set `URNETWORK_DEFAULT_ACL_GROUP=default` to provision on such a server. The [contract](../../EMBED_CONTRACT.md#backend-acl-groups) has the rules.

**Data caps.** Each client has an optional monthly cap, which resets at 00:00 UTC on the first of the month, and an optional running-total cap, which never resets on its own. Each option merges: an option you leave out keeps its value, `null` clears that cap, and a byte count sets it. A cap of `0` pauses the installation until the cap changes.

```sh
./token-server cap user:alice:22222222-2222-2222-2222-222222222222 --monthly 10000000000
./token-server cap user:alice:22222222-2222-2222-2222-222222222222 --total null --reset-total
./token-server usage user:alice:22222222-2222-2222-2222-222222222222
./token-server usage-all
```

At a cap the client gets no new transfer contracts: a hard stop, not a throttle. Usage is accounted when transfer contracts settle, so the used counts lag live traffic and a client can pass a cap by up to the size of its contracts still open; set caps with that headroom. To give a user one budget across several installations, set each installation's caps from the user's budget and adjust from `usage-all`.

The same requests with curl, `C` being the client's `client_id`:

```sh
API=https://api.bringyour.com
curl -fsS -X POST "$API/network/auth-client" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"description": "embed client", "device_spec": "urnetwork-examples/curl"}'
curl -fsS -X POST "$API/network/client-acl-group" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C", "acl_group": "isolated"}'
curl -fsS -X POST "$API/network/client-data-cap" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C", "monthly_byte_limit": 10000000000}'
curl -fsS "$API/network/client-data-cap?client_id=C" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
curl -fsS -X POST "$API/network/remove-client" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  -H 'Content-Type: application/json' --data '{"client_id": "C"}'
```

**Pause and remove.** Pause an installation with a cap of `0`: it gets no new transfer contracts, and its data stops within the life of the contracts already open; set the cap back to resume. `./token-server remove <key>` removes the client: the platform refuses its client JWT on the next connection. Because a removed installation that signs in again gets a new client, keep a user out by refusing their sign-in in your service.

**The client limit.** A network can have 100 active top-level clients by default; with one client per installation, that is 100 running installations. Child clients and provider installs never count, and a client stops counting when it is removed or deactivated after 30 days without connecting. Past the limit, provisioning is refused: the token server answers `409 client_limit` and the commands print `client limit reached: your network is at its client limit; see https://ur.io/services`. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services). A refusal with `error.upgrade_required` is your plan's limit for concurrently connected clients, which an upgrade lifts.

## Package

The Go SDK compiles into the app's binary, so nothing else ships: the binary is the whole runtime on each OS. The SDK (urnetwork/sdk) and connect are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/): your app's own code can stay proprietary, and changes to the SDK's own source files are shared under the MPL. Publish the SDK's licenses and data attributions with your app; `sdk.GetLicenses("windows")`, `sdk.GetLicenses("apple")` or `sdk.GetLicenses("linux")` returns them with no network, and `./embed --licenses` prints the host OS's as a JSON array.

The Device carries only the traffic your app sends through it, so the app needs no VPN permission or entitlement. Your privacy disclosures still apply: your app routes some of its users' traffic through URnetwork, and its privacy policy and store privacy details must say so. The SDK ships no Apple privacy manifest; your app owns its disclosures.

`client.jwt` lives in the installation's private state directory, protected by the operating system's user isolation. A production app may also encrypt it with the platform's secret store: the Keychain on macOS, DPAPI on Windows or the Secret Service on Linux. It is a bearer secret: never print, log or back it up.

## Configure the installation

The app keeps everything in one private directory named by `URNETWORK_EMBED_STATE_DIR`, and gets its client JWT from your token server:

```sh
# Linux; on macOS use "$HOME/Library/Application Support/urnetwork-embed"
umask 077
export URNETWORK_EMBED_STATE_DIR="$HOME/.local/state/urnetwork-embed"
mkdir -p "$URNETWORK_EMBED_STATE_DIR"
chmod 700 "$URNETWORK_EMBED_STATE_DIR"
export URNETWORK_TOKEN_SERVER_URL='http://127.0.0.1:8790'
export URNETWORK_DEMO_SESSION='the-demo-session-token-from-sessions.json'
```

```powershell
# Windows: the profile directory keeps the files private to the user
$env:URNETWORK_EMBED_STATE_DIR = "$env:LOCALAPPDATA\urnetwork-embed"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_EMBED_STATE_DIR | Out-Null
$env:URNETWORK_TOKEN_SERVER_URL = 'http://127.0.0.1:8790'
$env:URNETWORK_DEMO_SESSION = 'the-demo-session-token-from-sessions.json'
```

The token server URL is an https origin, or explicit loopback http (`localhost`, `127.0.0.1`, `[::1]`) for local testing. The app fetches on every start, so each start reissues the client. Without a token server, the app uses the `client.jwt` already in the directory, for example one written by `./token-server provision <key> "$URNETWORK_EMBED_STATE_DIR/client.jwt"`. On first run the app creates `instance-id`, and the SDK keeps its bounded log files in `logs/`. `URNETWORK_API_URL` (default `https://api.bringyour.com`) is where the app reads its own caps. Never put the root credential on an installation.

## Run

```sh
./embed
```

On Windows run `.\embed.exe`. The app prints its client and installation IDs, then a status line whenever a field's text changes, and at least once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 33333333-3333-4333-8333-333333333333
status: connecting | data this month: checking | data total: checking
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| status | `connecting` until the window has a provider, then `connected`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` while a cap of `0` applies; `data cap reached, resets YYYY-MM-DD HH:MM UTC` at the monthly cap, and `data cap reached` at the running-total cap. |
| data this month | `<used> of <limit>` for the monthly cap in decimal units, `no cap` without one, `checking` before the first cap reading and `unavailable` if it failed. |
| data total | The same for the running-total cap. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit; the SDK reconnects by itself at the time shown, after 15 to 20 minutes. `data cap reached` means your backend's cap stopped new transfer contracts; the installation resumes when the month rolls over (for the monthly cap) or when your backend raises, clears or resets the cap. The app reads its caps at start, every 5 minutes and within seconds of a contract status change.

Ctrl-C stops the Device and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix: missing or invalid state, a network JWT in `client.jwt`, a token server answer of 401 or 409, a credential the server rejected, or an unknown argument. Exit code 1 is any other failure, such as an unreachable token server or any other token server answer. An embedded Device lives with its app, so there is no background template.

The commands are `./embed` (or `./embed run`), `--self-test`, `--licenses` and `--version`, which prints the SDK version.

## Next

The embed app ends where your app's own traffic begins. The [Sockets](../socket/README.md) and [Messages](../messages/README.md) examples take the installation's identity, so export the state directory's files:

```sh
export URNETWORK_CLIENT_JWT="$(cat "$URNETWORK_EMBED_STATE_DIR/client.jwt")"
export URNETWORK_INSTANCE_ID="$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")"
```

```powershell
$env:URNETWORK_CLIENT_JWT = (Get-Content "$env:URNETWORK_EMBED_STATE_DIR\client.jwt").Trim()
$env:URNETWORK_INSTANCE_ID = (Get-Content "$env:URNETWORK_EMBED_STATE_DIR\instance-id").Trim()
```

Run one program at a time with one identity. Sockets route TCP, UDP and HTTP clients through the Device; the [networking matrix](../../NETWORK_EXAMPLES.md) maps Go's HTTP stack to its adapter.

Messages exchange the [URMS](../../MESSAGES_PROTOCOL.md) text and ACK protocol with other clients of your network, but not on the embed Device, which does not provide. A Messages program starts its own provider-capable Device for the installation, and that Device provides to your network: your network's other clients can route traffic through the installation, so ask your users first. Messages also need the client in the `default` ACL group: provision with `URNETWORK_DEFAULT_ACL_GROUP=default`, or move the client with `./token-server acl <key> default`. An `isolated` client, the examples' default, never appears in your network's peer list; a `default` client appears there while the network has 100 or fewer recently active top-level clients that are not isolated, and your app decides what of it to show.
