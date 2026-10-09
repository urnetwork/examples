# Java peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [Main.java](Main.java), [UrMessages.java](UrMessages.java), [MessageCodec.java](MessageCodec.java) and [pom.xml](pom.xml); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use JDK 17+ and Maven. The POM compiles the shared integration helper and defaults `urnetwork.sdk.version` to `0.0.1-dev.0`. Install the matching local SDK or add `-Durnetwork.sdk.version=VERSION` to each Maven command to select an API-capable release. Run from `java/messages`:

```sh
mvn -q compile
mvn -q exec:java -Dexec.args=--self-test
mvn -q exec:java -Dexec.args=--version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
mvn -q exec:java -Dexec.args=self
mvn -q exec:java -Dexec.args=peers
mvn -q exec:java -Dexec.args=watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
mvn -q exec:java -Dexec.args="send $URNETWORK_PEER_CLIENT_ID hi"
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. The `Sdk.Device` wrapper has no subprotocol methods, so Java uses the C ABI through `Sdk.raw`, as [UrMessages.java](UrMessages.java) does: `urnet_device_local_enable_subprotocol`, `urnet_device_local_query_subprotocols`, `urnet_device_local_send_subprotocol_bytes`, `urnet_sub_close` with `urnet_release`, and `urnet_device_local_disable_subprotocol`. `urnet_device_local_subprotocol_stats` returns the counters as JSON (read it with `Sdk.takeString`), and `urnet_device_local_subprotocol_received_count` one ID's count. On Android, the gomobile SDK has the same calls as `DeviceLocal` methods instead: `enableSubprotocol`, `querySubprotocols`, `sendSubprotocolBytes`, `subprotocolStats`.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with protobuf-java. Add `com.google.protobuf:protobuf-java` at the version that matches your `protoc` (4.36.x for protoc 36.x; an older runtime cannot compile the generated code) and generate the `io.ur.examples.notes` classes with `protoc -I ../../go/messages/subprotocol --java_out=. notes.proto`:

```java
import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.jna.Memory;
import com.sun.jna.ptr.PointerByReference;
import io.ur.examples.notes.Ack;
import io.ur.examples.notes.Envelope;
import io.ur.examples.notes.Note;
import io.ur.sdk.Raw;
import io.ur.sdk.Sdk;
import java.nio.charset.StandardCharsets;

final class NotesProtocol {
  static final int SUBPROTOCOL = 4097;
  static final int MAX_MESSAGE_BYTES = 4608;

  static void send(long device, String destination, Envelope envelope) throws Exception {
    byte[] bytes = envelope.toByteArray();
    if (bytes.length > MAX_MESSAGE_BYTES)
      throw new Exception("envelope too large");
    try (Memory memory = new Memory(bytes.length)) {
      memory.write(0, bytes, 0, bytes.length);
      if (Sdk.raw.urnet_device_local_send_subprotocol_bytes(device, SUBPROTOCOL, destination, memory, bytes.length) == 0)
        throw new Exception("SDK did not enqueue message");
    }
  }

  // On the worker, with source and bytes copied in the listener.
  static void receive(long device, String source, byte[] bytes) throws Exception {
    Envelope envelope;
    try {
      envelope = Envelope.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      return; // malformed: drop and count, never acknowledge
    }
    switch (envelope.getKindCase()) {
      case NOTE -> {
        Note note = envelope.getNote();
        if (note.getMessageId() != 0 && note.getText().getBytes(StandardCharsets.UTF_8).length <= 4096)
          send(device, source, Envelope.newBuilder().setAck(Ack.newBuilder().setMessageId(note.getMessageId())).build());
      }
      case ACK -> {
        // match an outstanding note on (source, envelope.getAck().getMessageId())
      }
      default -> {
        // KIND_NOT_SET: no kind this version knows
      }
    }
  }
}
```

The listener copies before it returns and leaves parsing to the worker; keep it in a callback root, as [UrMessages.java](UrMessages.java) does:

```java
Raw.urnet_subprotocol_cb onNote = (u, protocol, source, pointer, length) -> {
  if (protocol == NotesProtocol.SUBPROTOCOL && source != null && length > 0 && length <= NotesProtocol.MAX_MESSAGE_BYTES)
    inbox.offer(new Received(source, pointer.getByteArray(0, length))); // a bounded queue; never block
};
var error = new PointerByReference();
long sub = Sdk.raw.urnet_device_local_enable_subprotocol(h, NotesProtocol.SUBPROTOCOL, onNote, null, error);
String message = Sdk.takeString(error.getValue());
if (message != null || sub == 0)
  throw new Exception(message != null ? message : "subprotocol registration failed");
NotesProtocol.send(h, destination, Envelope.newBuilder().setNote(Note.newBuilder().setMessageId(messageId).setText(text)).build());
```

`Received` is your record of the source and the copied bytes. Java's `long` holds the full unsigned 64-bit `message_id`; compare IDs with `==` and print them with `Long.toUnsignedString`.
