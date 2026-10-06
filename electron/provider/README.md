# Electron provider

[Native companion](../../javascript/integration/companion/README.md#provider-mode) · [JavaScript provider](../../javascript/provider/README.md) · [Provider](README.md)

This Electron app runs a URnetwork provider inside your application on Windows, macOS and Linux. It provides publicly as a provider client of your network and shows the provider status in its window and its tray: providing state, clients served, data provided and the payout wallet, read only. Electron cannot provide in its renderer or with the JavaScript SDK's WebAssembly alone, so the main process runs the JavaScript examples' [native companion](../../javascript/integration/companion/README.md#provider-mode) in provider mode as a child process, which owns the provider device and the installation state, and reads the whole provider status from the companion's loopback `/provider-status` route. The app follows the [provider contract](../../PROVIDER_CONTRACT.md) and its [Electron notes](../../PROVIDER_CONTRACT.md#electron).

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. This example starts providing without asking, because the consent screen belongs to your app.

The window shows the disclaimer next to **Start providing**.

## Files

| File | Purpose |
| --- | --- |
| [main.mjs](main.mjs) | Electron main process: window, tray, IPC handlers, start at login, quit. |
| [controller.mjs](controller.mjs) | Start, stop, the status read every second, the restart after a failure, the window's status payload. |
| [companion.mjs](companion.mjs) | The companion child process: binary location, environment, status route, stop. |
| [status.mjs](status.mjs) | Reading the companion's status into the status fields, and the status line text. |
| [state.mjs](state.mjs) | The private installation state directory and the client JWT import. |
| [wallet.mjs](wallet.mjs) | The read-only payout wallet from `GET /sn/wallet`. |
| [autostart.mjs](autostart.mjs) | Start at login: the login item on macOS and Windows, an XDG autostart entry on Linux. |
| [ipc.mjs](ipc.mjs), [preload.cjs](preload.cjs) | The IPC channels and status payload, and the sandboxed window's only bridge. |
| [renderer/](renderer/index.html) | The window: disclaimer, Start, the four fields, Stop, client JWT import and start at login. |
| [icon.mjs](icon.mjs) | The tray icon, drawn at runtime. |
| [test/](test/status.test.mjs) | Credential-free unit tests, run with `npm test` without Electron, and a stand-in companion for them. |

## Build and test

Use Node 24 or later with npm, and Go 1.26.7 or later for the companion. The app's only dependency is Electron 44; it needs no URnetwork JavaScript package, because the companion serves the whole status. The companion's [go.mod](../../javascript/integration/companion/go.mod) requires the SDK as `github.com/urnetwork/sdk/v2026 v2026`, a version query rather than a pin: `go mod tidy` resolves it to the latest 2026 SDK release, so run it first. Provider mode needs the provider intent, client limit and extender APIs, which are in the first SDK release after sdk `c638dfa8`.

The app runs the companion from `bin/<platform>-<arch>/` in this directory, named with Node's `process.platform` and `process.arch`: `bin/darwin-arm64/ur-companion`, `bin/linux-x64/ur-companion`, `bin/win32-x64/ur-companion.exe`. Set `URNETWORK_COMPANION_PATH` to an absolute path to run another build. On macOS and Linux, from the examples checkout:

```sh
cd javascript/integration/companion
go mod tidy
go build -o "../../../electron/provider/bin/$(node -p 'process.platform + "-" + process.arch')/ur-companion" .
cd ../../../electron/provider
npm ci
npm test
npm start
```

On Windows, in PowerShell (use `win32-arm64` on Arm):

```powershell
cd javascript\integration\companion
go mod tidy
go build -o ..\..\..\electron\provider\bin\win32-x64\ur-companion.exe .
cd ..\..\..\electron\provider
npm ci
npm test
npm start
```

`npm test` runs the self-test as unit tests with `node --test`; it starts no Electron and no native companion, connects to nothing beyond loopback, and needs no credentials. It checks the disclaimer text, the window's disclaimer next to Start, the byte, status text and status line formats, the providing state rules and their order (`stopped` before `client limit`, `client limit` before `paused`), a new client limit retry time, the payout wallet labels, reading the companion's status into the four fields (including `100000+` at the clients-served limit and 0 bytes without packet stats), the client JWT claim, the state directory (private permissions, atomic replacement, `instance-id` created once and reused, configuration errors), the client JWT import, the companion's environment and status route, its stop and restart policy (with a stand-in companion process), the IPC payload, the preload bridge and the window's script, start at login, and entry points that start without `import.meta.main`, which Node defines only from 24.2. The companion owns `identity.json` and counts the clients served; its own tests check the identity handling and the contract's peer and clients-served vectors (`go test` in the companion directory).

### Package

Go cross-builds the companion for every target from any host. Build one per target into `bin/`:

| Target | `GOOS` and `GOARCH` | Companion file |
| --- | --- | --- |
| macOS, Apple silicon | `darwin` `arm64` | `bin/darwin-arm64/ur-companion` |
| macOS, Intel | `darwin` `amd64` | `bin/darwin-x64/ur-companion` |
| Windows x64 | `windows` `amd64` | `bin/win32-x64/ur-companion.exe` |
| Linux x64 | `linux` `amd64` | `bin/linux-x64/ur-companion` |
| Linux arm64 | `linux` `arm64` | `bin/linux-arm64/ur-companion` |

```sh
cd javascript/integration/companion
GOOS=windows GOARCH=amd64 go build -o ../../../electron/provider/bin/win32-x64/ur-companion.exe .
```

A packaged app runs the companion from its resources directory (`process.resourcesPath`): `Contents/Resources/ur-companion` in the macOS app bundle, `resources\ur-companion.exe` on Windows and `resources/ur-companion` on Linux. With [Electron Packager](https://github.com/electron/packager), add it as an extra resource, from this directory:

```sh
npx @electron/packager . --platform=darwin --arch=arm64 --out=out --extra-resource=bin/darwin-arm64/ur-companion --ignore='^/(bin|out|test)'
npx @electron/packager . --platform=win32 --arch=x64 --out=out --extra-resource=bin/win32-x64/ur-companion.exe --ignore='^/(bin|out|test)'
npx @electron/packager . --platform=linux --arch=x64 --out=out --extra-resource=bin/linux-x64/ur-companion --ignore='^/(bin|out|test)'
```

Sign the companion with the app: on macOS it is a second executable in the bundle and needs the hardened runtime and notarization like the app; on Windows sign both executables.

## Backend: provision and map the payout wallet

Only your backend holds `URNETWORK_ROOT_JWT`. For each installation it provisions a **provider install**: a top-level client created with `"provide_intent": true`. A provider install never joins your network's peer list, does not count toward the network's top-level client caps, and is exempt from your plan's client limit while it qualifies as a provider ([contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients)). The [Go backend tool](../../go/provider/README.md#backend-provision-and-map-the-payout-wallet) provisions installations and maps the payout wallet, and uses only the standard library:

```sh
cd go/provider/server
go build -o wallet .
umask 077
mkdir -p /absolute/path/to/private-service-state/wallet
export URNETWORK_WALLET_DIR='/absolute/path/to/private-service-state/wallet'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
./wallet provision user:alice:laptop-1 /absolute/path/to/private-service-state/alice-laptop-1.jwt
```

`provision` creates a provider install, or reissues the installation's stored client, and writes its client JWT to the file you name with owner-only permissions; your backend delivers that token to the installation, where the app imports it as `client.jwt`. With curl, the backend posts `{"description": "provider user:alice:laptop-1", "device_spec": "urnetwork-examples/electron-provider", "provide_intent": true}` to `/network/auth-client` with the root JWT, as the [contract](../../PROVIDER_CONTRACT.md#backend-provision-provider-clients) shows.

The payout wallet is your fixed Bittensor coldkey. The coldkey owner signs a consent message offline with their own wallet tool; the coldkey never touches the backend or the app. One **network consent** covers every provider client of your network, including the ones you provision later:

```sh
export URNETWORK_PAYOUT_COLDKEY='5...your-coldkey-ss58-address'
./wallet network-challenge
./wallet network-accept '0x...128-hex-character-signature'
./wallet show
```

A **per-provider consent** covers one client and takes precedence over the network consent for it, for a client that must earn to another coldkey: `./wallet challenge <client-id>` and `./wallet accept <client-id> <signature>`. The [contract](../../PROVIDER_CONTRACT.md#payout-wallet-mapping) gives the same steps with curl, the signing format and the precedence rules. The app never sets a wallet; it only reads the one your backend mapped.

## Configure the installation

The app keeps the installation state in a private `provider` directory inside its user data directory, which it creates with owner-only permissions on first start and shows in its window:

| OS | State directory |
| --- | --- |
| macOS | `~/Library/Application Support/URnetwork Provider/provider` |
| Windows | `%LOCALAPPDATA%\URnetwork Provider\provider` (the app keeps its user data out of the roaming profile) |
| Linux | `~/.config/URnetwork Provider/provider`, or under `$XDG_CONFIG_HOME` |

Click **Import client JWT…** and pick the file your backend delivered, such as the file `./wallet provision` wrote. The main process checks its `client_id` claim (a network JWT is refused) and saves it as `client.jwt` with owner-only permissions; the token never reaches the window. You can also write the file yourself while the app is stopped:

```sh
# macOS; on Linux use "$HOME/.config/URnetwork Provider/provider"
umask 077
STATE_DIR="$HOME/Library/Application Support/URnetwork Provider/provider"
mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
printf '%s\n' 'scoped-client-jwt-from-your-backend' > "$STATE_DIR/client.jwt"
```

```powershell
# Windows: the profile directory keeps the files private to the user
$stateDir = "$env:LOCALAPPDATA\URnetwork Provider\provider"
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
Set-Content -Path "$stateDir\client.jwt" -Value 'scoped-client-jwt-from-your-backend'
```

On Start the app creates `instance-id`; the companion creates `identity.json`, rewrites `client.jwt` whenever the SDK refreshes the token, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation: the identity keeps the provider's keys stable across restarts. Never put the root JWT on an installation, and never build a token into the app.

## Run

```sh
npm start
```

Or start the packaged app. Click **Start providing**: the app starts the companion with the state directory, a new random companion token and a free loopback port, reads its status every second and shows four fields:

| Field | Meaning |
| --- | --- |
| Status | `starting` until the provider is enabled and connected to the platform, then `providing`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` and `stopped` otherwise. |
| Clients served | Distinct clients that opened a contract with this provider since providing started. |
| Data provided | Bytes relayed for clients, both directions, since providing started. |
| Payout wallet | The mapped coldkey, read only, with `(this provider)` for this client's own mapping, `(network)` for the network's wallet, `(hotkey)` for the network's hotkey delegation (a later server and SDK change) or `(another provider)`; `checking`, `not set` or `unavailable` otherwise. |

The tray icon's tooltip shows the same values as the contract's status line:

```text
status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)
```

Every value comes from the companion's `/provider-status` route, which the companion reads in process with the full SDK: the provide mode, enabled and paused state, the provider's platform connection, the client limit status, the provider packet stats (data provided adds their two byte counts) and the clients served, which the companion counts by peer with the contract's rules. Each Start runs a new companion, so both counts start again from 0; while stopped they show 0.

`client limit` means the platform disconnected this client because your network reached its plan's concurrent client limit and this installation has not qualified as a provider. The SDK retries by itself after about 15 to 20 minutes, at the time the status shows. A provider install that qualifies as a provider (providing publicly, passing its egress probe and reliable) is exempt from the limit and does not get this status.

While providing, the SDK also runs the provider extender role, on by default: it listens on TCP 443, which the role needs, and UDP 443 and 4053 when it can bind them, so that clients that cannot reach the platform directly can connect through this provider. Windows and macOS may ask to allow incoming connections for the companion the first time; on Linux, without the privilege to bind TCP 443, providing continues with the role off, and the SDK tries TCP 443 again every few minutes. Only the subnet's command-line provider also listens on UDP 53; clients of the role try both UDP 53 and 4053. The companion creates its device with `NewDeviceLocalWithProvideExtender` and both extender settings on; passing `false` for `defaultProvideExtender` there turns the default off (see the [contract](../../PROVIDER_CONTRACT.md#app-lifecycle)).

**Stop providing** closes the companion's standard input, which stops providing on every OS; the app kills the companion if it has not exited after 15 seconds. The app reads the companion's exit code as a supervisor does:

| Companion exit | The app |
| --- | --- |
| 0 | Stopped on request. |
| 78 | A configuration or credential problem that a restart does not fix, such as a missing `client.jwt` or a credential the server rejected: the window shows the companion's message and the app does not start it again. Import a new scoped JWT from your backend, then Start. |
| Any other | A failure: the window shows it and the app starts the companion again after 30 seconds; Stop cancels that. |

The SDK's log is in `logs/` in the state directory.

## Run in the background

GUI apps do not use the [background templates](../../background/README.md); this app uses the tray. Closing the window keeps providing, and the tray menu shows the status, opens the window, stops providing and quits. Its **Start providing…** opens the window, so the disclaimer stays next to the control that starts providing. Quit stops providing before the app exits, and the companion also stops when the app exits any other way, because its standard input closes.

**Start providing at login** in the window launches the app at login, which then starts providing in the tray without opening its window. On macOS and Windows the app registers itself with `app.setLoginItemSettings`; on macOS only the packaged app can be a login item. On Linux it writes the XDG autostart entry `~/.config/autostart/urnetwork-electron-provider.desktop`, which runs the app with `--login`. A Linux desktop needs a tray (StatusNotifier or AppIndicator) host to show the icon; GNOME needs the AppIndicator extension.
