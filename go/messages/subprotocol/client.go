package subprotocol

import (
	"context"
	"slices"

	connect "github.com/urnetwork/connect/v2026"
	"google.golang.org/protobuf/proto"
)

// For Go programs that hold their own connect client. connect.ProtoCodec
// marshals the envelope straight into the frame's pooled buffer and parses it
// from the received frame without another copy; this codec adds the
// protocol's receive rules, so connect drops and counts what Parse would
// refuse before the handler runs.
type codec struct {
	connect.SubprotocolCodec[*Envelope]
}

// connect.ProtoCodec for *Envelope that refuses more than MaxMessageBytes
// before parsing and an envelope that Validate refuses after.
func Codec() connect.SubprotocolCodec[*Envelope] {
	return codec{connect.ProtoCodec[*Envelope]()}
}

func (self codec) Unmarshal(b []byte, envelope *Envelope) error {
	if MaxMessageBytes < len(b) {
		return ErrTooLarge
	}
	if err := self.SubprotocolCodec.Unmarshal(b, envelope); err != nil {
		return err
	}
	return Validate(envelope)
}

// One envelope the handler queued, with its source client.
type Delivery struct {
	Source   connect.Id
	Envelope *Envelope
}

// Registers this protocol on a connect client. The handler runs inline on the
// client's receive goroutine and the envelope is borrowed until it returns, so
// it clones and queues, dropping when the queue is full. A message the codec
// refuses is counted in client.SubprotocolStats().DroppedDecode and never
// reaches the handler. Unregister with the returned func.
func Register(client *connect.Client, queue chan<- Delivery) (unregister func(), err error) {
	return connect.RegisterSubprotocol(
		client,
		Id,
		Codec(),
		func(source connect.TransferPath, envelope *Envelope, peer connect.Peer) {
			select {
			case queue <- Delivery{Source: source.SourceId, Envelope: proto.Clone(envelope).(*Envelope)}:
			default:
			}
		},
	)
}

// Validates one envelope and enqueues it with this protocol's codec, so a
// client that only sends needs no registration. It waits for room in the
// send queue: call it from a worker, never from a receive handler. The ack
// callback reports the transport's acknowledgement of the message, not the
// peer application's Ack.
func SendClient(client *connect.Client, destination connect.Id, envelope *Envelope, ackCallback connect.AckFunction) error {
	if err := Validate(envelope); err != nil {
		return err
	}
	if MaxMessageBytes < proto.Size(envelope) {
		return ErrTooLarge
	}
	sent := connect.SendSubprotocol(
		client,
		Id,
		envelope,
		destination,
		ackCallback,
		connect.WithSubprotocolCodec(Codec()),
	)
	if !sent {
		return ErrNotEnqueued
	}
	return nil
}

// Asks the peer which subprotocols it supports and reports whether this one
// is among them. An error (ctx expired, or a peer older than the query) says
// nothing about the peer.
func SupportedClient(ctx context.Context, client *connect.Client, destination connect.Id) (bool, error) {
	ids, err := client.QuerySubprotocols(ctx, destination)
	if err != nil {
		return false, err
	}
	return slices.Contains(ids, Id), nil
}
