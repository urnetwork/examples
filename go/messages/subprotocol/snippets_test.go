package subprotocol_test

// The Go snippets of SUBPROTOCOLS.md and go/messages/README.md, verbatim
// inside functions so that go vet and go test compile them. They need a live
// Device or connect client, so the tests do not call them; subprotocol_test.go
// runs the same steps between two in-process clients.

import (
	"context"
	"errors"
	"log"
	"slices"
	"time"
	"unicode/utf8"

	connect "github.com/urnetwork/connect/v2026"
	notes "github.com/urnetwork/examples/go/messages/subprotocol"
	sdk "github.com/urnetwork/sdk/v2026"
	"google.golang.org/protobuf/proto"
)

// SUBPROTOCOLS.md: Register on the Device

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

func enable(device *sdk.DeviceLocal) error {
	l := &listener{received: make(chan received, 64)}
	sub, err := device.EnableSubprotocol(4097, l) // keep sub and l for the session
	if err != nil {
		return err
	}
	defer sub.Close()
	return nil
}

// SUBPROTOCOLS.md: Go programs with a connect client

func connectPath(ctx context.Context, client *connect.Client, destinationId connect.Id, envelope *notes.Envelope, queue chan *notes.Envelope) error {
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
	return nil
}

// SUBPROTOCOLS.md: Send

func sendNote(device *sdk.DeviceLocal, destination *sdk.Id, messageId uint64, text string) error {
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
	return nil
}

// SUBPROTOCOLS.md: Receive and parse

func handle(device *sdk.DeviceLocal, r received) error {
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
	return nil
}

// go/messages/README.md: the Device path with this package

func devicePath(device *sdk.DeviceLocal, peer *sdk.Id) error {
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
	return nil
}

// go/messages/README.md: the connect path with this package

func clientPath(ctx context.Context, client *connect.Client, peer connect.Id) error {
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
	return nil
}
