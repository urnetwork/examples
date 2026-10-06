# TypeScript provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This typed Node 24 console app runs a URnetwork provider inside your application on Windows, macOS and Linux. It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. The JavaScript SDK cannot provide by itself, so the app starts the [native companion](../../javascript/integration/companion/README.md#provider-mode) in provider mode as its child process: the companion owns the provider device and the installation state, reads the status with the full SDK, and serves it to the app on its status route. It follows the [provider contract](../../PROVIDER_CONTRACT.md), mirrors the [Go provider](../../go/provider/README.md), and behaves like the [JavaScript provider](../../javascript/provider/README.md). Node 24 runs the `.ts` files directly with type stripping.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [main.ts](main.ts) | Commands and exit codes. |
| [session.ts](session.ts) | Provider lifecycle: the companion child process, its status route, the payout wallet read, status lines, stop. |
| [companion.ts](companion.ts) | Starting, reading and stopping the native companion in provider mode. |
| [status.ts](status.ts) | Status fields, status line text and the clients-served count. |
| [state.ts](state.ts) | The private installation state directory. |
| [selftest.ts](selftest.ts), [provider.test.ts](provider.test.ts) | Credential-free self-test; `npm test` runs it and also tests the exit codes and the companion child process against a stand-in companion. |
| [package.json](package.json), [tsconfig.json](tsconfig.json) | Scripts, the type-check packages and settings; the app has no runtime package dependencies. |
| [javascript/integration/companion/](../../javascript/integration/companion/README.md#provider-mode) | The native companion (Go) that owns the provider device. |

## Build and self-test

Use Node 24 or later and Go 1.26.7 or later. The app needs the native companion built, and `npm ci` installs only the type-check packages (TypeScript and the Node types). The companion's `go.mod` requires the SDK as `github.com/urnetwork/sdk/v2026 v2026`, a version query: `go mod tidy` resolves it to the latest 2026 SDK release, which needs network access. Providing needs the first SDK release after sdk `c638dfa8` (provider intent, client limit status, extender settings). From `examples` on macOS or Linux:

```sh
cd javascript/integration/companion
go mod tidy
go test .
go build -o bin/ur-companion .
cd ../../../typescript/provider
npm ci
npm run build
npm run self-test
npm test
node main.ts --version
```

On Windows, in PowerShell from `examples`:

```powershell
cd javascript\integration\companion
go mod tidy
go test .
go build -o bin\ur-companion.exe .
cd ..\..\..\typescript\provider
npm ci
npm run build
npm run self-test
npm test
node main.ts --version
```

`npm run build` type-checks with `tsc` and writes no files; there is no compiled entry point. The app runs the companion from `javascript/integration/companion/bin/ur-companion` (`ur-companion.exe` on Windows); set `URNETWORK_COMPANION_PATH` to its absolute path to use another location. The companion is pure Go, so any host builds every desktop target, for example `GOOS=windows GOARCH=amd64 go build -o bin/ur-companion.exe .`.

`node main.ts --self-test` needs no credentials, no network, no companion and no installed packages. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels and the wallet read, the companion's status route (with the clients served and the data provided it reports) and exit codes, and the state-file handling (private permissions, atomic replacement, instance ID and identity checks). The companion counts the clients served, so its Go tests check the contract's peer and clients-served vectors. `npm test` type-checks, runs the same checks and tests the exit codes and the companion child process with a stand-in companion script (macOS and Linux). `--version` prints the companion's version line, which names the SDK it was built with.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations for every language and keeps them in a private `providers.json`:

```sh
cd go/provider/server
go build -o wallet .
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

With curl and jq, create a provider install, and send only the stored `client_id` with `description` and `device_spec` to reissue it:

```sh
API=https://api.bringyour.com
umask 077
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/typescript-provider", "provide_intent": true}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json
jq -r .client_id auth-client.json
jq -r .by_client_jwt auth-client.json > client.jwt
rm auth-client.json
```

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later; a **per-provider consent** covers one client and takes precedence over the network consent for it. With the Go wallet tool:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message for the signer, and `network-accept` submits the coldkey owner's signature. For an independent signer of one client, `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>` map a per-provider consent. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl, the signing format and the precedence rules.

## Configure the installation

The app reads everything from one private directory named by `URNETWORK_PROVIDER_STATE_DIR`, and the companion uses the same directory. Create it with owner-only permissions and write the scoped client JWT into `client.jwt`:

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

On a test machine that also runs the backend tool, `./wallet provision user:alice:laptop-1 "$URNETWORK_PROVIDER_STATE_DIR/client.jwt"` writes the file directly. On first run the app creates `instance-id`, the companion creates `identity.json`, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the companion rewrites `client.jwt` whenever the SDK refreshes the token. Never put the root JWT on an installation.

## Run

```sh
node main.ts
```

On Windows run the same command in PowerShell; `npm start` also works. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

```text
status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)
```

| Field | Meaning |
| --- | --- |
| status | `starting` until the provider is enabled and connected to the platform, then `providing`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` and `stopped` otherwise. |
| clients served | Distinct clients that opened a contract with this provider since the app started. |
| data provided | Bytes relayed for clients, both directions, since the app started. |
| payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping, `(network)` for the network's wallet, `(hotkey)` for the network's hotkey delegation (a later server and SDK change) or `(another provider)`; `checking`, `not set` or `unavailable` otherwise. |

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit and this installation has not qualified as a provider. The SDK retries by itself after about 15 to 20 minutes, at the time the status shows. A provider install that qualifies as a provider (providing publicly, passing its egress probe and reliable) is exempt from the limit and does not get this status.

How the app reads each field: the companion's [`/provider-status`](../../javascript/integration/companion/README.md#provider-mode) serves the provide mode, enabled and paused state, the provider's platform connection, the client limit status, the provider packet stats (data provided) and the clients served, which the companion counts from the provider contract listeners with the contract's peer rules. The app applies the contract's status rules to them; the wallet is `GET /sn/wallet` with the scoped JWT. The app does not load the JavaScript SDK: the SDK binds neither the provider connection nor the client limit status, and its remotes run in browser state only mode, which gets no provider packet stats or contract details over the companion's device RPC.

SDK errors appear on stderr from the companion; its full SDK log is in `logs/` in the state directory.

While providing, the companion also runs the provider extender role, on by default: it listens on TCP 443 and UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections for `ur-companion` the first time; on Linux, without the privilege to bind these ports, providing continues without the role. The companion creates its device with `NewDeviceLocalWithProvideExtender` and both extender settings on; passing `false` for `defaultProvideExtender` there turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C stops providing and exits with code 0: the app closes the companion's standard input, and the companion sets the provide mode to none, closes the device and exits. If the app itself dies, the companion's input closes and it stops too. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt`, a companion that is not built, or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of `node` (`command -v node`; on Windows `(Get-Command node).Source`), `@ARGUMENT@` is the absolute path of `main.ts` followed by `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the node path, the `main.ts` path and `run`; for launchd, add one `<string>` per argument; for the Windows launcher, `$ProviderArguments = @('C:\path\to\main.ts', 'run')`. The supervisor runs one process: the app starts the companion and stops it. If the companion binary is not in its default place, add `URNETWORK_COMPANION_PATH` to the template's environment.
