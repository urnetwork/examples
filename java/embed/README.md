# Java embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This Java console app embeds URnetwork in your own product on Windows, macOS and Linux. It obtains its installation's scoped client JWT from your backend, starts the SDK's local Device with it, and shows the connection and the installation's data caps. The Device carries only your app's own traffic: the app uses no VPN APIs and does not provide. It follows the [embed contract](../../EMBED_CONTRACT.md) and the [Go reference](../../go/embed/README.md), through the desktop JVM binding `io.ur:urnetwork-sdk` (JNA over the SDK's C ABI). Its backend half, [server/](server/), provisions one client per user installation and sets each client's data caps.

## Files

| File | Purpose |
| --- | --- |
| [Main.java](Main.java) | Commands, exit codes, Ctrl-C and SIGTERM. |
| [Token.java](Token.java) | `fetchClientJwt`: the client JWT from the token server, or the `client.jwt` your backend tool wrote. |
| [EmbedSession.java](EmbedSession.java) | The embedded Device over the C ABI: listeners, connect location, cap reads, status, stop. |
| [EmbedStatus.java](EmbedStatus.java) | The status rules, data amounts and status line. |
| [Caps.java](Caps.java) | The cap object and the cap read with the client JWT. |
| [InstallationState.java](InstallationState.java) | The private installation state directory and the settings. |
| [Json.java](Json.java) | A small strict JSON reader and writer; the JDK has none. |
| [SelfTest.java](SelfTest.java) | Credential-free self-test. |
| [pom.xml](pom.xml) | Maven build; `urnetwork.sdk.version` selects the SDK package. |
| [server/src/main/java/EmbedServer.java](server/src/main/java/EmbedServer.java) | The backend tool: `provision`, `cap`, `usage`, `usage-all`, `remove`. |
| [server/src/main/java/SelfTest.java](server/src/main/java/SelfTest.java) | The backend tool's credential-free self-test, against a stand-in API. |
| [server/pom.xml](server/pom.xml) | The backend tool's Maven build (Jackson). |

## Build and self-test

Use JDK 17 or later and Maven. The app needs the desktop SDK package `io.ur:urnetwork-sdk` from the first release after sdk `c638dfa8`, which adds the client limit status. `urnetwork.sdk.version` defaults to `0.0.1-dev.0`, the version of a local SDK build; add `-Durnetwork.sdk.version=VERSION` to each Maven command to select a release. For a local build, clone sdk with its sibling repositories, then build the package and install it in Maven Local as the [Java installation guide](../README.md#github-and-local-builds) describes:

```sh
make -C sdk/java
mvn install:install-file -Dfile=sdk/java/dist/artifacts/urnetwork-sdk-0.0.1-dev.0.jar -DpomFile=sdk/java/dist/artifacts/urnetwork-sdk-0.0.1-dev.0.pom
```

That jar carries the native SDK runtime for the OS and architecture that built it; set `URNETWORK_SDK_LIBRARY` to the absolute path of another `libURnetworkSdk.dylib`, `libURnetworkSdk.so` or `URnetworkSdk.dll` to load it instead. From `java/embed` on macOS and Linux:

```sh
mvn -q package
java -jar target/urnetwork-embed.jar --self-test
java -jar target/urnetwork-embed.jar --version
```

On Windows, in PowerShell:

```powershell
mvn -q package
java -jar target\urnetwork-embed.jar --self-test
java -jar target\urnetwork-embed.jar --version
```

`mvn package` builds `target/urnetwork-embed.jar`, copies the SDK and JNA jars into `target/dependency`, which the jar's manifest names, and runs the self-test (`-DskipTests` skips it). Keep the `dependency` directory next to the jar when you move it. `--self-test` needs no credentials and no network, and does not load the native runtime. It checks the data amounts, the reset time, the client limit text, the status lines and rules, the data fields, the cap object, the client JWT claim, the token fetch against a stand-in token server, the cap read and the state directory, and the usage exit code. `--version` loads the native runtime and prints its SDK version.

The backend tool needs only Jackson. From `java/embed/server` (the classpath uses the POSIX separator; use `;` on Windows):

```sh
mvn -q compile dependency:copy-dependencies
java -cp 'target/classes:target/dependency/*' EmbedServer --self-test
```

## Backend

Only your backend holds the root credential. For production make it an **API key**: `POST /account/api-key`, called with a network JWT, creates a long-lived key that begins `urn_` and is shown only once. An API key authenticates as your network exactly like the root JWT, with the same full-network scope, so treat it like a production database password: keep it in your secret store and out of apps, logs and repositories. To rotate it, create a new key, deploy it, then remove the old one with `POST /account/api-key/remove`; remove a leaked key at once. The tools read either kind from `URNETWORK_ROOT_JWT`.

The [Go token server](../../go/embed/README.md) is the reference backend: it authenticates your app's session, provisions or reissues the installation's client, applies default caps to new clients, and answers the client JWT at `POST /urnetwork/client-token`. The app calls it when `URNETWORK_TOKEN_SERVER_URL` is set. From `go/embed/server`:

```sh
go build -o token-server .
./token-server --self-test
umask 077
mkdir -p /absolute/path/to/private-service-state/embed
export URNETWORK_ROOT_JWT='api-key-or-network-jwt-from-your-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/embed/token-server-clients.json'
export URNETWORK_DEMO_SESSIONS='/absolute/path/to/private-service-state/embed/sessions.json'
export URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT=10000000000
./token-server
```

`sessions.json` stands in for your real sign-in: `{"version": 1, "sessions": {"<random token of 32 characters or more>": "alice"}}`, private like the map. Serve the token server to the internet only through your own HTTPS front end.

The Java backend tool runs the same provisioning from a Java backend, and sets caps. Your service authenticates its user first and passes the key internally: `user:<service-user-id>:<installation-id>` gives each installation that can run alongside another its own client, because the platform keeps one connection per client. Give the tool its own map; it refuses the token server's. From `java/embed/server`:

```sh
export URNETWORK_ROOT_JWT='api-key-or-network-jwt-from-your-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/embed/java-clients.json'
CP='target/classes:target/dependency/*'
java -cp "$CP" EmbedServer provision user:alice:22222222-2222-2222-2222-222222222222 /absolute/path/to/alice-laptop.jwt
java -cp "$CP" EmbedServer cap user:alice:22222222-2222-2222-2222-222222222222 --monthly 10000000000
java -cp "$CP" EmbedServer usage user:alice:22222222-2222-2222-2222-222222222222
java -cp "$CP" EmbedServer usage-all
java -cp "$CP" EmbedServer remove user:alice:22222222-2222-2222-2222-222222222222
```

`provision` reissues the key's client or creates one (a client deactivated after 30 days without connecting is replaced), writes its client JWT to the file you name with owner-only permissions and prints only `{"client_id": "..."}`. `cap` sends only the options you give: `--monthly` and `--total` take a byte count or `null` (clear that cap), and `--reset-total` starts a new running-total period. Each cap is optional: the monthly cap resets at 00:00 UTC on the first of the month, the running total only when you reset it. At a cap the client gets no new transfer contracts; usage is counted as contracts settle, so it lags live traffic and can pass a cap by up to the contracts still open. `usage-all` pages through every capped client of your network. Exit codes are 0, 78 for a configuration or credential problem and 1 for any other failure. With curl:

```sh
API=https://api.bringyour.com
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "embed client", "device_spec": "urnetwork-examples/curl"}'
curl -fsS -X POST "$API/network/client-data-cap" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"client_id": "11111111-1111-1111-1111-111111111111", "monthly_byte_limit": 10000000000}'
curl -fsS "$API/network/client-data-cap?client_id=11111111-1111-1111-1111-111111111111" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
```

**Pause** a user's installation with a cap of `0` (`cap <key> --monthly 0`); set the cap back to resume. **Remove** its client with `remove`, or `POST /network/remove-client`: the platform refuses its client JWT on the next connection, and if that user signs in again your backend provisions a new client, so refuse their sign-in to keep them out.

A network can have 100 active top-level clients by default; each embedded installation is one, and a client stops counting when it is removed or deactivated after 30 days without connecting. Past the limit, provisioning answers `Client limit exceeded.` and the tool prints `client limit reached: your network is at its client limit; see https://ur.io/services`. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

Ship `urnetwork-embed.jar` with its `dependency` directory: the SDK jar carries the native SDK runtime, and JNA loads it. The bytecode is the same on every OS; each OS and architecture needs its own native runtime in the SDK jar, or one named by `URNETWORK_SDK_LIBRARY`. A tool such as jpackage bundles a Java runtime with the app.

The SDK is licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), a file-level copyleft: your app's own code can stay proprietary, and changes to the SDK's own source files are shared under the MPL. Publish the SDK's licenses and data attributions with your app; `urnet_get_licenses` (`GetLicenses` in Go) returns them for your app kind (`windows`, `linux`, `apple` and others) with no network. The app routes some of its users' traffic through URnetwork, so say so in your privacy policy; the SDK ships no Apple privacy manifest, and your app owns its disclosures.

`client.jwt` is a bearer secret. The app keeps it in the private state directory below; a production app may also encrypt it with the platform's secret store (DPAPI on Windows, the Keychain on macOS, the Secret Service on Linux). Never print, log or back it up.

## Configure the installation

The app reads one private directory named by `URNETWORK_EMBED_STATE_DIR`. Create it with owner-only permissions:

```sh
# Linux; on macOS use "$HOME/Library/Application Support/urnetwork-embed"
umask 077
export URNETWORK_EMBED_STATE_DIR="$HOME/.local/state/urnetwork-embed"
mkdir -p "$URNETWORK_EMBED_STATE_DIR"
chmod 700 "$URNETWORK_EMBED_STATE_DIR"
export URNETWORK_TOKEN_SERVER_URL='http://127.0.0.1:8790'
export URNETWORK_DEMO_SESSION='the-demo-session-token-from-sessions.json'
```

```powershell
# Windows: the profile directory keeps the files private to the user
$env:URNETWORK_EMBED_STATE_DIR = "$env:LOCALAPPDATA\urnetwork-embed"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_EMBED_STATE_DIR | Out-Null
$env:URNETWORK_TOKEN_SERVER_URL = 'http://127.0.0.1:8790'
$env:URNETWORK_DEMO_SESSION = 'the-demo-session-token-from-sessions.json'
```

With the token server configured, every start fetches the client JWT and saves it as `client.jwt`. The URL is an HTTPS origin, or loopback HTTP (`localhost`, `127.0.0.1`, `[::1]`) for local testing. Without a token server, write `client.jwt` with the backend tool instead: `java -cp "$CP" EmbedServer provision <key> "$URNETWORK_EMBED_STATE_DIR/client.jwt"` from `java/embed/server` on a test machine. On first run the app creates `instance-id`, the installation ID it sends to the token server, and the SDK keeps its bounded log files in `logs/`. Keep the directory for the life of the installation; the app rewrites `client.jwt` whenever the SDK refreshes the token. `URNETWORK_API_URL` selects another API origin for the cap reads.

## Run

```sh
java -jar target/urnetwork-embed.jar
```

On Windows run `java -jar target\urnetwork-embed.jar`. The app prints its client and installation IDs, then a status line on stdout whenever a field changes, and at least once a minute:

```text
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| status | `connecting` until the window has a provider, then `connected`; `client limit, retry at HH:MM UTC` while the platform holds this client off; `paused` at a cap of 0; `data cap reached, resets YYYY-MM-DD HH:MM UTC` at the monthly cap, or `data cap reached` at the running total. |
| data this month | `<used> of <limit>` for the monthly cap, `no cap` without one, `checking` before the first cap reading and `unavailable` when it failed. |
| data total | The same for the running-total cap. |

`client limit` means the platform disconnected this client because your network reached its plan's limit for concurrently connected clients; the SDK reconnects by itself after 15 to 20 minutes, at the time the status shows. `data cap reached` means a cap your backend set is reached: the client gets no new data until the month rolls over or your backend raises, clears or resets the cap. The app reads its caps at start, every 5 minutes and soon after the contract status changes. Data amounts are in decimal units (1 GB is 1,000,000,000 bytes), as data plans are sold.

Ctrl-C or SIGTERM stops the Device and exits with code 0. Exit code 78 means a configuration or credential problem that a restart does not fix: a missing state directory, no token server and no `client.jwt`, a network JWT, a token server answer of 401 or 409, or a credential the server rejected; sign in again or provision a new client JWT. Exit code 1 is any other failure, such as an unreachable token server. The SDK copies its log lines to stderr; its full log is in `logs/`.

## Next

The embed example ends where your app's own traffic begins. With the same state directory, the [Java Sockets example](../socket/README.md) routes TCP, UDP and HTTP clients through the Device, and the [Java Messages example](../messages/README.md) exchanges messages with other clients of your network:

```sh
export URNETWORK_CLIENT_JWT="$(cat "$URNETWORK_EMBED_STATE_DIR/client.jwt")"
export URNETWORK_INSTANCE_ID="$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")"
```

```powershell
$env:URNETWORK_CLIENT_JWT = (Get-Content "$env:URNETWORK_EMBED_STATE_DIR\client.jwt" -Raw).Trim()
$env:URNETWORK_INSTANCE_ID = (Get-Content "$env:URNETWORK_EMBED_STATE_DIR\instance-id" -Raw).Trim()
```

Run one program at a time with one identity. Every embed client is a top-level client of your network, so it appears in your network's peer list while the network has 100 or fewer recently active top-level clients; the Messages example depends on that list, and your app decides what of it to show.
