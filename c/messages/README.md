# C peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.c](main.c), [runtime.c](runtime.c), [codec.h](codec.h) and [Makefile](Makefile); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use C11 and a native SDK with peer/subprotocol APIs. The defaults use `../../../sdk/cgo/include` and `../../../sdk/cgo/build/packages/darwin-arm64` from this directory. Build the adjacent SDK first, or override the two paths as shown below. Run from `c/messages`:

```sh
make self-test
make
./messages-example --self-test
./messages-example --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

For an installed native package, supply its include and library directories:

```sh
make SDK_INCLUDE=/absolute/path/to/include SDK_LIBDIR=/absolute/path/to/lib
```

The [CMake build](CMakeLists.txt) uses the package's `urnetwork::sdk` target:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH=/absolute/path/to/sdk-prefix
cmake --build build
./build/messages-example --self-test
```

The native runtime has POSIX and Windows branches; this example was validated on macOS arm64. On Windows, use the executable/configuration path produced by CMake. C's `make self-test` checks the shared codec without loading the SDK.

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
./messages-example self
./messages-example peers
./messages-example watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
./messages-example send $URNETWORK_PEER_CLIENT_ID hi
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. The C calls are the ones [runtime.c](runtime.c) already makes: `urnet_device_local_enable_subprotocol`, `urnet_device_local_query_subprotocols`, `urnet_device_local_send_subprotocol_bytes`, `urnet_sub_close` with `urnet_release`, and `urnet_device_local_disable_subprotocol`. `urnet_device_local_subprotocol_stats` returns the counters as JSON, and `urnet_device_local_subprotocol_received_count` one ID's count.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with [protobuf-c](https://github.com/protobuf-c/protobuf-c). Generate `notes.pb-c.c` and `notes.pb-c.h` with `protoc -I ../../go/messages/subprotocol --c_out=. notes.proto`, with protobuf-c's `protoc-gen-c` plugin on the `PATH` (the older `protoc-c` command is deprecated), then compile `notes.pb-c.c` and link `libprotobuf-c`:

```c
#include "codec.h" /* urms_utf8 */
#include "notes.pb-c.h"
#include "urnetwork_sdk.h"

enum { NOTES_SUBPROTOCOL = 4097, NOTES_MAX_MESSAGE = 4608 };

static bool send_note(uint64_t device, const char *destination, uint64_t id,
                      char *text) {
  Notes__V1__Note note = NOTES__V1__NOTE__INIT;
  Notes__V1__Envelope envelope = NOTES__V1__ENVELOPE__INIT;
  uint8_t bytes[NOTES_MAX_MESSAGE];
  size_t n = strlen(text);
  if (!id || n > 4096 || !urms_utf8((const uint8_t *)text, n))
    return false;
  note.message_id = id;
  note.text = text;
  envelope.kind_case = NOTES__V1__ENVELOPE__KIND_NOTE;
  envelope.note = &note;
  if (notes__v1__envelope__get_packed_size(&envelope) > sizeof bytes)
    return false;
  n = notes__v1__envelope__pack(&envelope, bytes);
  return urnet_device_local_send_subprotocol_bytes(
      device, NOTES_SUBPROTOCOL, destination, bytes, (int32_t)n);
}

/* On the worker. The urnet_subprotocol_cb refused more than
 * NOTES_MAX_MESSAGE bytes and copied source and bytes before returning. */
static void receive_note(uint64_t device, const char *source,
                         const uint8_t *bytes, size_t n) {
  Notes__V1__Envelope *envelope = notes__v1__envelope__unpack(NULL, n, bytes);
  if (!envelope)
    return; /* malformed: drop and count, never acknowledge */
  if (envelope->kind_case == NOTES__V1__ENVELOPE__KIND_NOTE) {
    const Notes__V1__Note *note = envelope->note;
    size_t length = strlen(note->text);
    /* protobuf-c ends text at its first NUL and does not check UTF-8; a
     * repack of the received size shows that nothing was cut off */
    if (note->message_id && length <= 4096 &&
        urms_utf8((const uint8_t *)note->text, length) &&
        notes__v1__envelope__get_packed_size(envelope) == n) {
      Notes__V1__Ack ack = NOTES__V1__ACK__INIT;
      Notes__V1__Envelope reply = NOTES__V1__ENVELOPE__INIT;
      uint8_t out[32];
      ack.message_id = note->message_id;
      reply.kind_case = NOTES__V1__ENVELOPE__KIND_ACK;
      reply.ack = &ack;
      urnet_device_local_send_subprotocol_bytes(
          device, NOTES_SUBPROTOCOL, source, out,
          (int32_t)notes__v1__envelope__pack(&reply, out));
    }
  } else if (envelope->kind_case == NOTES__V1__ENVELOPE__KIND_ACK) {
    /* match an outstanding note on (source, envelope->ack->message_id) */
  } /* any other kind_case: no kind this version knows, so drop it */
  notes__v1__envelope__free_unpacked(envelope, NULL);
}
```

Register with `urnet_device_local_enable_subprotocol(device, NOTES_SUBPROTOCOL, on_note, NULL, &error)` and keep the subscription handle. Like `message` in [runtime.c](runtime.c), `on_note` refuses other IDs and oversized messages, copies the bytes and the source string into a queued event, and returns; `receive_note` runs on the worker. `notes__v1__envelope__unpack` returns `NULL` for bytes that are not an `Envelope`, and every result it returns is freed with `notes__v1__envelope__free_unpacked`.
