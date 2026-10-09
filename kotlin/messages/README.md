# Kotlin peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [Main.kt](src/main/kotlin/Main.kt) and [build.gradle.kts](build.gradle.kts); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use Gradle and JDK 21; the build selects Kotlin 2.2.20. It compiles [client/UrSession.java](../integration/client/UrSession.java), [UrMessages.java](../../java/messages/UrMessages.java) and [MessageCodec.java](../../java/messages/MessageCodec.java), so keep the adjacent Java directory. Install the matching SDK in Maven Local or add `-PsdkVersion=VERSION` to each Gradle command; the default is `0.0.1-dev.0`. Run from `kotlin/messages`:

```sh
gradle --console=plain build
gradle --console=plain run --args=--self-test
gradle --console=plain run --args=--version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
gradle --console=plain run --args=self
gradle --console=plain run --args=peers
gradle --console=plain run --args=watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
gradle --console=plain run --args="send $URNETWORK_PEER_CLIENT_ID hi"
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. On the JVM, Kotlin uses the C ABI through `Sdk.raw`, like the shared [UrMessages.java](../../java/messages/UrMessages.java): `urnet_device_local_enable_subprotocol`, `urnet_device_local_query_subprotocols`, `urnet_device_local_send_subprotocol_bytes`, `urnet_sub_close` with `urnet_release`, and `urnet_device_local_disable_subprotocol`; `urnet_device_local_subprotocol_stats` and `urnet_device_local_subprotocol_received_count` read the counters. On Android, the gomobile SDK has the same calls as `DeviceLocal` methods: `enableSubprotocol`, `querySubprotocols`, `sendSubprotocolBytes`, `subprotocolStats`.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with protobuf-kotlin. Add `com.google.protobuf:protobuf-kotlin` at the version that matches your `protoc` (4.36.x for protoc 36.x), and add `"io/ur/examples/notes/**"` to the `java.include(...)` list in [build.gradle.kts](build.gradle.kts), which otherwise compiles only the three shared Java files. Then generate the Java classes beside the shared Java example, as the [Java guide](../../java/messages/README.md#custom-protobuf-messages) does, and the Kotlin builders here, with `protoc -I ../../go/messages/subprotocol --java_out=../../java/messages --kotlin_out=src/main/kotlin notes.proto`:

```kotlin
import com.google.protobuf.InvalidProtocolBufferException
import com.sun.jna.Memory
import io.ur.examples.notes.Envelope
import io.ur.examples.notes.ack
import io.ur.examples.notes.envelope
import io.ur.examples.notes.note
import io.ur.sdk.Raw
import io.ur.sdk.Sdk

const val NOTES = 4097L
const val MAX_MESSAGE_BYTES = 4608

fun sendEnvelope(device: Long, destination: String, message: Envelope) {
    val bytes = message.toByteArray()
    require(bytes.size <= MAX_MESSAGE_BYTES) { "envelope too large" }
    Memory(bytes.size.toLong()).use { memory ->
        memory.write(0, bytes, 0, bytes.size)
        check(Sdk.raw.urnet_device_local_send_subprotocol_bytes(device, NOTES, destination, memory, bytes.size) != 0.toByte()) {
            "SDK did not enqueue message"
        }
    }
}

// On the worker, with source and bytes copied in the listener.
fun receiveEnvelope(device: Long, source: String, bytes: ByteArray) {
    val message = try {
        Envelope.parseFrom(bytes)
    } catch (e: InvalidProtocolBufferException) {
        return // malformed: drop and count, never acknowledge
    }
    when (message.kindCase) {
        Envelope.KindCase.NOTE ->
            if (message.note.messageId != 0L && message.note.text.toByteArray().size <= 4096) {
                sendEnvelope(device, source, envelope { ack = ack { messageId = message.note.messageId } })
            }
        Envelope.KindCase.ACK -> Unit // match an outstanding note on (source, message.ack.messageId)
        else -> Unit // KIND_NOT_SET: no kind this version knows
    }
}
```

The listener copies before it returns and leaves parsing to the worker; keep it in a callback root:

```kotlin
val onNote = Raw.urnet_subprotocol_cb { _, protocol, source, pointer, length ->
    if (protocol == NOTES && source != null && length in 1..MAX_MESSAGE_BYTES) {
        inbox.offer(source to pointer.getByteArray(0, length)) // a bounded queue; never block
    }
}
sendEnvelope(h, destination, envelope { note = note { messageId = id; text = body } })
```

Register `onNote` with `urnet_device_local_enable_subprotocol` and check its error exactly as `UrMessages.java` does for subprotocol 4096. Kotlin's `Long` holds the full unsigned 64-bit `message_id`; print it with `toULong()`.
