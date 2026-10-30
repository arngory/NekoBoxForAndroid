//go:build !with_masterdnsvpn

package libcore

import "github.com/sagernet/sing-box/adapter/outbound"

func registerMasterDNSVPN(registry *outbound.Registry) {}
