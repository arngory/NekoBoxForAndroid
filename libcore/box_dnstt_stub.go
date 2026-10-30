//go:build !with_dnstt

package libcore

import "github.com/sagernet/sing-box/adapter/outbound"

func registerDNSTT(registry *outbound.Registry) {}
