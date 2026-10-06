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
| [server/](server/main.go) | Backend-only tool that maps a provider client to the payout coldkey. |

## Build and self-test

Use Go 1.26.7 or later. The module pins an SDK release with the provider status APIs; dependency download may need network access the first time. From `go/provider`:

```sh
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

`--self-test` needs no credentials and no network. It checks the disclaimer text, the status line format, the providing state rules, the clients-served count and the state-file handling (private permissions, atomic replacement, instance ID and identity reuse).

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a provider client with the [Go allocator](../integration/README.md#backend-allocator), the same top-level client the [integration contract](../../INTEGRATION_CONTRACT.md) describes. Keep provider installations in their own private map, keyed per installation:

```sh
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/providers.json'
../integration/server/allocator user:alice:laptop-1   # built as in the integration guide
```

The allocator prints `client_id` and `by_client_jwt`. Your backend delivers `by_client_jwt` to that installation as its `client.jwt`.

The payout wallet is your fixed Bittensor coldkey. The backend maps each provider client to it once, with a consent message that the coldkey owner signs offline with their own wallet tool; the coldkey never touches the backend or the app. The wallet tool in [server/](server/main.go) runs those steps for one client at a time and uses only the standard library:

```sh
cd server
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet challenge 11111111-1111-1111-1111-111111111111
./wallet accept 11111111-1111-1111-1111-111111111111 '0x...128-hex-character-signature'
./wallet show
```

`challenge` saves the exact consent message as `consent-<client-id>.txt` in the wallet directory. The coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature; `accept` submits it and saves a receipt. `show` lists the network's mapped wallets. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl and the signing format.

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

On first run the app creates `instance-id` and `identity.json` there, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts, and the app rewrites `client.jwt` whenever the SDK refreshes the token. Never put the root JWT on an installation.

## Run

```sh
./provider
```

On Windows run `.\provider.exe`. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the state, the clients-served count or the payout wallet changes, and at least once a minute:

```text
status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)
```

| Field | Meaning |
| --- | --- |
| status | `starting` until the provider is enabled and connected to the platform, then `providing`; `paused` and `stopped` otherwise. |
| clients served | Distinct clients that opened a contract with this provider since the app started. |
| data provided | Bytes relayed for clients, both directions, since the app started. |
| payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping or `(network)` for the network's wallet; `checking`, `not set` or `unavailable` otherwise. |

SDK errors also appear on stderr; the SDK's full log is in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role: it listens on TCP 443 and UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind these ports, providing continues without the role. See the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle) to turn the role off.

Ctrl-C stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the built `provider` (or `provider.exe`), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`.
