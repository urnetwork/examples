# Go peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.go](main.go), [codec.go](codec.go), [codec_test.go](codec_test.go) and [go.mod](go.mod); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use Go 1.26.7+. The checked-in calendar SDK pin is a release baseline; select a release containing peer/subprotocol APIs with `go get github.com/urnetwork/sdk/v2026@VERSION` and `go mod tidy` before building. The integration module is linked by a local replacement. Run from `go/messages`:

```sh
go build -o messages-example .
go test ./...
./messages-example --self-test
./messages-example --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

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

## Your own subprotocol and protobuf messages

[subprotocol](subprotocol/) is the compiled form of [Subprotocols](../../SUBPROTOCOLS.md): a protobuf protocol, [notes.proto](subprotocol/notes.proto), on subprotocol **4097** beside URMS. [device.go](subprotocol/device.go) sends and receives it through a Device, and [client.go](subprotocol/client.go) registers it on a connect client with a size-bounded `connect.ProtoCodec`. The tests run both paths between two connect clients wired in-process, with no credentials and no network, and [snippets_test.go](subprotocol/snippets_test.go) compiles the Go snippets of the guide and of this section. Run from `go/messages`:

```sh
go test ./subprotocol
```

The snippets import the package as `notes "github.com/urnetwork/examples/go/messages/subprotocol"`. On a Device, the listener bounds and copies each message, `Supported` queries the peer, and `Receive` parses a message and acknowledges a note off the receive path:

```go
listener := notes.NewListener(64)
sub, err := device.EnableSubprotocol(notes.Id, listener)
if err != nil {
	return err
}
defer sub.Close()

if supported, err := notes.Supported(device, peer, 10*time.Second); err != nil || !supported {
	return errors.New("peer support unknown or absent")
}
messageId, err := notes.NewMessageId()
if err != nil {
	return err
}
if err := notes.Send(device, peer, notes.NewNote(messageId, "hello")); err != nil {
	return err
}
for received := range listener.Received {
	envelope, err := notes.Receive(device, received) // parses, and acknowledges a note
	if err != nil {
		continue // malformed: dropped, never acknowledged
	}
	if ack := envelope.GetAck(); ack != nil && received.Source.String() == peer.String() && ack.GetMessageId() == messageId {
		return nil // the peer accepted the note
	}
}
```

A Go program that holds its own `*connect.Client` registers the same protocol instead. The codec drops and counts what `Parse` would refuse, so the handler only clones and queues, and `SendClient` passes the codec with `connect.WithSubprotocolCodec`:

```go
queue := make(chan notes.Delivery, 64)
unregister, err := notes.Register(client, queue)
if err != nil {
	return err
}
defer unregister()

if supported, err := notes.SupportedClient(ctx, client, peer); err != nil || !supported {
	return errors.New("peer support unknown or absent")
}
messageId, err := notes.NewMessageId()
if err != nil {
	return err
}
if err := notes.SendClient(client, peer, notes.NewNote(messageId, "hello"), func(err error) {}); err != nil {
	return err
}
for delivery := range queue {
	if note := delivery.Envelope.GetNote(); note != nil {
		if err := notes.SendClient(client, delivery.Source, notes.NewAck(note.GetMessageId()), func(err error) {}); err != nil {
			return err
		}
	} else if ack := delivery.Envelope.GetAck(); delivery.Source == peer && ack.GetMessageId() == messageId {
		return nil // the peer accepted the note
	}
}
```

`notes.Id` is 4097 and `notes.MaxMessageBytes` is 4608. After editing [notes.proto](subprotocol/notes.proto), run `go generate ./subprotocol`, which needs `protoc` and protoc-gen-go v1.36.11. Its tests pass against the module's pinned SDK and connect releases and against `v2026.10.8-1066946420`, whose subprotocol code matches `main`.
