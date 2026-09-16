# JavaScript service integration

[Installation](../README.md) · [Integration](README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md)

Only the authenticated service backend holds `URNETWORK_ROOT_JWT`. The app receives a scoped JWT with an assigned `client_id`, plus the configuration of a hosted Device. Follow the [shared integration contract](../../INTEGRATION_CONTRACT.md) for provisioning and ownership checks.

## Client setup

The reusable bootstrap is [client.mjs](client.mjs), and [main.mjs](main.mjs) is its runnable integration entry point. `clientConfig(process.env, hosted)` requires `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID` and overrides any `byJwt`/`instanceId` values in the JSON file. `openDevice(URNetwork, config)` initializes WASM, waits for hosted RPC connectivity, selects a location and returns a session whose `close()` closes the Device and runtime.

Use Node 24+. Run from `javascript/integration`:

```sh
npm install
npm run self-test
```

The self-test initializes and closes real SDK WASM without credentials. The package selects `@urnetwork/sdk` on the `nightly` tag; if the required APIs are not published, install the matching local package with `npm install /absolute/path/to/urnetwork-sdk-VERSION.tgz` before running these checks.

Create an absolute-path JSON file with the hosted Device settings issued by your hosting service:

```json
{
  "apiUrl": "api.bringyour.com",
  "platformUrl": "connect.bringyour.com",
  "proxyUrl": "your-hosted-device-websocket-url",
  "signedProxyId": "your-hosted-device-HMAC-auth-token"
}
```

```sh
export URNETWORK_DEVICE_CONFIG='/absolute/path/to/device.json'
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='the-actual-hosted-device-instance-uuid'
npm start
```

All three environment variables are required for this entry point. `URNETWORK_INSTANCE_ID` must identify the actual hosted process; use the stable ID supplied by your hosting service. The scoped JWT and signed proxy credential are distinct secrets. Keep the configuration private. The allocator below issues client credentials; it does not create the hosted proxy or supply its WebSocket URL, signed proxy ID or instance ID.

The Node [socket program](../socket/README.md) uses this same environment merge. The [JavaScript browser form](../../javascript/socket/README.md#run-the-browser) instead requires all six JSON fields, including `byJwt` and `instanceId`, because it does not read shell environment variables.

Hosted `DeviceRemote` exposes no subprotocol API, and hosted proxy devices are excluded from the visible peer list. [Messages](../messages/README.md) provides the common codec and an explicit unsupported-runtime gate; real peer messaging requires a runtime with local Device peer/subprotocol support and two distinct clients.

## Backend allocator

The server-only executable is [allocator.mjs](server/allocator.mjs) and [package.json](server/package.json). Node 22+ is required. There are no dependencies to install and no compilation step; the server uses built-in `fetch` and filesystem APIs. Run these commands from `javascript/integration/server`:

```sh
node allocator.mjs --self-test
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
node allocator.mjs user:alice
```

Replace the example state directory with an absolute, service-owned path. `URNETWORK_ROOT_JWT` and `URNETWORK_CLIENT_MAP` are required. `URNETWORK_API_URL` is optional and defaults to the HTTPS origin shown; explicit localhost HTTP is accepted only for a test/mock endpoint. The allocator appends `/network/auth-client` itself.

`user:alice` means the authenticated service user `alice`. The backend constructs this `user:<service-user-id>` key; it is never a URnetwork client ID or an untrusted request field. The allocator accepts exactly one such key, owns the mapping and accepts no caller-selected client ID. A new key omits `client_id` and `source_client_id` from the request; another invocation for the same key reissues only its stored `client_id`.

An exclusive per-map lock covers provisioning and an atomic private map update. The mapping stores client IDs, not JWTs. On success the allocator writes JSON containing `client_id` and `by_client_jwt` to stdout. Capture that result inside the authenticated backend and hand only the scoped credential to the authorized app as `URNETWORK_CLIENT_JWT`; keep tokens out of ordinary logs. See the [shared contract](../../INTEGRATION_CONTRACT.md#runnable-backend-allocators) for locking, key validation and configuration details.
