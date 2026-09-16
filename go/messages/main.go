package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"fmt"
	integration "github.com/urnetwork/examples/go/integration"
	sdk "github.com/urnetwork/sdk/v2026"
	"os"
	"os/signal"
	"strings"
	"time"
)

type event struct {
	kind      string
	source    string
	bytes     []byte
	peers     *sdk.NetworkPeers
	supported bool
}
type listener struct{ events chan event }

func (l *listener) put(e event) {
	select {
	case l.events <- e:
	default:
		fmt.Fprintln(os.Stderr, "event queue full; dropped")
	}
}
func (l *listener) SubprotocolMessage(protocol int32, source *sdk.Id, bytes []byte) {
	if protocol == subprotocol && source != nil && len(bytes) <= 4112 {
		l.put(event{kind: "message", source: source.String(), bytes: append([]byte(nil), bytes...)})
	}
}
func (l *listener) NetworkPeersChanged(peers *sdk.NetworkPeers) {
	l.put(event{kind: "peers", peers: peers})
}
func (l *listener) Result(ids *sdk.IntList, ok bool) {
	supported := false
	if ok && ids != nil {
		for i := 0; i < ids.Len(); i++ {
			if ids.Get(i) == subprotocol {
				supported = true
			}
		}
	}
	l.put(event{kind: "query", supported: supported})
}
func showPeers(peers *sdk.NetworkPeers) {
	if peers == nil {
		fmt.Println("peers unavailable (no snapshot)")
		return
	}
	fmt.Println("disconnected:", peers.DisconnectedCount)
	if peers.Connected == nil {
		fmt.Println("connected peers unavailable")
	}
	if peers.Connected != nil {
		for i := 0; i < peers.Connected.Len(); i++ {
			p := peers.Connected.Get(i)
			if p == nil {
				continue
			}
			b, _ := json.Marshal(p)
			fmt.Printf("%s color=%s\n", b, p.ColorHex())
		}
	}
}
func run() error {
	args := os.Args[1:]
	if len(args) == 1 && args[0] == "--self-test" {
		if e := codecSelfTest(); e != nil {
			return e
		}
		fmt.Println("URMS codec self-test passed")
		return nil
	}
	if len(args) == 1 && args[0] == "--version" {
		fmt.Println(sdk.Version)
		return nil
	}
	if len(args) == 0 || (args[0] != "self" && args[0] != "peers" && args[0] != "watch" && args[0] != "send") || (args[0] == "send" && len(args) < 3) {
		return fmt.Errorf("usage: --self-test | --version | self | peers | watch | send CLIENT_ID TEXT")
	}
	var destination *sdk.Id
	var err error
	if args[0] == "send" {
		destination, err = sdk.ParseId(args[1])
		if err != nil {
			return err
		}
	}
	session, err := integration.Open(false)
	if err != nil {
		return err
	}
	defer session.Close()
	device := session.Device
	l := &listener{make(chan event, 256)}
	sub, err := device.EnableSubprotocol(subprotocol, l)
	if err != nil {
		return err
	}
	defer sub.Close()
	peersSub := device.AddNetworkPeersChangeListener(l)
	defer peersSub.Close()
	device.SetProvideMode(sdk.ProvideModeNetwork)
	fmt.Println("self:", device.GetClientId())
	if args[0] == "self" {
		return nil
	}
	snapshot := device.GetNetworkPeers()
	showPeers(snapshot)
	if args[0] == "peers" && snapshot != nil {
		return nil
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	deadline := time.Now().Add(30 * time.Second)
	queried := false
	var random [8]byte
	if _, err = rand.Read(random[:]); err != nil {
		return err
	}
	pending := binary.BigEndian.Uint64(random[:])
	if pending == 0 {
		pending = 1
	}
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-tick.C:
			if destination != nil && !queried && device.GetProviderConnected() {
				queried = true
				device.QuerySubprotocols(destination, 10000, l)
			}
			if args[0] != "watch" && time.Now().After(deadline) {
				return fmt.Errorf("connection, peer snapshot, query, or ACK timed out")
			}
		case e := <-l.events:
			switch e.kind {
			case "peers":
				showPeers(e.peers)
				if args[0] == "peers" && e.peers != nil {
					return nil
				}
			case "query":
				if !e.supported {
					return fmt.Errorf("peer query failed or peer does not advertise 4096")
				}
				b, err := encode(frame{1, pending, strings.Join(args[2:], " ")})
				if err != nil {
					return err
				}
				if !device.SendSubprotocolBytes(subprotocol, destination, b) {
					return fmt.Errorf("SDK did not enqueue message")
				}
				fmt.Println("sent:", pending, "waiting for ACK")
				deadline = time.Now().Add(10 * time.Second)
			case "message":
				f, err := decode(e.bytes)
				if err != nil {
					fmt.Fprintln(os.Stderr, "rejected frame:", err)
					continue
				}
				fmt.Printf("kind=%d source=%s id=%d text=%q\n", f.kind, e.source, f.id, f.text)
				if f.kind == 1 {
					source, err := sdk.ParseId(e.source)
					if err != nil {
						continue
					}
					ack, _ := encode(frame{2, f.id, ""})
					if !device.SendSubprotocolBytes(subprotocol, source, ack) {
						fmt.Fprintln(os.Stderr, "ACK was not enqueued")
					}
				} else if destination != nil && e.source == destination.String() && f.id == pending {
					return nil
				}
			}
		}
	}
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
