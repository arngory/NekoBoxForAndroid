//go:build with_masterdnsvpn

package libcore

import (
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/protocol/masterdnsvpn"
)

// the MasterDnsVPN outbound (experimental), only in builds with with_masterdnsvpn
func registerMasterDNSVPN(registry *outbound.Registry) {
	masterdnsvpn.RegisterOutbound(registry)
}
