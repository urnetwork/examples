# C++ peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.cpp](main.cpp), the [shared C runtime](../../c/messages/runtime.c), and [Makefile](Makefile); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use C++17, a C compiler and a native SDK with peer/subprotocol APIs. Keep the adjacent C example tree. The Makefile defaults use `../../../sdk/cgo/include` and `../../../sdk/cgo/build/packages/darwin-arm64`. Run from `cpp/messages`:

```sh
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

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. This example drives the shared C runtime, and the C++ header `urnetwork_sdk.hpp` wraps the same ABI: `urnet::DeviceLocal` (for example from `urnet::newDeviceLocalWithDefaults`) has `enableSubprotocol`, `querySubprotocols`, `sendSubprotocolBytes`, `disableSubprotocol`, `enabledSubprotocols`, `subprotocolReceivedCount` and `subprotocolStats`. `enableSubprotocol` throws `urnet::Error` for a refused ID and returns a `urnet::Sub` that closes when destroyed, so keep it for the session.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with libprotobuf. Generate `notes.pb.h` and `notes.pb.cc` with `protoc -I ../../go/messages/subprotocol --cpp_out=. notes.proto`, then compile `notes.pb.cc` and link libprotobuf with its Abseil libraries (`pkg-config --cflags --libs protobuf`, or `protobuf::libprotobuf` in CMake):

```cpp
#include "notes.pb.h"
#include "urnetwork_sdk.hpp"

constexpr int64_t kNotes = 4097;
constexpr size_t kMaxMessageBytes = 4608;

bool sendEnvelope(const urnet::DeviceLocal& device, const std::string& destination,
                  const notes::v1::Envelope& envelope) {
  std::string bytes;
  if (!envelope.SerializeToString(&bytes) || bytes.size() > kMaxMessageBytes)
    return false;
  return device.sendSubprotocolBytes(kNotes, destination,
                                     reinterpret_cast<const uint8_t*>(bytes.data()),
                                     static_cast<int32_t>(bytes.size()));
}

// On the worker, with source and bytes copied in the listener.
void receiveEnvelope(const urnet::DeviceLocal& device, const std::string& source,
                     const std::string& bytes) {
  notes::v1::Envelope envelope;
  if (bytes.size() > kMaxMessageBytes || !envelope.ParseFromString(bytes))
    return;  // malformed: drop and count, never acknowledge
  switch (envelope.kind_case()) {
    case notes::v1::Envelope::kNote:
      if (envelope.note().message_id() != 0 && envelope.note().text().size() <= 4096) {
        notes::v1::Envelope reply;
        reply.mutable_ack()->set_message_id(envelope.note().message_id());
        sendEnvelope(device, source, reply);
      }
      break;
    case notes::v1::Envelope::kAck:
      // match an outstanding note on (source, envelope.ack().message_id())
      break;
    default:  // KIND_NOT_SET: no kind this version knows
      break;
  }
}
```

The listener only copies and queues; `inbox` stands for your bounded, thread-safe queue:

```cpp
urnet::Sub sub = device.enableSubprotocol(
    kNotes, [&inbox](int64_t id, std::string source, const uint8_t* bytes, int32_t length) {
      if (id == kNotes && length > 0 && static_cast<size_t>(length) <= kMaxMessageBytes)
        inbox.tryPush(std::move(source), std::string(reinterpret_cast<const char*>(bytes), static_cast<size_t>(length)));
    });
```

The wrapper passes `source` as an owned `std::string`, but `bytes` is valid only during the call. Parse and reply on the worker, never in the listener: `sendSubprotocolBytes` can wait for room in the send queue.
