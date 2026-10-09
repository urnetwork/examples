package subprotocol

import (
	"context"
	"errors"
	"slices"
	"strings"
	"testing"
	"time"

	connect "github.com/urnetwork/connect/v2026"
	sdk "github.com/urnetwork/sdk/v2026"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

const wait = 10 * time.Second

// Two connect clients wired to each other over connect's in-process
// transports, with no contracts and no encryption: the pairing the SDK's own
// subprotocol tests use. Nothing leaves the process.
func newClientPair(t *testing.T) (*connect.Client, *connect.Client) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	settings := func() *connect.ClientSettings {
		clientSettings := connect.DefaultClientSettings()
		clientSettings.EncryptionSettings.Mode = connect.EncryptionModeOff
		return clientSettings
	}
	a := connect.NewClient(ctx, connect.NewId(), connect.NewNoContractClientOob(), settings())
	b := connect.NewClient(ctx, connect.NewId(), connect.NewNoContractClientOob(), settings())
	aToB := make(chan []byte, 8)
	bToA := make(chan []byte, 8)
	a.RouteManager().UpdateTransport(connect.NewSendClientTransport(connect.DestinationId(b.ClientId())), []connect.Route{aToB})
	a.RouteManager().UpdateTransport(connect.NewReceiveGatewayTransport(), []connect.Route{bToA})
	b.RouteManager().UpdateTransport(connect.NewSendClientTransport(connect.DestinationId(a.ClientId())), []connect.Route{bToA})
	b.RouteManager().UpdateTransport(connect.NewReceiveGatewayTransport(), []connect.Route{aToB})
	a.ContractManager().AddNoContractPeer(b.ClientId())
	b.ContractManager().AddNoContractPeer(a.ClientId())
	t.Cleanup(func() {
		a.Cancel()
		b.Cancel()
		cancel()
	})
	return a, b
}

func sdkId(id connect.Id) *sdk.Id {
	parsed, err := sdk.ParseId(id.String())
	if err != nil {
		panic(err)
	}
	return parsed
}

// A stand-in for an SDK Device on a connect client that does what sdk
// device_local_subprotocol.go does: a raw callback per enabled id that hands
// the listener one pooled copy of the bytes and releases it when the listener
// returns, a send of a copy of the caller's bytes, and a query answered from a
// worker. It overwrites the pooled copy before releasing it, so a listener
// that kept the borrowed slice sees the damage every time. afterListener, when
// set, runs after that overwrite and before the release.
type clientDevice struct {
	client        *connect.Client
	afterListener func()
}

var _ Device = (*clientDevice)(nil)

type subFunc func()

func (self subFunc) Close() {
	self()
}

func (self *clientDevice) EnableSubprotocol(subprotocolId int32, listener sdk.SubprotocolListener) (sdk.Sub, error) {
	if subprotocolId < sdk.SubprotocolReservedLimit || 0xFFFF < subprotocolId || listener == nil {
		return nil, errors.New("invalid subprotocol id or listener")
	}
	remove, err := self.client.AddSubprotocolRawCallback(
		connect.SubprotocolId(subprotocolId),
		func(source connect.TransferPath, id connect.SubprotocolId, messageBytes []byte, peer connect.Peer) {
			retained, release := connect.RetainSubprotocolBytes(messageBytes)
			defer release()
			listener.SubprotocolMessage(subprotocolId, sdkId(source.SourceId), retained)
			for i := range retained {
				retained[i] = 0xEE
			}
			if self.afterListener != nil {
				self.afterListener()
			}
		},
	)
	if err != nil {
		return nil, err
	}
	return subFunc(remove), nil
}

func (self *clientDevice) SendSubprotocolBytes(subprotocolId int32, destinationClientId *sdk.Id, messageBytes []byte) bool {
	if destinationClientId == nil || subprotocolId <= 0 || 0xFFFF < subprotocolId {
		return false
	}
	destination, err := connect.ParseId(destinationClientId.String())
	if err != nil {
		return false
	}
	return self.client.SendSubprotocolBytes(connect.SubprotocolId(subprotocolId), slices.Clone(messageBytes), destination, func(err error) {})
}

func (self *clientDevice) QuerySubprotocols(destinationClientId *sdk.Id, timeoutMillis int64, callback sdk.SubprotocolsQueryCallback) {
	if destinationClientId == nil {
		callback.Result(sdk.NewIntList(), false)
		return
	}
	destination, err := connect.ParseId(destinationClientId.String())
	if err != nil {
		callback.Result(sdk.NewIntList(), false)
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
		defer cancel()
		ids, err := self.client.QuerySubprotocols(ctx, destination)
		list := sdk.NewIntList()
		if err != nil {
			callback.Result(list, false)
			return
		}
		for _, id := range ids {
			list.Add(int(id))
		}
		callback.Result(list, true)
	}()
}

func receive(t *testing.T, listener *Listener) Received {
	t.Helper()
	select {
	case received := <-listener.Received:
		return received
	case <-time.After(wait):
		t.Fatal("no message")
		return Received{}
	}
}

// A note and its Ack between two clients through the Device path: query,
// marshal, send, copy in the listener, parse off the receive path,
// acknowledge and correlate.
func TestDeviceNoteAndAck(t *testing.T) {
	a, b := newClientPair(t)
	deviceA := &clientDevice{client: a}
	deviceB := &clientDevice{client: b}
	idA := sdkId(a.ClientId())
	idB := sdkId(b.ClientId())

	// b answers without the id until it enables it, and a client that is not
	// there never answers
	supported, err := Supported(deviceA, idB, wait)
	if err != nil || supported {
		t.Fatalf("before enable: supported=%t err=%v", supported, err)
	}
	if _, err := Supported(deviceA, sdk.NewId(), 300*time.Millisecond); !errors.Is(err, ErrNoAnswer) {
		t.Fatalf("absent peer: %v", err)
	}

	listenerA := NewListener(16)
	subA, err := deviceA.EnableSubprotocol(Id, listenerA)
	if err != nil {
		t.Fatal(err)
	}
	defer subA.Close()
	listenerB := NewListener(16)
	subB, err := deviceB.EnableSubprotocol(Id, listenerB)
	if err != nil {
		t.Fatal(err)
	}
	defer subB.Close()
	if _, err := deviceB.EnableSubprotocol(sdk.SubprotocolReservedLimit-1, listenerB); err == nil {
		t.Fatal("a reserved id was enabled")
	}

	supported, err = Supported(deviceA, idB, wait)
	if err != nil || !supported {
		t.Fatalf("after enable: supported=%t err=%v", supported, err)
	}

	messageId, err := NewMessageId()
	if err != nil || messageId == 0 {
		t.Fatalf("message id %d: %v", messageId, err)
	}
	if err := Send(deviceA, idB, NewNote(messageId, "hello 🙂")); err != nil {
		t.Fatal(err)
	}

	// b's copy survived the stand-in overwriting the borrowed bytes
	received := receive(t, listenerB)
	if received.Source.String() != idA.String() {
		t.Fatalf("note from %s", received.Source)
	}
	envelope, err := Receive(deviceB, received)
	if err != nil {
		t.Fatal(err)
	}
	if envelope.GetNote().GetMessageId() != messageId || envelope.GetNote().GetText() != "hello 🙂" {
		t.Fatalf("note %v", envelope)
	}

	// a correlates the Ack on (source client, message id) and does not answer it
	sent := a.SubprotocolStats().Sent
	received = receive(t, listenerA)
	envelope, err = Receive(deviceA, received)
	if err != nil {
		t.Fatal(err)
	}
	if received.Source.String() != idB.String() || envelope.GetAck().GetMessageId() != messageId {
		t.Fatalf("ack %v from %s", envelope, received.Source)
	}
	if a.SubprotocolStats().Sent != sent {
		t.Fatal("an Ack was acknowledged")
	}
}

// A listener that keeps the borrowed slice instead of copying it reads
// whatever the buffer holds after the callback.
func TestDeviceBorrowedBytes(t *testing.T) {
	a, b := newClientPair(t)
	var kept []byte
	keptErr := make(chan error, 1)
	deviceB := &clientDevice{
		client: b,
		afterListener: func() {
			_, err := Parse(kept)
			keptErr <- err
		},
	}
	sub, err := deviceB.EnableSubprotocol(Id, keepingListener(func(messageBytes []byte) {
		kept = messageBytes
	}))
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()
	if err := Send(&clientDevice{client: a}, sdkId(b.ClientId()), NewNote(1, "hi")); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-keptErr:
		if err == nil {
			t.Fatal("the borrowed bytes were still valid after the callback")
		}
	case <-time.After(wait):
		t.Fatal("no message")
	}
}

type keepingListener func(messageBytes []byte)

func (self keepingListener) SubprotocolMessage(subprotocolId int32, sourceClientId *sdk.Id, messageBytes []byte) {
	self(messageBytes)
}

func TestListenerBounds(t *testing.T) {
	listener := NewListener(1)
	source := sdk.NewId()
	listener.SubprotocolMessage(Id, source, make([]byte, MaxMessageBytes+1))
	listener.SubprotocolMessage(Id+1, source, []byte{})
	listener.SubprotocolMessage(Id, nil, []byte{})
	if dropped := listener.Dropped.Load(); dropped != 3 || len(listener.Received) != 0 {
		t.Fatalf("dropped %d queued %d", dropped, len(listener.Received))
	}
	listener.SubprotocolMessage(Id, source, []byte{})
	listener.SubprotocolMessage(Id, source, []byte{})
	if dropped := listener.Dropped.Load(); dropped != 4 || len(listener.Received) != 1 {
		t.Fatalf("full queue: dropped %d queued %d", dropped, len(listener.Received))
	}
}

func appendField(b []byte, number protowire.Number, value []byte) []byte {
	b = protowire.AppendTag(b, number, protowire.BytesType)
	return protowire.AppendBytes(b, value)
}

func TestParse(t *testing.T) {
	note := NewNote(^uint64(0), strings.Repeat("é", MaxTextBytes/2))
	b, err := Marshal(note)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := Parse(b)
	if err != nil || !proto.Equal(parsed, note) {
		t.Fatalf("round trip: %v %v", parsed, err)
	}

	// a later version's field inside a known kind is skipped
	newerNote := protowire.AppendTag(nil, 1, protowire.VarintType)
	newerNote = protowire.AppendVarint(newerNote, 7)
	newerNote = protowire.AppendTag(newerNote, 9, protowire.VarintType)
	newerNote = protowire.AppendVarint(newerNote, 1)
	parsed, err = Parse(appendField(nil, 1, newerNote))
	if err != nil || parsed.GetNote().GetMessageId() != 7 {
		t.Fatalf("unknown field: %v %v", parsed, err)
	}

	marshal := func(envelope *Envelope) []byte {
		b, err := proto.Marshal(envelope)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	invalidUtf8 := protowire.AppendTag(nil, 1, protowire.VarintType)
	invalidUtf8 = protowire.AppendVarint(invalidUtf8, 1)
	invalidUtf8 = appendField(invalidUtf8, 2, []byte{0xff})
	for _, c := range []struct {
		name  string
		bytes []byte
		err   error
	}{
		{"oversized", make([]byte, MaxMessageBytes+1), ErrTooLarge},
		{"not protobuf", []byte{0xff, 0xff, 0xff}, nil},
		{"empty", nil, ErrNoKind},
		{"later kind", appendField(nil, 3, nil), ErrNoKind},
		{"zero note id", marshal(NewNote(0, "x")), ErrZeroId},
		{"zero ack id", marshal(NewAck(0)), ErrZeroId},
		{"long text", marshal(NewNote(1, strings.Repeat("x", MaxTextBytes+1))), ErrTextTooLong},
		{"invalid utf-8", appendField(nil, 1, invalidUtf8), nil},
	} {
		t.Run(c.name, func(t *testing.T) {
			envelope, err := Parse(c.bytes)
			if err == nil || envelope != nil {
				t.Fatalf("accepted %x", c.bytes)
			}
			if c.err != nil && !errors.Is(err, c.err) {
				t.Fatalf("error %v, want %v", err, c.err)
			}
		})
	}

	for _, envelope := range []*Envelope{{}, NewNote(0, ""), NewNote(1, "\xff"), NewAck(0)} {
		if _, err := Marshal(envelope); err == nil {
			t.Fatalf("marshalled %v", envelope)
		}
	}
	padded := NewNote(1, "x")
	padded.ProtoReflect().SetUnknown(appendField(nil, 15, make([]byte, MaxMessageBytes)))
	if _, err := Marshal(padded); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversized marshal: %v", err)
	}
}

func deliver(t *testing.T, queue chan Delivery) Delivery {
	t.Helper()
	select {
	case delivery := <-queue:
		return delivery
	case <-time.After(wait):
		t.Fatal("no delivery")
		return Delivery{}
	}
}

func waitFor(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(wait)
	for !condition() {
		if time.Now().After(deadline) {
			t.Fatal("timed out")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// The connect path between two clients: registration rules, the peer query,
// a send-only codec, raw callbacks ahead of the codec, refused bytes, an Ack
// with the registered codec, and the stats.
func TestClientRegisterQueryAndSend(t *testing.T) {
	a, b := newClientPair(t)
	ctx, cancel := context.WithTimeout(context.Background(), wait)
	defer cancel()

	// b answers without the id until it registers it
	if supported, err := SupportedClient(ctx, a, b.ClientId()); err != nil || supported {
		t.Fatalf("before register: supported=%t err=%v", supported, err)
	}

	queueA := make(chan Delivery, 8)
	unregisterA, err := Register(a, queueA)
	if err != nil {
		t.Fatal(err)
	}
	defer unregisterA()
	queueB := make(chan Delivery, 8)
	unregisterB, err := Register(b, queueB)
	if err != nil {
		t.Fatal(err)
	}
	ignore := func(connect.TransferPath, *Envelope, connect.Peer) {}
	if _, err := connect.RegisterSubprotocol(b, Id, connect.ProtoCodec[*Envelope](), ignore); err == nil {
		t.Fatal("a second codec was registered for the id")
	}
	if _, err := connect.RegisterSubprotocol(b, connect.SubprotocolReservedLimit-1, connect.ProtoCodec[*Envelope](), ignore); err == nil {
		t.Fatal("a reserved id was registered")
	}

	// raw callbacks get every message of the id, before the codec runs
	raw := make(chan []byte, 8)
	removeRaw, err := b.AddSubprotocolRawCallback(Id, func(source connect.TransferPath, subprotocolId connect.SubprotocolId, messageBytes []byte, peer connect.Peer) {
		select {
		case raw <- slices.Clone(messageBytes):
		default:
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	nextRaw := func() []byte {
		t.Helper()
		select {
		case rawBytes := <-raw:
			return rawBytes
		case <-time.After(wait):
			t.Fatal("the raw callback did not run")
			return nil
		}
	}

	if supported, err := SupportedClient(ctx, a, b.ClientId()); err != nil || !supported {
		t.Fatalf("after register: supported=%t err=%v", supported, err)
	}

	messageId, err := NewMessageId()
	if err != nil {
		t.Fatal(err)
	}
	transportAck := make(chan error, 1)
	if err := SendClient(a, b.ClientId(), NewNote(messageId, "hello"), func(err error) {
		transportAck <- err
	}); err != nil {
		t.Fatal(err)
	}
	delivery := deliver(t, queueB)
	if delivery.Source != a.ClientId() || delivery.Envelope.GetNote().GetMessageId() != messageId || delivery.Envelope.GetNote().GetText() != "hello" {
		t.Fatalf("delivery %v from %s", delivery.Envelope, delivery.Source)
	}
	if envelope, err := Parse(nextRaw()); err != nil || envelope.GetNote().GetMessageId() != messageId {
		t.Fatalf("raw: %v %v", envelope, err)
	}
	select {
	case err := <-transportAck:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(wait):
		t.Fatal("no transport ack")
	}

	// b acknowledges with its registered codec, and a correlates the Ack on
	// (source client, message id)
	if !connect.SendSubprotocol(b, Id, NewAck(messageId), a.ClientId(), func(error) {}) {
		t.Fatal("ack not enqueued")
	}
	delivery = deliver(t, queueA)
	if delivery.Source != b.ClientId() || delivery.Envelope.GetAck().GetMessageId() != messageId {
		t.Fatalf("ack %v from %s", delivery.Envelope, delivery.Source)
	}

	// bytes the codec refuses reach the raw callback but never the handler
	before := b.SubprotocolStats()
	zeroId, err := proto.Marshal(NewNote(0, "x"))
	if err != nil {
		t.Fatal(err)
	}
	for _, refused := range [][]byte{{0xff, 0xff, 0xff}, make([]byte, MaxMessageBytes+1), zeroId} {
		if !a.SendSubprotocolBytes(Id, refused, b.ClientId(), func(error) {}) {
			t.Fatal("not enqueued")
		}
		nextRaw()
	}
	waitFor(t, func() bool {
		return before.DroppedDecode+3 <= b.SubprotocolStats().DroppedDecode
	})
	select {
	case delivery := <-queueB:
		t.Fatalf("refused bytes reached the handler: %v", delivery.Envelope)
	default:
	}
	if received := b.SubprotocolStats().ReceivedById[Id]; received < 4 {
		t.Fatalf("received %d", received)
	}

	// with no listener left, b stops advertising the id, drops its messages
	// and counts them, and its per-id count goes with the registration
	unregisterB()
	removeRaw()
	if supported, err := SupportedClient(ctx, a, b.ClientId()); err != nil || supported {
		t.Fatalf("after unregister: supported=%t err=%v", supported, err)
	}
	before = b.SubprotocolStats()
	marshalled, err := Marshal(NewNote(messageId, "late"))
	if err != nil {
		t.Fatal(err)
	}
	if !a.SendSubprotocolBytes(Id, marshalled, b.ClientId(), func(error) {}) {
		t.Fatal("not enqueued")
	}
	waitFor(t, func() bool {
		return before.DroppedUnregistered+1 <= b.SubprotocolStats().DroppedUnregistered
	})
	if _, ok := b.SubprotocolStats().ReceivedById[Id]; ok {
		t.Fatal("the per-id count outlived the registration")
	}
}
