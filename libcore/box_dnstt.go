//go:build with_dnstt

package libcore

import (
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/protocol/dnstt"
)

// the DNS tunnel outbound (experimental), only in builds with with_dnstt
func registerDNSTT(registry *outbound.Registry) {
	dnstt.RegisterOutbound(registry)
}
