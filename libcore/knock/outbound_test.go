package knock

import (
	"context"
	"io"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type recorder struct {
	access sync.Mutex
	events []string
}

func (r *recorder) add(event string) {
	r.access.Lock()
	r.events = append(r.events, event)
	r.access.Unlock()
}

func (r *recorder) snapshot() []string {
	r.access.Lock()
	defer r.access.Unlock()
	return append([]string(nil), r.events...)
}

func listenTCP(t *testing.T, r *recorder, name string) uint16 {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			r.add(name)
			if name == "server" {
				go func() {
					defer conn.Close()
					io.Copy(conn, conn)
				}()
			} else {
				conn.Close()
			}
		}
	}()
	return uint16(listener.Addr().(*net.TCPAddr).Port)
}

func listenUDP(t *testing.T, r *recorder, name string, payloads chan<- string) uint16 {
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, _, err := conn.ReadFrom(buffer)
			if err != nil {
				return
			}
			r.add(name)
			payloads <- string(buffer[:n])
		}
	}()
	return uint16(conn.LocalAddr().(*net.UDPAddr).Port)
}

func newBox(t *testing.T, options Options) *Outbound {
	registry := outbound.NewRegistry()
	direct.RegisterOutbound(registry)
	Register(registry)
	dnsRegistry := dns.NewTransportRegistry()
	local.RegisterTransport(dnsRegistry)
	ctx := box.Context(context.Background(), inbound.NewRegistry(), registry, endpoint.NewRegistry(),
		dnsRegistry, service.NewRegistry(), certificate.NewRegistry())
	instance, err := box.New(box.Options{
		Context: ctx,
		Options: option.Options{
			Log:       &option.LogOptions{Disabled: true},
			Outbounds: []option.Outbound{{Type: TypeKnock, Tag: "knock", Options: &options}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	outbound, loaded := instance.Outbound().Outbound("knock")
	if !loaded {
		t.Fatal("knock outbound not found")
	}
	return outbound.(*Outbound)
}

func TestKnockSequenceBeforeDial(t *testing.T) {
	r := &recorder{}
	payloads := make(chan string, 4)
	first := listenTCP(t, r, "tcp-1")
	second := listenUDP(t, r, "udp-2", payloads)
	server := listenTCP(t, r, "server")

	outbound := newBox(t, Options{
		Sequence: []Step{{Port: first}, {Port: second, Network: "udp", Payload: "6b6e6f636b"}},
		Interval: 0,
		TTL:      0,
	})
	outbound.interval = 50 * time.Millisecond
	outbound.ttl = time.Minute

	destination := M.ParseSocksaddrHostPort("127.0.0.1", server)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := outbound.DialContext(ctx, N.NetworkTCP, destination)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if payload := <-payloads; payload != "knock" {
		t.Fatalf("udp payload: %q", payload)
	}
	time.Sleep(100 * time.Millisecond)
	events := r.snapshot()
	want := []string{"tcp-1", "udp-2", "server"}
	if len(events) != len(want) {
		t.Fatalf("events: %v, want %v", events, want)
	}
	for i := range want {
		if events[i] != want[i] {
			t.Fatalf("events: %v, want %v", events, want)
		}
	}

	// within ttl the sequence is not repeated
	conn, err = outbound.DialContext(ctx, N.NetworkTCP, destination)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	time.Sleep(100 * time.Millisecond)
	if events = r.snapshot(); len(events) != 4 || events[3] != "server" {
		t.Fatalf("second dial re-knocked: %v", events)
	}
}

func TestParseSequence(t *testing.T) {
	for i, sequence := range [][]Step{
		nil,
		{{Port: 0}},
		{{Port: 1, Network: "icmp"}},
		{{Port: 1, Payload: "00"}},
		{{Port: 1, Network: "udp", Payload: "zz"}},
	} {
		if _, err := parseSequence(sequence); err == nil {
			t.Fatal("case " + strconv.Itoa(i) + ": expected error")
		}
	}
	steps, err := parseSequence([]Step{{Port: 7000}, {Port: 8000, Network: "UDP", Payload: "ff"}})
	if err != nil {
		t.Fatal(err)
	}
	if steps[0].udp || !steps[1].udp || steps[1].payload[0] != 0xff {
		t.Fatalf("unexpected steps: %+v", steps)
	}
}
