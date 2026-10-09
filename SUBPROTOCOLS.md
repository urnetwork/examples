# Application subprotocols

A subprotocol carries an application's own messages between clients of one network over the URnetwork transfer layer. Every message has a 16-bit subprotocol ID and bytes that only the two applications interpret. The [messages examples](README.md) run [URMS v1](MESSAGES_PROTOCOL.md) on subprotocol **4096**. This guide shows how to register an ID of your own on a Device, check that a peer supports it, send and receive, read the stats, and carry protobuf messages with versioning and acknowledgements. Each language's Messages guide ends with that binding's protobuf snippet. [go/messages/subprotocol](go/messages/subprotocol/) is the compiled and tested Go form, and its [snippets_test.go](go/messages/subprotocol/snippets_test.go) compiles every Go snippet below.

The names below were checked on 2026-10-09 against sdk and connect `main`: the Device API in [device_local_subprotocol.go](https://github.com/urnetwork/sdk/blob/main/device_local_subprotocol.go), the C ABI in [cgo/include/urnetwork_sdk.h](https://github.com/urnetwork/sdk/blob/main/cgo/include/urnetwork_sdk.h), the JS/TS API in [js/src/subprotocol.ts](https://github.com/urnetwork/sdk/blob/main/js/src/subprotocol.ts), and the transfer layer in connect's [subprotocol.go](https://github.com/urnetwork/connect/blob/main/subprotocol.go). connect's [SUBPROTOCOL.md](https://github.com/urnetwork/connect/blob/main/SUBPROTOCOL.md) records the design; §10 is the final design, and where the design and the code differ, the code is authoritative. Peer messaging (URmessage) has moved out of connect and the core SDK into [github.com/urnetwork/message](https://github.com/urnetwork/message) (connect#219, sdk#158); the subprotocol layer stays in connect and the core SDK.

## Choose an ID

| ID | Owner |
| --- | --- |
| `0` | Invalid |
| `1` through `1023` | The network. `SubprotocolReservedLimit` is `1024`. |
| `1024` through `65535` | Applications. URMS uses `4096`; the protobuf example uses `4097`. |

`EnableSubprotocol` refuses an ID outside `1` through `65535` and any ID below `1024` (`checkDeviceSubprotocolId`). connect's `RegisterSubprotocol` and `AddSubprotocolRawCallback` refuse `0` and the reserved IDs (`checkSubprotocolId`), and JS `enableSubprotocol` accepts `1024` through `65535`. Sends check only `1` through `65535`, so on that side the rule is yours: never send below `1024`. A peer query answer lists only IDs at or above `1024`.

The ID is the only type information on the wire, and there is no registry: an ID means what the clients of your network agree it means. Give each protocol one ID, document its encoding next to it and keep both for the protocol's lifetime. An incompatible change takes a new ID, so old and new peers can run side by side and the peer query tells a sender which one to use.

## Register on the Device

A local Device (`DeviceLocal`) owns the registrations. They are applied to the Device's own client, the one peers address by its client ID, and applied again when the Device replaces that client, so an application can enable its IDs as soon as the Device exists.

| `DeviceLocal` method | Behavior |
| --- | --- |
| `EnableSubprotocol(subprotocolId int32, listener SubprotocolListener) (Sub, error)` | Adds a listener for the ID. Any number of listeners may share an ID. A nil listener and an invalid or reserved ID are refused. `Sub.Close()` removes this listener; the ID's registration goes with its last listener. |
| `DisableSubprotocol(subprotocolId int32)` | Removes every listener of the ID. |
| `EnabledSubprotocols() *IntList` | The enabled IDs, sorted. |
| `QuerySubprotocols(destinationClientId *Id, timeoutMillis int64, callback SubprotocolsQueryCallback)` | Asks the peer which IDs it can receive and answers once with `Result(subprotocolIds *IntList, ok bool)` from a worker; the caller never blocks. A timeout of zero or less means 10 seconds. |
| `SendSubprotocolBytes(subprotocolId int32, destinationClientId *Id, messageBytes []byte) bool` | Copies the bytes into one message and enqueues it; the caller keeps its slice. `true` means enqueued. |
| `SubprotocolReceivedCount(subprotocolId int32) int64` | Messages delivered to the ID's listeners. |
| `SubprotocolStats() *SubprotocolStats` | The counters of the Device's client; see [Stats](#stats). |

A listener implements `SubprotocolMessage(subprotocolId int32, sourceClientId *Id, messageBytes []byte)`. In Go:

```go
type received struct {
	source *sdk.Id
	bytes  []byte
}

type listener struct{ received chan received }

func (self *listener) SubprotocolMessage(subprotocolId int32, source *sdk.Id, messageBytes []byte) {
	// inline on the receive goroutine; messageBytes is borrowed until return
	if subprotocolId != 4097 || source == nil || 4608 < len(messageBytes) {
		return
	}
	select {
	case self.received <- received{source: source, bytes: slices.Clone(messageBytes)}:
	default: // full: drop and count, never block
	}
}
```

```go
l := &listener{received: make(chan received, 64)}
sub, err := device.EnableSubprotocol(4097, l) // keep sub and l for the session
if err != nil {
	return err
}
defer sub.Close()
```

The lifecycle matches [URMS](MESSAGES_PROTOCOL.md#discovery-and-send-lifecycle):

1. **Enable** the ID before you query or send, and keep the `Sub` and the listener for as long as the protocol runs. As the messages examples do, call `SetProvideMode(ProvideModeNetwork)` so the Device can receive messages from its network.
2. **Query** the selected peer with a bounded timeout and send only when the answer includes your ID. A peer answers with the application IDs that have a listener or codec on its client. `ok == false` means the query failed, timed out or reached a peer too old to answer: support is unknown, not absent. The answer describes that moment; query again after a reconnect and before a later send.
3. **Send** with `SendSubprotocolBytes` and check the result. `false` means no client is attached, the destination is nil or the control ID, the ID is outside `1` through `65535`, or the enqueue failed.
4. **Receive** in the listener: check the ID and a length bound, copy the bytes, enqueue them for a worker, and return. Parse, act and reply on the worker.
5. **Stop** by closing each `Sub` (or `DisableSubprotocol` for all of an ID's listeners), then drain workers and close the Device.

### Receive lifetime

- The listener runs inline on the client's receive goroutine and must not block. A slow listener slows delivery on the receive sequence it runs on, so queue work elsewhere and drop when the queue is full.
- `messageBytes` is one pooled copy that the SDK releases when the listeners return (`deliver` in `device_local_subprotocol.go`). Copy what you keep. Bound the length before copying.
- The Go SDK allocates `sourceClientId` for each message, so Go may keep it. C ABI strings and buffers passed to callbacks are valid only during the call; copy the source string too.
- Never send from the listener. `SendSubprotocolBytes` can wait for room in the send queue; reply from the worker.
- FFI bindings must keep the callback object reachable after Device close, because a late callback can race native close; the messages examples keep a small process-lifetime callback root.

### Delivery and size

A subprotocol message travels as one frame through the same send machinery as other connect frames. The Device API reports only the enqueue: it exposes no transport acknowledgement, a peer older than subprotocols drops the frame, and nothing survives a restart. Only an application acknowledgement confirms that the receiving application accepted a message; see [Acknowledgements and correlation](#acknowledgements-and-correlation).

A message is never fragmented. Each transport's message limit has a floor of `ClientSettings.MinimumMessageLenLimit()`, 16 KiB on connect `main` (`transfer.go`), before pack and encryption overhead. Keep messages to a few KiB and enforce a protocol maximum on both send and receive: URMS uses 4112 bytes and the protobuf example 4608. The JS/TS companion RPC refuses frames above 65535 bytes. Split larger data across messages in the application.

## Stats

`SubprotocolStats()` reads the counters of the Device's current client. They are monotonic for that client and all zero while no client is attached. `SubprotocolReceivedCount(id)` is `0` for an ID that is not enabled, and the count goes with the ID's last listener.

| Field | Counts |
| --- | --- |
| `Sent`, `SentByteCount` | Messages enqueued and their bytes, including the few bytes of subprotocol header |
| `Received`, `ReceivedByteCount` | Messages delivered for an ID with a listener or codec, and their payload bytes |
| `DroppedUnregistered` | Messages for an ID with no listener or codec on this client |
| `DroppedDecode` | Malformed subprotocol or query frames, and (connect only) payloads a registered codec refused |
| `MarshalOverrun` | connect codecs whose `Size` disagreed with `MarshalAppend` |
| `QueriesSent`, `QueriesAnswered`, `QueryReplyDrops` | Queries this client sent, queries it answered, and answers it could not enqueue |

Device listeners never decode, so parse failures on the Device path are the application's to count. The C ABI returns the stats as JSON with these field names, and C++ as `urnet::SubprotocolStats`.

## Bindings

| Binding | Subprotocol API on sdk `main` |
| --- | --- |
| Go | The `sdk.DeviceLocal` methods above. A Go program holding a companion `DeviceRemote` uses `OpenSubprotocolContext(ctx, id)`, whose `RemoteSubprotocol` has `Receive`, `Send`, `Query` and `Close`; the JS/TS binding wraps it. |
| Android, Apple (gomobile) | The same `DeviceLocal` methods with `SubprotocolListener` and `SubprotocolsQueryCallback`: `com.bringyour.sdk.DeviceLocal.enableSubprotocol(int, SubprotocolListener)` in Java and Kotlin, `try device.enableSubprotocol(_:listener:)` in Swift. The mobile build's `sdk_mobile_bind` tag keeps the companion RPC types out, and `build/cmd/mobileexports` fails the build if a `DeviceLocal` subprotocol method is skipped. sdk#153, still open, would allowlist those RPC types instead; `main` does not need it. |
| C | `urnet_device_local_enable_subprotocol`, `_disable_subprotocol`, `_enabled_subprotocols`, `_query_subprotocols`, `_send_subprotocol_bytes`, `_subprotocol_received_count` and `_subprotocol_stats`, the callbacks `urnet_subprotocol_cb` and `urnet_subprotocols_query_cb` (which receives the IDs as a JSON array), and `URNET_SUBPROTOCOL_RESERVED_LIMIT`. Close a subscription with `urnet_sub_close`, then `urnet_release`. |
| C++ | `urnet::DeviceLocal::enableSubprotocol`, `disableSubprotocol`, `enabledSubprotocols`, `querySubprotocols`, `sendSubprotocolBytes`, `subprotocolReceivedCount` and `subprotocolStats` in `urnetwork_sdk.hpp`. The returned `urnet::Sub` closes on destruction. |
| Java, Kotlin (JVM) | The C ABI through JNA: `Sdk.raw` (`io.ur.sdk.Raw`) and `Raw.urnet_subprotocol_cb`. The `Sdk.Device` wrapper has no subprotocol methods. |
| C# | The C ABI: `URnetwork.SDK.Raw`. The `Device` wrapper has no subprotocol methods. |
| Python | The C ABI: `urnetwork.raw`, with the callback types in `urnetwork._raw`. The `urnetwork.Device` wrapper has no subprotocol methods. |
| Ruby | The C ABI: `URnetwork::Raw`. The `URnetwork::Device` wrapper has no subprotocol methods. |
| Rust | The C ABI: `urnetwork_sdk::native()` returns `raw::Raw`. The `Device` wrapper has no subprotocol methods. |
| JavaScript, TypeScript | `device.enableSubprotocol(id, listener)` resolves to a `SubprotocolSubscription` with `send`, `querySubprotocols`, `close` and `closed`, through the [native companion](javascript/integration/companion/README.md) only. There is no disable-all, enabled list, received count or stats, and hosted proxy devices reject subprotocols. |

## Go programs with a connect client

A Go program that holds its own `*connect.Client` can register a typed codec instead of raw bytes. The Device itself uses only raw callbacks on its client, so a program built on a Device uses the Device API. In these snippets `notes` is the package generated from [notes.proto](#the-example-schema): `notes "github.com/urnetwork/examples/go/messages/subprotocol"`.

```go
id := connect.SubprotocolId(4097)

unregister, err := connect.RegisterSubprotocol(client, id, connect.ProtoCodec[*notes.Envelope](),
	func(source connect.TransferPath, envelope *notes.Envelope, peer connect.Peer) {
		// inline on the receive goroutine; envelope is borrowed until return
		select {
		case queue <- proto.Clone(envelope).(*notes.Envelope):
		default:
		}
	})
if err != nil {
	return err
}
defer unregister()

remove, err := client.AddSubprotocolRawCallback(id,
	func(source connect.TransferPath, subprotocolId connect.SubprotocolId, messageBytes []byte, peer connect.Peer) {
		// the raw bytes, before the codec runs; borrowed until return
	})
if err != nil {
	return err
}
defer remove()

ids, err := client.QuerySubprotocols(ctx, destinationId)
if err != nil || !slices.Contains(ids, id) {
	return errors.New("peer support unknown or absent")
}
if !connect.SendSubprotocol(client, id, envelope, destinationId, func(err error) {
	// the transport acknowledged the message, or err; not the peer application's Ack
}) {
	return errors.New("not enqueued")
}
log.Printf("%+v", client.SubprotocolStats()) // ReceivedById counts each registered id
```

- `RegisterSubprotocol[T](client, id, codec, handler)` allows one codec per ID; a second registration is an error. `AddSubprotocolRawCallback` allows any number of raw callbacks. For each message the raw callbacks run first, then the codec and the handler. A payload the codec refuses is counted in `DroppedDecode` and never reaches the handler, and a message for an ID with neither is counted in `DroppedUnregistered`.
- `ProtoCodec[T proto.Message]()` adapts any protobuf message type. A hand-rolled encoding implements `SubprotocolCodec[T]` (`Size`, `MarshalAppend`, `Unmarshal`) and may add `SubprotocolMessagePool[T]` (`New`, `Release`) to own its instances. Wrap a codec to add your size limit and validation, as [client.go](go/messages/subprotocol/client.go) does.
- `SendSubprotocol` uses the registered codec, or `WithSubprotocolCodec(codec)` passed as an option when the client only sends. It waits for room in the send queue; `SendSubprotocolWithTimeout` bounds the wait, and `SendSubprotocolMulti` and `SendSubprotocolMultiHop` follow `SendMulti` and `SendMultiHop`. Send from a worker, never from a handler.
- `client.SendSubprotocolBytes(id, messageBytes, destinationId, ackCallback)` takes ownership of `messageBytes` (connect returns it to its message pool), so pass a slice you do not use again. `DeviceLocal.SendSubprotocolBytes` passes connect a copy for you.
- A handler's message and a raw callback's bytes are borrowed until it returns. Clone the message, or keep raw bytes with `RetainSubprotocolBytes`, which returns a pooled copy and its release function.

## Protobuf messages

The subprotocol carries bytes, so protobuf runs on top of any binding: marshal a message, send its bytes with `SendSubprotocolBytes`, and parse the bytes a listener copied. Use one schema per subprotocol ID, a size limit on both sides, strict parsing and explicit acknowledgements.

### One ID per schema, or one envelope

Protobuf bytes do not say which message type they are, and parsing bytes as the wrong type usually succeeds with default values and unknown fields. Each subprotocol ID therefore has exactly one top-level message type. Two layouts follow from that:

- **One envelope with a `oneof`** (recommended for one protocol). Every message of the ID is an `Envelope` whose `oneof` case is the message kind. One enable, one listener and one query answer cover the protocol; acknowledgements and correlation IDs live in the same schema; and a kind added later reaches an old receiver as an envelope with no kind it knows, which it can drop. The cost is a tag and a length per message.
- **One ID per schema.** Each message type has its own ID, so the peer query advertises each type separately and `SubprotocolReceivedCount` counts each one. Use it for unrelated message types with separate owners or lifecycles. Every type then needs its own registration, and acknowledgements need their own ID or a field in each type.

### The example schema

[notes.proto](go/messages/subprotocol/notes.proto) is the protobuf protocol on subprotocol 4097: the URMS TEXT and ACK exchange as protobuf, with an envelope of at most 4608 bytes and note text of at most 4096 UTF-8 bytes.

```proto
syntax = "proto3";

package notes.v1;

option go_package = "github.com/urnetwork/examples/go/messages/subprotocol";
option java_package = "io.ur.examples.notes";
option java_multiple_files = true;

message Envelope {
  oneof kind {
    Note note = 1;
    Ack ack = 2;
  }
}

message Note {
  uint64 message_id = 1;
  string text = 2;
}

message Ack {
  uint64 message_id = 1;
}
```

The package name is not on the wire; the example keeps it short, and an application should use its own.

### Generate code

Generate from the shared file, for example `protoc -I go/messages/subprotocol --python_out=. notes.proto`, and add the language's runtime library at the release that matches your `protoc`. For protoc 36.x that is protobuf-java and protobuf-kotlin 4.36.x, Python `protobuf` 7.36.x and Google.Protobuf 3.36.x; Java and Python code generated by a newer `protoc` fails against an older runtime.

| Language | Generator | Runtime |
| --- | --- | --- |
| Go | `protoc --go_out` (protoc-gen-go) | `google.golang.org/protobuf` |
| Python | `protoc --python_out` | `protobuf` |
| JavaScript, TypeScript | `protoc-gen-es` (`@bufbuild/protoc-gen-es`) | `@bufbuild/protobuf` |
| Java | `protoc --java_out` | `com.google.protobuf:protobuf-java` |
| Kotlin | `protoc --java_out --kotlin_out` | `com.google.protobuf:protobuf-kotlin` |
| Swift | `protoc --swift_out` (protoc-gen-swift) | `SwiftProtobuf` |
| Rust | `prost-build` in `build.rs` | `prost` |
| C# | `protoc --csharp_out` | `Google.Protobuf` |
| C++ | `protoc --cpp_out` | libprotobuf |
| C | `protoc --c_out` (protoc-gen-c) | protobuf-c |
| Ruby | `protoc --ruby_out` | `google-protobuf` |

### Send

Validate what the schema cannot express, build the envelope, marshal, check the size and send:

```go
if messageId == 0 || 4096 < len(text) || !utf8.ValidString(text) {
	return errors.New("invalid note")
}
envelope := &notes.Envelope{Kind: &notes.Envelope_Note{Note: &notes.Note{MessageId: messageId, Text: text}}}
messageBytes, err := proto.Marshal(envelope)
if err != nil || 4608 < len(messageBytes) {
	return errors.New("invalid or oversized envelope")
}
if !device.SendSubprotocolBytes(4097, destination, messageBytes) {
	return errors.New("not enqueued")
}
```

### Receive and parse

On the worker, parse each copied message strictly:

```go
envelope := &notes.Envelope{}
if 4608 < len(r.bytes) || proto.Unmarshal(r.bytes, envelope) != nil {
	return errors.New("malformed envelope") // drop and count; never acknowledge
}
switch kind := envelope.Kind.(type) {
case *notes.Envelope_Note:
	if kind.Note.MessageId == 0 || 4096 < len(kind.Note.Text) {
		return errors.New("invalid note")
	}
	// accept the note, then acknowledge it to its source
	ack := &notes.Envelope{Kind: &notes.Envelope_Ack{Ack: &notes.Ack{MessageId: kind.Note.MessageId}}}
	ackBytes, err := proto.Marshal(ack)
	if err != nil || !device.SendSubprotocolBytes(4097, r.source, ackBytes) {
		return errors.New("ack not enqueued")
	}
case *notes.Envelope_Ack:
	// match an outstanding note on (r.source, kind.Ack.MessageId)
default:
	return errors.New("no kind this version knows") // drop and count
}
```

- Check the length before parsing. The listener already refused oversized messages, and the worker checks again.
- proto3 has no required fields. A missing field parses as its default (`0`, `""`), so validate what your protocol requires after parsing: a kind is set, `message_id` is not zero, the text is within its limit.
- Treat a parse error as a malformed message: drop it, count it, and never acknowledge it. Go's `proto.Unmarshal` also rejects invalid UTF-8 in a `string` field; where a runtime does not check, validate strings yourself or use `bytes`.

### Versioning

Peers upgrade at different times, so every change must parse on both versions:

- The wire carries field numbers and wire types, not names. Never change the number or type of a field in use, and never reuse the number or name of a removed field; reserve them.
- Add a field with a new number. An old receiver skips fields it does not know, so the message still parses, and the new field reads as its default on peers that do not send it.
- A new `oneof` kind reaches an old receiver as an envelope with no kind it knows. It must drop that envelope and must not acknowledge it, so a sender of a new kind needs an acknowledgement timeout or a capability check before it relies on the kind.
- An incompatible change takes a new subprotocol ID (a `notes.v2` on 4098, for example). Enable both IDs during the migration and let the peer query choose; renaming the protobuf package does not change the wire.
- Serialized bytes are not canonical across runtimes and versions. Do not expect a peer to reproduce them for a hash or signature; sign the exact bytes you send, in a `bytes` field.

```proto
message Note {
  reserved 3;
  reserved "priority";
  uint64 message_id = 1;
  string text = 2;
  int64 sent_millis = 4; // added later; old receivers skip it
}
```

### Acknowledgements and correlation

The application acknowledgement and its correlation ID belong in the application message, as URMS ACK does:

- A Device send reports only the enqueue, and connect's ack callback reports the transport's acknowledgement. Neither says that the receiving application parsed and accepted the message.
- Give each request a nonzero 64-bit `message_id` the sender chooses, unique among its outstanding messages to that peer. A retry of the same message keeps its ID.
- The receiver replies to the message's source client with an `Ack` holding the same `message_id`, after it has validated and accepted the message. It never acknowledges a malformed message or an `Ack`.
- The sender matches an `Ack` on **(source client ID, message_id)**. An unsolicited `Ack`, or one from another client, matches nothing. A timeout leaves delivery uncertain.
- A receiver that may see retries suppresses duplicate processing by (source client ID, message_id) and acknowledges the duplicate again.

The tests in [subprotocol_test.go](go/messages/subprotocol/subprotocol_test.go) run this exchange between two in-process connect clients on both the Device path and the connect path, and check the borrowed-bytes rule, the size bound, unknown fields and kinds, the registration rules and the stats.
