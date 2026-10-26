// Package knock implements a port knocking outbound.
//
// The outbound is used as the `detour` of a proxy outbound. Before dialing the
// proxy server it sends a sequence of TCP SYNs / UDP datagrams to the same host,
// so a knockd-style firewall opens the real port for this client.
package knock

import (
	"context"
	"encoding/hex"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const TypeKnock = "knock"

const (
	maxSteps        = 32
	defaultInterval = 200 * time.Millisecond
	defaultTTL      = 10 * time.Second
	maxPayload      = 1024
)

type Step struct {
	Port    uint16 `json:"port"`
	Network string `json:"network,omitempty"`
	Payload string `json:"payload,omitempty"`
}

type Options struct {
	option.DialerOptions
	Sequence []Step             `json:"sequence"`
	Interval badoption.Duration `json:"interval,omitempty"`
	TTL      badoption.Duration `json:"ttl,omitempty"`
}

func Register(registry *outbound.Registry) {
	outbound.Register[Options](registry, TypeKnock, New)
}

type step struct {
	port    uint16
	udp     bool
	payload []byte
}

type target struct {
	access    sync.Mutex
	lastKnock time.Time
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	ctx            context.Context
	logger         logger.ContextLogger
	dialer         N.Dialer
	hasDetour      bool
	domainResolver *option.DomainResolveOptions
	steps          []step
	interval       time.Duration
	ttl            time.Duration

	access  sync.Mutex
	targets map[string]*target
}

func New(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options Options) (adapter.Outbound, error) {
	steps, err := parseSequence(options.Sequence)
	if err != nil {
		return nil, err
	}
	interval := time.Duration(options.Interval)
	if interval <= 0 {
		interval = defaultInterval
	}
	ttl := time.Duration(options.TTL)
	if ttl <= 0 {
		ttl = defaultTTL
	}
	// Without a detour the server address is resolved here, so that the knocks
	// and the real connection go to the same IP.
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, false)
	if err != nil {
		return nil, err
	}
	return &Outbound{
		Adapter:        outbound.NewAdapterWithDialerOptions(TypeKnock, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		ctx:            ctx,
		logger:         logger,
		dialer:         outboundDialer,
		hasDetour:      options.Detour != "",
		domainResolver: options.DomainResolver,
		steps:          steps,
		interval:       interval,
		ttl:            ttl,
		targets:        make(map[string]*target),
	}, nil
}

func parseSequence(sequence []Step) ([]step, error) {
	if len(sequence) == 0 {
		return nil, E.New("knock: empty sequence")
	}
	if len(sequence) > maxSteps {
		return nil, E.New("knock: too many steps: ", len(sequence))
	}
	steps := make([]step, 0, len(sequence))
	for i, s := range sequence {
		if s.Port == 0 {
			return nil, E.New("knock: step ", i+1, ": missing port")
		}
		var parsed step
		parsed.port = s.Port
		switch strings.ToLower(s.Network) {
		case "", N.NetworkTCP:
		case N.NetworkUDP:
			parsed.udp = true
		default:
			return nil, E.New("knock: step ", i+1, ": unknown network: ", s.Network)
		}
		if s.Payload != "" {
			if !parsed.udp {
				return nil, E.New("knock: step ", i+1, ": payload is only supported for udp")
			}
			payload, err := hex.DecodeString(s.Payload)
			if err != nil {
				return nil, E.Cause(err, "knock: step ", i+1, ": decode payload")
			}
			if len(payload) > maxPayload {
				return nil, E.New("knock: step ", i+1, ": payload too large")
			}
			parsed.payload = payload
		}
		steps = append(steps, parsed)
	}
	return steps, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	destination, err := o.resolve(ctx, destination)
	if err != nil {
		return nil, err
	}
	err = o.knock(ctx, destination)
	if err != nil {
		return nil, err
	}
	return o.dialer.DialContext(ctx, network, destination)
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	destination, err := o.resolve(ctx, destination)
	if err != nil {
		return nil, err
	}
	err = o.knock(ctx, destination)
	if err != nil {
		return nil, err
	}
	return o.dialer.ListenPacket(ctx, destination)
}

func (o *Outbound) resolve(ctx context.Context, destination M.Socksaddr) (M.Socksaddr, error) {
	if !destination.IsFqdn() || o.hasDetour {
		return destination, nil
	}
	queryOptions, err := dialer.NewDNSQueryOptions(o.ctx, o.domainResolver, false)
	if err != nil {
		return M.Socksaddr{}, err
	}
	addresses, err := service.FromContext[adapter.DNSRouter](o.ctx).Lookup(ctx, destination.Fqdn, queryOptions)
	if err != nil {
		return M.Socksaddr{}, err
	}
	if len(addresses) == 0 {
		return M.Socksaddr{}, E.New("knock: no address for ", destination.Fqdn)
	}
	return M.SocksaddrFrom(addresses[0], destination.Port), nil
}

func (o *Outbound) target(host string) *target {
	o.access.Lock()
	defer o.access.Unlock()
	t, loaded := o.targets[host]
	if !loaded {
		t = &target{}
		o.targets[host] = t
	}
	return t
}

// knock sends the sequence to the host unless it was knocked within ttl.
// Concurrent dials to the same host wait for a single knock.
func (o *Outbound) knock(ctx context.Context, destination M.Socksaddr) error {
	t := o.target(destination.AddrString())
	t.access.Lock()
	defer t.access.Unlock()
	if !t.lastKnock.IsZero() && time.Since(t.lastKnock) < o.ttl {
		return nil
	}
	o.logger.InfoContext(ctx, "knocking ", destination.AddrString(), " (", len(o.steps), " steps)")
	for i, s := range o.steps {
		if i > 0 {
			err := sleep(ctx, o.interval)
			if err != nil {
				return err
			}
		}
		o.send(ctx, M.Socksaddr{Addr: destination.Addr, Fqdn: destination.Fqdn, Port: s.port}, s)
	}
	// give the firewall time to apply the rule before the real connection
	err := sleep(ctx, o.interval)
	if err != nil {
		return err
	}
	t.lastKnock = time.Now()
	return nil
}

func (o *Outbound) send(ctx context.Context, destination M.Socksaddr, s step) {
	if s.udp {
		packetConn, err := o.dialer.ListenPacket(ctx, destination)
		if err != nil {
			o.logger.DebugContext(ctx, "knock udp ", destination, ": ", err)
			return
		}
		defer packetConn.Close()
		var addr net.Addr = destination
		if destination.IsIP() {
			addr = destination.UDPAddr()
		}
		_, err = packetConn.WriteTo(s.payload, addr)
		if err != nil {
			o.logger.DebugContext(ctx, "knock udp ", destination, ": ", err)
		}
		return
	}
	// A closed port never answers, so the SYN is sent in the background and
	// the attempt is abandoned once the next step is due.
	dialCtx, cancel := context.WithTimeout(ctx, o.interval)
	go func() {
		defer cancel()
		conn, err := o.dialer.DialContext(dialCtx, N.NetworkTCP, destination)
		if err == nil {
			conn.Close()
		}
	}()
}

func sleep(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
