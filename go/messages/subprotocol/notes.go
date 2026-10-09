// Package subprotocol is the compiled form of the Go snippets in
// SUBPROTOCOLS.md: an application protocol of protobuf messages on its own
// subprotocol id. Every message is one Envelope (notes.proto) holding a Note
// or the Ack that confirms it. device.go sends and receives it through an SDK
// Device; client.go registers it on a connect client.
package subprotocol

//go:generate protoc --go_out=. --go_opt=paths=source_relative notes.proto

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"unicode/utf8"

	"google.golang.org/protobuf/proto"
)

// The application subprotocol id of this protocol. Ids below 1024 belong to
// the network, and the URMS text example uses 4096.
const Id = 4097

// The most UTF-8 bytes in one note's text.
const MaxTextBytes = 4096

// The largest envelope either side sends or parses: a note of MaxTextBytes
// plus room for the envelope, the id and fields that a later version adds.
// The sender checks it before sending and the receiver before it copies or
// parses.
const MaxMessageBytes = 4608

var (
	ErrTooLarge    = errors.New("envelope exceeds MaxMessageBytes")
	ErrNoKind      = errors.New("envelope has no kind this version knows")
	ErrZeroId      = errors.New("message id 0 is invalid")
	ErrTextTooLong = errors.New("note text exceeds MaxTextBytes")
	ErrTextUtf8    = errors.New("note text is not valid UTF-8")
)

// A note to send. messageId is nonzero; see NewMessageId.
func NewNote(messageId uint64, text string) *Envelope {
	return &Envelope{Kind: &Envelope_Note{Note: &Note{MessageId: messageId, Text: text}}}
}

// The acknowledgement of the note with messageId.
func NewAck(messageId uint64) *Envelope {
	return &Envelope{Kind: &Envelope_Ack{Ack: &Ack{MessageId: messageId}}}
}

// A random nonzero message id for a new note.
func NewMessageId() (uint64, error) {
	var b [8]byte
	for {
		if _, err := rand.Read(b[:]); err != nil {
			return 0, err
		}
		if id := binary.BigEndian.Uint64(b[:]); id != 0 {
			return id, nil
		}
	}
}

// Checks the rules the wire format cannot express: exactly one known kind, a
// nonzero message id, and note text within MaxTextBytes and valid UTF-8.
func Validate(envelope *Envelope) error {
	switch {
	case envelope.GetNote() != nil:
		note := envelope.GetNote()
		if note.GetMessageId() == 0 {
			return ErrZeroId
		}
		if MaxTextBytes < len(note.GetText()) {
			return ErrTextTooLong
		}
		if !utf8.ValidString(note.GetText()) {
			return ErrTextUtf8
		}
		return nil
	case envelope.GetAck() != nil:
		if envelope.GetAck().GetMessageId() == 0 {
			return ErrZeroId
		}
		return nil
	default:
		// no kind, or a kind added by a later version (its field is unknown here)
		return ErrNoKind
	}
}

// Validates and encodes one envelope for sending.
func Marshal(envelope *Envelope) ([]byte, error) {
	if err := Validate(envelope); err != nil {
		return nil, err
	}
	messageBytes, err := proto.Marshal(envelope)
	if err != nil {
		return nil, err
	}
	if MaxMessageBytes < len(messageBytes) {
		return nil, ErrTooLarge
	}
	return messageBytes, nil
}

// Decodes and validates one received envelope. It refuses more than
// MaxMessageBytes before decoding, bytes that are not an Envelope (including
// invalid UTF-8 in a string, which proto.Unmarshal rejects) and anything
// Validate refuses. Unknown fields are kept and ignored, so a later version
// can add fields.
func Parse(messageBytes []byte) (*Envelope, error) {
	if MaxMessageBytes < len(messageBytes) {
		return nil, ErrTooLarge
	}
	envelope := &Envelope{}
	if err := proto.Unmarshal(messageBytes, envelope); err != nil {
		return nil, fmt.Errorf("invalid envelope: %w", err)
	}
	if err := Validate(envelope); err != nil {
		return nil, err
	}
	return envelope, nil
}
