# Rust service integration

[Installation](../README.md) · [Integration](README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md)

The app receives a scoped client JWT from its authenticated service backend. Only the backend holds `URNETWORK_ROOT_JWT` and provisions clients through `POST /network/auth-client`. The [shared integration contract](../../INTEGRATION_CONTRACT.md) defines new-client allocation, authorized reissue and the credential boundary.

## Client setup

The reusable bootstrap is [session.rs](session.rs). Both programs include this module by path. `Session::open_with_connect(false)` opens the messaging Device; `Session::open()` also selects a location for sockets. Stop Device work before the session is dropped.

Use a Rust toolchain supporting edition 2024. The messages crate defaults to SDK `0.0.1-dev.0`; [Messages](../messages/README.md#build-and-check) shows a local crate patch for development before publication. From `rust/integration`, build and check the sibling client program:

```sh
cd ../messages
cargo build
cargo run -- --version
```

The version check needs no client credentials. In that same messages directory, configure the app and print its assigned client identity:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
cargo run -- self
```

The JWT must contain its assigned `client_id`. Generate an installation UUID once, save it in installation storage and reuse it; for example, `uuidgen` generates one on macOS. The program requires both environment variables and does not persist a new UUID for you. Two messaging terminals need distinct client JWTs/client IDs and distinct persisted instance IDs in the same network. Use [Messages](../messages/README.md) for live `watch`/`send` commands and [Sockets](../socket/README.md) for HTTP/TCP/UDP.

## Backend allocator

The server-only executable is [main.rs](server/src/main.rs), [Cargo.toml](server/Cargo.toml) and [Cargo.lock](server/Cargo.lock). A Rust toolchain supporting edition 2024 is required. The independent server crate uses reqwest, serde, serde_json and base64; it does not depend on the URnetwork SDK. Run these commands from `rust/integration/server`:

```sh
cargo build --locked
./target/debug/urnetwork-server-allocator --self-test
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
./target/debug/urnetwork-server-allocator user:alice
```

Replace the example state directory with an absolute, service-owned path. `URNETWORK_ROOT_JWT` and `URNETWORK_CLIENT_MAP` are required. `URNETWORK_API_URL` is optional and defaults to the HTTPS origin shown; explicit localhost HTTP is accepted only for a test/mock endpoint. The allocator appends `/network/auth-client` itself.

`user:alice` means the authenticated service user `alice`. The backend constructs this `user:<service-user-id>` key; it is never a URnetwork client ID or an untrusted request field. The allocator accepts exactly one such key, owns the mapping and accepts no caller-selected client ID. A new key omits `client_id` and `source_client_id` from the request; another invocation for the same key reissues only its stored `client_id`.

An exclusive per-map lock covers provisioning and an atomic private map update. The mapping stores client IDs, not JWTs. On success the allocator writes JSON containing `client_id` and `by_client_jwt` to stdout. Capture that result inside the authenticated backend and hand only the scoped credential to the authorized app as `URNETWORK_CLIENT_JWT`; keep tokens out of ordinary logs. See the [shared contract](../../INTEGRATION_CONTRACT.md#runnable-backend-allocators) for locking, key validation and configuration details.
