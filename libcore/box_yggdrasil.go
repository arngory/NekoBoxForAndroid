//go:build with_yggdrasil

package libcore

import (
	"crypto/ed25519"
	"encoding/hex"
	"net"

	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/protocol/yggdrasil"

	"github.com/yggdrasil-network/yggdrasil-go/src/address"
)

// the Yggdrasil endpoint (experimental), only in builds with with_yggdrasil
func registerYggdrasil(registry *endpoint.Registry) {
	yggdrasil.RegisterEndpoint(registry)
}

// YggdrasilGenerateKey returns a new node key as yggdrasil-go stores it, 128 hex digits.
func YggdrasilGenerateKey() (string, error) {
	_, privateKey, err := ed25519.GenerateKey(nil)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(privateKey), nil
}

// YggdrasilAddress is the node's 200::/7 address for a key, "" for an invalid one.
func YggdrasilAddress(privateKeyHex string) string {
	key, err := hex.DecodeString(privateKeyHex)
	if err != nil || len(key) != ed25519.PrivateKeySize {
		return ""
	}
	nodeAddress := address.AddrForKey(ed25519.PrivateKey(key).Public().(ed25519.PublicKey))
	if nodeAddress == nil {
		return ""
	}
	return net.IP(nodeAddress[:]).String()
}
