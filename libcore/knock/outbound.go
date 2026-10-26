// Package knock implements a port knocking outbound.
//
// The outbound is used as the `detour` of a proxy outbound. Before dialing the
// proxy server it sends a sequence of TCP SYNs / UDP datagrams to the same host,
// so a knockd-style firewall opens the real port for this client.
//
// A knock is repeated when the session breaks: after a default network change
// (new client address), when a dial fails on a cached knock, and once per
// silence episode when a connection gets no reply for `silence` after sending.
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
	// Silence re-knocks once when a connection gets no reply this long after
	// sending. Zero disables it.
	Silence badoption.Duration `json:"silence,omitempty"`
}

func Register(registry *outbound.Registry) {
	outbound.Register[Options](registry, TypeKnock, New)
}

type step struct {
	port    uint16
	udp     bool
	payload []byte
}

// target is the knock state of one server address. The lock only guards the
// fields; it is never held while knocking.
type target struct {
	access     sync.Mutex
	lastKnock  time.Time
	generation uint64
	inflight   chan struct{}
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
	silence        time.Duration

	// bumped on network changes, knocks of an older generation are stale
	generation atomic.Uint64

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
	silence := time.Duration(options.Silence)
	if silence < 0 {
		silence = 0
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
		silence:        silence,
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
	if err != nil && !knocked {
		// the cached knock may be stale (window closed, server restarted): knock and retry once
		o.logger.DebugContext(ctx, "dial after cached knock failed, knocking again: ", err)
		if _, knockErr := o.knock(ctx, destination, true); knockErr != nil {
			return nil, err
		}
		conn, err = o.dialer.DialContext(ctx, network, destination)
	}
	if err != nil {
		// the port may need a moment more, knock again on the next attempt
		o.forget(destination)
		return nil, err
	}
	return o.watchConn(conn, destination), nil
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	destination, err := o.resolve(ctx, destination)
	if err != nil {
		return nil, err
	}
	// e.g. multi-peer WireGuard listens on 0.0.0.0, there is nothing to knock at
	if !knockable(destination) {
		return o.dialer.ListenPacket(ctx, destination)
	}
	_, err = o.knock(ctx, destination, false)
	if err != nil {
		return nil, err
	}
	packetConn, err := o.dialer.ListenPacket(ctx, destination)
	if err != nil {
		return nil, err
	}
	if o.silence <= 0 {
		return packetConn, nil
	}
	return &silentPacketConn{PacketConn: packetConn, watchdog: o.newWatchdog(destination)}, nil
}

// InterfaceUpdated is called when the default network changes. The server
// only opened the port for the old client address, so every knock is stale.
// It must not block: sing-box calls it from ResetNetwork.
func (o *Outbound) InterfaceUpdated(ctx context.Context) {
	o.generation.Add(1)
}

func knockable(destination M.Socksaddr) bool {
	if destination.Port == 0 {
		return false
	}
	return destination.IsFqdn() || destination.Addr.IsValid() && !destination.Addr.IsUnspecified()
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

// cached reports whether a knock to the host is still considered valid.
func (o *Outbound) cached(destination M.Socksaddr) bool {
	t := o.target(destination.AddrString())
	t.access.Lock()
	defer t.access.Unlock()
	return !t.lastKnock.IsZero() && t.generation == o.generation.Load() && time.Since(t.lastKnock) < o.ttl
}

// knock sends the sequence to the host unless a knock is still valid (with
// force: unless it was sent just now) and reports whether it sent one.
// Concurrent callers share one knock in flight.
func (o *Outbound) knock(ctx context.Context, destination M.Socksaddr, force bool) (bool, error) {
	t := o.target(destination.AddrString())
	for {
		t.access.Lock()
		generation := o.generation.Load()
		if !t.lastKnock.IsZero() && t.generation == generation {
			since := time.Since(t.lastKnock)
			if since < o.ttl && !force || since < minReknockGap {
				t.access.Unlock()
				return false, nil
			}
		}
		if t.inflight == nil {
			done := make(chan struct{})
			t.inflight = done
			t.access.Unlock()
			err := o.sendSequence(ctx, destination)
			t.access.Lock()
			t.inflight = nil
			if err == nil {
				// a network change during the knock leaves it stale
				t.lastKnock = time.Now()
				t.generation = generation
			}
			t.access.Unlock()
			close(done)
			return err == nil, err
		}
		inflight := t.inflight
		t.access.Unlock()
		select {
		case <-inflight:
			// re-check: the knock may have failed or become stale
			force = false
		case <-ctx.Done():
			return false, ctx.Err()
		}
	}
}

func (o *Outbound) sendSequence(ctx context.Context, destination M.Socksaddr) error {
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
	return sleep(ctx, o.interval)
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

// watchdog re-knocks once when data was sent but nothing came back for
// `silence`, then checks after another `silence` whether the server answers
// again. It re-arms only after a reply, so a dead server is knocked once per
// connection; reconnects knock on their own. Connections are never closed
// here: a slow reply (long polling) must not break them.
type watchdog struct {
	outbound    *Outbound
	destination M.Socksaddr
	lastRead    atomic.Int64
	pending     atomic.Bool
	knocked     atomic.Bool
	closed      atomic.Bool
}

func (o *Outbound) newWatchdog(destination M.Socksaddr) *watchdog {
	w := &watchdog{outbound: o, destination: destination}
	w.lastRead.Store(time.Now().UnixNano())
	return w
}

func (w *watchdog) onRead() {
	w.lastRead.Store(time.Now().UnixNano())
	if w.knocked.CompareAndSwap(true, false) {
		w.outbound.logger.Info("server ", w.destination.AddrString(), " answers again after re-knock")
	}
}

func (w *watchdog) onWrite() {
	if w.knocked.Load() || !w.pending.CompareAndSwap(false, true) {
		return
	}
	sentAt := time.Now().UnixNano()
	time.AfterFunc(w.outbound.silence, func() {
		w.pending.Store(false)
		if w.closed.Load() || w.lastRead.Load() >= sentAt || !w.knocked.CompareAndSwap(false, true) {
			return
		}
		o := w.outbound
		ctx, cancel := context.WithTimeout(o.ctx, o.interval*time.Duration(len(o.steps)+2)+5*time.Second)
		defer cancel()
		knocked, err := o.knock(ctx, w.destination, true)
		if err != nil || !knocked {
			return
		}
		o.logger.Warn("no reply from ", w.destination.AddrString(), " for ", o.silence, ", knocked again")
		knockedAt := time.Now().UnixNano()
		time.AfterFunc(o.silence, func() {
			if !w.closed.Load() && w.lastRead.Load() < knockedAt {
				o.logger.Warn("still no reply from ", w.destination.AddrString(), " after re-knock")
			}
		})
	})
}

func (o *Outbound) watchConn(conn net.Conn, destination M.Socksaddr) net.Conn {
	if o.silence <= 0 {
		return conn
	}
	return &silentConn{Conn: conn, watchdog: o.newWatchdog(destination)}
}

type silentConn struct {
	net.Conn
	*watchdog
}

func (c *silentConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		c.onRead()
	}
	return n, err
}

func (c *silentConn) Write(p []byte) (int, error) {
	c.onWrite()
	return c.Conn.Write(p)
}

func (c *silentConn) Close() error {
	c.closed.Store(true)
	return c.Conn.Close()
}

type silentPacketConn struct {
	net.PacketConn
	*watchdog
}

func (c *silentPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(p)
	if err == nil {
		c.onRead()
	}
	return n, addr, err
}

func (c *silentPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	c.onWrite()
	return c.PacketConn.WriteTo(p, addr)
}

func (c *silentPacketConn) Close() error {
	c.closed.Store(true)
	return c.PacketConn.Close()
}
