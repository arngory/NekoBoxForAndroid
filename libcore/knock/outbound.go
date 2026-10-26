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
	"sync/atomic"
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
	// lower bound between forced knocks to the same host
	minReknockGap = 2 * time.Second
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

var (
	_ adapter.Outbound                = (*Outbound)(nil)
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
)

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
	knocked, err := o.knock(ctx, destination, false)
	if err != nil {
		return nil, err
	}
	conn, err := o.dialer.DialContext(ctx, network, destination)
	if err == nil {
		return conn, nil
	}
	if knocked {
		// the port may need a moment more, knock again on the next attempt
		o.forget(destination)
		return nil, err
	}
	// the cached knock may be stale (window closed, server restarted): knock and retry once
	o.logger.DebugContext(ctx, "dial after cached knock failed, knocking again: ", err)
	if _, knockErr := o.knock(ctx, destination, true); knockErr != nil {
		return nil, err
	}
	conn, err = o.dialer.DialContext(ctx, network, destination)
	if err != nil {
		o.forget(destination)
	}
	return conn, err
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	destination, err := o.resolve(ctx, destination)
	if err != nil {
		return nil, err
	}
	_, err = o.knock(ctx, destination, false)
	if err != nil {
		return nil, err
	}
	packetConn, err := o.dialer.ListenPacket(ctx, destination)
	if err != nil {
		return nil, err
	}
	return newSilenceConn(packetConn, o, destination), nil
}

// InterfaceUpdated is called when the default network changes. The server
// only opened the port for the old client address, so forget every knock.
func (o *Outbound) InterfaceUpdated(ctx context.Context) {
	o.access.Lock()
	targets := make([]*target, 0, len(o.targets))
	for _, t := range o.targets {
		targets = append(targets, t)
	}
	o.access.Unlock()
	for _, t := range targets {
		t.access.Lock()
		t.lastKnock = time.Time{}
		t.access.Unlock()
	}
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

func (o *Outbound) forget(destination M.Socksaddr) {
	t := o.target(destination.AddrString())
	t.access.Lock()
	t.lastKnock = time.Time{}
	t.access.Unlock()
}

// knock sends the sequence to the host unless it was knocked within ttl
// (with force, unless it was knocked just now) and reports whether it did.
// Concurrent dials to the same host wait for a single knock.
func (o *Outbound) knock(ctx context.Context, destination M.Socksaddr, force bool) (bool, error) {
	t := o.target(destination.AddrString())
	t.access.Lock()
	defer t.access.Unlock()
	if !t.lastKnock.IsZero() {
		since := time.Since(t.lastKnock)
		if since < o.ttl && !force || since < minReknockGap {
			return false, nil
		}
	}
	o.logger.InfoContext(ctx, "knocking ", destination.AddrString(), " (", len(o.steps), " steps)")
	for i, s := range o.steps {
		if i > 0 {
			err := sleep(ctx, o.interval)
			if err != nil {
				return false, err
			}
		}
		o.send(ctx, M.Socksaddr{Addr: destination.Addr, Fqdn: destination.Fqdn, Port: s.port}, s)
	}
	// give the firewall time to apply the rule before the real connection
	err := sleep(ctx, o.interval)
	if err != nil {
		return false, err
	}
	t.lastKnock = time.Now()
	return true, nil
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

// silenceConn re-knocks when a UDP session keeps sending but hears nothing
// back for ttl, e.g. WireGuard after the server dropped its firewall state.
// Long-lived UDP transports never dial again, so this is the only hook.
type silenceConn struct {
	net.PacketConn
	outbound    *Outbound
	destination M.Socksaddr
	lastRead    atomic.Int64
	knocking    atomic.Bool
}

func newSilenceConn(conn net.PacketConn, outbound *Outbound, destination M.Socksaddr) *silenceConn {
	c := &silenceConn{PacketConn: conn, outbound: outbound, destination: destination}
	c.lastRead.Store(time.Now().UnixNano())
	return c
}

func (c *silenceConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(p)
	if err == nil {
		c.lastRead.Store(time.Now().UnixNano())
	}
	return n, addr, err
}

func (c *silenceConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if time.Since(time.Unix(0, c.lastRead.Load())) > c.outbound.ttl && c.knocking.CompareAndSwap(false, true) {
		go func() {
			defer c.knocking.Store(false)
			ctx, cancel := context.WithTimeout(c.outbound.ctx, c.outbound.interval*time.Duration(len(c.outbound.steps)+2)+time.Second)
			defer cancel()
			if knocked, _ := c.outbound.knock(ctx, c.destination, true); knocked {
				c.outbound.logger.Info("no reply from ", c.destination.AddrString(), " for ", c.outbound.ttl, ", knocked again")
				// wait another ttl for a reply before the next knock
				c.lastRead.Store(time.Now().UnixNano())
			}
		}()
	}
	return c.PacketConn.WriteTo(p, addr)
}
