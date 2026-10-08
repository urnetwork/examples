# C++ embed

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](README.md)

This console app embeds the URnetwork SDK in your own product on Windows, macOS and Linux, on the SDK's C++ header `urnetwork_sdk.hpp`: a header-only C++17 layer over the C ABI with handle-owning classes, `std::function` listeners and nlohmann/json data types. It obtains its installation's scoped client JWT from your backend, starts a local device with it, and shows the status and the data caps your backend set for the installation. The device carries only the app's own traffic: it is not a device VPN and it does not provide. What the app sends through it continues in the [Sockets](../socket/README.md) and [Messages](../messages/README.md) examples. It follows the [embed contract](../../EMBED_CONTRACT.md) and the [Go reference](../../go/embed/README.md).

## Files

| File | Purpose |
| --- | --- |
| [main.cpp](main.cpp) | Commands, exit codes, stop signals, the SDK's logs and `--licenses`. |
| [session.hpp](session.hpp) | Device lifecycle on the C++ SDK: creation with the client JWT and instance ID, listeners, the best available destination, cap reads, status and close. |
| [fetch.hpp](fetch.hpp) | `fetchClientJwt`, the token fetch to replace with your own sign-in, plus the cap read and the configuration, over a replaceable HTTP function. |
| [http_curl.hpp](http_curl.hpp) | That HTTP function on libcurl. |
| [status.hpp](status.hpp) | Status rules, data fields, decimal byte amounts, reset and retry times, and the cap object. |
| [state.hpp](state.hpp) | The private installation state directory, with base64, UUID and JWT payload decoding. |
| [command.hpp](command.hpp) | The command line and the exit codes. |
| [selftest.hpp](selftest.hpp), [selftest_main.cpp](selftest_main.cpp) | Credential-free self-test: `./embed --self-test`, and `make self-test` without the SDK library or libcurl. |
| [Makefile](Makefile), [CMakeLists.txt](CMakeLists.txt) | Builds for Make and for CMake with the SDK package. |
| [server/embed_server.cpp](server/embed_server.cpp) | The backend tool: `provision`, `cap`, `usage`, `usage-all` and `remove`. |

## Build and self-test

Use a C++17 compiler, nlohmann/json 3.11 or later (`brew install nlohmann-json`, `apt install nlohmann-json3-dev`, or `vcpkg install nlohmann-json`), libcurl, and a native SDK build with `urnet_device_get_client_limit_status` and `urnet_get_licenses`: the first SDK release after sdk `c638dfa8`, or a local build of sdk main. Like the other C++ examples, the Makefile defaults use `../../../sdk/cgo/include` and `../../../sdk/cgo/build/packages/darwin-arm64` (after `make -C sdk/cgo package` in an adjacent sdk checkout) and Homebrew's nlohmann/json; pass other directories for installed packages. libcurl comes from `pkg-config` (Homebrew's `curl` on macOS, `libcurl4-openssl-dev` on Debian and Ubuntu). From `cpp/embed` on macOS or Linux:

```sh
make SDK_INCLUDE=/absolute/path/to/include SDK_LIBDIR=/absolute/path/to/lib JSON_INCLUDE=/absolute/path/to/json/include
make self-test SDK_INCLUDE=/absolute/path/to/include JSON_INCLUDE=/absolute/path/to/json/include
./embed --self-test
./embed --version
```

The Makefile records `SDK_LIBDIR` as the run path. To ship the library beside the app on Linux, build with `SDK_RPATH='$$ORIGIN'` and copy `libURnetworkSdk.so` next to `embed`. The [CMake build](CMakeLists.txt) uses the package's `urnetwork::sdk` target, `nlohmann_json::nlohmann_json` and CMake's `CURL::libcurl`, and registers the self-test with CTest:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH=/absolute/path/to/sdk-prefix
cmake --build build
ctest --test-dir build
./build/embed --self-test
```

On Windows, in PowerShell, with Visual Studio and libcurl from vcpkg:

```powershell
vcpkg install curl:x64-windows nlohmann-json:x64-windows
cmake -S . -B build -DCMAKE_PREFIX_PATH=C:\path\to\urnetwork-sdk -DCMAKE_TOOLCHAIN_FILE=C:\vcpkg\scripts\buildsystems\vcpkg.cmake
cmake --build build --config Release
ctest --test-dir build -C Release
.\build\Release\embed.exe --self-test
.\build\Release\embed.exe --version
```

The CMake build copies `URnetworkSdk.dll` beside `embed.exe`, and vcpkg's toolchain copies libcurl's DLL; keep both there.

`--self-test` needs no credentials and no network and creates no device. It checks the contract's byte, reset time, client limit and status line vectors and the order of the status rules; the data fields (`checking`, `unavailable`, a later failure keeping the last reading, `no cap` for a null limit); the cap object (null and absent limits, `capped` and `capped_reason`, an unknown reason, refused answers); the JWT `client_id` claim; the token fetch against a stand-in server (the request, saving `client.jwt` atomically, a mismatched client refused, and each answer mapped to its exit code); the cap read; the state files (private permissions, atomic replacement, `instance-id` created once, a symlink refused); and the configuration errors. `make self-test` and the CMake `embed-self-test` target run the same checks in a binary that links neither the SDK library nor libcurl. This example was built and its self-tests run on macOS arm64 against a local build of sdk main; the Linux and Windows builds were not run.

The backend tool in [server](server/embed_server.cpp) is a POSIX command-line program on libcurl and nlohmann/json, like the [integration allocator](../integration/server/allocator.cpp) it extends:

```sh
cd server
make
./embed-server --self-test
```

## Backend: provision clients and set data caps

Only your backend holds the root credential. Use an **API key** in production: create one with `POST /account/api-key` (called with a network JWT); it authenticates as your network exactly like the network JWT and is just as powerful, so keep it in your secret store, never on an installation. To rotate it, create a new key, deploy it, then remove the old one with `POST /account/api-key/remove`; remove a leaked key at once ([contract](../../EMBED_CONTRACT.md#the-root-credential)).

Your backend provisions **one client per running installation**: the platform keeps one connection per client, so two installations that share a client keep displacing each other. The key is `user:<service-user-id>:<installation-id>`, where the installation ID is the app's `instance-id`. Your apps can get their token from the [Go token server](../../go/embed/README.md), the reference backend they call over HTTP, or your backend can run this language's tool:

```sh
cd server
umask 077
mkdir -p /absolute/path/to/private-service-state
export URNETWORK_ROOT_JWT='api-key-or-network-jwt-from-your-secret-store'
export URNETWORK_CLIENT_MAP='/absolute/path/to/private-service-state/embed-clients.json'
KEY='user:alice:22222222-2222-2222-2222-222222222222'
./embed-server provision "$KEY" /absolute/path/to/private-service-state/alice.jwt
./embed-server cap "$KEY" --monthly 10000000000
./embed-server usage "$KEY"
./embed-server usage-all
```

`provision` reissues the key's client or provisions a new one, provisions again when the old client was deactivated after 30 days without connecting, writes the client JWT to the file you name with owner-only permissions, never prints it, and prints `{"client_id": "..."}`. Your backend delivers that token to the installation as its `client.jwt`. `cap` posts only the options you give: `--monthly` and `--total` take a byte count or `null` (clear the cap), and `--reset-total` starts a new running total. The monthly cap resets at 00:00 UTC on the first of the month; a cap of `0` pauses the installation. `usage` prints the key's cap object and `usage-all` prints every capped client of your network, one per line. Without the tool, the backend sends the requests itself:

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

To **pause** an installation, set a cap to `0` (`./embed-server cap "$KEY" --monthly 0`); set it back to resume. To **remove** one, `./embed-server remove "$KEY"` removes its client and its mapping; the installation loses access at its next connection, and your service must also refuse that user's sign-in, or the next sign-in provisions a new client.

A network can have **100 top-level clients** by default: the active clients that are not provider installs, so each running installation counts until it is removed or deactivated after 30 days without connecting. Past the limit, provisioning is refused and the tool prints `client limit reached: your network is at its client limit; see https://ur.io/services` with exit code 78. An **Embed plan** raises your network's limit: [request one on the Services page](https://ur.io/services).

## Package

- **The SDK runtime** ships beside the app; `urnetwork_sdk.hpp` is header-only and adds no library of its own: `libURnetworkSdk.dylib` on macOS (the recorded run path, or `@executable_path` in a bundle), `libURnetworkSdk.so` on Linux (build with `SDK_RPATH='$$ORIGIN'`), `URnetworkSdk.dll` on Windows. The app also needs libcurl: the system's on macOS and Linux, and libcurl's DLL beside `embed.exe` on Windows.
- **License:** the SDK and connect are under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), a file-level copyleft: your app's code can stay proprietary, and changes to the SDK's own files must be shared under the MPL. Publish the SDK's licenses and data attributions with your app: `./embed --licenses` prints them as JSON from `urnet::getLicenses` for this operating system's app kind (`apple`, `windows` or `linux`), with no network.
- **In-app traffic only:** the device routes only what the app sends through it, so the app needs no VPN entitlement. Your privacy policy and store disclosures must still say that the app routes some of its users' traffic through URnetwork; the SDK ships no Apple privacy manifest.
- **`client.jwt`** lives in the private state directory below and is a bearer secret: never print, log or back it up. A production app may also encrypt it with the Keychain, DPAPI or the Secret Service.

## Configure the installation

The app keeps its state in one private directory named by `URNETWORK_EMBED_STATE_DIR`: `client.jwt`, `instance-id` (created on first run) and the SDK's bounded `logs/`. With the token server, the app fetches its client JWT on every start:

```sh
# Linux; on macOS use "$HOME/Library/Application Support/urnetwork-embed"
umask 077
export URNETWORK_EMBED_STATE_DIR="$HOME/.local/state/urnetwork-embed"
mkdir -p "$URNETWORK_EMBED_STATE_DIR"
chmod 700 "$URNETWORK_EMBED_STATE_DIR"
export URNETWORK_TOKEN_SERVER_URL='http://127.0.0.1:8790'
export URNETWORK_DEMO_SESSION='demo-session-token-from-the-token-server'
```

```powershell
# Windows: the profile directory keeps the files private to the user
$env:URNETWORK_EMBED_STATE_DIR = "$env:LOCALAPPDATA\urnetwork-embed"
New-Item -ItemType Directory -Force -Path $env:URNETWORK_EMBED_STATE_DIR | Out-Null
$env:URNETWORK_TOKEN_SERVER_URL = 'http://127.0.0.1:8790'
$env:URNETWORK_DEMO_SESSION = 'demo-session-token-from-the-token-server'
```

The token server URL is an HTTPS origin, or loopback HTTP (`localhost`, `127.0.0.1`, `[::1]`) for local testing. Without a token server, leave both settings unset and write `client.jwt` with the backend tool: run `./embed` once to create `instance-id` (it exits with code 78 because there is no `client.jwt` yet), then provision with that installation ID:

```sh
./server/embed-server provision "user:alice:$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")" "$URNETWORK_EMBED_STATE_DIR/client.jwt"
```

`URNETWORK_API_URL` (default `https://api.bringyour.com`) is where the app reads its caps. Never put the root credential on an installation.

## Run

```sh
./embed
```

On Windows run `.\embed.exe` with `URnetworkSdk.dll` and libcurl's DLL beside it. The app prints its client and installation IDs, then a status line on stdout whenever a field changes, and at least once a minute:

```text
embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222
status: connecting | data this month: checking | data total: checking
status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
```

| Field | Meaning |
| --- | --- |
| status | `connecting` until the device has a provider for the app's traffic, then `connected`; `client limit, retry at HH:MM UTC`; `paused` while a cap your backend set is `0`; `data cap reached, resets YYYY-MM-DD HH:MM UTC` for the monthly cap, or `data cap reached` for the running total. |
| data this month | `checking` until the first cap read, `unavailable` if it failed, `no cap` without a monthly cap, otherwise the bytes used of the cap in decimal units. |
| data total | The same for the running-total cap. |

`client limit` means the platform disconnected this client because your network reached its plan's limit on concurrently connected clients; the SDK reconnects by itself at the time shown. At a data cap the device gets no new transfer contracts until the month rolls over at 00:00 UTC on the first, or your backend raises, clears or resets the cap; usage can pass a cap by up to the size of the contracts still open. The app reads its caps with its own client JWT at start, every 5 minutes and within 5 seconds of a contract change; a server without the cap routes shows `unavailable`.

SDK log lines also appear on stderr, and the full log is in `logs/`. Ctrl-C (or SIGTERM) stops the device and exits with code 0. Exit code 78 is a configuration or credential problem that a restart does not fix: missing or invalid state, a network JWT, a credential the server rejected, or a token server answer of 401 or 409. Exit code 1 is any other failure.

## Next: traffic through the device

The embed example ends where the app's own traffic begins. The [Sockets](../socket/README.md) and [Messages](../messages/README.md) examples use the same identity from the state directory:

```sh
export URNETWORK_CLIENT_JWT="$(cat "$URNETWORK_EMBED_STATE_DIR/client.jwt")"
export URNETWORK_INSTANCE_ID="$(cat "$URNETWORK_EMBED_STATE_DIR/instance-id")"
```

Run one program at a time with one identity.
