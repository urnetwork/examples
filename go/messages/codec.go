package main

import (
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

const subprotocol = 4096

type frame struct {
	kind byte
	id   uint64
	text string
}

func encode(f frame) ([]byte, error) {
	if f.id == 0 || (f.kind != 1 && f.kind != 2) || !utf8.ValidString(f.text) || len(f.text) > 4096 || (f.kind == 2 && f.text != "") {
		return nil, errors.New("invalid message")
	}
	b := make([]byte, 16+len(f.text))
	copy(b, "URMS")
	b[4], b[5] = 1, f.kind
	binary.BigEndian.PutUint16(b[6:8], uint16(len(f.text)))
	binary.BigEndian.PutUint64(b[8:16], f.id)
	copy(b[16:], f.text)
	return b, nil
}
func decode(b []byte) (frame, error) {
	if len(b) < 16 || len(b) > 4112 || string(b[:4]) != "URMS" || b[4] != 1 || int(binary.BigEndian.Uint16(b[6:8])) != len(b)-16 {
		return frame{}, errors.New("invalid frame")
	}
	f := frame{b[5], binary.BigEndian.Uint64(b[8:16]), string(b[16:])}
	if _, err := encode(f); err != nil {
		return frame{}, err
	}
	return f, nil
}
func codecSelfTest() error {
	golden, _ := hex.DecodeString("55524d530101000200000000000000016869")
	ack, _ := hex.DecodeString("55524d53010200000000000000000001")
	for _, c := range []struct {
		f     frame
		bytes []byte
	}{{frame{1, 1, "hi"}, golden}, {frame{2, 1, ""}, ack}} {
		b, e := encode(c.f)
		if e != nil || string(b) != string(c.bytes) {
			return errors.New("golden encode failed")
		}
		f, e := decode(c.bytes)
		if e != nil || f != c.f {
			return errors.New("golden decode failed")
		}
	}
	for _, s := range []string{"", "é🙂\x00", strings.Repeat("x", 4096), strings.Repeat("é", 2048)} {
		f := frame{1, ^uint64(0), s}
		b, _ := encode(f)
		got, e := decode(b)
		if e != nil || got != f {
			return errors.New("round trip failed")
		}
	}
	bad := [][]byte{append(append([]byte{}, golden...), 0), append(append([]byte{}, golden[:16]...), 0xc0, 0xaf)}
	for n := range len(golden) {
		bad = append(bad, golden[:n])
	}
	maximum, _ := encode(frame{1, 1, strings.Repeat("x", 4096)})
	bad = append(bad, append(maximum, 0))
	for _, change := range [][2]int{{0, 0}, {4, 2}, {5, 3}, {5, 2}, {6, 16}, {7, 1}, {15, 0}} {
		b := append([]byte{}, golden...)
		b[change[0]] = byte(change[1])
		bad = append(bad, b)
	}
	for _, b := range bad {
		if _, e := decode(b); e == nil {
			return fmt.Errorf("accepted malformed frame %x", b)
		}
	}
	for _, f := range []frame{{1, 0, ""}, {2, 1, "x"}, {3, 1, ""}, {1, 1, strings.Repeat("x", 4097)}, {1, 1, "\xff"}} {
		if _, e := encode(f); e == nil {
			return errors.New("accepted invalid message")
		}
	}
	return nil
}
