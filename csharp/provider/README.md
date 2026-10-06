# C# provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This .NET 8 console app runs a URnetwork provider inside your application on Windows, macOS and Linux. It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md) and the [Go reference](../../go/provider/README.md), through the `URnetwork.SDK` managed wrapper over the SDK's C ABI.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [Program.cs](Program.cs) | Commands, exit codes, Ctrl-C and SIGTERM. |
| [Session.cs](Session.cs) | Provider device lifecycle over the C ABI: identity, public provide mode, listeners, status, stop. |
| [Status.cs](Status.cs) | Status fields, status line text, the clients-served count and the SDK's JSON values. |
| [State.cs](State.cs) | The private installation state directory. |
| [SelfTest.cs](SelfTest.cs) | Credential-free self-test. |
| [ProviderExample.csproj](ProviderExample.csproj) | .NET 8 project; `UrSdkVersion` selects the `URnetwork.SDK` package. |

## Build and self-test

Use the .NET 8 SDK. The `URnetwork.SDK` package carries the native SDK library for each supported OS and architecture. The example needs an SDK with the provider APIs it calls (provider intent, the client limit status and the extender-aware device constructor): the first SDK release after sdk `c638dfa8`, or a local package built from sdk main. The project defaults `UrSdkVersion` to `0.0.1-dev.0`, the local package: in an SDK checkout run `make -C sdk/csharp`, then restore from its artifact directory. To use a release instead, build with `-p:UrSdkVersion=VERSION`. From `csharp/provider` on macOS and Linux:

```sh
dotnet restore --source /absolute/path/to/sdk/csharp/dist/artifacts --source https://api.nuget.org/v3/index.json
dotnet build
dotnet run --no-build -- --self-test
dotnet run --no-build -- --version
```

On Windows, in PowerShell:

```powershell
dotnet restore --source C:\path\to\sdk\csharp\dist\artifacts --source https://api.nuget.org/v3/index.json
dotnet build
dotnet run --no-build -- --self-test
dotnet run --no-build -- --version
```

NuGet reuses a package version once it is in its cache, so after rebuilding the local package, delete the cached `urnetwork.sdk/0.0.1-dev.0` directory under `~/.nuget/packages` (`%USERPROFILE%\.nuget\packages` on Windows) before you restore; otherwise the build keeps the older package.

`--self-test` needs no credentials, no network and no native SDK library. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the SDK's JSON values and the state-file handling (private permissions, symlink refusal, atomic replacement, instance ID and identity reuse), and the usage exit code. `--version` loads the native library and prints the SDK version. To load another build of the library, set `URNETWORK_SDK_LIBRARY` to its absolute path.

Publish the app for each OS with its runtime identifier. The output in `bin/Release/net8.0/<rid>/publish/` holds `ProviderExample` (`ProviderExample.exe` on Windows) with the SDK library beside it:

```sh
dotnet publish -c Release -r osx-arm64 --self-contained true    # macOS on Apple silicon
dotnet publish -c Release -r linux-x64 --self-contained true    # Linux; linux-arm64 on ARM
```

```powershell
dotnet publish -c Release -r win-x64 --self-contained true
```

Any host builds every runtime identifier that the package carries. A self-contained build includes the .NET runtime, so it also runs where .NET is not installed and in background services. With `--self-contained false` the launcher needs a .NET 8 runtime that it can find: a runtime in a standard location, or `DOTNET_ROOT` (for example `/opt/homebrew/opt/dotnet@8/libexec` for Homebrew's `dotnet@8`).

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations and maps the payout wallet; it uses only the Go standard library. From `go/provider/server`:

```sh
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` keeps your installations in a private `providers.json`, reissues an installation it already maps and otherwise creates a provider install, and writes the client JWT to the file you name with owner-only permissions. Without Go, a backend sends the same request with curl and jq:

```sh
API=https://api.bringyour.com
umask 077
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/csharp-provider", "provide_intent": true}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json    # a refusal answers 200 with error.message
jq -r .client_id auth-client.json                   # keep it in the installation map
jq -r .by_client_jwt auth-client.json > client.jwt  # deliver it to the installation
rm auth-client.json
```

Keep each installation's `client_id` in a private map. A reissue posts `{"client_id": "<stored client id>", "description": …, "device_spec": …}` without `provide_intent`; when it answers `Client does not exist.` (a client deactivated after 30 days without connecting), remove the mapping and create a new provider install. Your backend delivers `by_client_jwt` to the installation as its `client.jwt`. The [C# allocator](../integration/README.md#backend-allocator) provisions ordinary clients, without the flag.

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message as `network-consent.txt` in the wallet directory; the coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature for `network-accept`. A **per-provider consent** covers one client and takes precedence over the network consent for it, for example for an installation that an independent signer runs: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives both with curl, the signing format and the precedence rules.

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

From `csharp/provider`, with the app published for your runtime identifier:

```sh
bin/Release/net8.0/osx-arm64/publish/ProviderExample    # macOS
bin/Release/net8.0/linux-x64/publish/ProviderExample    # Linux
```

```powershell
.\bin\Release\net8.0\win-x64\publish\ProviderExample.exe
```

`dotnet run --no-build` runs the development build the same way. The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

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

The SDK copies its log lines to stderr; its full log is in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443 and UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections the first time; on Linux, without the privilege to bind these ports, providing continues without the role. The app creates its device with `urnet_new_device_local_with_provide_extender` and both extender settings on; setting `DefaultProvideExtender` to `false` in [Session.cs](Session.cs) turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C or SIGTERM stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the published `ProviderExample` (or `ProviderExample.exe`), `@ARGUMENT@` is `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is the path followed by `run`. Publish self-contained for a service: systemd, launchd and Task Scheduler do not read your shell profile, so a framework-dependent launcher may not find .NET there. The SDK library must stay beside the executable, as `dotnet publish` puts it.
