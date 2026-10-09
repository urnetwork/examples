# Electron embed

[Native companion](../../javascript/integration/companion/README.md#embed-mode) · [JavaScript embed](../../javascript/embed/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This Electron app embeds URnetwork in a desktop product on Windows, macOS and Linux, following the [embed contract](../../EMBED_CONTRACT.md) and its [Electron notes](../../EMBED_CONTRACT.md#electron). Start obtains the installation's scoped client JWT from your backend's [token server](../../EMBED_CONTRACT.md#the-token-server), starts the embedded device and shows its status and its own data caps. The device carries only the traffic the app sends through it: it uses no VPN API. Electron cannot run the SDK's native Device in its renderer, so the main process runs the JavaScript examples' [native companion](../../javascript/integration/companion/README.md#embed-mode) in embed mode as a child process, which owns the device and the installation state, and reads the status from the companion's loopback `/embed-status` route.

## Files

| File | Purpose |
| --- | --- |
| [main.mjs](main.mjs) | The window, the IPC handlers and the app lifecycle. |
| [controller.mjs](controller.mjs) | Start, Stop and the status without Electron: the token fetch, the companion, the status reads, the cap reads and the licenses. |
| [token.mjs](token.mjs) | `fetchClientJwt`: the one place the app obtains its client JWT. Replace it with your own sign-in. |
| [companion.mjs](companion.mjs) | Finding, starting, reading and stopping the native companion in embed mode. |
| [caps.mjs](caps.mjs), [status.mjs](status.mjs) | The app's own data caps, and the contract's status rules, data fields and text. |
| [state.mjs](state.mjs) | The private installation state directory. |
| [ipc.mjs](ipc.mjs), [preload.cjs](preload.cjs) | The IPC channels and status payload, and the window's only bridge. |
| [renderer/](renderer/index.html) | The window: token server settings, Start and Stop, the status fields, the IDs and the licenses. |
| [test/](test/) | Unit tests with `node --test`: no Electron, no native companion, no credentials. |

## Build and test

Use Node 24 or later with npm, and Go 1.26.7 or later for the companion. The app's only dependency is Electron 44; it needs no URnetwork JavaScript package, because the companion serves the status. The companion's [go.mod](../../javascript/integration/companion/go.mod) requires the SDK as `github.com/urnetwork/sdk/v2026 v2026`, a version query rather than a pin: `go mod tidy` resolves it to the latest 2026 SDK release, so run it first. The embed app needs an SDK release from the first release after sdk `c638dfa8`.

The app runs the companion from `bin/<platform>-<arch>/` in this directory, named with Node's `process.platform` and `process.arch`: `bin/darwin-arm64/ur-companion`, `bin/linux-x64/ur-companion`, `bin/win32-x64/ur-companion.exe`. Set `URNETWORK_COMPANION_PATH` to an absolute path to run another build. On macOS and Linux, from the examples checkout:

```sh
cd javascript/integration/companion
go mod tidy
go build -o "../../../electron/embed/bin/$(node -p 'process.platform + "-" + process.arch')/ur-companion" .
cd ../../../electron/embed
npm ci
npm test
npm start
```

On Windows, in PowerShell (use `win32-arm64` on Arm):

```powershell
cd javascript\integration\companion
go mod tidy
go build -o ..\..\..\electron\embed\bin\win32-x64\ur-companion.exe .
cd ..\..\..\electron\embed
npm ci
npm test
npm start
```

`npm test` runs the contract's self-test checks as unit tests with `node --test`; it starts no Electron and no native companion, connects to nothing beyond loopback, and needs no credentials. It checks the byte, reset time and client limit text, the status rules and their order (including the window's `signed out` and `stopped`), the data fields, the token fetch against a stand-in token server (the request, the saved `client.jwt`, a mismatched claim, and 401 and 409 as `signed out`), the state directory (private permissions, atomic replacement, `instance-id` created once and reused, the token server settings), the companion's environment, listening line and status route (with a stand-in companion process, with and without the licenses), the controller's Start, Stop, cap reads and companion exits, the IPC payload, the preload bridge, the window's script, and entry points that start without `import.meta.main`. The companion's own tests check embed mode (`go test` in the companion directory).

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
GOOS=windows GOARCH=amd64 go build -o ../../../electron/embed/bin/win32-x64/ur-companion.exe .
```

A packaged app runs the companion from its resources directory (`process.resourcesPath`): `Contents/Resources/ur-companion` in the macOS app bundle, `resources\ur-companion.exe` on Windows and `resources/ur-companion` on Linux. With [Electron Packager](https://github.com/electron/packager), add it as an extra resource, from this directory:

```sh
npx @electron/packager . --platform=darwin --arch=arm64 --out=out --extra-resource=bin/darwin-arm64/ur-companion --ignore='^/(bin|out|test)'
npx @electron/packager . --platform=win32 --arch=x64 --out=out --extra-resource=bin/win32-x64/ur-companion.exe --ignore='^/(bin|out|test)'
npx @electron/packager . --platform=linux --arch=x64 --out=out --extra-resource=bin/linux-x64/ur-companion --ignore='^/(bin|out|test)'
```

Sign the companion with the app: on macOS it is a second executable in the bundle and needs the hardened runtime and notarization like the app; on Windows sign both executables. The SDK and connect are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/): your app's own code can stay proprietary, and changes to the SDK's own files are shared under the MPL. The window lists the SDK's licenses and data attributions that the companion's `/embed-status` returns for the host OS (`GetLicenses`); keep them visible in your app. The device needs no VPN entitlement, but your privacy policy and store privacy details must say that the app routes some of its users' traffic through URnetwork.

`client.jwt` lives in the installation's private state directory, protected by the operating system's user isolation. A production app may also encrypt it with Electron's `safeStorage` before saving it. Either way it is a bearer secret: never print, log or back it up.

## Backend

Only your backend holds the root credential, `URNETWORK_ROOT_JWT`; for production make it an [API key](../../EMBED_CONTRACT.md#the-root-credential), rotated by creating a new key, deploying it and removing the old one. The app uses the [Go token server](../../go/embed/README.md) at runtime: it posts the installation's `instance-id` with the demo session token, and the server provisions or reissues **one client per running installation**, applies default caps to new clients and answers the client JWT ([HTTP contract](../../EMBED_CONTRACT.md#the-token-server)). For production, put your own authenticated endpoint behind the same contract, and replace `fetchClientJwt` with your sign-in.

**ACL groups.** The token server puts each new client in `URNETWORK_DEFAULT_ACL_GROUP`, `isolated` unless you set `default`, before it answers the client JWT, so your users never see each other in your network's peer list; an app that uses Messages keeps `default`, and the token server's `acl <key> default|isolated` command moves a client later. On a server without ACL groups the token server logs that once and still answers the token ([contract](../../EMBED_CONTRACT.md#backend-acl-groups)).

Caps belong to each installation's client: set them with the token server's defaults, its backend commands or a [backend tool](../../EMBED_CONTRACT.md#backend-tools). The monthly cap resets at 00:00 UTC on the first of the month; the running total only when your backend resets it; a cap of `0` pauses the installation. Usage is accounted when contracts settle, so a client can pass a cap by up to its open contracts. Removing a client makes the installation lose access at its next reconnect. The curl equivalents are in the contract: [provision](../../EMBED_CONTRACT.md#backend-provision-clients), [caps](../../EMBED_CONTRACT.md#backend-per-user-data-caps).

**The client limit.** A network can have 100 active top-level clients by default; provider installs and child clients never count, and a client stops counting when it is removed or after 30 days without connecting. Past the limit the token server answers 409 `client_limit`, and the window shows `signed out` with the message. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Configure the installation

The app keeps the installation state in a private `embed` directory inside its user data directory: `~/Library/Application Support/URnetwork Embed/embed` on macOS, `~/.config/URnetwork Embed/embed` on Linux and `%LOCALAPPDATA%\URnetwork Embed\embed` on Windows, which stays out of the roaming profile. It creates the directory with owner-only permissions and `instance-id` on first start.

In the window, enter the token server URL (an HTTPS origin, or `http://127.0.0.1:8790` for a local token server) and a demo session token from the token server's session file, then Save. The main process saves both in `token-server.json` in the state directory; the window never gets the session back, and an empty session field keeps the saved one. Set `URNETWORK_API_URL` before starting the app to read the caps from another API than `https://api.bringyour.com`.

## Run

Start the app with `npm start`, or the packaged app. Press **Start**: the app obtains the client JWT and starts the device. The window shows:

| Field | Shows |
| --- | --- |
| Status | `signed out`, `stopped`, `client limit, retry at HH:MM UTC`, `paused`, `data cap reached, resets YYYY-MM-DD HH:MM UTC`, `data cap reached`, `connected` or `connecting`: the first that applies ([rules](../../EMBED_CONTRACT.md#status)). |
| Data this month | `checking`, `unavailable`, `no cap`, or the bytes used this UTC month of the monthly cap. While Embed isn't enabled for your network, the cap read answers `Embed isn't enabled for this network.` and both data fields read `unavailable` ([contract](../../EMBED_CONTRACT.md#embed-enablement)). |
| Data total | The same for the running-total cap. |
| Client ID, Installation ID | The installation's client and its `instance-id`. |

`signed out` follows an auth logout (the companion exits with 78) or a 401 or 409 from the token server, until the next Start; `client limit` means the platform holds this client off for your network's concurrent client limit and the SDK retries by itself at the time shown; `data cap reached` means a cap is reached until the month rolls over or your backend raises, clears or resets it. The app reads the status every second, the caps every 5 minutes and within 5 seconds of a change in the device's contract status, and the licenses once. **Stop** closes the device; closing the window quits the app, which closes the device first.

## Next

The embed example ends where the app's own traffic begins ([contract](../../EMBED_CONTRACT.md#next-traffic-through-the-device)). The installation's state works with the other examples: `URNETWORK_CLIENT_JWT` is the content of `client.jwt` and `URNETWORK_INSTANCE_ID` the content of `instance-id`; run one program at a time with one identity. The [JavaScript Sockets examples](../../javascript/socket/README.md) use a hosted Device today, and the [Messages examples](../../javascript/messages/README.md) run the native companion in its messaging mode with that identity. That companion's Device provides to your network, unlike the embed Device, so ask your users first, and the client must be in the `default` [ACL group](../../EMBED_CONTRACT.md#backend-acl-groups); an `isolated` client, the token server's default, never appears in your network's peer list.
