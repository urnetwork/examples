# Swift peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.swift](Sources/MessagesExample/main.swift), [Codec.swift](Sources/MessagesExample/Codec.swift) and [Package.swift](Package.swift); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use Swift 5.9+ and macOS 14+. The package depends on [URExampleIntegration](../integration/Package.swift). Point `URNETWORK_XCFRAMEWORK` at an absolute path to a matching local framework; alternatively, remove that override and set `URNETWORK_SDK_VERSION` to a published peer/subprotocol-capable release. Run from `swift/messages`:

```sh
export URNETWORK_XCFRAMEWORK='/absolute/path/to/URnetworkSdk.xcframework'
swift build
swift run MessagesExample --self-test
swift run MessagesExample --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

The standalone codec test needs no SDK or XCFramework:

```sh
swiftc Sources/MessagesExample/Codec.swift tests/main.swift -o /tmp/urms-swift-codec-test
/tmp/urms-swift-codec-test
```

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
swift run MessagesExample self
swift run MessagesExample peers
swift run MessagesExample watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
swift run MessagesExample send $URNETWORK_PEER_CLIENT_ID hi
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. Swift uses the gomobile `SdkDeviceLocal` methods that [main.swift](Sources/MessagesExample/main.swift) already calls: `enableSubprotocol(_:listener:)`, `querySubprotocols(_:timeoutMillis:callback:)`, `sendSubprotocolBytes(_:destinationClientId:messageBytes:)` and `disableSubprotocol(_:)`, with `subprotocolStats()` and `subprotocolReceivedCount(_:)` for the counters.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with SwiftProtobuf. Add the `swift-protobuf` package (product `SwiftProtobuf`) at 1.27.0 or later, which added `serializedBytes()` and `init(serializedBytes:)`; with Swift 5.9, stay below 1.30, which needs Swift 5.10. Generate `notes.pb.swift` with the `protoc-gen-swift` of the same release: `protoc -I ../../go/messages/subprotocol --swift_out=Sources/MessagesExample notes.proto`:

```swift
import Foundation
import SwiftProtobuf
import URExampleIntegration
import URnetworkSdk

let notesSubprotocol: Int32 = 4097
let maxMessageBytes = 4608

func sendEnvelope(_ device: SdkDeviceLocal, _ destination: SdkId?, _ envelope: Notes_V1_Envelope) throws {
  let data: Data = try envelope.serializedBytes()
  guard data.count <= maxMessageBytes else { throw ClientSetupError("envelope too large") }
  guard device.sendSubprotocolBytes(notesSubprotocol, destinationClientId: destination, messageBytes: data)
  else { throw ClientSetupError("SDK did not enqueue message") }
}

// On the worker, with source and bytes copied in the listener.
func receiveEnvelope(_ device: SdkDeviceLocal, _ source: SdkId?, _ data: Data) throws {
  guard let envelope = try? Notes_V1_Envelope(serializedBytes: data) else {
    return  // malformed: drop and count, never acknowledge
  }
  switch envelope.kind {
  case .note(let note)? where note.messageID != 0 && note.text.utf8.count <= 4096:
    var reply = Notes_V1_Envelope()
    reply.ack.messageID = note.messageID
    try sendEnvelope(device, source, reply)
  case .ack(let ack)?:
    _ = ack.messageID  // match an outstanding note on (source, ack.messageID)
  default:
    break  // nil, an invalid note, or a kind from a later version
  }
}

func sendNote(_ device: SdkDeviceLocal, _ destination: SdkId?, _ messageID: UInt64, _ text: String) throws {
  var envelope = Notes_V1_Envelope()
  envelope.note.messageID = messageID
  envelope.note.text = text
  try sendEnvelope(device, destination, envelope)
}
```

In `subprotocolMessage(_:sourceClientId:messageBytes:)`, check `subprotocolId == notesSubprotocol` and `1...maxMessageBytes`, and copy with `withUnsafeBytes` before returning, as `main.swift` does: the `Data` the SDK passes is valid only during the call. Setting `envelope.note` or `reply.ack` selects that `oneof` case.
