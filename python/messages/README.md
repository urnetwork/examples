# Python peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.py](main.py), [codec.py](codec.py) and [requirements.txt](requirements.txt); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use Python 3.10+. The codec self-test runs before SDK import and needs no SDK installation. The requirements pin `urnetwork-sdk==0.0.1.dev0`; install its matching local wheel if unpublished, or update that pin for a peer/subprotocol-capable release. There is no compilation step. Run from `python/messages`:

```sh
python3 main.py --self-test
python3 -m pip install -r requirements.txt
python3 main.py --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
python3 main.py self
python3 main.py peers
python3 main.py watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
python3 main.py send $URNETWORK_PEER_CLIENT_ID hi
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. The `urnetwork.Device` wrapper has no subprotocol methods, so Python uses the C ABI in `urnetwork.raw`, as [main.py](main.py) does: `urnet_device_local_enable_subprotocol`, `urnet_device_local_query_subprotocols`, `urnet_device_local_send_subprotocol_bytes`, `urnet_sub_close` with `urnet_release`, and `urnet_device_local_disable_subprotocol`, with the callback types `urnet_subprotocol_cb` and `urnet_subprotocols_query_cb` from `urnetwork._raw`. `urnet_device_local_subprotocol_stats` returns the counters as JSON (read it with `take_string` from the integration client), and `urnet_device_local_subprotocol_received_count` one ID's count.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with the `protobuf` package. Install it at the version that matches your `protoc` (7.36.x for protoc 36.x; the generated module refuses an older runtime) and generate `notes_pb2.py` with `protoc -I ../../go/messages/subprotocol --python_out=. notes.proto`:

```python
import ctypes as C

from google.protobuf.message import DecodeError
from urnetwork import raw
from urnetwork._raw import urnet_subprotocol_cb

import notes_pb2

NOTES = 4097
MAX_MESSAGE_BYTES = 4608


def send_envelope(h, destination, envelope):
    data = envelope.SerializeToString()
    if len(data) > MAX_MESSAGE_BYTES:
        raise ValueError("envelope too large")
    buffer = (C.c_uint8 * len(data)).from_buffer_copy(data)
    if not raw.urnet_device_local_send_subprotocol_bytes(
        h, NOTES, destination.encode(), buffer, len(data)
    ):
        raise RuntimeError("SDK did not enqueue message")


# Copies before it returns; parsing runs on the worker. Keep it referenced
# for the life of the process, as main.py does with _CALLBACK_ROOTS.
@urnet_subprotocol_cb
def on_note(_, protocol, source, pointer, length):
    if protocol == NOTES and source and 0 < length <= MAX_MESSAGE_BYTES:
        enqueue(("note", source.decode(), C.string_at(pointer, length)))


def receive_envelope(h, source, data):
    envelope = notes_pb2.Envelope()
    try:
        envelope.ParseFromString(data)
    except (DecodeError, UnicodeDecodeError):
        return  # malformed: drop and count, never acknowledge
    kind = envelope.WhichOneof("kind")
    if kind == "note":
        note = envelope.note
        if note.message_id and len(note.text.encode()) <= 4096:
            reply = notes_pb2.Envelope(ack=notes_pb2.Ack(message_id=note.message_id))
            send_envelope(h, source, reply)
    elif kind == "ack":
        pass  # match an outstanding note on (source, envelope.ack.message_id)
    # None: no kind this version knows


send_envelope(
    h,
    destination,
    notes_pb2.Envelope(note=notes_pb2.Note(message_id=message_id, text=text)),
)
```

Register `on_note` with `urnet_device_local_enable_subprotocol(h, NOTES, on_note, None, C.byref(error))` and check the error as `main.py` does. The default upb backend raises `DecodeError` for invalid UTF-8 in `text`, and the pure-Python backend raises `UnicodeDecodeError`, so the parse catches both. `enqueue` stands for your bounded queue, which drops when full. Python integers hold the full unsigned 64-bit `message_id`.
