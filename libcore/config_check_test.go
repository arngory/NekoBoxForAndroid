package libcore

import (
	"context"
	"os"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// TestConfigCheck parses and builds a config shaped like ConfigBuilder output
// with the registries NekoBox ships, so schema changes in the core are caught.
func TestConfigCheck(t *testing.T) {
	content, err := os.ReadFile("testdata/config_1_14.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = checkConfig(content); err != nil {
		t.Fatal(err)
	}
	// the legacy DNS server format must keep failing, or the test checks nothing
	if checkConfig([]byte(`{"dns":{"servers":[{"address":"tls://1.1.1.1"}]}}`)) == nil {
		t.Fatal("legacy DNS server accepted")
	}
}

func checkConfig(content []byte) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, content); err != nil {
		return err
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return err
	}
	return instance.Close()
}
