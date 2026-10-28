package libcore

import (
	"encoding/json"
	"os"
	"reflect"
	"regexp"
	"sort"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

// The app writes sing-box JSON through hand-maintained classes in SingBoxOptions.java, and sing-box
// rejects unknown fields. This test compares those classes with the option structs of the core
// in use: a field sing-box no longer knows fails the test, new core fields are only logged.

const javaOptionsPath = "../app/src/main/java/io/niko/SingBoxOptions.java"

var javaOptionStructs = map[string][]any{
	"Outbound_DirectOptions":      {option.DirectOutboundOptions{}},
	"Outbound_SocksOptions":       {option.SOCKSOutboundOptions{}},
	"Outbound_HTTPOptions":        {option.HTTPOutboundOptions{}},
	"Outbound_ShadowsocksOptions": {option.ShadowsocksOutboundOptions{}},
	"Outbound_VMessOptions":       {option.VMessOutboundOptions{}},
	"Outbound_TrojanOptions":      {option.TrojanOutboundOptions{}},
	"Outbound_HysteriaOptions":    {option.HysteriaOutboundOptions{}},
	"Outbound_TorOptions":         {option.TorOutboundOptions{}},
	"Outbound_SSHOptions":         {option.SSHOutboundOptions{}},
	"Outbound_ShadowTLSOptions":   {option.ShadowTLSOutboundOptions{}},
	"Outbound_VLESSOptions":       {option.VLESSOutboundOptions{}},
	"Outbound_TUICOptions":        {option.TUICOutboundOptions{}},
	"Outbound_Hysteria2Options":   {option.Hysteria2OutboundOptions{}},
	"Outbound_SelectorOptions":    {option.SelectorOutboundOptions{}},
	"Outbound_URLTestOptions":     {option.URLTestOutboundOptions{}},
	"Outbound_AnyTLSOptions":      {option.AnyTLSOutboundOptions{}},
	"Endpoint_WireGuardOptions":   {option.WireGuardEndpointOptions{}},
	"EndpointWireGuardPeer":       {option.WireGuardPeer{}},

	"Inbound_TunOptions":      {option.TunInboundOptions{}},
	"Inbound_DirectOptions":   {option.DirectInboundOptions{}},
	"Inbound_SocksOptions":    {option.SocksInboundOptions{}},
	"Inbound_HTTPOptions":     {option.HTTPMixedInboundOptions{}},
	"Inbound_MixedOptions":    {option.HTTPMixedInboundOptions{}},
	"Inbound_RedirectOptions": {option.RedirectInboundOptions{}},
	"Inbound_TProxyOptions":   {option.TProxyInboundOptions{}},

	"Rule_DefaultOptions":    {option.DefaultRule{}},
	"DNSRule_DefaultOptions": {option.DefaultDNSRule{}},
	"RuleSet":                {option.RuleSet{}},
	"RouteOptions":           {option.RouteOptions{}},
	"LogOptions":             {option.LogOptions{}},
	"NTPOptions":             {option.NTPOptions{}},
	"ExperimentalOptions":    {option.ExperimentalOptions{}},
	"ClashAPIOptions":        {option.ClashAPIOptions{}},
	"CacheFile":              {option.CacheFileOptions{}},

	"OutboundTLSOptions":     {option.OutboundTLSOptions{}},
	"OutboundECHOptions":     {option.OutboundECHOptions{}},
	"OutboundUTLSOptions":    {option.OutboundUTLSOptions{}},
	"OutboundRealityOptions": {option.OutboundRealityOptions{}},
	"MultiplexOptions":       {option.OutboundMultiplexOptions{}},
	"UDPOverTCPOptions":      {option.UDPOverTCPOptions{}},
	"Hysteria2Obfs":          {option.Hysteria2Obfs{}},
	"TunPlatformOptions":     {option.TunPlatformOptions{}},
	"HTTPProxyOptions":       {option.HTTPProxyOptions{}},

	"V2RayTransportOptions_HTTPOptions":        {option.V2RayHTTPOptions{}},
	"V2RayTransportOptions_WebsocketOptions":   {option.V2RayWebsocketOptions{}},
	"V2RayTransportOptions_GRPCOptions":        {option.V2RayGRPCOptions{}},
	"V2RayTransportOptions_HTTPUpgradeOptions": {option.V2RayHTTPUpgradeOptions{}},
}

// typed classes extend a wrapper holding "type" (and "tag"), which sing-box decodes on its own
var wrapperClasses = map[string]bool{
	"Outbound":              true,
	"Inbound":               true,
	"Endpoint":              true,
	"Rule":                  true,
	"DNSRule":               true,
	"V2RayTransportOptions": true,
}

type javaField struct {
	name     string // JSON name
	javaType string
}

type javaClass struct {
	parent string
	fields []javaField
	end    int // line index of the closing brace
}

var (
	javaClassPattern = regexp.MustCompile(`^\s*public static (?:final )?class (\w+)(?: extends (\w+))?`)
	javaFieldPattern = regexp.MustCompile(`^\s*public ([\w<>, ?\[\]]+) (\w+);`)
	javaNamePattern  = regexp.MustCompile(`@SerializedName\("([^"]+)"\)`)
)

func parseJavaOptions(lines []string) map[string]*javaClass {
	classes := make(map[string]*javaClass)
	var current *javaClass
	var serializedName string
	for i, line := range lines {
		if match := javaClassPattern.FindStringSubmatch(line); match != nil {
			current = &javaClass{parent: match[2], end: -1}
			classes[match[1]] = current
			continue
		}
		if current != nil && current.end < 0 && line == "    }" {
			current.end = i
			continue
		}
		if match := javaNamePattern.FindStringSubmatch(line); match != nil {
			serializedName = match[1]
			continue
		}
		if current == nil {
			continue
		}
		if match := javaFieldPattern.FindStringSubmatch(line); match != nil &&
			!strings.Contains(line, " static ") && !strings.Contains(line, " transient ") {
			name := match[2]
			if serializedName != "" {
				name = serializedName
			}
			current.fields = append(current.fields, javaField{name, match[1]})
		}
		serializedName = ""
	}
	return classes
}

func (c *javaClass) allFields(classes map[string]*javaClass) []javaField {
	fields := append([]javaField(nil), c.fields...)
	if parent := classes[c.parent]; parent != nil && !wrapperClasses[c.parent] {
		fields = append(fields, parent.allFields(classes)...)
	}
	return fields
}

// goJSONFields lists the JSON keys of an option struct. Embedded structs are inline, and so are
// struct fields tagged "-": sing-box merges those (rule actions, rule set types) with badjson.
func goJSONFields(typ reflect.Type, fields map[string]reflect.Type) {
	for typ.Kind() == reflect.Pointer {
		typ = typ.Elem()
	}
	if typ.Kind() != reflect.Struct {
		return
	}
	for i := 0; i < typ.NumField(); i++ {
		field := typ.Field(i)
		name, _, _ := strings.Cut(field.Tag.Get("json"), ",")
		inner := field.Type
		for inner.Kind() == reflect.Pointer {
			inner = inner.Elem()
		}
		if field.Anonymous && name == "" || name == "-" && inner.Kind() == reflect.Struct {
			goJSONFields(inner, fields)
			continue
		}
		if name == "-" || !field.IsExported() {
			continue
		}
		if name == "" {
			name = field.Name
		}
		fields[name] = field.Type
	}
}

var (
	durationType  = reflect.TypeOf(badoption.Duration(0))
	bytesType     = reflect.TypeOf([]byte(nil))
	marshalerType = reflect.TypeOf((*json.Marshaler)(nil)).Elem()
)

// javaTypeFor is the Java type the app should use for a Go option type; "" means "any object".
func javaTypeFor(typ reflect.Type) string {
	for typ.Kind() == reflect.Pointer {
		typ = typ.Elem()
	}
	if typ == durationType {
		return "String"
	}
	if typ == bytesType {
		return "String" // base64
	}
	if typ.Kind() != reflect.Slice && (typ.Implements(marshalerType) || reflect.PointerTo(typ).Implements(marshalerType)) {
		return "" // custom JSON form, e.g. an enum written as a string
	}
	switch typ.Kind() {
	case reflect.String:
		return "String"
	case reflect.Bool:
		return "Boolean"
	case reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint:
		return "Integer"
	case reflect.Int64, reflect.Uint64:
		return "Long"
	case reflect.Float32, reflect.Float64:
		return "Double"
	case reflect.Slice:
		return "List<" + boxed(javaTypeFor(typ.Elem())) + ">"
	case reflect.Map:
		return "Map<String, Object>"
	}
	return ""
}

func boxed(javaType string) string {
	if javaType == "" {
		return "Object"
	}
	return javaType
}

// javaTypeMismatch reports a Java type sing-box can not decode, e.g. a number for a duration.
func javaTypeMismatch(expected, actual string) bool {
	scalar := map[string]bool{"String": true, "Boolean": true, "Integer": true, "Long": true, "Double": true}
	number := map[string]bool{"Integer": true, "Long": true, "Double": true}
	switch {
	case expected == "" || !scalar[actual] && !strings.HasPrefix(actual, "List"):
		return false // objects and maps are not checked
	case strings.HasPrefix(expected, "List<"):
		// a Listable also takes a single value
		element := strings.TrimSuffix(strings.TrimPrefix(expected, "List<"), ">")
		return scalar[actual] && actual != element && !(number[actual] && number[element])
	case number[expected]:
		return !number[actual]
	default:
		return actual != expected
	}
}

var javaKeywords = map[string]bool{"default": true, "interface": true, "package": true, "private": true, "static": true}

func TestJavaOptionsMatchCore(t *testing.T) {
	content, err := os.ReadFile(javaOptionsPath)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(string(content), "\n")
	classes := parseJavaOptions(lines)
	update := os.Getenv("UPDATE_JAVA_OPTIONS") == "1"
	additions := make(map[int][]string) // closing-brace line -> new field lines

	var names []string
	for name := range javaOptionStructs {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		class := classes[name]
		if class == nil {
			t.Errorf("%s: class not found in SingBoxOptions.java", name)
			continue
		}
		known := make(map[string]reflect.Type)
		for _, value := range javaOptionStructs[name] {
			goJSONFields(reflect.TypeOf(value), known)
		}
		javaFields := make(map[string]bool)
		for _, field := range class.allFields(classes) {
			javaFields[field.name] = true
			goType, exists := known[field.name]
			if !exists {
				t.Errorf("%s.%s: sing-box does not know this field", name, field.name)
			} else if expected := javaTypeFor(goType); javaTypeMismatch(expected, field.javaType) {
				t.Errorf("%s.%s: is %s in Java, sing-box needs %s", name, field.name, field.javaType, expected)
			}
		}
		var missing []string
		for field := range known {
			if !javaFields[field] {
				missing = append(missing, field)
			}
		}
		if len(missing) == 0 {
			continue
		}
		sort.Strings(missing)
		if !update {
			t.Logf("%s: not in Java yet: %s", name, strings.Join(missing, ", "))
			continue
		}
		block := []string{"", "        // added from sing-box option." + reflect.TypeOf(javaOptionStructs[name][0]).Name()}
		for _, field := range missing {
			javaName := field
			if javaKeywords[field] {
				block = append(block, "        @SerializedName(\""+field+"\")")
				javaName = field + "_"
			}
			block = append(block, "        public "+boxed(javaTypeFor(known[field]))+" "+javaName+";", "")
		}
		additions[class.end] = block
	}
	if !update || len(additions) == 0 {
		return
	}
	var output []string
	for i, line := range lines {
		output = append(output, additions[i]...)
		output = append(output, line)
	}
	if err := os.WriteFile(javaOptionsPath, []byte(strings.Join(output, "\n")), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("added fields to %d classes", len(additions))
}
