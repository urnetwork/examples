# Python embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This console app embeds URnetwork in your own product on Windows, macOS and Linux, through the SDK's C ABI with the `urnetwork` package (ctypes). It obtains its installation's scoped client JWT from your token server, starts a local device with it, and shows the connection status and this client's own data caps. The device carries only the app's own traffic, not the device's: it uses no operating system VPN API. It follows the [embed contract](../../EMBED_CONTRACT.md); the [Go embed app](../../go/embed/README.md) is the reference. What the app does with the device continues in [Next](#next).

## Files

| File | Purpose |
| --- | --- |
| [main.py](main.py) | Commands, settings and exit codes. |
| [client_token.py](client_token.py) | `fetch_client_jwt`, the one function to replace with your own sign-in: the token server request and its checks. |
| [session.py](session.py) | Device lifecycle over the C ABI: manager, device, listeners, connect location, status, cap reads, stop. |
| [status.py](status.py) | The status rules, the data fields and the status line text. |
| [caps.py](caps.py) | The cap object and the app's read of its own caps with its client JWT. |
| [state.py](state.py) | The private installation state directory. |
| [transport.py](transport.py) | The HTTP client (urllib, no redirects, bounded answers) and the origin rule. |
| [selftest.py](selftest.py) | Credential-free self-test; it needs only Python, not the native SDK. |
| [test_embed.py](test_embed.py) | Unit tests: each self-test check, the device lifecycle against a stand-in for the C ABI, and the HTTP client against a loopback stand-in. |
| [requirements.txt](requirements.txt) | The `urnetwork-sdk` package, which carries the native SDK. |
| [server/backend.py](server/backend.py) | The backend-only tool: provisions clients, sets and reads data caps, removes clients. It extends the [integration allocator](../integration/server/allocator.py). |

## Build and self-test

Use Python 3.10 or later; there is no compile step. The self-test, the unit tests and the backend tool need only Python. `requirements.txt` asks for `urnetwork-sdk>=0.0.1.dev0`, a lower bound rather than a pin, so pip installs the newest release, previews included; pin `urnetwork-sdk==<version>` in your product. The app needs an SDK release after sdk `c638dfa8`, which added the client limit status; until that release is published, build the package from an sdk checkout with `make -C sdk/python` and install its wheel instead. From `python/embed` on macOS and Linux:

```sh
python3 main.py --self-test
python3 -m unittest -v
python3 server/backend.py --self-test
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python main.py --licenses > licenses.json
python main.py --version
```

On Windows, in PowerShell:

```powershell
py main.py --self-test
py -m unittest -v
py server\backend.py --self-test
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py --licenses > licenses.json
python main.py --version
```

To run against an sdk checkout without a wheel, skip the package and set `PYTHONPATH` to the checkout's `sdk/python/src` and `URNETWORK_SDK_LIBRARY` to its C ABI library in `sdk/cgo/build/<os>/<arch>/` (`libURnetworkSdk.dylib`, `libURnetworkSdk.so` or `URnetworkSdk.dll`).

`--self-test` needs no credentials, no network and no native SDK. It checks the data amount, reset time, client limit and status line vectors, the status rules and their order, the data fields, the cap object parsing, the JWT claim, the token fetch against a stand-in token server (the request, the atomic save of `client.jwt`, the claim check and the answers mapped to exit codes), the URL rules, the state files, the configuration exit codes and the start line. The unit tests run the same checks one by one, plus the device lifecycle against a stand-in for the C ABI (the device arguments, the listeners, the connect location, the status getters, a refreshed token used for the cap reads, a contract status change, a rejected credential and the close order), the cap reader thread, and the HTTP client against a loopback stand-in server. When the package source is importable (installed, or with `PYTHONPATH` as above), the unit tests also check every C ABI call against the package's own ctypes signatures, without loading the native library. `--version` prints the SDK version and `--licenses` the SDK's licenses and data attributions as a JSON array, to publish with your app; both fail when the native library does not load, with exit 78 and an `SDK version mismatch` line naming the missing function when the library is older than the package. The self-test checks that with a stand-in package.

## Backend

Only your backend holds the **root credential**. Use an **API key** in production: `POST /account/api-key`, called with a network JWT, creates a key that begins `urn_` and is shown only once; `GET /account/api-keys` lists the keys without their secrets; `POST /account/api-key/remove` revokes one. An API key authenticates as your network exactly like the network JWT, with the same full-network scope, not a narrower one. Keep it in the backend's secret store, rotate it by creating a new key, deploying it and then removing the old key, and remove a leaked key at once. The tools read either kind from `URNETWORK_ROOT_JWT` ([contract](../../EMBED_CONTRACT.md#the-root-credential)).

Your backend provisions **one client per running installation**: the platform keeps one connection per client, so two installations that share a client keep displacing each other. The tools key each client `user:<service-user-id>:<installation-id>`, where the installation ID is the installation's `instance-id`; a service whose users run only one installation may key `user:<service-user-id>`. A client that has not connected for 30 days is deactivated, and the next provision creates a new one.

**With the token server.** The [Go token server](../../go/embed/server/README.md) is the reference backend for this app: the app posts its `instance-id` to `POST /urnetwork/client-token` with a session token, and the token server provisions or reissues the installation's client, puts new clients in their ACL group (`isolated` unless `URNETWORK_DEFAULT_ACL_GROUP` is `default`), applies default caps to them and answers with the client JWT. See its README for its settings and the demo session file.

**With the Python backend tool.** [server/backend.py](server/backend.py) does the same with only the standard library, against its own private map (never the token server's). From `python/embed/server`, after your service authenticates the user:

```sh
umask 077
mkdir -p /absolute/path/to/private-service-state
export URNETWORK_ROOT_JWT='urn_your-api-key-from-the-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/embed-clients.json'
export URNETWORK_DEFAULT_ACL_GROUP=isolated
python3 backend.py provision user:alice:22222222-2222-2222-2222-222222222222 /absolute/path/to/alice-laptop.jwt
python3 backend.py cap user:alice:22222222-2222-2222-2222-222222222222 --monthly 10000000000
python3 backend.py usage user:alice:22222222-2222-2222-2222-222222222222
python3 backend.py usage-all
python3 backend.py acl user:alice:22222222-2222-2222-2222-222222222222 default
python3 backend.py status
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

New clients go into the ACL group that `URNETWORK_DEFAULT_ACL_GROUP` names: `isolated` when it is unset, so your users never see each other in your network's peer list, or `default` for an app that uses Messages. `provision` saves a new client's mapping with a `pending_acl` record, applies the group with `POST /network/client-acl-group`, and only then writes the client JWT; after a failure the record stays and the next `provision` applies the group. `acl <key> default` or `acl <key> isolated` moves a client later and prints `{"client_id": "...", "acl_group": "..."}`. `cap`, `usage`, `remove` and `acl` exit 78 with `no client is mapped for that key; run provision first` for a key without a client, and a held map lock exits 1. On a server that predates a route the tool exits 1 with `/network/client-acl-group answered 404: the server predates ACL groups`, or `<path> answered 404: the server predates the data-cap routes` for the cap routes; set `URNETWORK_DEFAULT_ACL_GROUP=default` to provision on a server without ACL groups ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)). `status` prints your network's Embed state from `GET /network/embed` as one line, `embed enabled: yes | client limit: 5000 | active clients: 1234`. Until the URnetwork team enables Embed for your network (an [Embed plan](https://ur.io/services)), `cap`, `usage`, `usage-all` and `acl` exit 78 with `embed not enabled: Embed isn't enabled for this network; see https://ur.io/services`, and `provision` still writes the client JWT but keeps the default ACL group in `pending_acl` and prints `embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services` on stderr; the next `provision` after Embed is enabled applies it ([contract](../../EMBED_CONTRACT.md#embed-enablement)).

**Pause and remove.** `python3 backend.py cap <key> --monthly 0` pauses an installation at once; set the cap back to resume. A monthly cap of 0 stays when the month rolls over. `python3 backend.py remove <key>` removes the client with `POST /network/remove-client` and drops the mapping: the installation loses access at its next reconnect. If the user signs in again your backend provisions a new client, so to keep a user out, your service refuses their sign-in.

**The client limit.** A network can have 100 active top-level clients by default; provider installs and child clients never count, and a client stops counting when it is removed or after 30 days without connecting. Past that, provisioning answers `Client limit exceeded.` and the tool exits 78 with `client limit reached: your network is at its client limit; see https://ur.io/services`. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

The tool exits 0 on success, 78 for a configuration or credential problem (missing settings, an invalid key or map, the root credential refused, the client limit) and 1 for any other failure, with one stderr line that never contains a secret.

## Package

Ship the `urnetwork-sdk` package with your app: its platform wheels carry the native library for Windows, macOS and Linux, so the app needs no Go toolchain and no custom library path. The SDK and connect are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), a file-level copyleft: your app's own code can stay proprietary, and changes to the SDK's own files are shared under the MPL. Publish the SDK's licenses and data attributions with your app; `urnet_get_licenses` returns them for the app kind (`windows`, `linux`, `apple`, `android`, `web` or `extension`) with no network:

```python
import ctypes, json, urnetwork

pointer = urnetwork.raw.urnet_get_licenses(b"windows")
licenses = json.loads(ctypes.string_at(pointer))
urnetwork.raw.urnet_free_string(pointer)
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

The token server URL is an HTTPS origin, or loopback HTTP (`localhost`, `127.0.0.1`, `[::1]`) for local testing. The demo session stands in for your app's real sign-in. Without a token server, leave both unset and write `client.jwt` into the directory instead, for example with `python3 server/backend.py provision <key> "$URNETWORK_EMBED_STATE_DIR/client.jwt"` on a test machine. `URNETWORK_API_URL` (default `https://api.bringyour.com`) is the API origin for the cap reads. On first run the app creates `instance-id`, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation. Never put the root credential on an installation.

## Run

From `python/embed`, with the settings above, on macOS and Linux:

```sh
.venv/bin/python main.py
```

On Windows, in PowerShell:

```powershell
.venv\Scripts\python.exe main.py
```

The app prints its client ID and installation ID, then a status line on stdout whenever a field's text changes, and at least once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| status | `connecting` until the device has a provider for its traffic, then `connected`; `client limit, retry at HH:MM UTC`, `paused` and `data cap reached` as below. |
| data this month | This client's usage against its monthly cap: `checking` before the first read, `unavailable` if it failed, `no cap` without a monthly cap, otherwise `<used> of <limit>`. While Embed isn't enabled for your network, the cap read answers `Embed isn't enabled for this network.` and both data fields read `unavailable` ([contract](../../EMBED_CONTRACT.md#embed-enablement)). |
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
