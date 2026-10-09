package subprotocol

import (
	"errors"
	"slices"
	"sync/atomic"
	"time"

	sdk "github.com/urnetwork/sdk/v2026"
)

// The Device subprotocol methods this example uses, with the signatures that
// *sdk.DeviceLocal declares (sdk device_local_subprotocol.go). The tests drive
// an in-process stand-in through the same interface.
type Device interface {
	EnableSubprotocol(subprotocolId int32, listener sdk.SubprotocolListener) (sdk.Sub, error)
	SendSubprotocolBytes(subprotocolId int32, destinationClientId *sdk.Id, messageBytes []byte) bool
	QuerySubprotocols(destinationClientId *sdk.Id, timeoutMillis int64, callback sdk.SubprotocolsQueryCallback)
}

var _ Device = (*sdk.DeviceLocal)(nil)

var (
	ErrNotEnqueued = errors.New("the SDK did not enqueue the message")
	ErrNoAnswer    = errors.New("the peer did not answer the subprotocol query")
)

// Marshals one envelope and hands it to the device. nil means the SDK accepted
// the bytes for sending, not that the peer received them; only its Ack does.
// The device copies the bytes, so the caller keeps its slice.
func Send(device Device, destination *sdk.Id, envelope *Envelope) error {
	messageBytes, err := Marshal(envelope)
	if err != nil {
		return err
	}
	if !device.SendSubprotocolBytes(Id, destination, messageBytes) {
		return ErrNotEnqueued
	}
	return nil
}

// One message the listener copied: its source client and its own bytes. The
// Go SDK allocates the source id for each message, so it can be kept.
type Received struct {
	Source *sdk.Id
	Bytes  []byte
}

// The device listener of this subprotocol. SubprotocolMessage runs inline on
// the client's receive goroutine, and the bytes are borrowed until it returns,
// so it only bounds, copies and queues. Receive parses on the application's
// goroutine.
type Listener struct {
	Received chan Received
	// messages refused for their size or dropped because the queue was full
	Dropped atomic.Uint64
}

func NewListener(queueSize int) *Listener {
	return &Listener{Received: make(chan Received, queueSize)}
}

func (self *Listener) SubprotocolMessage(subprotocolId int32, sourceClientId *sdk.Id, messageBytes []byte) {
	if subprotocolId != Id || sourceClientId == nil || MaxMessageBytes < len(messageBytes) {
		self.Dropped.Add(1)
		return
	}
	select {
	case self.Received <- Received{Source: sourceClientId, Bytes: slices.Clone(messageBytes)}:
	default:
		// never block the receive path; the sender's Ack timeout covers the loss
		self.Dropped.Add(1)
	}
}

// Parses one queued message and acknowledges a valid note to its source. A
// message that does not parse is returned as an error and never acknowledged,
// and an Ack is never acknowledged.
func Receive(device Device, received Received) (*Envelope, error) {
	envelope, err := Parse(received.Bytes)
	if err != nil {
		return nil, err
	}
	if note := envelope.GetNote(); note != nil {
		if err := Send(device, received.Source, NewAck(note.GetMessageId())); err != nil {
			return envelope, err
		}
	}
	return envelope, nil
}

// Asks the peer which subprotocols it supports. true means it advertised this
// one, and false with a nil error means it answered without it. ErrNoAnswer
// means a timeout, a failed query or a peer older than the query, which says
// nothing about the peer.
func Supported(device Device, destination *sdk.Id, timeout time.Duration) (bool, error) {
	answers := make(queryCallback, 1)
	device.QuerySubprotocols(destination, timeout.Milliseconds(), answers)
	select {
	case answer := <-answers:
		if !answer.ok {
			return false, ErrNoAnswer
		}
		return answer.supported, nil
	case <-time.After(timeout + time.Second):
		return false, ErrNoAnswer
	}
}

type queryAnswer struct {
	supported bool
	ok        bool
}

// Receives the one answer to a query, which the SDK delivers from a worker.
type queryCallback chan queryAnswer

func (self queryCallback) Result(subprotocolIds *sdk.IntList, ok bool) {
	answer := queryAnswer{ok: ok}
	if ok && subprotocolIds != nil {
		answer.supported = subprotocolIds.Contains(Id)
	}
	select {
	case self <- answer:
	default:
	}
}
