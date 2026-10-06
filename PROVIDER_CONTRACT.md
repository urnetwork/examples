# Provider integration

A provider example runs a URnetwork provider inside an application. Each installation is a **provider client** of the developer's one URnetwork network: it provides publicly, and its payouts go to the developer's fixed Bittensor payout wallet. The app shows four things: whether it is providing, the clients it served, the data it provided and the payout wallet, read only. Earnings totals come from the developer's backend, not from the app.

This contract applies to every provider example: the twelve [language folders](README.md), each as `<language>/provider/`, and the `electron/provider/`, `tauri/provider/` and `android/provider/` platforms. The [Go provider](go/provider/README.md) is the reference implementation. The [integration contract](INTEGRATION_CONTRACT.md) still governs the backend and client credential boundary; this document adds what providing needs.

## Consent disclaimer

Every provider example shows this text verbatim. Console apps print it at start; GUI and Android apps show it next to the control that starts providing; every README shows it under its title.

```text
Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app.
```

The examples start providing without a consent screen of their own. An app built from them must ask for consent first and must let the user stop providing at any time. The examples do not cap bandwidth.

Self-tests check the text by its SHA-256 over the UTF-8 bytes, with LF line breaks and no trailing newline: `83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c`. A README may join the three lines into one paragraph with the first two words in bold.

## Credential boundary

| Value | Owner | Purpose |
| --- | --- | --- |
| `URNETWORK_ROOT_JWT` | Backend secret store | Provisions provider clients and maps them to the payout wallet. Never shipped in an app, installer or repository. |
| Payout coldkey address (ss58) | Backend configuration | The fixed Bittensor payout wallet. Public, but set only by the backend. |
| Coldkey secret (mnemonic or seed) | The coldkey owner, offline | Signs wallet mapping consents with the owner's own wallet tool. Never on the backend, in the app or in this repository. |
| Provider `client_id` | Backend mapping and the client JWT | The installation's provider client. |
| `client.jwt` | The installation's private state | The scoped client JWT from the backend. The SDK refreshes it and the app saves the refreshed token. A bearer secret. |
| `instance-id`, `identity.json` | The installation's private state | Created by the app on first run; see [Installation state](#installation-state). |

Apps never call `POST /sn/wallet/network-consent`, `POST /sn/wallet/consent` or `POST /sn/wallet`, never hold the root JWT and never handle USDC payout settings: payouts are subnet payouts, and `/account/payout-wallet` is not part of this contract. Apps read the payout wallet only to display it.

## Backend: provision provider clients

The backend provisions one provider client per installation with `POST /network/auth-client`, exactly as the [integration contract](INTEGRATION_CONTRACT.md#backend-provisioning) describes for a top-level client: root JWT, `description` and `device_spec`, no `client_id` and no `source_client_id` for a new client; the stored `client_id` for a reissue. Each language's existing [allocator](INTEGRATION_CONTRACT.md#runnable-backend-allocators) does this without changes. Keep provider installations in their own private map (for example `providers.json`) and key them per installation, for example `user:alice:laptop-1`. A provider is an ordinary client at provisioning time: the app makes it a provider by setting the public provide mode.

The backend delivers `by_client_jwt` to the installation as its `client.jwt`. A client that has not connected for 30 days is deactivated, and its reissue then fails with `Client does not exist.`; remove that mapping and provision a new client. The [network consent](#payout-wallet-mapping) already covers the new client; with per-provider consents it needs its own.

Provision each installation as a top-level client. A child client (one provisioned with a `source_client_id`) can connect and set the public provide mode, but the server never offers child clients to other users as providers: provider selection counts only top-level clients, on purpose, because child clients are a consumer's short-lived connection identities. A child client would provide nothing and earn nothing.

## Payout wallet mapping

The payout wallet is the developer's fixed Bittensor coldkey. The server pays a provider client only through a **wallet mapping consent** that the coldkey owner signed, offline, with their own wallet tool (btcli, the polkadot.js extension or apps, subkey); the coldkey never touches the backend or the app. The backend requests each consent message with the root JWT and submits the signature. There are two kinds:

| Mode | One signature covers | Use it for |
| --- | --- | --- |
| **Network consent** | Every provider client of the network: the ones provisioned now and every one provisioned later. | Apps that embed a provider: the coldkey owner signs once per network, and no key ships in any app. |
| **Per-provider consent** | One provider client. | A client that must earn to a different coldkey, for example one run by an independent signer. |

For each provider client and each epoch, a per-provider consent in force at that epoch wins; otherwise the network consent pays. A consent is in force from its `from_epoch` through its `through_epoch` (at most 65,536 epochs), and only looking forward: it never pays an epoch that started before its message expired. To change the coldkey, sign a new consent of the same kind; it starts at a later epoch than the one it replaces, and earlier epochs keep the earlier coldkey. There is no unsigned revocation. A client returns to the network consent when its own consent ends, or from the first epoch of a replacement consent for that client signed with the network's coldkey.

**Network consent** (the root JWT is the network's credential and names no client):

1. Read the current epoch: `GET /sn/epoch` (no authentication) returns `{"epoch": E, ...}`.
2. Request the consent message: `POST /sn/wallet/network-consent` with `{"coldkey_ss58", "from_epoch": E+1, "through_epoch": E+65536}`. The answer is `{"message": "..."}`, starting with the line `Approve URnetwork network wallet mapping`. A client JWT is refused.
3. The coldkey owner signs the **exact** message bytes within five minutes: an sr25519 signature in the `substrate` signing context over the UTF-8 text, either raw or wrapped as `<Bytes>…</Bytes>` (what a Polkadot extension `signRaw` of type `bytes` signs). The signature is 64 bytes, sent as hex with an optional `0x`.
4. Submit it: `POST /sn/wallet` with `{"coldkey_ss58", "message", "signature"}` and no `client_id`. Success returns `mapping_hash` and `mapping_generation`. A well-formed signature from another key returns `error.code` `signature_mismatch`. Retrying the same message and signature returns the same mapping; an expired message needs a new step 2.
5. Check: `GET /sn/wallet` lists the network consent with `"consent_scope": "network"` and no `client_id`.

**Per-provider consent**: the same steps with `POST /sn/wallet/consent` and `{"client_id", "coldkey_ss58", "from_epoch", "through_epoch"}` in step 2 (the message starts with `Approve URnetwork provider wallet mapping`), and the same `client_id` in step 4. `GET /sn/wallet` lists it with the client's `client_id` and `"consent_scope": "provider"`. A wallet listed without `consent_scope` was set by an older login proof or is the network-level copy; settlement does not pay it.

The [Go wallet tool](go/provider/README.md#backend-provision-and-map-the-payout-wallet) runs both: `network-challenge` and `network-accept` for the network consent, `challenge` and `accept` for one client, and `show`. It checks that each message names the network (or client) and the coldkey it requested before saving it for the signer, and keeps a receipt. The network consent with curl and jq (jq keeps the message bytes exact):

```sh
API=https://api.bringyour.com
COLDKEY='5...your-coldkey-ss58-address'
EPOCH=$(curl -fsS "$API/sn/epoch" | jq -r .epoch)
jq -n --arg coldkey "$COLDKEY" --argjson epoch "$EPOCH" \
  '{coldkey_ss58: $coldkey, from_epoch: ($epoch + 1), through_epoch: ($epoch + 65536)}' \
  | curl -fsS -X POST "$API/sn/wallet/network-consent" \
      -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' --data-binary @- \
  | jq -j .message > network-consent.txt
# the coldkey owner signs the exact bytes of network-consent.txt offline and returns SIGNATURE
jq -n --rawfile message network-consent.txt --arg coldkey "$COLDKEY" --arg signature "$SIGNATURE" \
  '{coldkey_ss58: $coldkey, message: $message, signature: $signature}' \
  | curl -fsS -X POST "$API/sn/wallet" \
      -H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json' --data-binary @-
curl -fsS "$API/sn/wallet" -H "Authorization: Bearer $URNETWORK_ROOT_JWT"
```

For one client, add `--arg client "$CLIENT_ID"` and `client_id: $client` to both jq objects and post the first to `$API/sn/wallet/consent`.

If a consent request answers that the wallet mapping authority is unavailable, the deployment has not enabled wallet mapping yet; retry later. A server without network consents answers `POST /sn/wallet/network-consent` with 404; per-provider consents work there.

## Installation state

Each installation keeps its state in one private directory. The socket and messaging examples read `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`; a provider runs unattended for long periods and must save a refreshed token and its identity, so it keeps everything in a directory it can update. Console examples read the directory's absolute path from `URNETWORK_PROVIDER_STATE_DIR`; GUI and Android apps use a private directory in their application data. On POSIX the directory is mode 0700 and its files 0600: refuse a directory or file that group or others can access, and refuse a symlinked file. On Windows keep it under the user's profile (`%LOCALAPPDATA%`). Replace files atomically: write a private temporary file in the same directory, sync it, then rename it over the old one.

| File | Written by | Content |
| --- | --- | --- |
| `client.jwt` | The developer or the app's sign-in flow; the app on refresh | The scoped client JWT; surrounding whitespace is ignored. It must carry a `client_id` claim (a network JWT has none). When the SDK refreshes the token, the app writes the new one here. Never print or log it. |
| `instance-id` | The app, on first run | One UUID, kept for the life of the installation. |
| `identity.json` | The app, on first run | The provider identity, so the provider keeps its keys across restarts. |
| `logs/` | The SDK | The SDK's bounded log files; see [App lifecycle](#app-lifecycle). |

`identity.json` is one JSON object; byte fields are standard base64:

```json
{
  "version": 1,
  "client_id": "11111111-1111-1111-1111-111111111111",
  "client_key_seed": "<32 bytes>",
  "provide_tls_certificate_pem": "<pem bytes>",
  "provide_tls_private_key_pem": "<pem bytes>",
  "extender_key_seed": "<bytes, optional>"
}
```

At start, if `identity.json` exists and its `client_id` equals the JWT's `client_id`, create the device with that key material. If it is missing, or names another client, create the device without key material and save the device's key material as the new `identity.json`. A file that does not parse, has another version or a seed that is not 32 bytes is a configuration error. The identity holds private keys: never print or log it.

## App lifecycle

1. Show the consent disclaimer.
2. Load the installation state and check `client.jwt`. A missing or invalid state is a configuration error (exit 78).
3. Point the SDK's log files at a `logs` directory in the installation state with `SetLogDir` (C ABI `urnet_set_log_dir`). The SDK then writes 16 MiB files there and keeps the newest four at each start; without it, it writes unbounded files to the system temp directory. The SDK also copies its log lines to stderr; Go can lower that copy to errors with the glog `stderrthreshold` flag, other bindings keep it.
4. Create a network space manager without storage, the `ur.network`/`main` network space with migration host `bringyour.com`, and set the space API's JWT to `client.jwt`, as the [integration helpers](INTEGRATION_CONTRACT.md#app-startup-and-cleanup) do.
5. Create the local device with the installation's key material, or with defaults on first run and then save `identity.json`. Use a description and spec that name the example, such as `Go provider example` and `urnetwork-examples/go-provider`.
6. Add the listeners: JWT refresh (save `client.jwt`), auth logout (stop with exit 78), and the provider ingress and egress contract details (clients served).
7. Set the provide mode to **public** (`ProvideModePublic`, value 3).
8. Read the payout wallet once at start and every 10 minutes.
9. Read the status every second and show it.
10. To stop: set the provide mode to none (value 0), close the subscriptions, close the device, then close the manager, and release native handles where the binding has them.

| Operation | Go SDK and gomobile (Android, Swift on macOS) | C ABI (C, C++, C#, Java, Kotlin, Python, Ruby, Rust, Swift on Linux and Windows, Tauri) |
| --- | --- | --- |
| Logs | `SetLogDir` | `urnet_set_log_dir` |
| Manager and space | `NewNetworkSpaceManagerNoStorage`, `UpdateNetworkSpaceValues`, `GetApi().SetByJwt` | `urnet_new_network_space_manager_no_storage`, `urnet_network_space_manager_update_network_space_values`, `urnet_network_space_get_api`, `urnet_api_set_by_jwt` |
| Device | `NewDeviceLocalWithKeyMaterial(…, NewDeviceLocalKeyMaterial(seed, certPem, keyPem))` plus `SetExtenderKeySeed`; `NewDeviceLocalWithDefaults` on first run | `urnet_new_device_local_key_material`, `urnet_device_local_key_material_set_extender_key_seed`, `urnet_new_device_local_with_key_material`; `urnet_new_device_local_with_defaults` |
| Identity to save | `GetKeyMaterial()` and its getters | `urnet_device_local_get_client_key_seed`, `…_get_provide_tls_certificate_pem`, `…_get_provide_tls_private_key_pem`, `…_get_extender_key_seed` (buffer-out) |
| Provide | `SetProvideMode`, `GetProvideMode`, `GetProvidePaused`, `GetProvideEnabled`, `GetProviderConnected` | `urnet_device_set_provide_mode`, `urnet_device_get_provide_mode`, `urnet_device_get_provide_paused`, `urnet_device_get_provide_enabled`, `urnet_device_local_get_provider_connected` |
| Data provided | `GetProviderPacketStats` | `urnet_device_get_provider_packet_stats` (JSON) |
| Clients served | `AddProviderIngressContractDetailsChangeListener`, `AddProviderEgressContractDetailsChangeListener` | `urnet_device_add_provider_ingress_contract_details_change_listener`, `urnet_device_add_provider_egress_contract_details_change_listener` (one JSON object per call) |
| Payout wallet | `SyncSnWallet` (callback), then `GetSnWallet` | `urnet_device_local_sync_sn_wallet`, `urnet_device_local_get_sn_wallet` (JSON or NULL) |
| Credential | `AddJwtRefreshListener`, `AddAuthLogoutListener` | `urnet_device_add_jwt_refresh_listener`, `urnet_device_add_auth_logout_listener` |
| Close | `Sub.Close`, `Close` | `urnet_sub_close`, `urnet_device_close`, `urnet_network_space_manager_close`, then `urnet_release` |

C ABI callbacks run on SDK threads: copy what they carry before returning, keep the callback objects alive until their subscription closes, and hand work to the app's own thread. Structured values cross as JSON with the Go field names:

```json
{"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "...": 0}
{"ContractId": "33333333-3333-3333-3333-333333333333", "ContractTransferPath": {"SourceId": "22222222-2222-2222-2222-222222222222", "DestinationId": "11111111-1111-1111-1111-111111111111", "StreamId": null}, "Status": "open", "...": 0}
{"coldkey_ss58": "5...", "client_id": "11111111-1111-1111-1111-111111111111", "set_at_millis": 1}
```

Where a binding cannot read the wallet through the SDK, read `GET /sn/wallet` with the scoped client JWT: its `wallet` is the effective wallet in the order settlement pays (the client's own consent, else the network consent), with `client_id` set only for the client's own mapping.

**Extender role.** While it provides, the device also runs the provider extender role, which is on by default: it listens on TCP 443 and on UDP 443, 53 and 4053 so that clients that cannot reach the platform directly can connect through this provider, and it logs those listeners on stderr. Windows and macOS may ask the user to allow incoming connections the first time; on Linux an unprivileged process cannot bind these ports, and providing continues without the role. Android and iOS builds of the SDK do not include the role. The examples keep the default on and their READMEs say so.

One device setting turns the default off: `DefaultProvideExtender` set to false. The user's own choice (`SetProvideExtender`) still overrides it, and the separate hard switch `ProvideExtenderEnabled` set to false means the role never runs. Both settings need an SDK that includes them (sdk branch `fix/provide-extender-default`, not yet in a release); the published SDK has only `ProvideExtenderEnabled`.

| Binding | Default off |
| --- | --- |
| Go | `DeviceLocalSettings.DefaultProvideExtender = false` on `DefaultDeviceLocalSettings()` for `NewDeviceLocal`, or `NewDeviceLocalWithProvideExtender(…, provideExtenderEnabled true, defaultProvideExtender false)` |
| gomobile | the same settings property, or `Sdk.newDeviceLocalWithProvideExtender` (Java, Kotlin) and `SdkNewDeviceLocalWithProvideExtender` (Swift) |
| C ABI | `urnet_new_device_local_with_provide_extender(…, key_material, provide_extender_enabled true, default_provide_extender false, …)`, which also takes the installation's key material. `urnet_new_device_local` with the settings JSON `{"DefaultProvideExtender": false}` works too (omitted fields keep the defaults of `urnet_default_device_local_settings()`; durations are integer nanoseconds), but those settings carry no key material. |
| C++ | `urnet::newDeviceLocalWithProvideExtender` |

## Status

Every example shows these four fields with these exact rules. Console examples print one status line when the state, the clients-served count or the payout wallet changes, and otherwise once a minute; GUI and Android apps show the same fields as labeled values.

| Field | Rule |
| --- | --- |
| Status | `stopped` when the provide mode is not public; else `paused` when provide is paused; else `providing` when provide is enabled **and** the provider is connected to the platform; else `starting`. Do not use `GetProviderReady`: it also waits for processed client key registration, which default device settings do not enable, so it stays false. |
| Clients served | Distinct client peers of provider contracts since the app started. The peer of a receive (ingress) contract is its path's `SourceId`, of a send (egress) contract its `DestinationId`. A missing or all-zero ID falls back to `stream:<StreamId>`, then `contract:<ContractId>`. Count at most 100,000 distinct peers; beyond that, show the limit with a trailing `+`. |
| Data provided | `RemoteEgressByteCount + RemoteIngressByteCount` of the provider packet stats: bytes relayed for clients in both directions since the device started; 0 when the stats are null. |
| Payout wallet | `checking` until the first wallet read; `unavailable` if that read fails (a later failure keeps the last value); `not set` when no wallet is mapped; otherwise the coldkey ss58 address with its scope: `(this provider)` when the wallet's `client_id` is this client, `(network)` when it has none, `(another provider)` otherwise. |

Bytes use binary units with one decimal: below 1024 `N B`, otherwise `KiB`, `MiB`, `GiB`, `TiB`, `PiB`, `EiB`, moving to the next unit when the rounded value reaches 1024.0. The console status line is:

```text
status: <status> | clients served: <count> | data provided: <bytes> | payout wallet: <wallet>
```

Golden vectors for self-tests:

| Input | Text |
| --- | --- |
| 0, 1023, 1024, 1536 bytes | `0 B`, `1023 B`, `1.0 KiB`, `1.5 KiB` |
| 1048575, 13002342 bytes | `1.0 MiB`, `12.4 MiB` |
| 5×1024³, 3×1024⁴ bytes | `5.0 GiB`, `3.0 TiB` |
| starting, 0 clients, 0 bytes, wallet checking | `status: starting \| clients served: 0 \| data provided: 0 B \| payout wallet: checking` |
| providing, 3 clients, 13002342 bytes, network wallet A | `status: providing \| clients served: 3 \| data provided: 12.4 MiB \| payout wallet: A (network)` |
| paused, limit reached, 1536 bytes, own wallet A | `status: paused \| clients served: 100000+ \| data provided: 1.5 KiB \| payout wallet: A (this provider)` |
| stopped, 0 clients, 0 bytes, no wallet | `status: stopped \| clients served: 0 \| data provided: 0 B \| payout wallet: not set` |

Here `A` is `5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY`, the public Substrate development account, used only as test data. Peer vectors, with provider `1111…`, clients `2222…` and `3333…`, stream `4444…`:

| Contract | Direction | Peer key |
| --- | --- | --- |
| `SourceId` 2222…, `DestinationId` 1111… | receive | `22222222-2222-2222-2222-222222222222` |
| `SourceId` 1111…, `DestinationId` 2222… | send | `22222222-2222-2222-2222-222222222222` |
| `SourceId` 0000…, `DestinationId` 1111…, `StreamId` 4444… | receive | `stream:44444444-4444-4444-4444-444444444444` |
| no path, `ContractId` 8888… | receive | `contract:88888888-8888-8888-8888-888888888888` |

The first two contracts of client `2222…` count once; adding client `3333…` counts two.

## Exit codes

Console examples exit with **0** after a requested stop (Ctrl-C, SIGTERM), **78** for a configuration or credential problem that a restart does not fix (missing or invalid state, a network JWT, or the server rejecting the client credential), and **1** for any other failure. Supervisors restart on failure but not after 0 or 78; see [background](background/README.md).

## Run in the background

Console examples share the [background templates](background/README.md): a systemd user service on Linux, a launchd agent on macOS and a Task Scheduler task on Windows. Each template sets `URNETWORK_PROVIDER_STATE_DIR` and runs the example's command, so the service definition names a path, never a credential. Each example README lists its own `@COMMAND@` and `@ARGUMENT@` values. GUI and Android apps use the patterns in [Platform notes](#platform-notes).

## Self-test

Every example has a self-test that needs no credentials and no network and creates no device. Console examples run it with `--self-test` and print a single passed line; GUI and Android samples run it as their unit tests. Where the language allows, the self-test does not load the native SDK runtime. It checks:

- the disclaimer text against the SHA-256 above;
- the byte, status line, peer key and clients-served vectors above, and the status rules (including `stopped` for the network provide mode);
- the payout wallet scope labels;
- the JWT `client_id` claim: accepted for a client JWT, refused for a network JWT, a malformed token and an invalid UUID;
- the state directory: private permissions enforced on POSIX, atomic replacement, `instance-id` created once and reused, `identity.json` round trip, another client's identity ignored, an invalid identity refused;
- the configuration errors (missing or relative state directory, missing `client.jwt`) and the usage exit code 78.

## README template

Each `<platform>/provider/README.md` follows the [Go provider README](go/provider/README.md):

1. Title `# <Platform> provider` and the folder's navigation line with a `Provider` entry.
2. One paragraph on what the app does, linking this contract; the consent disclaimer.
3. Files: a table of the example's files and their roles.
4. Build and self-test: exact commands for Windows (PowerShell), macOS and Linux, and the SDK version or local build it needs.
5. Backend: provisioning with the language's allocator, and the [wallet mapping](#payout-wallet-mapping), network consent first, via the Go wallet tool or curl.
6. Configure the installation: creating the private state directory and `client.jwt` on each OS.
7. Run: per-OS commands, a sample status line, the field table, the extender role's listeners and the exit codes.
8. Run in the background: the example's values for the [background templates](background/README.md), or the platform pattern.

## Platform notes

### Electron

Electron cannot run a provider in the renderer or in Node's WebAssembly SDK. The main process starts the JavaScript examples' [native companion](javascript/integration/companion/README.md) in provider mode as a child process and talks to it with `@urnetwork/sdk` over the companion's loopback device RPC; the renderer gets status over IPC through a preload script with context isolation. Keep `client.jwt`, `instance-id` and `identity.json` in a private `provider` directory inside `app.getPath('userData')`, which the companion uses as its state directory. Starting and stopping providing starts and stops the companion. The app keeps providing while its window is closed by staying in the tray, and offers start at login with `app.setLoginItemSettings` on macOS and Windows and an XDG autostart entry on Linux. Package the companion binary for each OS and architecture next to the app.

### Tauri

Tauri runs the provider in its Rust core with the `urnetwork-sdk` crate through the C ABI, in process: no sidecar. Tauri commands start and stop providing; a status event or command feeds the web view. Keep the state in a private `provider` directory inside the app data directory. The app keeps providing in the system tray when its window closes; start at login uses the Tauri autostart plugin. Build per OS with the native runtime the crate embeds.

### Android

The Android app is a Kotlin app on the gomobile AAR (`io.ur:urnetwork-sdk-android`, package `com.bringyour.sdk`). Providing runs in a foreground service, which owns the device, starts from an explicit user action and stops from the app and from its notification. Android 14 and later need a foreground service type: use `specialUse` with the `FOREGROUND_SERVICE_SPECIAL_USE` permission and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property that explains providing; `dataSync` is limited to six hours a day from Android 15. Request `POST_NOTIFICATIONS` on Android 13 and later. Keep the state in the app's private files directory, never in the APK or backups. Provide on unmetered networks by default and pause providing (`SetProvidePaused`) on metered ones. The activity binds to the service for the status fields.

### JavaScript and TypeScript

The WebAssembly SDK cannot provide by itself. The [native companion](javascript/integration/companion/README.md) owns the provider device in provider mode and its installation state; the Node program shows the status through the companion's device RPC (`getProvideEnabled`, the contract view controllers' provider packet stats and provider contract rows) and reads the wallet with `GET /sn/wallet`. The JavaScript SDK does not bind `GetProviderConnected`, so the companion serves its RPC only once the provider is connected, as it does today for messaging.

### Swift

On macOS the gomobile XCFramework exposes the Go API. The Swift package has no Linux or Windows binary, so the cross-platform provider uses the C ABI header and library through a SwiftPM C module on all three OSes.

These details were checked against the workspace sources of the SDK, the server and the subnet on 2026-10-06, including the network consent changes on their `fix/network-wallet-consent` branches and the extender settings on sdk `fix/provide-extender-default`.
