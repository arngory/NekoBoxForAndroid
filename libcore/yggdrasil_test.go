//go:build with_yggdrasil

package libcore

import (
	"strings"
	"testing"
)

func TestYggdrasilKeyAndAddress(t *testing.T) {
	key, err := YggdrasilGenerateKey()
	if err != nil || len(key) != 128 {
		t.Fatalf("key %q: %v", key, err)
	}
	address := YggdrasilAddress(key)
	if !strings.HasPrefix(address, "2") && !strings.HasPrefix(address, "3") {
		t.Fatalf("address %q outside 200::/7", address)
	}
	if YggdrasilAddress("abc") != "" || YggdrasilAddress(strings.Repeat("ab", 32)) != "" {
		t.Fatal("invalid keys give an address")
	}
}
