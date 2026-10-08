# Embed integration

An embed example puts URnetwork inside a third party's own product. The developer's backend provisions a URnetwork client for each of its users' installations and delivers that client's scoped JWT to its app; the app embeds the SDK and starts a Device with it. The two halves carry equal weight: [provisioning](#backend-provision-clients) and [per-user data caps](#backend-per-user-data-caps) on the backend, and [packaging and embedding the SDK](#packaging-and-embedding-the-sdk) in the app. The embedded Device carries only the app's own traffic; what the app does with it continues in the [Sockets and Messages examples](#next-traffic-through-the-device).

This contract applies to every embed example: the twelve [language folders](README.md), each as `<language>/embed/` with its backend tool in `<language>/embed/server/`, and the `electron/embed/`, `tauri/embed/` and `android/embed/` apps. The [Go token server](#the-token-server) in `go/embed/server/` is the reference backend, and the [Go embed app](go/embed/README.md) is the reference app. The [integration contract](INTEGRATION_CONTRACT.md) still governs the backend and client credential boundary; this document adds what embedding needs.

## Who holds what

| Value | Owner | Purpose |
| --- | --- | --- |
| Root credential (`URNETWORK_ROOT_JWT`) | Backend secret store | Provisions clients, sets data caps and removes clients for the whole network. An [API key](#the-root-credential) or a network JWT. Never shipped in an app, installer, browser bundle, repository or log. |
| Service user ID and installation ID | Backend | Decide which installation may obtain or renew which client. The backend's map key is `user:<service-user-id>:<installation-id>`. |
| Client map | Backend private state | Maps each key to its URnetwork `client_id`. It stores client IDs, never tokens. |
| URnetwork `client_id` | Backend map and the client JWT | The installation's client in the developer's network. |
| `client.jwt` | The installation's private state | The scoped client JWT the backend delivered. The SDK refreshes it and the app saves the refreshed token. A bearer secret. |
| `instance-id` | The installation's private state | One UUID created on first run and kept for the life of the installation. It is also the installation ID the app sends to the backend. |
| Data caps | URnetwork, set by the backend | Optional monthly and running-total byte caps per client. The app reads its own with its client JWT. |
| Demo session token | The app (examples only) | Stands in for the developer's real sign-in when the app calls the token server. |

No key ships in an app. The app never holds the root credential, never sets data caps and never provisions or removes clients.

## One client per running installation

The platform keeps one resident connection per `client_id`. When a second installation connects with the same client and a different instance ID, it replaces the first one's resident, and the two keep displacing each other. Every installation that can run at the same time as another needs its own client, as the [integration contract](INTEGRATION_CONTRACT.md#identity-and-storage) already requires for concurrently running clients. The examples key each client by `user:<service-user-id>:<installation-id>`, where the installation ID is the installation's `instance-id`. A service whose users only ever run one installation may key by `user:<service-user-id>`.

Data caps belong to a client, so with one client per installation they apply per installation. To give a user one budget across several installations, set each installation's caps from the user's budget, read usage across the user's clients with the [bulk read](#read-caps-and-usage), and adjust.

An app chooses its own installation ID, so the backend bounds how many installations one user may create: the token server allows 5 by default.

## The root credential

The backend's root credential is either a network JWT (from the `/auth` sign-in routes) or, for production, an **API key**:

- `POST /account/api-key`, called with a network JWT, creates a long-lived key that begins `urn_`. It is shown only in that response.
- `GET /account/api-keys` lists the keys' metadata, never their secrets.
- `POST /account/api-key/remove` revokes a key.

An API key authenticates as the network exactly like a network JWT and carries no `client_id`, so it is a drop-in `Authorization: Bearer` credential for `POST /network/auth-client` and every data-cap route. Its scope is the same full network: it is **not** narrower than the root JWT, and anyone holding it can provision clients, change caps and remove clients for the whole network. Sessions authenticated with an API key run with pro mode off. The examples read either kind from `URNETWORK_ROOT_JWT`.

Treat the root credential like a production database password: keep it in the backend's secret store, give it only to the service that provisions, and keep it out of apps, logs, error messages, URLs and repositories. To rotate an API key, create a new key, deploy it to the backend, then remove the old key. Remove a leaked key at once. Removing a root credential stops it from provisioning; it is not how you cut off a user — [pause or remove that user's client](#backend-pause-remove-and-delete-users).

## Backend: provision clients

The backend provisions with `POST /network/auth-client` and the root credential, as the [integration contract](INTEGRATION_CONTRACT.md#backend-provisioning) describes for a top-level client:

- **New client:** send `description` and `device_spec`, and neither `client_id` nor `source_client_id`. The answer carries the new `client_id` and its scoped `by_client_jwt`.
- **Reissue:** send the stored `client_id` with the same `description` and `device_spec`. The answer carries a fresh `by_client_jwt` for the same client; check that its `client_id` matches the map.
- **Deactivated client:** a client that has not connected for 30 days is deactivated, and its reissue answers `Client does not exist.` Remove that mapping, provision a new client, and apply the installation's caps to the new client.

Check the HTTP result, the API error and both success fields, check that the JWT's `client_id` claim equals the answer's `client_id`, and refuse a new `client_id` that the map already assigns to another key. A refusal answers 200 with `error.message`. The description is visible to URnetwork, so do not put your users' identifiers in it; the map already links each client to its user.

**The client limit.** A network can have 100 top-level clients by default. The limit counts the network's active clients that have no `source_client_id` and are not provider installs; child clients and provider installs never count. Creating a client past the limit is refused with `error.client_limit_exceeded` and the message `Client limit exceeded.` A client stops counting when it is removed or when it is deactivated after 30 days without connecting. An **Embed plan** raises the network's limit: [request one on the Services page](https://ur.io/services). A refusal with `error.upgrade_required` is a different limit: the network's plan limit for concurrently connected top-level clients, which an upgrade lifts.

With curl and jq:

```sh
API=https://api.bringyour.com
umask 077
curl -fsS -X POST "$API/network/auth-client" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data '{"description": "embed client", "device_spec": "urnetwork-examples/curl"}' \
  > auth-client.json
jq -r '.error.message // empty' auth-client.json    # a refusal answers 200, e.g. "Client limit exceeded."
jq -r .client_id auth-client.json                   # keep it in your map
jq -r .by_client_jwt auth-client.json > client.jwt  # deliver it to the installation
rm auth-client.json
```

For a reissue, post `{"client_id": "<stored client id>", "description": "embed client", "device_spec": "urnetwork-examples/curl"}` instead.

## Backend: per-user data caps

Each client has two optional caps, and either, both or neither may be set:

- The **monthly cap** limits the bytes the client uses in each UTC calendar month. It resets at 00:00 UTC on the first of the month, not at midnight in the user's time zone.
- The **running-total cap** limits the bytes used since the total last started. It never resets on its own: the backend raises or clears it, or starts a new total period with `reset_total`.

At a cap the client gets no new transfer contracts until the month rolls over, the cap is raised or cleared, or the total is reset: a hard stop, not a throttle. Usage is accounted when transfer contracts settle, so the used counts lag live traffic, and a client can pass a cap by up to the size of its contracts that are still open. Set caps with that headroom in mind. A cap of `0` pauses the client at once; a pause lasts until that cap changes, and the month rolling over does not lift a monthly cap of `0`.

### Set caps

`POST /network/client-data-cap` with the root credential sets a client's caps; a client JWT is refused, and the `client_id` must belong to the caller's network. Each limit field **merges**: omit it to keep the current value, send `null` to clear that cap, or send a byte count (an integer, `0` or more) to set it. `reset_total: true` zeroes the running total and starts a new total period. The answer is the client's [cap object](#the-cap-object) after the change. In the table, `C` is the client's `client_id`.

| Body | Effect |
| --- | --- |
| `{"client_id": C, "monthly_byte_limit": 10000000000}` | 10 GB a month; the running-total cap is unchanged. |
| `{"client_id": C, "monthly_byte_limit": 20000000000}` | Raises only the monthly cap; the running-total cap is untouched. |
| `{"client_id": C, "total_byte_limit": null}` | Clears the running-total cap; the monthly cap is unchanged. |
| `{"client_id": C, "monthly_byte_limit": 0}` | Pauses the client until the monthly cap changes. |
| `{"client_id": C, "reset_total": true}` | Starts a new running-total period; both caps are unchanged. |

API units are integer bytes. Show GB or TB only in user interfaces; the examples format with [decimal units](#status).

### Read caps and usage

- `GET /network/client-data-cap?client_id=C` with the root credential reads any client of the network.
- `GET /network/client-data-cap` with a client JWT reads that client; `client_id` may be omitted and must match the JWT when given. This is how the app reads its own caps.
- `GET /network/client-data-caps` with the root credential lists every client of the network that has a cap set, in a stable order, a page at a time: `?limit=` takes 1 to 1000 (default 100), and the answer's `next_cursor` goes back as `?cursor=` until it is `null`. Reconcile thousands of users with it instead of one call per client.

There are no threshold notifications; the backend and the app poll these reads.

### The cap object

| Field | Meaning |
| --- | --- |
| `client_id` | The client. |
| `monthly_byte_limit` | The monthly cap in bytes, or `null` for none. |
| `monthly_used_byte_count` | Bytes used in the current UTC calendar month. |
| `monthly_period_start`, `monthly_period_end` | The current month, RFC 3339 in UTC; the end is when monthly usage resets. |
| `total_byte_limit` | The running-total cap in bytes, or `null` for none. |
| `total_used_byte_count` | Bytes used since `total_period_start`. |
| `total_period_start` | When the running total last started. |
| `capped` | True while a cap is reached: the client gets no new transfer contracts. |
| `capped_reason` | `monthly`, `total`, or `""` when the client is not capped. |

A failure answers with `{"error": {"message": "..."}}`. With curl:

```sh
CLIENT_ID=11111111-1111-1111-1111-111111111111
curl -fsS -X POST "$API/network/client-data-cap" \
  -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' \
  --data "{\"client_id\": \"$CLIENT_ID\", \"monthly_byte_limit\": 10000000000}"
curl -fsS "$API/network/client-data-cap?client_id=$CLIENT_ID" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
curl -fsS "$API/network/client-data-caps?limit=1000" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
```

## Backend: pause, remove and delete users

- **Pause** a user's installation by setting a cap to `0`: it gets no new transfer contracts from then on, and its data stops within the life of the contracts already open. Set the cap back to resume.
- **Remove** a client with `POST /network/remove-client` and `{"client_id": C}`. Removing deactivates the client: the platform refuses its client JWT on the next connection, so the installation loses access at its next reconnect. Remove its mapping too; if the installation signs in again, the backend provisions a new client, so to keep a user out, your service refuses their sign-in. A remove that answers `Client does not exist.` means the client is already gone: remove the mapping.
- **Delete** a user by removing the client of each of their installations and dropping those mappings.

## Lifecycle

| Event | What happens | Backend action |
| --- | --- | --- |
| First sign-in on an installation | No mapping yet | Provision a new client, map it, apply caps, deliver the client JWT. |
| Later sign-in or app start | The mapping exists | Reissue with the stored `client_id` and deliver the new client JWT. |
| The client JWT nears expiry | The SDK refreshes it; the app saves the new token | None. |
| The credential is rejected | The SDK reports an auth logout; the app stops | On the next sign-in, reissue, or provision if the reissue answers `Client does not exist.` |
| 30 days without connecting | The client is deactivated and stops counting toward the limit | The next reissue answers `Client does not exist.`: drop the mapping, provision a new client, re-apply caps. |
| Monthly cap reached | `capped` with `monthly` until 00:00 UTC on the first | Raise or clear the monthly cap, or wait for the month. |
| Running total reached | `capped` with `total`; no automatic reset | Raise or clear the cap, or send `reset_total`. |
| Pause | A cap of `0` | Set the cap back. |
| The network is at its client limit | Provisioning answers `Client limit exceeded.` | An [Embed plan](https://ur.io/services), or remove clients you no longer need. |
| User deleted | — | Remove each installation's client and drop the mappings. |

## The token server

The Go token server in `go/embed/server/` is a minimal backend the embed apps call over HTTP to obtain their client JWT: the shape of a real service's sign-in endpoint. It uses only the Go standard library and no URnetwork SDK. It authenticates the app's demo session, provisions or reissues the installation's client, applies default caps to new clients and returns the client JWT. The same binary also runs the [backend commands](#backend-tools) against its own map.

### Configuration

| Setting | Requirement |
| --- | --- |
| `URNETWORK_ROOT_JWT` | Required root credential, an API key or a network JWT. |
| `URNETWORK_CLIENT_MAP` | Required absolute filename of the token server's own map, in an existing private, service-owned directory. Do not share it with the language backend tools or the integration allocators. |
| `URNETWORK_DEMO_SESSIONS` | Required absolute filename of the private demo session file below. |
| `URNETWORK_API_URL` | Optional HTTPS origin, default `https://api.bringyour.com`, with the [allocators'](INTEGRATION_CONTRACT.md#runnable-backend-allocators) rules: no credentials, path, query or fragment; explicit loopback HTTP only for local mocks. |
| `URNETWORK_TOKEN_SERVER_ADDRESS` | Optional bind address, default `127.0.0.1:8790`. Serve the internet through your own HTTPS front end, never this listener directly. |
| `URNETWORK_MAX_INSTALLATIONS_PER_USER` | Optional, default 5. |
| `URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT`, `URNETWORK_DEFAULT_TOTAL_BYTE_LIMIT` | Optional integers, 0 or more, applied once to each **new** client. Unset means no default. A reissue never re-applies them, so a cap the backend changed later stays changed. |

The demo session file stands in for your service's real authentication; your service maps its own session (a cookie, an OAuth access token) to its service user ID instead. It is one JSON object, private like the map (0600 in a 0700 directory on POSIX):

```json
{
  "version": 1,
  "sessions": {
    "<random token, at least 32 characters>": "alice"
  }
}
```

Tokens are unique. Service user IDs match `[A-Za-z0-9][A-Za-z0-9_.@-]{0,72}`: no colon, so that `user:<service-user-id>:<installation-id>` splits one way and stays within the map key pattern. Compare presented tokens in constant time. A file that does not parse, has another version, a token shorter than 32 characters or an invalid user ID is a configuration error.

### HTTP contract

```http
POST /urnetwork/client-token
Authorization: Bearer <demo session token>
Content-Type: application/json

{"installation_id": "<the installation's instance-id>"}
```

A success answers 200 with:

```json
{
  "client_id": "11111111-1111-1111-1111-111111111111",
  "by_client_jwt": "<the installation's scoped client JWT>",
  "data_cap": {"client_id": "11111111-1111-1111-1111-111111111111", "monthly_byte_limit": 10000000000, "...": 0}
}
```

`data_cap` is the client's [cap object](#the-cap-object), read with the root credential, or `null` when that read fails or the server has no cap routes yet; it never blocks the token. Every answer carries `Cache-Control: no-store`. An error answers with `{"error": {"code": "<code>", "message": "<text>"}}`:

| Status | `code` | When |
| --- | --- | --- |
| 400 | `invalid_request` | Not JSON, a body over 4 KiB, or an `installation_id` that is not a lowercase UUID. |
| 401 | `unauthorized` | A missing or unknown demo session token. |
| 409 | `installation_limit` | The user already has the maximum number of installations. |
| 409 | `client_limit` | URnetwork refused a new client with `error.client_limit_exceeded` or `error.upgrade_required`. The message points to the [Services page](https://ur.io/services). |
| 502 | `upstream` | The URnetwork API failed, answered something invalid, or the default caps could not be applied. |
| 503 | `busy` | Another process holds the map lock; retry. |

Other methods answer 405 and other paths 404, in the same error shape. There is no browser CORS support: the callers are native apps.

### Behavior

1. Authenticate the demo session and validate `installation_id`. The key is `user:<service-user-id>:<installation-id>`.
2. Take the map lock: an in-process mutex and the [allocators'](INTEGRATION_CONTRACT.md#runnable-backend-allocators) exclusive `<map>.lock` directory, held through the remote calls and the map update.
3. A mapped key is reissued. If the reissue answers `Client does not exist.`, remove the mapping and continue as a new key.
4. A new key first checks the installation limit: the number of mapped keys beginning `user:<service-user-id>:`. Then it provisions a new client (`"description": "embed installation"`, `"device_spec": "urnetwork-examples/embed-token-server"`), validates the answer, and saves the mapping.
5. When default caps are configured, a new client's key is saved in the map's `pending_caps` together with the mapping, then the caps are applied with `POST /network/client-data-cap` (only the configured fields), and the key leaves `pending_caps` once that succeeds. If applying fails, the answer is 502 and no token is returned. A later request for a key that is still in `pending_caps` applies the default caps before it returns a token. A key never stays uncapped without a record.
6. Read the cap object, release the lock and answer.

The token server's map is the allocators' map with one more optional field:

```json
{
  "version": 1,
  "clients": {"user:alice:22222222-2222-2222-2222-222222222222": "11111111-1111-1111-1111-111111111111"},
  "pending_caps": []
}
```

It never answers with, prints or logs the root credential, a session token or a client JWT other than the one `by_client_jwt` returns; its log lines carry the route, the status and the latency, not keys or IDs.

### Self-test

`token-server --self-test` needs no credentials and no network. Against a mock API it checks: session authentication (an unknown token is 401, a short token or a bad user ID is a configuration error); the installation ID and body rules; a new key versus a reissue, with the right wire fields (`client_id` only on a reissue, never `source_client_id`); the `Client does not exist.` re-provision; the installation limit; `client_limit` for both refusal flags; default caps applied once to a new client and never on a reissue, `pending_caps` set before and cleared after, and retried on the next request when applying failed; `data_cap` as `null` when the cap read fails; `Cache-Control: no-store`; the 404, 405 and 503 answers; private map and session file permissions; and that no answer or output contains the root credential.

## Backend tools

Every console language has a backend tool in `<language>/embed/server/`, and the Go token server has the same commands. Each extends the language's [integration allocator](INTEGRATION_CONTRACT.md#runnable-backend-allocators): the same settings (`URNETWORK_ROOT_JWT`, `URNETWORK_CLIENT_MAP`, optional `URNETWORK_API_URL`), map format, key pattern, lock and response checks.

| Command | Does |
| --- | --- |
| `provision <key> <client-jwt-file>` | Reissues the key's client, or provisions a new one; on `Client does not exist.` it drops the mapping and provisions a new client. It writes the client JWT to the named file (owner-only, replaced atomically), never prints it, and prints `{"client_id": "..."}`. |
| `cap <key> [--monthly <bytes>\|--monthly null] [--total <bytes>\|--total null] [--reset-total]` | Posts only the given fields to `POST /network/client-data-cap` and prints the cap object. At least one option; byte counts are decimal integers from 0 to 9223372036854775807, with no units. |
| `usage <key>` | Prints the key's cap object, read with the root credential. |
| `usage-all` | Pages through `GET /network/client-data-caps` with `limit=1000` and prints one cap object per line, stopping at a `null` cursor or a repeated one. |
| `remove <key>` | Removes the key's client with `POST /network/remove-client`, then the mapping (also when the answer is `Client does not exist.`), and prints `{"removed": "<client_id>"}`. |
| `--self-test` | The credential-free self-test below. |

`<key>` matches the allocator pattern, so `user:alice` and `user:alice:22222222-2222-2222-2222-222222222222` both work. The language tools accept a map with only `version` and `clients` and refuse one with other fields, such as the token server's `pending_caps`, so that two tools never rewrite each other's map. The description they send is `embed client` and the device spec `urnetwork-examples/<language>-embed-server`.

A refusal for either client limit flag prints `client limit reached: your network is at its client limit; see https://ur.io/services` on stderr. The tools exit **0** on success, **78** for a configuration or credential problem (missing settings, an invalid key or map, the root credential refused, the client limit), and **1** for any other failure, with one stderr line that never contains a secret.

The self-test needs no credentials and no network. It checks the allocator rules (new versus reissue, key and argument rejection, private map round trip, response and claim checks), the `Client does not exist.` re-provision, the client JWT file's private permissions and that the token never reaches stdout or stderr, the merge request bodies (an omitted option is absent from the JSON, `null` is JSON `null`, byte counts are integers, `reset_total` appears only when given), the cap object parsing, the `usage-all` paging and its stop on a repeated cursor, `remove` dropping the mapping for both answers, the refusal of a map with unknown fields, and the exit codes.

## Packaging and embedding the SDK

**In-app traffic only.** The embedded Device routes only the traffic the app sends through it: a socket opened on the Device, or an HTTP client pointed at the [loopback proxy](NETWORK_EXAMPLES.md#shared-interpretation). It does not use the operating system's VPN APIs, so the app needs no Android `VpnService` and no Apple Network Extension VPN entitlement, and Google Play's VpnService declaration does not apply. The app's privacy disclosures still apply: it routes some of its users' traffic through URnetwork, and its privacy policy, App Store privacy details and Play data safety form must say so. The SDK ships no Apple privacy manifest (`PrivacyInfo.xcprivacy`); each embedding app owns its disclosures.

**License.** The SDK (urnetwork/sdk) and connect are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), a file-level copyleft. The embedding app's own code can stay proprietary; changes to the SDK's own source files must be shared under the MPL. Publish the SDK's licenses and data attributions with the app: `GetLicenses(app)` (C ABI `urnet_get_licenses`) returns them for the app kind (`android`, `apple`, `windows`, `linux`, `web` or `extension`) with no network.

| Platform | How the SDK is added and pinned | Binding | Native runtime |
| --- | --- | --- | --- |
| Go | `go get github.com/urnetwork/sdk/v2026@<version>`; the module tag is the pin | The Go SDK, in process | Pure Go; every desktop target cross-compiles from any host. |
| C | vcpkg or Conan `urnetwork-sdk`, pinned by port or recipe version | The C ABI, `urnetwork_sdk.h` | Ship the URnetworkSdk shared library with the app. |
| C++ | The same package; `urnetwork_sdk.hpp` (C++17 and nlohmann/json) | The C ABI | As C. |
| C# | NuGet `URnetwork.SDK` (`--version <version>`) | P/Invoke over the C ABI | The package carries the runtime for each RID. |
| Java | Maven `io.ur:urnetwork-sdk:<version>` | JNA over the C ABI | The JAR bundles the runtime. |
| Kotlin | The same JAR, `io.ur:urnetwork-sdk:<version>` | JNA over the C ABI | As Java. |
| Python | `pip install urnetwork-sdk==<version>`; `import urnetwork` | ctypes over the C ABI | Platform wheels carry the library. |
| Ruby | `gem install urnetwork-sdk -v <version>` | FFI over the C ABI | Platform gems carry the runtime. |
| Rust | `cargo add urnetwork-sdk@<version>` | The C ABI through the crate | The crate embeds a checksum-verified runtime at build time. |
| Swift | The C ABI header and library through a SwiftPM C module on macOS, Linux and Windows; Apple app targets may use the gomobile XCFramework package instead | The C ABI, or gomobile on Apple | Ship the library; the XCFramework carries its own. |
| JavaScript, TypeScript | The [native companion](javascript/integration/companion/README.md) in [embed mode](#javascript-and-typescript), a Go binary built with the Go SDK and started as a child process | A side-service process | Ship one companion binary per OS and architecture. |
| Electron | The same companion, started by the main process | A side-service process | `bin/<platform>-<arch>/` next to the app. |
| Tauri | The `urnetwork-sdk` crate in the Rust core, in process | The C ABI through the crate | As Rust. |
| Android | `implementation("io.ur:urnetwork-sdk-android:<version>")`, package `com.bringyour.sdk` | gomobile | The AAR carries arm64-v8a, armeabi-v7a and x86_64 libraries. |

Some package coordinates are awaiting their first publication; each [installation guide](README.md) gives the local build path.

**Secrets at rest.** The examples keep `client.jwt` in the private [installation state](#installation-state), protected by the operating system's user isolation. A production app may also encrypt it with the platform's secret store: the Keychain on Apple platforms, the Android Keystore, DPAPI on Windows, the Secret Service (libsecret) on Linux, or Electron's `safeStorage`. Either way the client JWT is a bearer secret: never print, log or back it up.

## Installation state

Each installation keeps its state in one private directory with the [provider contract's rules](PROVIDER_CONTRACT.md#installation-state): on POSIX the directory is mode 0700 and its files 0600, a directory or file that group or others can access is refused, a symlinked file is refused, and files are replaced atomically (a private temporary file in the same directory, synced, then renamed over the old one). On Windows keep it under `%LOCALAPPDATA%`. Console examples read its absolute path from `URNETWORK_EMBED_STATE_DIR`; GUI and Android apps use a private `embed` directory in their application data.

| File | Written by | Content |
| --- | --- | --- |
| `client.jwt` | The token fetch, the backend tool or the developer; the app on refresh | The scoped client JWT; surrounding whitespace is ignored. It must carry a `client_id` claim (a network JWT has none). Never print or log it. |
| `instance-id` | The app, on first run | One UUID, kept for the life of the installation. |
| `token-server.json` | GUI and Android apps | `{"url": "...", "session": "..."}`: the token server origin and the demo session token. |
| `logs/` | The SDK | The SDK's bounded log files, as in the [provider contract](PROVIDER_CONTRACT.md#app-lifecycle). |

The state directory works with the Sockets and Messages examples: `URNETWORK_CLIENT_JWT` is the content of `client.jwt` and `URNETWORK_INSTANCE_ID` the content of `instance-id`. Run one program at a time with one identity.

## Obtaining the client JWT

Each example has one named function, such as `fetchClientJwt`, that obtains the client JWT, so the pattern is easy to find and to replace with your own sign-in:

- **Token server.** When a token server is configured — console examples read `URNETWORK_TOKEN_SERVER_URL` and `URNETWORK_DEMO_SESSION`, GUI and Android apps read `token-server.json` — the app posts its `instance-id` to `POST /urnetwork/client-token` with the demo session as the bearer token, checks that `by_client_jwt` carries a `client_id` claim equal to `client_id`, and saves it as `client.jwt`. The URL is an HTTPS origin, or explicit loopback HTTP (`localhost`, `127.0.0.1`, `[::1]`) for local testing. It fetches on every start, so a start reissues the client.
- **Otherwise** the app uses the `client.jwt` already in its state, as written by a [backend tool](#backend-tools)'s `provision` or by the developer.

| Token server answer | Console exit | GUI and Android |
| --- | --- | --- |
| 200 | — | — |
| 401 `unauthorized`, 409 `installation_limit` or `client_limit` | 78 | `signed out`, with the error's message |
| Unreachable, 5xx, or an invalid answer | 1 | `stopped`, with the error's message |

## App lifecycle

1. Load the installation state; create `instance-id` on first run.
2. Obtain the client JWT. A missing or invalid JWT, or a network JWT, is a configuration error (exit 78).
3. Point the SDK's log files at `logs/` with `SetLogDir` (C ABI `urnet_set_log_dir`).
4. Create a network space manager without storage, the `ur.network`/`main` network space with migration host `bringyour.com`, and set the space API's JWT to `client.jwt`, as the [integration helpers](INTEGRATION_CONTRACT.md#app-startup-and-cleanup) do.
5. Create the local device with `NewDeviceLocalWithDefaults(space, clientJwt, description, deviceSpec, appVersion, instanceId, false)` and a description and spec that name the example, such as `Go embed example` and `urnetwork-examples/go-embed`. Leave the provide mode at its default: an embed app does not provide.
6. Add the listeners: JWT refresh (save `client.jwt`), auth logout (stop; console exit 78, GUI `signed out`), and contract status change (re-read the caps within 5 seconds). Window status and client limit status listeners are optional; the status read covers them.
7. Set the connect location to best available, the destination of the app's own traffic.
8. Read the caps with the client JWT (`GET /network/client-data-cap` at `URNETWORK_API_URL`, default `https://api.bringyour.com`) at start and every 5 minutes. The token server's `data_cap` may serve as the first reading.
9. Read the status every second and show it.
10. To stop: close the subscriptions, close the device, then close the manager, and release native handles where the binding has them.

| Operation | Go SDK and gomobile | C ABI |
| --- | --- | --- |
| Logs | `SetLogDir` | `urnet_set_log_dir` |
| Manager and space | `NewNetworkSpaceManagerNoStorage`, `UpdateNetworkSpaceValues`, `GetApi().SetByJwt` | `urnet_new_network_space_manager_no_storage`, `urnet_network_space_manager_update_network_space_values`, `urnet_network_space_get_api`, `urnet_api_set_by_jwt` |
| Device | `NewDeviceLocalWithDefaults(…, instanceId, false)`; gomobile `Sdk.newDeviceLocalWithDefaults` (Java, Kotlin), `SdkNewDeviceLocalWithDefaults` (Swift) | `urnet_new_device_local_with_defaults` |
| Destination | `SetConnectLocation(&ConnectLocation{ConnectLocationId: &ConnectLocationId{BestAvailable: true}})` | `urnet_device_set_connect_location(device, "{\"connect_location_id\":{\"best_available\":true}}")` |
| Connection | `GetWindowStatus`; optional `AddWindowStatusChangeListener` | `urnet_device_get_window_status` (JSON with the Go field names, or NULL before the window exists; free with `urnet_free_string`); optional `urnet_device_add_window_status_change_listener` |
| Client limit | `GetClientLimitStatus` | `urnet_device_get_client_limit_status` (JSON; NULL reads as no limit) |
| Contract status | `AddContractStatusChangeListener` | `urnet_device_add_contract_status_change_listener` |
| Credential | `AddJwtRefreshListener`, `AddAuthLogoutListener` | `urnet_device_add_jwt_refresh_listener`, `urnet_device_add_auth_logout_listener` |
| Licenses | `GetLicenses(app)` | `urnet_get_licenses` (JSON) |
| Close | `Sub.Close`, `Close` | `urnet_sub_close`, `urnet_device_close`, `urnet_network_space_manager_close`, then `urnet_release` |

C ABI callbacks run on SDK threads: copy what they carry before returning, keep the callback objects alive until their subscription closes, and hand work to the app's own thread. The client limit status is the provider contract's: `Status` is `""` or `"client_limit_exceeded"` (the platform disconnected this client for its network's concurrent client limit, and the SDK holds its platform connections for 15 minutes plus up to 5 minutes of jitter), and `RetryTime` is when the hold ends, in unix milliseconds, 0 when there is none.

## Status

Every example shows these fields with these exact rules. Console examples print the client ID and installation ID at start, then one status line when any field's text changes, and otherwise once a minute; GUI and Android apps show the same fields as labeled values, with the client ID and installation ID.

| Field | Rule |
| --- | --- |
| Status | The first rule that applies: `signed out` (GUI and Android, after an auth logout or a 401 or 409 from the token server, until started again); `stopped` (GUI and Android, before start or after stop); `client limit` while the client limit status is `client_limit_exceeded`, written `client limit, retry at HH:MM UTC` with the [provider contract's rounding](PROVIDER_CONTRACT.md#status) and plain `client limit` when the retry time is 0; `paused` while the latest cap reading is `capped` and the cap that `capped_reason` names is `0`; `data cap reached, resets YYYY-MM-DD HH:MM UTC` while it is `capped` with `monthly`, from `monthly_period_end` in UTC with the seconds rounded **up** to the next whole minute; plain `data cap reached` while it is `capped` with `total`, or with a `monthly_period_end` that does not parse; `connected` while the window status has at least one provider added (`ProviderStateAdded` of 1 or more); otherwise `connecting`. |
| Data this month | `checking` until the first cap reading; `unavailable` if that reading fails (a later failure keeps the last value; a server without the cap routes answers 404, which counts as a failure); `no cap` when `monthly_byte_limit` is `null`; otherwise `<used> of <limit>` from `monthly_used_byte_count` and `monthly_byte_limit`. |
| Data total | The same rule with `total_used_byte_count` and `total_byte_limit`. |

A field without a cap shows `no cap`, never a used count: a server need not report usage for an uncapped client. Data amounts use decimal units, because data plans and the Embed plan's monthly data budget are sold in them: below 1000 `N B`, otherwise `kB`, `MB`, `GB`, `TB`, `PB`, `EB` in powers of 1000, with one decimal, moving to the next unit when the rounded value reaches 1000.0. Round to the nearest tenth with ties to even, as Go's `%.1f` does: an exact half such as 1250 bytes (1.25 kB) shows `1.2 kB` and 1750 bytes shows `1.8 kB`. Formatters that round ties up (JavaScript `toFixed`, Java and Kotlin `String.format`) need an explicit tie rule. The console status line is:

```text
status: <status> | data this month: <monthly> | data total: <total>
```

Golden vectors for self-tests:

| Input | Text |
| --- | --- |
| 0, 999, 1000, 999949 bytes | `0 B`, `999 B`, `1.0 kB`, `999.9 kB` |
| 1250, 1750 bytes (exact halves, ties to even) | `1.2 kB`, `1.8 kB` |
| 999999 bytes (rounds to 1000.0 kB, next unit) | `1.0 MB` |
| 1234567890, 5000000000, 10000000000, 3000000000000 bytes | `1.2 GB`, `5.0 GB`, `10.0 GB`, `3.0 TB` |
| `monthly_period_end` `2026-11-01T00:00:00Z` | `resets 2026-11-01 00:00 UTC` |
| `monthly_period_end` `2026-10-31T23:59:00.001Z` (rounds up) | `resets 2026-11-01 00:00 UTC` |
| `monthly_period_end` `2026-10-31T19:00:00-05:00` (to UTC) | `resets 2026-11-01 00:00 UTC` |
| client limit, retry 1791313500000 (2026-10-06 19:05:00.000 UTC) | `client limit, retry at 19:05 UTC` |
| client limit, retry 1791313440001 (19:04:00.001 UTC, rounds up) | `client limit, retry at 19:05 UTC` |
| client limit, retry 0 | `client limit` |
| connecting, caps not read yet | `status: connecting \| data this month: checking \| data total: checking` |
| connected, monthly 1234567890 of 5000000000, no total cap | `status: connected \| data this month: 1.2 GB of 5.0 GB \| data total: no cap` |
| connected, the first cap reading failed | `status: connected \| data this month: unavailable \| data total: unavailable` |
| capped `monthly`, 5000000000 of 5000000000, ends `2026-11-01T00:00:00Z` | `status: data cap reached, resets 2026-11-01 00:00 UTC \| data this month: 5.0 GB of 5.0 GB \| data total: no cap` |
| capped `total`, 10000000000 of 10000000000, no monthly cap | `status: data cap reached \| data this month: no cap \| data total: 10.0 GB of 10.0 GB` |
| capped `monthly`, monthly limit 0, used 0 | `status: paused \| data this month: 0 B of 0 B \| data total: no cap` |
| client limit retrying at 1791313500000, monthly 0 of 5000000000 | `status: client limit, retry at 19:05 UTC \| data this month: 0 B of 5.0 GB \| data total: no cap` |

Status rule vectors, for the order of the rules (GUI and Android show the first two; console examples never print them):

| Started | Signed out | Client limit status | Latest cap reading | Providers added | Status |
| --- | --- | --- | --- | --- | --- |
| no | no | — | — | — | `stopped` |
| no | yes | — | — | — | `signed out` |
| yes | no | `client_limit_exceeded`, retry 1791313500000 | capped `monthly`, limit 0 | 3 | `client limit, retry at 19:05 UTC` |
| yes | no | `""` | capped `monthly`, monthly limit 0 | 3 | `paused` |
| yes | no | `""` | capped `total`, total limit 0, monthly limit 5000000000 | 3 | `paused` |
| yes | no | `""` | capped `monthly`, limit 5000000000, ends `2026-11-01T00:00:00Z` | 3 | `data cap reached, resets 2026-11-01 00:00 UTC` |
| yes | no | `""` | capped `total`, limit 10000000000 | 0 | `data cap reached` |
| yes | no | `""` | not capped | 1 | `connected` |
| yes | no | `""` | checking | 0 | `connecting` |

## Exit codes

Console examples exit with **0** after a requested stop (Ctrl-C, SIGTERM), **78** for a configuration or credential problem that a restart does not fix (missing or invalid state, a network JWT, an auth logout, or a token server answer of 401 or 409), and **1** for any other failure. An embedded Device lives with its app, so the embed examples have no background templates.

## Self-test

Every embed app has a self-test that needs no credentials and no network and creates no device. Console examples run it with `--self-test` and print a single passed line; GUI and Android samples run it as their unit tests. Where the language allows, it does not load the native SDK runtime. It checks:

- the byte, reset time, client limit text and status line vectors above, and the status rules with the rule vectors (`client limit` before `paused`, `paused` before `data cap reached`, the cap that `capped_reason` names deciding `paused`);
- the data fields: `checking`, `unavailable`, a later failure keeping the last value, `no cap` for a `null` limit even with a used count, and `<used> of <limit>`;
- the cap object parsing: `null` or absent limits, `capped` and `capped_reason`, an unknown `capped_reason` read as capped without a reset time;
- the JWT `client_id` claim: accepted for a client JWT, refused for a network JWT, a malformed token and an invalid UUID;
- the token fetch against a stand-in server: the request (the bearer session, `installation_id` equal to `instance-id`), saving `client.jwt` atomically, a `client_id` that does not match the claim refused, and the answers mapped to the exit codes and states above;
- the state directory: private permissions enforced on POSIX, atomic replacement, `instance-id` created once and reused, a symlinked file refused;
- the configuration errors (missing or relative state directory, no token server and no `client.jwt`) and the usage exit code 78.

## Next: traffic through the Device

The embed example ends where the app's own traffic begins. Use the same Device:

- **Sockets** route TCP, UDP and HTTP clients through it. Libraries that need an operating system socket use the loopback proxy; the [networking matrix](NETWORK_EXAMPLES.md) maps each language's HTTP stack to its adapter.
- **Messages** exchange the [URMS](MESSAGES_PROTOCOL.md) text and ACK protocol with other clients of your network.

Every embed client is a top-level client, so it appears in your network's peer list (`GET /network/peers` and the peer change stream) while the network has 100 or fewer recently active top-level clients; the Messages examples depend on that list, and your app decides what of it to show.

## README template

Each `<platform>/embed/README.md` follows the [Go embed README](go/embed/README.md):

1. Title `# <Platform> embed` and the folder's navigation line with an `Embed` entry.
2. One paragraph on what the app does, linking this contract; in-app traffic only.
3. Files: a table of the example's files and their roles, including the backend tool.
4. Build and self-test: exact commands for Windows (PowerShell), macOS and Linux, and the SDK version or local build it needs.
5. Backend: the [root credential](#the-root-credential) (an API key for production), provisioning and caps with the [token server](#the-token-server) or the language's [backend tool](#backend-tools), with curl equivalents; pausing and removing a client; the [client limit](#backend-provision-clients) and the Embed plan.
6. Package: how the SDK and its native runtime ship with the app on each OS, the license and `GetLicenses`, and where `client.jwt` lives.
7. Configure the installation: the state directory on each OS and the token server settings, or a `client.jwt` from `provision`.
8. Run: per-OS commands, a sample status line, the field table, the `client limit` and data cap states in a sentence each, and the exit codes.
9. Next: the language's [Sockets](README.md) and Messages guides, with the state directory exported as `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`.

## Platform notes

### Electron

Electron cannot run the SDK's native Device in the renderer. The main process obtains the client JWT from the token server, keeps the state in a private `embed` directory inside `app.getPath('userData')`, and starts the [native companion](#javascript-and-typescript) in embed mode as a child process with that directory, a random per-launch companion token, a free loopback port and `URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE=1`. It reads `/embed-status` every second, and the caps every 5 minutes and within 5 seconds of a change in its `ContractStatus`; the renderer gets the fields over IPC through a preload script with context isolation, and the client JWT and the demo session never reach the renderer after it saves them. The window has the token server URL and demo session fields, Start, Stop, the status fields, the client and installation IDs, and the licenses that `/embed-status` returns. A companion exit with code 78 shows `signed out`. Package the companion for each OS and architecture in `bin/<platform>-<arch>/`; the app needs no URnetwork JavaScript package.

### Tauri

Tauri runs the Device in its Rust core with the `urnetwork-sdk` crate through the C ABI, in process: no sidecar. The embed core (state, token fetch, status rules and the session) is the library of the Rust embed example in `rust/embed`, so the console app and this app share one tested core. Tauri commands start and stop; a status event feeds the web view. Keep the state in a private `embed` directory inside the local app data directory, named after the app's identifier.

### Android

The Android app is a Kotlin app on the gomobile AAR. The Device lives in an application-scoped holder, started from an explicit user action and closed on Stop or when the app signs out; in-app traffic in the foreground needs no foreground service and no VpnService, and the app needs only the `INTERNET` permission. Keep the state in a private `embed` directory in the no-backup files directory, never in the APK or backups. Your app's sign-in provides the session; for local testing, debug builds import the token server URL and the demo session over adb, and release builds have no import path. A debug network security configuration allows cleartext HTTP only to the loopback token server (`10.0.2.2` from the emulator, or `adb reverse tcp:8790 tcp:8790` from a device); release builds use HTTPS.

### JavaScript and TypeScript

The WebAssembly SDK cannot run a local Device, so the [native companion](javascript/integration/companion/README.md) owns it. Its **embed mode** (`URNETWORK_COMPANION_EMBED=1`, which cannot be combined with `URNETWORK_COMPANION_PROVIDE`) is new in this contract:

- It reads `client.jwt` and `instance-id` from `URNETWORK_EMBED_STATE_DIR` with the [installation state](#installation-state) rules, rewrites `client.jwt` when the SDK refreshes it, and points the SDK's logs at `logs/` there.
- It creates its device as the native examples do (description `JavaScript embed companion`, spec `urnetwork-examples/node-embed-companion`), sets the connect location to best available, and waits for the platform without a time limit, because a client limit hold lasts 15 to 20 minutes.
- It serves `GET /embed-status` from the start, authorized by `URNETWORK_COMPANION_TOKEN` like its other routes: `{"ClientId": "...", "InstanceId": "...", "WindowStatus": {...} or null, "ClientLimitStatus": {"Status": "", "RetryTime": 0}, "ContractStatus": {...} or null, "Licenses": {...}}`, with the Go field names and `Licenses` from `GetLicenses` for the host OS's app kind.
- It serves `/device-rpc` as the messaging companion does, for what a browser-state DeviceRemote gets.
- An auth logout stops the device and exits with code 78. It honors `URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE` like provider mode.

The JavaScript and TypeScript embed apps are Node 24 programs: they obtain the client JWT, then start the companion as a child process (`bin/<platform>-<arch>/ur-companion`, or `URNETWORK_COMPANION_PATH`), with a random per-launch token and a free loopback port, read `/embed-status` every second and the caps with `fetch` every 5 minutes and within 5 seconds of a change in its `ContractStatus`, and print the status line. Messages continue on the companion as in the [Messages examples](javascript/messages/README.md); the JavaScript and TypeScript [Sockets examples](javascript/socket/README.md) use a hosted Device today.

### Swift

The cross-platform Swift embed app uses the C ABI header and library through a SwiftPM C module on macOS, Linux and Windows, as the Swift provider does. An app for Apple platforms can use the gomobile XCFramework, which exposes the same Go API (`SdkNewDeviceLocalWithDefaults`).

## Release availability

The embed apps need no SDK API beyond what ships today; they read the caps over HTTP. They need an SDK release from the first release after sdk `c638dfa8`, which adds the client limit status. The companion's embed mode and `/embed-status` route are example code in this repository, built against that SDK.

The data-cap routes (`POST` and `GET /network/client-data-cap`, `GET /network/client-data-caps`) and the per-network client limit that an Embed plan raises ship with the server release that adds them; they follow the API in connect's `api/bringyour.yml`. On an older server the cap routes answer 404: the apps show `unavailable` for the data fields, and the backend tools report the failure.

These details were checked on 2026-10-08 against the workspace sources of the SDK (device creation, the window, contract and client limit status, the licenses and the C ABI exports), the server (API-key sessions, client removal, the active-client check on connect, the resident per client and the top-level client cap) and the examples' integration and provider contracts.
