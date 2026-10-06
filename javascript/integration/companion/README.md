# Native companion for JavaScript and TypeScript

[JavaScript integration](../README.md) · [JavaScript messages](../../messages/README.md) · [JavaScript provider](../../provider/README.md) · [TypeScript integration](../../../typescript/integration/README.md) · [TypeScript messages](../../../typescript/messages/README.md) · [TypeScript provider](../../../typescript/provider/README.md)

The companion owns a provider-capable native `DeviceLocal` connected to URnetwork. The Node application uses the real SDK WASM `DeviceRemote` through its extension RPC transport and handles peer selection, URMS encoding/decoding, incoming messages and ACKs. The loopback WebSocket carries SDK RPC; peer traffic uses native Device subprotocol messages with preserved boundaries.

The current browser WASM platform transport cannot run a connected provider by itself. Hosted proxy devices also remain excluded from the visible peer list and reject the new subprotocol RPC. This companion keeps those boundaries intact. It is an app-side process using a scoped client JWT; the service backend alone holds `URNETWORK_ROOT_JWT`.

The same binary is also the provider of the JavaScript, TypeScript and Electron provider examples: see [Provider mode](#provider-mode). Without `URNETWORK_COMPANION_PROVIDE` it is the messaging companion described first.

## Build from sibling checkouts

Use **Node 24+**, **Go 1.26.7+**, npm and make. The companion's [go.mod](go.mod) requires the SDK as `github.com/urnetwork/sdk/v2026 v2026`, a version query rather than a pin: `go mod tidy` resolves it to the latest 2026 SDK release and records that version, so run it first (and again to move to a newer release). It needs network access. The JavaScript packages that talk to the companion depend on `file:../../../sdk/js`, the SDK's JavaScript bundle and WASM built from an sdk checkout, which needs its Go siblings. Keep these repositories as siblings:

```text
workspace/
  examples/
  sdk/
  connect/
  glog/
  goidenticons/
  gvisor/
```

Build the JavaScript bundle and its matching WASM before installing a package that uses it, then the companion. From `workspace/`:

```sh
npm --prefix sdk/js ci
make -C sdk/js build_wasm
npm --prefix sdk/js run build
go -C examples/javascript/integration/companion mod tidy
go -C examples/javascript/integration/companion test .
mkdir -p examples/javascript/integration/companion/bin
go -C examples/javascript/integration/companion build -o bin/ur-companion .
```

The binary is `examples/javascript/integration/companion/bin/ur-companion` (`ur-companion.exe` if you choose that output filename on Windows). The shell examples below use POSIX syntax. Source: [main.go](main.go); credential-free authentication checks: [main_test.go](main_test.go).

Then run `npm ci` in each message directory you intend to use. No package in this flow embeds a root JWT. The existing hosted socket/bootstrap packages have their own documented install path.

## Configure one installation

Obtain a distinct top-level scoped client JWT from your [authenticated backend allocator](../README.md#backend-allocator). Generate an installation UUID once and persist it. Generate a random companion token, save it privately, and load the same token into the companion and Node controller.

For one-time generation, these Node commands print a UUID and 32 random bytes encoded as 64 hex characters:

```sh
node --input-type=module -e 'import {randomUUID} from "node:crypto"; console.log(randomUUID())'
node --input-type=module -e 'import {randomBytes} from "node:crypto"; console.log(randomBytes(32).toString("hex"))'
```

Use the saved values on later launches. In both app-side processes, configure:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='saved-installation-uuid'
export URNETWORK_COMPANION_TOKEN='saved-random-64-hex-character-token'
```

The token must be at least 32 characters; use the generated random value rather than a chosen password. It authorizes local Device control and is separate from the scoped JWT. The RPC also checks that the client ID and instance ID match the companion. Never supply a root JWT to either app-side process.

Start the companion from this directory:

```sh
./bin/ur-companion --version
./bin/ur-companion
```

It waits up to 30 seconds for the native provider connection, then prints its client/instance IDs and listening address. Keep it running while using the message CLI.

| Setting | Meaning |
| --- | --- |
| `URNETWORK_CLIENT_JWT` | Required scoped credential, containing the assigned client ID. Same value in companion and Node controller. |
| `URNETWORK_INSTANCE_ID` | Required persisted installation UUID. Same value in both processes. |
| `URNETWORK_COMPANION_TOKEN` | Required random local bearer token of at least 32 characters. Same value in both processes. |
| `URNETWORK_COMPANION_ADDRESS` | Companion-only bind address, default `127.0.0.1:8787`. Use numeric loopback, such as `127.0.0.1:8788` or `[::1]:8787`; update the Node configuration URL to match. |
| `URNETWORK_COMPANION_ORIGIN` | Optional exact allowed browser Origin, for example `http://localhost:5173`, without a path or trailing slash. Leave unset for the Node CLI. |
| `URNETWORK_DEVICE_CONFIG` | Node-only absolute filename for the JSON below. |

The bundled server serves `ws://` on loopback and permits one active controller at a time. By default it accepts the Node CLI's absent Origin and rejects browser Origins. For your own browser integration, set the exact permitted Origin before starting the companion; the token remains required. The supplied messages programs are Node CLIs. Browser apps can supply the SDK's opaque extension-owned transport; this repository does not ship a messaging browser UI.

Create the Node configuration file with the main-environment URLs used by this companion:

```json
{
  "apiUrl": "https://api.bringyour.com",
  "platformUrl": "wss://connect.bringyour.com",
  "companionUrl": "ws://127.0.0.1:8787/device-rpc"
}
```

```sh
export URNETWORK_DEVICE_CONFIG='/absolute/path/to/message-device.json'
```

The file contains addresses; [clientConfig](../client.mjs) adds the scoped JWT and installation ID from the environment. [companion.mjs](../companion.mjs) adds the token to the local WebSocket request at runtime and validates the numeric loopback URL. A hosted `proxyUrl`/`signedProxyId` configuration does not replace this companion configuration. Use the [JavaScript](../../messages/README.md#configure-and-run) or [TypeScript](../../../typescript/messages/README.md#configure-and-run) commands next.

## Two clients on one machine

Provision clients A and B in the same URnetwork network, with separate installation UUIDs and tokens. Run companion A at `127.0.0.1:8787` and companion B with `URNETWORK_COMPANION_ADDRESS=127.0.0.1:8788`. Use separate JSON files with the corresponding `companionUrl` values.

This is four processes: two native companions and two Node controllers. Controller A uses A's saved environment/configuration and runs `npm start -- watch`; controller B uses B's environment/configuration and runs `npm start -- send CLIENT_A_UUID 'hello'`. The watcher prints its own client ID, which B can select. B queries A's supported subprotocols, sends the TEXT and checks A's matching ACK. Do not run a second controller against a companion whose watcher is already active. On separate machines each companion can use its default loopback port.

Changing only the instance UUID while reusing one client JWT does not create a second messaging peer. A native language's message executable can replace either companion/controller pair.

## Subscription lifecycle

`openMessageDevice` returns a connecting Device. Install mirrored peer/state listeners before initial sync, wait for connectivity, then call `enableSubprotocol`. Browser-style listener changes can force an RPC reconnect; subscriptions belong to their original RPC session and are never silently replayed onto a replacement.

Observe `subscription.closed`. It resolves after normal unsubscribe and rejects on transport, callback or queue failure. Close the old subscription, reopen it and query the destination's support again before retrying. The example exits on failure so restarting the command creates a fresh registration. Cleanup closes the registration and waits for receive processing before removing peer listeners and closing the Device/WASM runtime. The transport detaches socket event listeners on SDK-initiated close and does not call back into released WASM functions.

The RPC bounds each session to 16 registrations, each raw frame to 65535 bytes, and each receive queue to 64 messages or 1 MiB. Overflow fails the subscription; frames are not coalesced. URMS enforces its smaller 4112-byte frame limit. The application's ACK confirms valid TEXT parsing/acceptance, not durable storage or human readership.

## Provider mode

The JavaScript SDK cannot provide by itself, so the [provider examples](../../../PROVIDER_CONTRACT.md#javascript-and-typescript) run this companion with `URNETWORK_COMPANION_PROVIDE=public`. It then owns the provider device and the installation's state directory, provides publicly as the installation's provider client, and the app shows the status. The [JavaScript](../../provider/README.md) and [TypeScript](../../../typescript/provider/README.md) provider programs and the Electron provider start it as a child process; you do not start it yourself. Source: [provider.go](provider.go) and [provider_state.go](provider_state.go); tests: [provider_test.go](provider_test.go).

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

| Setting | Meaning |
| --- | --- |
| `URNETWORK_COMPANION_PROVIDE` | `public` selects provider mode. Any other non-empty value is refused. |
| `URNETWORK_PROVIDER_STATE_DIR` | Required absolute path of the installation's private state directory (`chmod 700`), holding the scoped `client.jwt` from your backend ([installation state](../../../PROVIDER_CONTRACT.md#installation-state)). The companion creates `instance-id` and `identity.json` on first run, saves each refreshed `client.jwt`, and the SDK keeps its bounded log files in `logs/`. |
| `URNETWORK_COMPANION_TOKEN` | Required random token of at least 32 characters. The app generates a new one for each launch and passes it to the companion and to its own requests. |
| `URNETWORK_COMPANION_ADDRESS` | Numeric loopback address, default `127.0.0.1:8787`; the provider programs pass a free port. |
| `URNETWORK_COMPANION_ORIGIN` | Optional exact browser Origin, as in messaging mode. |
| `URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE` | `1` when an app starts the companion as a child process and keeps a pipe to its standard input. Closing the pipe stops providing, and so does the app exiting for any reason, so the provider never outlives the app that shows it. |

The companion prints the consent disclaimer, creates its device with the installation's identity and the extender role on, sets the public provide mode and waits for the provider's platform connection without a time limit: on a network over its client limit, the SDK holds a provider that has not qualified off for 15 to 20 minutes and then retries by itself. It serves two routes on its address, both authorized by `?token=` like messaging mode:

- `/device-rpc`: the SDK device RPC, only once the provider has connected; until then it answers 503 and the SDK's `DeviceRemote` dials again. The JavaScript SDK does not bind `GetProviderConnected`, so a served RPC tells the app that the provider connected. The app reads `getProvideEnabled()`, `getProvidePaused()` and the contract view controllers here; it cannot change the provide settings.
- `/provider-status`: the device values the JavaScript SDK does not bind, from the start, with the SDK's Go field names: `{"ProvideMode":3,"ProviderConnected":false,"ClientLimitStatus":{"Status":"client_limit_exceeded","RetryTime":1791313500000},"DeviceRpcStarted":false}`. `ProvideMode` 3 is public; `ClientLimitStatus` is the SDK's client limit status (`Status` `""` or `"client_limit_exceeded"`, `RetryTime` in unix milliseconds, 0 without a hold); `DeviceRpcStarted` says whether `/device-rpc` is served.

While providing, the extender role listens on TCP 443 and UDP 443, 53 and 4053 and logs those listeners on stderr. Ctrl-C, SIGTERM and a closed standard input stop providing. Exit codes: **0** after a requested stop, **78** for a configuration or credential problem that a restart does not fix (a missing or invalid state directory or token, a network JWT, or the server rejecting the client credential), and **1** for any other failure.

## Validation

From `workspace/`, after the SDK build:

```sh
UR_SUBPROTOCOL_WASM_TEST=1 go -C sdk test . -run '^TestSubprotocolWasmCompanionRoundTrip$' -count=1
```

This credential-free test runs the packaged Node WASM against a native DeviceLocal RPC session whose subprotocol client is connected to a second real network client in memory. It verifies exact URMS TEXT/ACK frames in both directions, protocol discovery, a live peer snapshot, identity pairing and unsubscribe. It does not contact the production URnetwork service. The native companion tests cover token and Origin validation and, for provider mode, the mode and settings checks, the installation state files, the two routes and the stop on a closed standard input, without a device or network; both language message directories also provide offline codec tests. Live service checks need separately provisioned clients and network connectivity.
