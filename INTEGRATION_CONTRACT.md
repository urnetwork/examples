# Service integration and client identity

Every example app receives a scoped client JWT through `URNETWORK_CLIENT_JWT`. The JWT must contain its assigned `client_id` claim. The service backend alone holds `URNETWORK_ROOT_JWT`, authenticates service users and provisions their clients. The app never receives the root JWT.

This contract applies to [all languages](README.md). Socket and messaging programs share the same client bootstrap. Messages additionally require a local Device with peer discovery and subprotocol support; see the [messaging protocol](MESSAGES_PROTOCOL.md).

## Identity and storage

| Value | Owner | Purpose |
| --- | --- | --- |
| Authenticated service user ID | Your backend | Establishes which user may obtain or renew which URnetwork client. |
| `URNETWORK_ROOT_JWT` | Your backend secret store | Authorizes provisioning in the service's URnetwork network. Never ship it in an app, browser bundle, hosted configuration or repository. |
| URnetwork `client_id` | Backend mapping and client JWT | Identifies the client's network presence and the destination/source for peer messages. Assign distinct clients to distinct service users. |
| `URNETWORK_CLIENT_JWT` | The authorized user's app | Scoped credential returned by provisioning. Pass it to Device creation and client API setup. Treat it as a bearer secret. |
| `URNETWORK_INSTANCE_ID` | Installation storage | UUID generated once for this installation and reused across launches. It does not create or replace a `client_id`. |

Store a durable mapping from authenticated service user to the issued URnetwork `client_id`. If the service supports multiple installations per user, make installation identity part of that mapping and provision distinct concurrently running clients as needed. Serialize first provisioning for a mapping key so concurrent requests do not create accidental duplicates.

Parsing a JWT locally can check that a `client_id` is present and display the assigned ID; it does not verify the token's signature or authorize a user. The provisioning endpoint must derive ownership from the authenticated service session and its stored mapping. Never accept an arbitrary user-provided client ID as authority to reissue a credential.

## Backend provisioning

After authenticating the user, the backend sends `POST /network/auth-client` to the configured URnetwork API over HTTPS, with `Authorization: Bearer <URNETWORK_ROOT_JWT>` and `Content-Type: application/json`.

For a **new top-level messaging client**, send only the descriptive fields needed here:

```json
{
  "description": "Example service user installation",
  "device_spec": "urnetwork-examples/python"
}
```

Omit both `client_id` and `source_client_id`. The API assigns a new client ID. `source_client_id` requests a child relationship and is not the way to create another visible top-level messaging peer. Hosted proxy devices are also excluded from the peer list.

The response contains the assigned `client_id` and scoped `by_client_jwt`; an `error` object can describe a provisioning failure. Check the HTTP result, the API error and both required success fields before storing the mapping or returning credentials. Do not log the token or include it in ordinary diagnostics.

For **reissue**, look up the client ID from the backend's authenticated-user mapping and supply that stored `client_id` with the descriptive fields. Continue to omit `source_client_id` for this top-level client. Check that the returned identity matches the mapping. If the user has no authorized mapping, follow the new-client flow instead of trusting an ID supplied by the app.

Return only the scoped JWT and the non-secret configuration the app needs through the authenticated service response. Its `by_client_jwt` value becomes `URNETWORK_CLIENT_JWT`. Keep the root credential on the backend for later provisioning; renewal must preserve the ownership check.

The field names and endpoint are defined in the [SDK API source](https://github.com/urnetwork/sdk/blob/main/api.go). The examples use `description` on the wire; the generated SDK property may be named `DeviceDescription` to avoid the Apple `description` property collision. These details were checked against the workspace source on 2026-09-15.

## Runnable backend allocators

Each language's [Integration guide](README.md) gives exact commands for its independent `integration/server` executable. These are backend CLI programs: your service authenticates its user first and supplies the allocator's single argument internally. The required argument is `user:<service-user-id>`, for example `user:alice`. It is never a URnetwork client ID or an arbitrary incoming request field. The accepted key pattern is `user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}` with an exact whole-string match. The allocator accepts no extra client-ID argument or option.

| Backend setting | Requirement |
| --- | --- |
| `URNETWORK_ROOT_JWT` | Required root credential from the backend secret store. Never send it to the app. |
| `URNETWORK_CLIENT_MAP` | Required absolute filename in an existing private, service-owned directory. The program creates the map when needed. |
| `URNETWORK_API_URL` | Optional HTTPS origin, default `https://api.bringyour.com`. No credentials, path beyond `/`, query or fragment. The program appends `/network/auth-client`. Explicit `http://localhost`, `http://127.0.0.1` or `http://[::1]`, optionally with a port, is permitted for local tests/mocks. |

The private map has this shape; it stores client IDs, not tokens:

```json
{
  "version": 1,
  "clients": {
    "user:alice": "11111111-1111-1111-1111-111111111111"
  }
}
```

The allocator holds an exclusive `<map-path>.lock` through the remote provisioning call and atomic map replacement. Most implementations use a lock directory; C# uses an exclusively created lock file at the same path. Concurrent invocations using that map fail while it is locked; the service may retry after the current call finishes. This serializes new allocations and prevents two simultaneous calls for one user from creating duplicate top-level clients. Files are written with private permissions (0600), and lock directories with 0700, where the platform supports them. Use a private parent directory, such as mode 0700 on POSIX, and preserve the map across backend restarts. An interrupted process can leave the lock; remove it only after confirming that no allocator still owns it. A remote success followed by a process failure before persistence requires backend reconciliation; the example cannot make the remote API and local file one transaction.

For a new key, the request includes `description` and `device_spec` and omits both identity fields. For a mapped key, only its stored `client_id` is added. The allocator validates the response fields, checks that the returned JWT's `client_id` claim agrees with the response and mapping, and rejects a returned client already owned by another key. Claim parsing is a consistency check; it is not local JWT signature verification.

Successful stdout is a JSON object with `client_id` and `by_client_jwt`. Capture it inside the service and return the scoped credential only through the authenticated backend-to-app response. Do not forward build-tool output or write JWTs to normal logs. Direct executable run commands are provided in each guide. The root JWT is never printed. `--self-test` exercises new/reissue requests, input rejection, mapping round-trips and response parsing without credentials or network; initial dependency installation may still access package registries.

These allocators provision client identities. They do not provision a JavaScript/TypeScript hosted proxy, its RPC URL, signed proxy credential or instance ID; obtain those separately from the hosting service.

## App startup and cleanup

```sh
export URNETWORK_CLIENT_JWT='scoped-jwt-returned-by-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
```

Use the language example's ID-generation mode where available, save that UUID, and export the saved value on subsequent runs. Keep separate storage for separate demo installations. Never use a fresh instance ID on every reconnect to compensate for a shared client credential.

All native client helpers require both environment variables. In particular, the Go helper no longer creates or saves an installation ID automatically. The messages programs have no `--new-id` mode; generate and persist an ID with an installation setup tool such as `uuidgen` on macOS, or use a socket program's documented ID-generation mode.

Create the network-space manager and Device with the scoped JWT, retain their owners while callbacks or sockets can run, and close subscriptions and connections before releasing the Device and manager. The language integration guide identifies its bootstrap implementation and run commands. Persist client state in storage appropriate to the application; the environment variables are the command-line examples' configuration interface.

For JavaScript and TypeScript, a hosted Device requires its real hosted instance ID, WebSocket RPC URL and signed proxy credential as well as the scoped JWT. An arbitrary local UUID cannot identify that remote process. Their [integration guide](javascript/integration/README.md) explains this hosted configuration and the current messaging capability gate.

## Two-terminal messaging demo

Provision two distinct top-level clients in the same URnetwork network, associated with two authorized demo users/installations. Give terminal A client A's scoped JWT and a persisted instance ID A. Give terminal B client B's scoped JWT and a different persisted instance ID B. Merely changing the instance ID while reusing one JWT still addresses the same client identity.

Start a supported [messages program](README.md) in each terminal. Wait for the live peer list to include the other client, select its `client_id`, and query its supported subprotocols before sending. Both sides must advertise subprotocol `4096`. The receiver validates the frame and returns the application ACK described in [URMS v1](MESSAGES_PROTOCOL.md). Different native languages use identical bytes and can participate in the same demo.
