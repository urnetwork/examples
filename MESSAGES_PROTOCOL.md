# URMS v1 peer messaging

The language [messages examples](README.md) exchange text and application acknowledgements using URnetwork subprotocol **4096** (`0x1000`). Each SDK subprotocol message carries exactly one URMS frame. The subprotocol ID belongs to the SDK envelope; it is not repeated in the application frame.

A protocol of your own runs beside URMS on another application subprotocol ID. [Subprotocols](SUBPROTOCOLS.md) covers the ID rules, registration on the Device, the peer query, stats and custom protobuf messages.

Both endpoints need distinct scoped clients in the same network, separate persisted instance IDs, and Devices that expose subprotocol messaging. Follow the [integration contract](INTEGRATION_CONTRACT.md) before running a two-terminal demo. JavaScript and TypeScript use Node 24 and a [native companion](javascript/integration/companion/README.md): the application processes URMS while a full native `DeviceLocal` carries network traffic through extension RPC. Hosted proxy devices remain ineligible for peer messaging.

## Wire layout

All integer fields are unsigned and use network byte order (big-endian). There is no padding or terminator. The fixed header is 16 bytes; the largest frame is 4112 bytes.

| Byte offset | Width | Field | Required value |
| --- | --- | --- | --- |
| 0 | 4 | Magic | ASCII `URMS`, hex `55 52 4d 53` |
| 4 | 1 | Version | `1` |
| 5 | 1 | Kind | `1` = TEXT; `2` = ACK |
| 6 | 2 | Payload length | Number of bytes following the header, at most 4096 |
| 8 | 8 | Message ID | `1` through `2^64 - 1`; zero is reserved and invalid |
| 16 | Payload length | Payload | Strict UTF-8 for TEXT; no bytes for ACK |

TEXT may carry zero through 4096 UTF-8 bytes. Count encoded bytes, not characters, UTF-16 code units or graphemes. Preserve the UTF-8 bytes without normalization. Do not append a C string terminator. In runtimes whose default string encoder replaces malformed input, reject invalid text rather than silently replacing it.

ACK uses kind `2`, payload length `0` and the exact message ID from the accepted TEXT. An ACK is always 16 bytes. It has no status string or echoed text.

The sender chooses a nonzero 64-bit ID for each new TEXT and avoids reusing an outstanding ID for the same peer. Track IDs as a full 64-bit integer; JavaScript/TypeScript use `BigInt`, since `Number` cannot exactly represent the whole range. An application retry of the same logical message keeps its ID.

## Exact validation

Validate before displaying TEXT, acknowledging it, or treating a received ACK as success:

1. Require at least 16 bytes, magic `URMS`, version `1`, kind TEXT or ACK, and a nonzero message ID.
2. Decode the unsigned payload length and reject values above 4096.
3. Require the entire received message length to equal `16 + payload_length`. Reject truncation, extra trailing bytes and concatenated frames.
4. For TEXT, require strict UTF-8: reject truncated sequences, stray continuation bytes, overlong encodings, surrogate code points and values above U+10FFFF. Do not use replacement decoding.
5. For ACK, require a zero payload length. Correlate the ACK with an outstanding send using **(source client ID, message ID)**.

Drop malformed or unsupported frames and report a bounded diagnostic; never ACK them. Never ACK an ACK. An unsolicited ACK or an ACK from a different client cannot satisfy an outstanding message.

## Golden vectors

TEXT `hi`, message ID `1`, payload length `2`:

```text
55524d530101000200000000000000016869
```

ACK for message ID `1`:

```text
55524d53010200000000000000000001
```

Both must round-trip byte-for-byte in every codec. Credential-free checks should also reject a short header, bad magic/version/kind, zero ID, mismatched or oversized lengths, malformed UTF-8 and nonempty ACK payloads. Test empty TEXT, multibyte UTF-8, the 4096-byte boundary and IDs above JavaScript's safe-integer range.

## Discovery and send lifecycle

Subscribe to the Device's real-time peer changes before obtaining the current snapshot, then refresh the displayed candidates on updates. Enable subprotocol `4096` and keep its listener subscription alive. For the JS/TS browser-style RPC client, install mirrored peer/state listeners before the initial sync, wait for that sync, and then enable the subprotocol. Adding such listeners later can reconnect the RPC transport and invalidate an existing subprotocol subscription. A service-side stored client mapping identifies ownership; it does not prove that a peer is currently connected. Exclude self and use the discovered peer's `client_id`, never its display name or installation UUID, as the destination.

Before sending TEXT, query the selected peer's supported subprotocols with a bounded timeout. Send only after a successful answer includes `4096`. A query failure, timeout, unsupported peer or disconnect is a visible failure to send; stale presence or a cached answer is not evidence of delivery. Re-query after a reconnect or before a later send. Real-time presence can change between the query and the send, so continue to handle enqueue failure and ACK timeout.

Successful `SendSubprotocol` enqueue means the SDK accepted the outbound bytes. It does not mean the destination application accepted them. Only a valid correlated URMS ACK confirms that the receiving example parsed and accepted that TEXT. This ACK does not assert that a person read it or that it was durably stored. A timeout leaves delivery uncertain: the receiver or its ACK may have been lost. URMS v1 does not provide durable storage, global ordering or exactly-once processing. Applications that retry should suppress duplicate processing by source and message ID while permitting another ACK for an already accepted TEXT.

For JS/TS, `device.enableSubprotocol(4096, listener)` returns a subscription with `send(clientId, bytes)`, `querySubprotocols(clientId, timeoutMillis)`, `close()`, and a `closed` Promise. A query result of `null` means unavailable/unanswered; an empty array means the peer advertised no subprotocols. Registrations belong to one RPC session and are not replayed after reconnects. A transport, listener or receive-queue failure rejects `closed`; recreate the registration and query support again before another send.

The companion RPC permits 16 registrations per session, at most 65535 bytes per raw frame, and a receive queue of 64 messages or 1 MiB per registration. Overflow fails the subscription instead of coalescing or silently replacing frames. URMS still applies its stricter 4112-byte frame limit.

## Callback ownership and shutdown

Treat incoming message bytes and native callback handles as valid only for the callback's documented lifetime. Copy the frame bytes and any source identity needed by deferred work **before the callback returns**. Decode and send ACKs from an application worker or queue so an inline receive callback stays short and cannot block the receive path. Do not retain a borrowed slice, pointer or FFI view for later parsing. Some bindings already copy at their boundary; that does not make every object received by a callback owned by the application.

Keep callback objects, subscriptions, the Device and its network-space manager alive for the whole session. Remove subscriptions, stop or drain workers and close the Device before releasing callback storage and the manager; ensure no queued job uses a closed handle. Check both enqueue and query outcomes, and bound waits for capability answers and ACKs. The [SDK subprotocol implementation](https://github.com/urnetwork/sdk/blob/main/device_local_subprotocol.go) defines the Device API and receive-lifetime boundary checked on 2026-09-15.

Do not assume native close synchronously drains every callback. Where late receive/query callbacks are possible, these command-line examples deliberately retain a small callback root until process exit, including the Java/Kotlin, C#, Ruby and Python FFI bridges. Remove subscriptions and close native handles normally while keeping those callback trampolines valid. A long-running application needs a binding-specific quiescence/lifetime strategy before reclaiming callback storage.
