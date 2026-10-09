# Go token server

[Go embed](../README.md) · [Embed contract](../../../EMBED_CONTRACT.md)

The reference embed backend, for your backend only. One binary, standard library only, no URnetwork SDK: an HTTP **token server** in the shape of your service's sign-in endpoint, which the embed apps call to get their installation's scoped client JWT, and the **backend commands** that provision clients, set their ACL group, set and read their data caps, and remove them. The token server and the commands share one private map, so a client the token server provisioned can be capped and removed from the command line. The Go, Electron, Tauri and Android embed apps use it as their backend; the [embed contract](../../../EMBED_CONTRACT.md#the-token-server) holds the exact rules.

## Files

| File | Purpose |
| --- | --- |
| [main.go](main.go) | Commands, settings and exit codes. |
| [tokenserver.go](tokenserver.go) | `POST /urnetwork/client-token`: authentication, the installation limit, the answers. |
| [provision.go](provision.go) | Issuing a key's client: reissue, re-provision a deactivated client, the default ACL group through `pending_acl` and default caps through `pending_caps`. |
| [commands.go](commands.go) | `provision`, `cap`, `usage`, `usage-all`, `remove` and `acl`. |
| [api.go](api.go) | The URnetwork API calls, made with the root credential. |
| [clientmap.go](clientmap.go) | The private client map and its lock. |
| [sessions.go](sessions.go) | The demo session file. |
| [selftest.go](selftest.go), [main_test.go](main_test.go) | Credential-free self-test against a mock API, also run by `go test`. |

## Build and self-test

Use Go 1.24 or later. From `go/embed/server` on macOS or Linux:

```sh
go build -o token-server .
go test ./...
./token-server --self-test
```

On Windows, in PowerShell, build `token-server.exe` and run `.\token-server.exe --self-test`. The self-test needs no credentials and no network and opens no listener: it runs the HTTP handler in process and the commands against a mock API. It checks the session authentication, the request rules, a new client versus a reissue and their wire fields, the re-provision of a deactivated client, the installation and client limits, the default ACL group and `pending_acl` (applied once, retried after a failure, reported once and the token still answered on a server without ACL groups), the default caps and `pending_caps`, `data_cap` as `null` when the cap read fails, the error answers, the command options and merge bodies, `usage-all` paging, `remove`, `acl`, the unmapped-key and 404 lines, the map and file permissions, the exit codes, and that no answer, output or log line contains the root credential.

## Configuration

| Setting | Requirement |
| --- | --- |
| `URNETWORK_ROOT_JWT` | Required root credential: an [API key](../README.md#backend) for production, or a network JWT. A client JWT is refused. |
| `URNETWORK_CLIENT_MAP` | Required absolute filename of this server's own map, in an existing directory private to its owner (0700 on POSIX). Do not share it with the language backend tools or the integration allocators. |
| `URNETWORK_API_URL` | Optional https origin, default `https://api.bringyour.com`; explicit loopback http only for a local mock. |
| `URNETWORK_DEFAULT_ACL_GROUP` | Optional, `isolated` (the default) or `default`: the [ACL group](../../../EMBED_CONTRACT.md#backend-acl-groups) of each new client, applied by the token server and by `provision`. `isolated` keeps your users out of each other's peer list; an app that uses Messages needs `default`. Other values are a configuration error. |
| `URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT`, `URNETWORK_DEFAULT_TOTAL_BYTE_LIMIT` | Optional byte counts, 0 or more, applied once to each **new** client, by the token server and by `provision`. Unset means no default. A reissue never re-applies them, so a cap you changed later stays changed. |
| `URNETWORK_DEMO_SESSIONS` | Token server only: required absolute filename of the private demo session file. |
| `URNETWORK_TOKEN_SERVER_ADDRESS` | Token server only: optional bind address, default `127.0.0.1:8790`. |
| `URNETWORK_MAX_INSTALLATIONS_PER_USER` | Token server only: optional, default 5. An app chooses its own installation ID, so this bounds how many clients one user can create. |

The demo session file stands in for your service's real authentication; your service maps its own session (a cookie, an OAuth access token) to its service user ID instead. It is one private JSON object (0600 in a 0700 directory on POSIX):

```json
{
  "version": 1,
  "sessions": {
    "<random token, at least 32 characters>": "alice"
  }
}
```

Tokens are at least 32 visible characters and are compared in constant time. Service user IDs match `[A-Za-z0-9][A-Za-z0-9_.@-]{0,72}`, with no colon, so the map key `user:<service-user-id>:<installation-id>` splits one way. Create a token with, for example, `openssl rand -hex 24`.

The map is the allocators' map with two more fields, `pending_acl` and `pending_caps`: the new clients that still owe their default ACL group and their default caps. It stores client IDs, never tokens:

```json
{
  "version": 1,
  "clients": {"user:alice:22222222-2222-2222-2222-222222222222": "11111111-1111-1111-1111-111111111111"},
  "pending_acl": [],
  "pending_caps": []
}
```

Every provision holds an in-process mutex and the exclusive `<map>.lock` directory through its API calls and the map update. A crash can leave the lock directory; remove it only after confirming that no process owns it.

## Run the token server

```sh
./token-server serve
```

It listens on `URNETWORK_TOKEN_SERVER_ADDRESS` and logs one line per request with the route, the status and the latency, never keys, IDs or tokens. Serve the internet through your own HTTPS front end, never this listener directly. Ctrl-C or SIGTERM stops it.

```http
POST /urnetwork/client-token
Authorization: Bearer <demo session token>
Content-Type: application/json

{"installation_id": "<the installation's instance-id, a lowercase UUID>"}
```

A success answers 200 with `{"client_id": "...", "by_client_jwt": "...", "data_cap": <cap object> | null}`: the installation's client, its scoped client JWT and its cap object, or `null` when the cap read fails, which never blocks the token. Every answer carries `Cache-Control: no-store`. An error answers `{"error": {"code": "...", "message": "..."}}`:

| Status | `code` | When |
| --- | --- | --- |
| 400 | `invalid_request` | Not JSON, a body over 4 KiB, or an `installation_id` that is not a lowercase UUID. |
| 401 | `unauthorized` | A missing or unknown demo session. |
| 409 | `installation_limit` | The user already has the most installations allowed. |
| 409 | `client_limit` | URnetwork refused a new client for the network's client limit; an [Embed plan](https://ur.io/services) raises it. |
| 502 | `upstream` | The URnetwork API failed, answered something invalid, or the default ACL group or caps could not be applied. |
| 503 | `busy` | Another process holds the map lock; retry. |

Other paths answer 404 and other methods 405, in the same shape; a local failure such as an invalid map answers 500 `internal`. The apps treat every answer other than 200, 401 and 409 as a failure (exit 1). A mapped installation is reissued; one whose client was deactivated gets a new client. A new installation's mapping and its `pending_acl` and `pending_caps` entries are saved together, then its ACL group and default caps are applied, and when applying fails the answer is 502 with no token: the next request applies them before it returns one. On a server without ACL groups the token server logs that once and answers the token, with the installation still in `pending_acl`. Until the team enables Embed for the network, the token server still answers tokens, with `data_cap` `null`, keeps new installations in `pending_acl` and `pending_caps`, and logs `Embed isn't enabled for this network: new clients' default ACL group and caps stay pending until it is; see https://ur.io/services` once; a request after Embed is enabled applies them. The token server labels its clients with the description `embed installation` and the device spec `urnetwork-examples/embed-token-server`. There is no browser CORS support: the callers are native apps.

## Commands

| Command | Does |
| --- | --- |
| `provision <key> <client-jwt-file>` | Reissues the key's client, or provisions a new one exactly as the token server does, default ACL group and caps included, and writes its client JWT to the file (owner-only, replaced atomically). Prints `{"client_id": "..."}`; never prints the JWT. On a server without ACL groups it exits 1 and writes no client JWT for a new client. |
| `cap <key> [--monthly <bytes>\|--monthly null] [--total <bytes>\|--total null] [--reset-total]` | Posts only the given options to `POST /network/client-data-cap` and prints the cap object. Byte counts are decimal integers from 0 to 9223372036854775807, with no units. |
| `usage <key>` | Prints the key's cap object. |
| `usage-all` | Prints the cap object of every capped client of the network, one per line, paging through `GET /network/client-data-caps`. |
| `remove <key>` | Removes the key's client and its mapping, also when the client is already gone. Prints `{"removed": "<client_id>"}`. |
| `acl <key> default\|isolated` | Sets the key's client's ACL group with `POST /network/client-acl-group` and prints `{"client_id": "...", "acl_group": "..."}`. |
| `status` | Prints the network's Embed state from `GET /network/embed` as one line, `embed enabled: yes \| client limit: 5000 \| active clients: 1234` (`no` when Embed isn't enabled). |

A key is `user:<service-user-id>` or `user:<service-user-id>:<installation-id>`, as the [allocators](../../../INTEGRATION_CONTRACT.md#runnable-backend-allocators) accept. The commands send the description `embed client` and the device spec `urnetwork-examples/go-embed-server`. They exit 0 on success, 78 for a configuration or credential problem (missing settings, an invalid key or map, the root credential refused, the client limit) and 1 for any other failure, with one stderr line that never contains a secret. A client limit refusal prints `client limit reached: your network is at its client limit; see https://ur.io/services`. `cap`, `usage`, `remove` or `acl` for a key without a client exits 78 with `no client is mapped for that key; run provision first`; a held lock exits 1 and names the lock. A server that predates a route exits 1 with `/network/client-data-cap answered 404: the server predates the data-cap routes` (or `/network/client-data-caps`), or `/network/client-acl-group answered 404: the server predates ACL groups`. Until the team enables Embed for the network, `cap`, `usage`, `usage-all` and `acl` exit 78 with `embed not enabled: Embed isn't enabled for this network; see https://ur.io/services`, and `provision` still writes the client JWT, keeps the default ACL group and caps pending and prints `embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services` on stderr. `status` exits 78 when the server refuses it and 1 with `/network/embed answered 404: the server predates Embed enablement` on a server without the route.
