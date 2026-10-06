# Swift service integration

[Installation](../README.md) · [Integration](README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md)

The app receives a scoped client JWT from its authenticated service backend. Only the backend holds `URNETWORK_ROOT_JWT` and provisions clients through `POST /network/auth-client`. The [shared integration contract](../../INTEGRATION_CONTRACT.md) defines new-client allocation, authorized reissue and the credential boundary.

## Client setup

The reusable bootstrap is [UrSession.swift](Sources/URExampleIntegration/UrSession.swift) and [Package.swift](Package.swift). The `URExampleIntegration` package is a shared dependency of both programs. Use `try UrSession(connect: false)` for messages or `try UrSession()` for sockets. Call `close()` after Device work ends; the helper closes the manager last.

Swift 5.9+ and macOS 14+ are required for these client executables. Set `URNETWORK_XCFRAMEWORK` to the absolute path of a matching locally built XCFramework, or select a published version with `URNETWORK_SDK_VERSION` after removing the local override. Relative framework paths resolve from this integration package. From `swift/integration`, build and check the sibling client program:

```sh
cd ../messages
export URNETWORK_XCFRAMEWORK='/absolute/path/to/URnetworkSdk.xcframework'
swift build
swift run MessagesExample --version
```

The version check needs no client credentials. In that same messages directory, configure the app and print its assigned client identity:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
swift run MessagesExample self
```

The JWT must contain its assigned `client_id`. Generate an installation UUID once, save it in installation storage and reuse it; for example, `uuidgen` generates one on macOS. The program requires both environment variables and does not persist a new UUID for you. Two messaging terminals need distinct client JWTs/client IDs and distinct persisted instance IDs in the same network. Use [Messages](../messages/README.md) for live `watch`/`send` commands and [Sockets](../socket/README.md) for HTTP/TCP/UDP.

## Backend allocator

The server-only executable is [main.swift](server/Sources/Allocator/main.swift) and [Package.swift](server/Package.swift). Swift 5.9+ and macOS 13+ are required for this Foundation command-line package. It has no SDK or XCFramework dependency. Run these commands from `swift/integration/server`:

```sh
swift build
.build/debug/Allocator --self-test
```

`--self-test` needs no credentials or network; dependency installation and compilation may download build inputs. It checks first allocation versus reissue, invalid service-user/client-ID arguments, private mapping persistence and response parsing.

Configure the backend process and invoke the allocator with a service-user key supplied internally after your service authenticates that user:

```sh
umask 077
mkdir -p /absolute/path/to/private-service-state
chmod 700 /absolute/path/to/private-service-state
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/clients.json'
export URNETWORK_API_URL='https://api.bringyour.com'
export URNETWORK_ROOT_JWT='backend-root-jwt-from-your-secret-store'
.build/debug/Allocator user:alice
```

Replace the example state directory with an absolute, service-owned path. `URNETWORK_ROOT_JWT` and `URNETWORK_CLIENT_MAP` are required. `URNETWORK_API_URL` is optional and defaults to the HTTPS origin shown; explicit localhost HTTP is accepted only for a test/mock endpoint. The allocator appends `/network/auth-client` itself.

`user:alice` means the authenticated service user `alice`. The backend constructs this `user:<service-user-id>` key; it is never a URnetwork client ID or an untrusted request field. The allocator accepts exactly one such key, owns the mapping and accepts no caller-selected client ID. A new key omits `client_id` and `source_client_id` from the request; another invocation for the same key reissues only its stored `client_id`.

An exclusive per-map lock covers provisioning and an atomic private map update. The mapping stores client IDs, not JWTs. On success the allocator writes JSON containing `client_id` and `by_client_jwt` to stdout. Capture that result inside the authenticated backend and hand only the scoped credential to the authorized app as `URNETWORK_CLIENT_JWT`; keep tokens out of ordinary logs. See the [shared contract](../../INTEGRATION_CONTRACT.md#runnable-backend-allocators) for locking, key validation and configuration details.
