# Ruby provider

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](README.md)

This console app runs a URnetwork provider inside your application on Windows, macOS and Linux, through the SDK's C ABI with the `urnetwork-sdk` gem (FFI). It provides publicly as a provider client of your network and shows the provider status: providing state, clients served, data provided and the payout wallet, read only. It follows the [provider contract](../../PROVIDER_CONTRACT.md); the [Go provider](../../go/provider/README.md) is the reference.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

## Files

| File | Purpose |
| --- | --- |
| [main.rb](main.rb) | The command line entry point. |
| [commands.rb](commands.rb) | Commands and exit codes, and loading the gem through Bundler. |
| [session.rb](session.rb) | Provider device lifecycle over the C ABI: identity, public provide mode, listeners, status, stop. |
| [status.rb](status.rb) | Status fields, status line text and the clients-served count. |
| [state.rb](state.rb) | The private installation state directory. |
| [selftest.rb](selftest.rb) | Credential-free self-test; it needs only Ruby, not the native SDK. |
| [provider_test.rb](provider_test.rb) | Minitest tests: each self-test check, and the provider lifecycle against a stand-in for the C ABI. |
| [Gemfile](Gemfile) | The `urnetwork-sdk` gem, which carries the native SDK, and minitest for the tests. |
| [go/provider/server](../../go/provider/server/main.go) | The backend-only tool that provisions provider installs and maps them to the payout coldkey (Go). |

## Build and self-test

Use Ruby 3.2 or later with Bundler; there is no compile step. The self-test needs only Ruby. The `Gemfile` asks for `urnetwork-sdk >= 0.0.1.pre.dev.0`, a lower bound rather than a pin, so Bundler installs the newest release, previews included. The provider needs an SDK release after sdk `c638dfa8`, which added provider intent, the client limit status and the extender-aware constructor; until that release is published, build the platform gem from an sdk checkout with `make -C sdk/ruby` and install it with `gem install /path/to/urnetwork-sdk-<version>-<platform>.gem` before `bundle install`. From `ruby/provider` on macOS, Linux and Windows (PowerShell):

```sh
ruby main.rb --self-test
bundle install
bundle exec ruby provider_test.rb
ruby main.rb --version
```

`--self-test` needs no credentials, no network and no native SDK. It checks the disclaimer text, the status line format and the client limit status text, the providing state rules and their order, the payout wallet labels, the clients-served count, the status values in the C ABI's JSON, the JWT claim, the state-file handling (private permissions, symlinks, atomic replacement, instance ID and identity reuse) and the usage exit code. The tests run the same checks one by one, plus the provider lifecycle against a stand-in for the C ABI: the first-run identity, the extender settings, the listeners, the status getters, the wallet labels, a refreshed token, a rejected credential and the close order. With the gem's native library loaded, they also check every call against the gem's FFI signatures. Without Bundler, `ruby provider_test.rb` runs the self-test checks and skips the lifecycle tests unless the ffi gem is installed. `--version` prints the SDK version, and fails when the gem or its native library does not load. The gem loads its native library from `URNETWORK_SDK_LIBRARY` when that is set, for example to try a library built in an sdk checkout's `sdk/cgo/build/<os>/<arch>/`.

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
  --data '{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/ruby-provider", "provide_intent": true}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json    # a refusal answers 200 with error.message
jq -r .client_id auth-client.json                   # keep it in the installation map
jq -r .by_client_jwt auth-client.json > client.jwt  # deliver it to the installation
rm auth-client.json
```

A reissue posts the stored `client_id` with the same `description` and `device_spec` and without `provide_intent`. The Ruby [allocator](../integration/server/allocator.rb) provisions ordinary clients, without the flag.

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

From `ruby/provider`, with the state directory set as above, on macOS, Linux and Windows (PowerShell):

```sh
ruby main.rb
```

The app sets up Bundler with its own `Gemfile` wherever it starts from, so `bundle exec` is optional. It prints the consent disclaimer, its client and instance IDs, then a status line on stdout whenever the status (with its retry time), the clients-served count or the payout wallet changes, and at least once a minute:

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

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443, which the role needs, and UDP 443 and 4053 when it can bind them, so that clients that cannot reach the platform directly can connect through this provider, and it prints those listeners on stderr. Windows and macOS may ask to allow incoming connections for Ruby the first time; on Linux, without the privilege to bind TCP 443, providing continues with the role off, and the SDK tries TCP 443 again every few minutes. Only the subnet's command-line provider also listens on UDP 53; clients of the role try both UDP 53 and 4053. The app creates its device with `urnet_new_device_local_with_provide_extender` and both extender settings on; setting `DEFAULT_PROVIDE_EXTENDER = false` in [session.rb](session.rb) turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

Ctrl-C or SIGTERM stops providing within a second and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected; issue a new scoped JWT from your backend. Exit code 1 is any other failure, including a gem that does not load.

## Run in the background

Use the shared [background templates](../../background/README.md) with these values: `@COMMAND@` is the absolute path of the Ruby that ran `bundle install` (`command -v ruby` on macOS and Linux, `(Get-Command ruby).Source` on Windows), `@ARGUMENT@` is the absolute path of `main.rb` followed by `run`, and `@STATE_DIR@` is the state directory above. For systemd, `ExecStart=` is that Ruby's path, then `/absolute/path/to/ruby/provider/main.rb run`. In the launchd plist, the script path and `run` are two `<string>` elements; in the Windows launcher, `$ProviderArguments = @('C:\path\to\ruby\provider\main.rb', 'run')`. When the gems live outside that Ruby's default gem directory (a version manager or a `GEM_HOME`), set the same `GEM_HOME` and `GEM_PATH` in the service definition.
