# Go provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux. It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It is the reference for the [provider contract](../../PROVIDER_CONTRACT.md), which every provider example follows.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [main.go](main.go) | Commands and exit codes. |
| [session.go](session.go) | Provider device lifecycle: identity, public provide mode, listeners, status, stop. |
| [status.go](status.go) | Status fields, status line text and the clients-served count. |
| [state.go](state.go) | The private installation state directory. |
| [selftest.go](selftest.go), [provider_test.go](provider_test.go) | Credential-free self-test, also run by `go test`. |
| [server/](server/main.go) | Backend-only tool that provisions provider installs ([provision.go](server/provision.go)) and maps them to the payout coldkey. |

## Build and self-test

Use Go 1.26.7 or later. `go.mod` requires the SDK as `github.com/urnetwork/sdk/v2026 v2026`, a version query rather than a pin: `go mod tidy` resolves it to the latest 2026 SDK release and records that version, so run it first (and again to move to a newer release). It needs network access. From `go/provider`:

```sh
go mod tidy
go build -o provider .
go test ./...
./provider --self-test
./provider --version
```

On Windows, in PowerShell, build `provider.exe` and run `.\provider.exe --self-test`. The SDK is pure Go, so any host builds every desktop target:

```sh
GOOS=windows GOARCH=amd64 go build -o provider-windows-amd64.exe .
GOOS=darwin GOARCH=arm64 go build -o provider-darwin-arm64 .
GOOS=linux GOARCH=amd64 go build -o provider-linux-amd64 .
```

`--self-test` needs no credentials and no network. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count and the state-file handling (private permissions, atomic replacement, instance ID and identity reuse).

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The backend tool in [server/](server/main.go) provisions installations and maps the payout wallet, and uses only the standard library:

```sh
cd server
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` keeps your installations in `providers.json` in the wallet directory, a private map from installation key to `client_id` in the shape of the [allocators'](../../INTEGRATION_CONTRACT.md#runnable-backend-allocators) maps. An installation it already maps is reissued: the request names the stored `client_id` and omits `provide_intent`, which the server ignores on a reissue. Otherwise it creates a provider install with `provide_intent`. When the server answers a reissue with `Client does not exist.` (a client deactivated after 30 days without connecting), it removes the mapping and creates a new provider install. It writes the client JWT to the file you name, with owner-only permissions, and never prints it; your backend delivers that token to the installation as its `client.jwt`. The language allocators provision ordinary clients, without the flag.

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later; a **per-provider consent** covers one client and takes precedence over the network consent for it. The same tool runs both:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message as `network-consent.txt` in the wallet directory, after checking that it names your network (from the root JWT) and the coldkey. The coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature; `network-accept` submits it and saves a receipt. For one client, `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>` do the same with `consent-<client-id>.txt`. `show` lists the network consent, the network wallet and each client's wallet, with the epochs each consent pays. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl, the signing format and the precedence rules.

## Configure the installation

The app reads everything from one private directory named by `URNETWORK_PROVIDER_STATE_DIR`. Create it with owner-only permissions and write the scoped client JWT into `client.jwt`:

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

On a test machine that also runs the backend tool, `./wallet provision user:alice:laptop-1 "$URNETWORK_PROVIDER_STATE_DIR/client.jwt"` writes the file directly. On first run the app creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. Never put the root JWT on an installation.

## Run

```sh
./provider
```

On Windows run `.\provider.exe`. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

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

SDK errors also appear on stderr; the SDK's full log is in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443, which the role needs, and UDP 443 and 4053 when it can bind them, so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind TCP 443, providing continues with the role off, and the SDK tries TCP 443 again every few minutes. Only the subnet's command-line provider also listens on UDP 53; clients of the role try both UDP 53 and 4053. The app creates its device with `NewDeviceLocalWithProvideExtender` and both extender settings on; passing `false` for `defaultProvideExtender` turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the built `provider` (or `provider.exe`), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`.
