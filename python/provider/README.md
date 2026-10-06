# Python provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux, through the SDK's C ABI with the `urnetwork` package (ctypes). It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md); the [Go provider](../../go/provider/README.md) is the reference.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [main.py](main.py) | Commands and exit codes. |
| [session.py](session.py) | Provider device lifecycle over the C ABI: identity, public provide mode, listeners, status, stop. |
| [status.py](status.py) | Status fields, status line text and the clients-served count. |
| [state.py](state.py) | The private installation state directory. |
| [selftest.py](selftest.py) | Credential-free self-test; it needs only Python, not the native SDK. |
| [test_provider.py](test_provider.py) | Unit tests: each self-test check, and the provider lifecycle against a stand-in for the C ABI. |
| [requirements.txt](requirements.txt) | The `urnetwork-sdk` package, which carries the native SDK. |
| [go/provider/server](../../go/provider/server/main.go) | The backend-only tool that provisions provider installs and maps them to the payout coldkey (Go). |

## Build and self-test

Use Python 3.10 or later; there is no compile step. The self-test and the unit tests need only Python. `requirements.txt` asks for `urnetwork-sdk>=0.0.1.dev0`, a lower bound rather than a pin, so pip installs the newest release, previews included. The provider needs an SDK release after sdk `c638dfa8`, which added provider intent, the client limit status and the extender-aware constructor; until that release is published, build the package from an sdk checkout with `make -C sdk/python` and install its wheel instead. From `python/provider` on macOS and Linux:

```sh
python3 main.py --self-test
python3 -m unittest -v
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python main.py --version
```

On Windows, in PowerShell:

```powershell
py main.py --self-test
py -m unittest -v
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py --version
```

To run against an sdk checkout without a wheel, skip the package and set `PYTHONPATH` to the checkout's `sdk/python/src` and `URNETWORK_SDK_LIBRARY` to its C ABI library in `sdk/cgo/build/<os>/<arch>/` (`libURnetworkSdk.dylib`, `libURnetworkSdk.so` or `URnetworkSdk.dll`).

`--self-test` needs no credentials, no network and no native SDK. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the status values in the C ABI's JSON, the JWT claim, the state-file handling (private permissions, symlinks, atomic replacement, instance ID and identity reuse) and the usage exit code. The unit tests run the same checks one by one, plus the provider lifecycle against a stand-in for the C ABI: the first-run identity, the extender settings, the listeners, the status getters, the wallet labels, a refreshed token, a rejected credential and the close order. When the `urnetwork` package and its native library load, the unit tests also check every call against the package's ctypes signatures. `--version` prints the SDK version, and fails when the native library does not load.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations and maps the payout wallet with only the Go standard library:

```sh
cd ../../go/provider/server
go build -o wallet .
./wallet --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` reissues an installation that it already maps and otherwise creates a provider install, then writes the client JWT to the file you name, with owner-only permissions, and never prints it. Your backend delivers that token to the installation as its `client.jwt`. Without the Go tool, create the provider install with curl and jq:

```sh
API=https://api.bringyour.com
umask 077
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/python-provider", "provide_intent": true}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json    # a refusal answers 200 with error.message
jq -r .client_id auth-client.json                   # keep it in the installation map
jq -r .by_client_jwt auth-client.json > client.jwt  # deliver it to the installation
rm auth-client.json
```

A reissue posts the stored `client_id` with the same `description` and `device_spec` and without `provide_intent`. The Python [allocator](../integration/server/allocator.py) provisions ordinary clients, without the flag.

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

`network-challenge` saves the exact consent message as `network-consent.txt` in the wallet directory, after checking that it names your network and the coldkey. The coldkey owner signs those exact bytes within five minutes (sr25519, "substrate" context, as btcli, polkadot.js and subkey sign) and returns the 64-byte hex signature, which `network-accept` submits. A **per-provider consent** covers one client and takes precedence over the network consent for it, for example for a client that an independent signer runs: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [provider contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives both consents with curl, the signing format and the precedence rules.

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

From `python/provider`, with the state directory set as above, on macOS and Linux:

```sh
.venv/bin/python main.py
```

On Windows, in PowerShell:

```powershell
.venv\Scripts\python.exe main.py
```

The app prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

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

The SDK copies its log lines to stderr, and the C ABI has no switch to lower that copy; the SDK's full log is in `logs/` in the state directory.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443, which the role needs, and UDP 443 and 4053 when it can bind them, so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections for Python the first time; on Linux, without the privilege to bind TCP 443, providing continues with the role off, and the SDK tries TCP 443 again every few minutes. Only the subnet's command-line provider also listens on UDP 53; clients of the role try both UDP 53 and 4053. The app creates its device with `urnet_new_device_local_with_provide_extender` and both extender settings on; setting `DEFAULT_PROVIDE_EXTENDER = False` in [session.py](session.py) turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C or SIGTERM stops providing and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the virtual environment's Python (`.venv/bin/python`, or `.venv\Scripts\python.exe` on Windows), `@ARGUMENT@` is the absolute path of `main.py` followed by `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is `/absolute/path/to/python/provider/.venv/bin/python /absolute/path/to/python/provider/main.py run`. In the launchd plist, the script path and `run` are two `<string>` elements; in the Windows launcher, `$ProviderArguments = @('C:\path\to\python\provider\main.py', 'run')`. When the app runs against an sdk checkout instead of the package, set `PYTHONPATH` and `URNETWORK_SDK_LIBRARY` in the service definition too.
