# Ruby embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This console app embeds URnetwork in your own product on Windows, macOS and Linux, through the SDK's C ABI with the `urnetwork-sdk` gem (FFI). It obtains its installation's scoped client JWT from your token server, starts a local device with it, and shows the connection status and this client's own data caps. The device carries only the app's own traffic, not the device's: it uses no operating system VPN API. It follows the [embed contract](../../EMBED_CONTRACT.md); the [Go embed app](../../go/embed/README.md) is the reference. What the app does with the device continues in [Next](#next).

## Files

| File | Purpose |
| --- | --- |
| [main.rb](main.rb) | The command line entry point. |
| [commands.rb](commands.rb) | Commands, settings and exit codes, and loading the gem through Bundler. |
| [client_token.rb](client_token.rb) | `ClientToken.fetch_client_jwt`, the one function to replace with your own sign-in: the token server request and its checks. |
| [session.rb](session.rb) | Device lifecycle over the C ABI: manager, device, listeners, connect location, status, stop. |
| [cap_reader.rb](cap_reader.rb) | The run loop's events and the thread that reads the client's own caps. |
| [status.rb](status.rb) | The status rules, the data fields and the status line text. |
| [caps.rb](caps.rb) | The cap object and the app's read of its own caps with its client JWT. |
| [state.rb](state.rb) | The private installation state directory. |
| [transport.rb](transport.rb) | The HTTP client (Net::HTTP, no redirects, bounded answers) and the origin rule. |
| [selftest.rb](selftest.rb) | Credential-free self-test; it needs only Ruby, not the native SDK. |
| [embed_test.rb](embed_test.rb) | Minitest tests: each self-test check, the cap reader, the HTTP client against a loopback stand-in, and the device lifecycle against a stand-in for the C ABI. |
| [Gemfile](Gemfile) | The `urnetwork-sdk` gem, which carries the native SDK, and minitest for the tests. |
| [server/backend.rb](server/backend.rb) | The backend-only tool: provisions clients, sets and reads data caps, removes clients. It extends the [integration allocator](../integration/server/allocator.rb). |

## Build and self-test

Use Ruby 3.2 or later with Bundler; there is no compile step. The self-test, the tests and the backend tool need only Ruby. The `Gemfile` asks for `urnetwork-sdk >= 0.0.1.pre.dev.0`, a lower bound rather than a pin, so Bundler installs the newest release, previews included; pin an exact version in your product. The app needs an SDK release after sdk `c638dfa8`, which added the client limit status; until that release is published, build the platform gem from an sdk checkout with `make -C sdk/ruby` and install it with `gem install /path/to/urnetwork-sdk-<version>-<platform>.gem` before `bundle install`. From `ruby/embed` on macOS, Linux and Windows (PowerShell):

```sh
ruby main.rb --self-test
ruby embed_test.rb
ruby server/backend.rb --self-test
bundle install
bundle exec ruby embed_test.rb
ruby main.rb --licenses > licenses.json
ruby main.rb --version
```

`--self-test` needs no credentials, no network and no native SDK. It checks the data amount, reset time, client limit and status line vectors, the status rules and their order, the data fields, the cap object parsing, the JWT claim, the token fetch against a stand-in token server (the request, the atomic save of `client.jwt`, the claim check and the answers mapped to exit codes), the URL rules, the state files, the configuration exit codes and the start line. Data amounts round half to even on the exact integer, so 1050 bytes is `1.0 kB` and 1150 bytes `1.2 kB` ([status.rb](status.rb)). The tests run the same checks one by one, plus the cap reader thread, the HTTP client against a loopback stand-in server, and the device lifecycle against a stand-in for the C ABI (the device arguments, the listeners, the connect location, the status getters, a refreshed token used for the cap reads, a contract status change, a rejected credential and the close order). The lifecycle tests run with or without the ffi gem. When the gem's bindings can be found (the installed `urnetwork-sdk` gem, or `URNETWORK_SDK_RUBY_DIR` naming an sdk checkout's `sdk/ruby`), the tests also check every C ABI call against the gem's `attach_function` declarations. `--version` prints the SDK version and `--licenses` the SDK's licenses and data attributions as a JSON array, to publish with your app; both fail when the gem or its native library does not load. The gem loads its native library from `URNETWORK_SDK_LIBRARY` when that is set.

## Backend

Only your backend holds the **root credential**. Use an **API key** in production: `POST /account/api-key`, called with a network JWT, creates a key that begins `urn_` and is shown only once; `GET /account/api-keys` lists the keys without their secrets; `POST /account/api-key/remove` revokes one. An API key authenticates as your network exactly like the network JWT, with the same full-network scope, not a narrower one. Keep it in the backend's secret store, rotate it by creating a new key, deploying it and then removing the old key, and remove a leaked key at once. The tools read either kind from `URNETWORK_ROOT_JWT` ([contract](../../EMBED_CONTRACT.md#the-root-credential)).

Your backend provisions **one client per running installation**: the platform keeps one connection per client, so two installations that share a client keep displacing each other. The tools key each client `user:<service-user-id>:<installation-id>`, where the installation ID is the installation's `instance-id`; a service whose users run only one installation may key `user:<service-user-id>`. A client that has not connected for 30 days is deactivated, and the next provision creates a new one.

**With the token server.** The [Go token server](../../go/embed/server/README.md) is the reference backend for this app: the app posts its `instance-id` to `POST /urnetwork/client-token` with a session token, and the token server provisions or reissues the installation's client, puts new clients in their ACL group (`isolated` unless `URNETWORK_DEFAULT_ACL_GROUP` is `default`), applies default caps to them and answers with the client JWT. See its README for its settings and the demo session file.

**With the Ruby backend tool.** [server/backend.rb](server/backend.rb) does the same with only the standard library, against its own private map (never the token server's). From `ruby/embed/server`, after your service authenticates the user:

```sh
umask 077
mkdir -p /absolute/path/to/private-service-state
export URNETWORK_ROOT_JWT='urn_your-api-key-from-the-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/embed-clients.json'
export URNETWORK_DEFAULT_ACL_GROUP=isolated
ruby backend.rb provision user:alice:22222222-2222-2222-2222-222222222222 /absolute/path/to/alice-laptop.jwt
ruby backend.rb cap user:alice:22222222-2222-2222-2222-222222222222 --monthly 10000000000
ruby backend.rb usage user:alice:22222222-2222-2222-2222-222222222222
ruby backend.rb usage-all
ruby backend.rb acl user:alice:22222222-2222-2222-2222-222222222222 default
```

`provision` reissues a mapped key or provisions a new client, writes the client JWT to the file you name with owner-only permissions, never prints it, and prints `{"client_id": "..."}`; deliver the token to the installation as its `client.jwt`. `cap` merges: each option you give sets that cap (`--monthly <bytes>` or `--total <bytes>`) or clears it (`null`), an option you leave out keeps its cap, and `--reset-total` starts a new running total. The **monthly cap** resets at 00:00 UTC on the first of the month; the **running-total cap** never resets on its own. At a cap the client gets no new transfer contracts until the month rolls over, the cap is raised or cleared, or the total is reset. Usage is counted when transfer contracts settle, so a client can pass a cap by up to the size of its contracts still open. Caps belong to a client, so with one client per installation a user's budget across installations is yours to divide, using `usage-all` to reconcile. `usage-all` prints one cap object per line for every client of your network that has a cap. The same requests with curl:

```sh
API=https://api.bringyour.com
CLIENT_ID=11111111-1111-1111-1111-111111111111
curl -fsS -X POST "$API/network/client-acl-group" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data "{\"client_id\": \"$CLIENT_ID\", \"acl_group\": \"isolated\"}"
curl -fsS -X POST "$API/network/client-data-cap" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data "{\"client_id\": \"$CLIENT_ID\", \"monthly_byte_limit\": 10000000000}"
curl -fsS "$API/network/client-data-cap?client_id=$CLIENT_ID" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
curl -fsS "$API/network/client-data-caps?limit=1000" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
```

Provisioning with curl is in the [contract](../../EMBED_CONTRACT.md#backend-provision-clients).

New clients go into the ACL group that `URNETWORK_DEFAULT_ACL_GROUP` names: `isolated` when it is unset, so your users never see each other in your network's peer list, or `default` for an app that uses Messages. `provision` saves a new client's mapping with a `pending_acl` record, applies the group with `POST /network/client-acl-group`, and only then writes the client JWT; after a failure the record stays and the next `provision` applies the group. `acl <key> default` or `acl <key> isolated` moves a client later and prints `{"client_id": "...", "acl_group": "..."}`. `cap`, `usage`, `remove` and `acl` exit 78 with `no client is mapped for that key; run provision first` for a key without a client, and a held map lock exits 1. On a server that predates a route the tool exits 1 with `/network/client-acl-group answered 404: the server predates ACL groups`, or `<path> answered 404: the server predates the data-cap routes` for the cap routes; set `URNETWORK_DEFAULT_ACL_GROUP=default` to provision on a server without ACL groups ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)).

**Pause and remove.** `ruby backend.rb cap <key> --monthly 0` pauses an installation at once; set the cap back to resume. A monthly cap of 0 stays when the month rolls over. `ruby backend.rb remove <key>` removes the client with `POST /network/remove-client` and drops the mapping: the installation loses access at its next reconnect. If the user signs in again your backend provisions a new client, so to keep a user out, your service refuses their sign-in.

**The client limit.** A network can have 100 active top-level clients by default; provider installs and child clients never count, and a client stops counting when it is removed or after 30 days without connecting. Past that, provisioning answers `Client limit exceeded.` and the tool exits 78 with `client limit reached: your network is at its client limit; see https://ur.io/services`. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

The tool exits 0 on success, 78 for a configuration or credential problem (missing settings, an invalid key or map, the root credential refused, the client limit) and 1 for any other failure, with one stderr line that never contains a secret.

## Package

Ship the `urnetwork-sdk` gem with your app: its platform gems carry the native runtime for Windows, macOS and Linux, so the app needs no Go toolchain and no custom library path. The SDK and connect are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), a file-level copyleft: your app's own code can stay proprietary, and changes to the SDK's own files are shared under the MPL. Publish the SDK's licenses and data attributions with your app; `urnet_get_licenses` returns them for the app kind (`windows`, `linux`, `apple`, `android`, `web` or `extension`) with no network:

```ruby
require "json"
require "urnetwork"

licenses = JSON.parse(URnetwork.take_string(URnetwork::Raw.urnet_get_licenses("windows")))
```

The device routes only the traffic your app sends through it, so the app needs no VPN permission or entitlement. Your privacy disclosures still apply: the app routes some of its users' traffic through URnetwork, and its privacy policy and store forms must say so. The SDK ships no Apple privacy manifest; your app owns its disclosures. `client.jwt` lives in the private state directory below; a production app may also encrypt it with the platform's secret store (DPAPI on Windows, the Keychain on macOS, the Secret Service on Linux). It is a bearer secret: never print, log or back it up.

## Configure the installation

The app keeps its state in one private directory named by `URNETWORK_EMBED_STATE_DIR`, and fetches its client JWT from your token server at every start:

```sh
# Linux; on macOS use "$HOME/Library/Application Support/urnetwork-embed"
umask 077
export URNETWORK_EMBED_STATE_DIR="$HOME/.local/state/urnetwork-embed"
mkdir -p "$URNETWORK_EMBED_STATE_DIR"
chmod 700 "$URNETWORK_EMBED_STATE_DIR"
export URNETWORK_TOKEN_SERVER_URL='http://127.0.0.1:8790'
export URNETWORK_DEMO_SESSION='demo-session-token-from-your-session-file'
```

```powershell
# Windows: the profile directory keeps the files private to the user
$env:URNETWORK_EMBED_STATE_DIR = "$env:LOCALAPPDATA\urnetwork-embed"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_EMBED_STATE_DIR | Out-Null
$env:URNETWORK_TOKEN_SERVER_URL = 'http://127.0.0.1:8790'
$env:URNETWORK_DEMO_SESSION = 'demo-session-token-from-your-session-file'
```

The token server URL is an HTTPS origin, or loopback HTTP (`localhost`, `127.0.0.1`, `[::1]`) for local testing. The demo session stands in for your app's real sign-in. Without a token server, leave both unset and write `client.jwt` into the directory instead, for example with `ruby server/backend.rb provision <key> "$URNETWORK_EMBED_STATE_DIR/client.jwt"` on a test machine. `URNETWORK_API_URL` (default `https://api.bringyour.com`) is the API origin for the cap reads. On first run the app creates `instance-id`, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation. Never put the root credential on an installation.

## Run

From `ruby/embed`, with the settings above, on macOS, Linux and Windows:

```sh
ruby main.rb
```

The app sets up Bundler with its own `Gemfile` wherever it starts from, so `bundle exec` is optional. It prints its client ID and installation ID, then a status line on stdout whenever a field's text changes, and at least once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| status | `connecting` until the device has a provider for its traffic, then `connected`; `client limit, retry at HH:MM UTC`, `paused` and `data cap reached` as below. |
| data this month | This client's usage against its monthly cap: `checking` before the first read, `unavailable` if it failed, `no cap` without a monthly cap, otherwise `<used> of <limit>`. |
| data total | The same for the running-total cap. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit; the SDK retries by itself at the time the status shows. `paused` means a cap of 0, and `data cap reached` means a cap is reached: `data cap reached, resets YYYY-MM-DD HH:MM UTC` for the monthly cap, plain `data cap reached` for the running total. The app reads its caps at start, every five minutes and when the device's contract status changes; data amounts are in decimal units (1 GB is 1,000,000,000 bytes).

The SDK copies its log lines to stderr; its full log is in `logs/` in the state directory. Ctrl-C or SIGTERM stops the device and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix: missing or invalid state, a network JWT, a credential the server rejected, or a token server answer of 401 or 409. Exit code 1 is any other failure, such as an unreachable token server or any other token server answer. The commands are `run` (the default), `--self-test`, `--licenses` and `--version`; any other argument is a usage error (78).

## Next

The device carries the traffic your app sends through it. Run one program at a time with this installation's identity, exporting the state directory for the other examples:

```sh
export URNETWORK_CLIENT_JWT="$(cat "$URNETWORK_EMBED_STATE_DIR/client.jwt")"
export URNETWORK_INSTANCE_ID="$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")"
```

- [Sockets](../socket/README.md): TCP, UDP and HTTP clients through the device.
- [Messages](../messages/README.md): Messages exchange the [URMS](../../MESSAGES_PROTOCOL.md) text and ACK protocol with other clients of your network, but not on the embed device, which does not provide. A Messages program starts its own provider-capable device for the installation, and that device provides to your network: your network's other clients can route traffic through the installation, so ask your users first. Messages also need the client in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups): provision with `URNETWORK_DEFAULT_ACL_GROUP=default`, or move the client with the backend tool's `acl <key> default`. An `isolated` client, the examples' default, never appears in your network's peer list; a `default` client appears there while the network has 100 or fewer recently active top-level clients that are not isolated, and your app decides what of it to show.
