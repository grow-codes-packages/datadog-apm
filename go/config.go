package datadogapm

// Configuration of the Datadog APM client (API.md §0, DECISIONS.md #1).

import (
	"fmt"
	"net"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Default ports of the Datadog Agent.
const (
	// OTLPHTTPPort is the default Agent OTLP/HTTP port.
	OTLPHTTPPort = 4318
	// OTLPGRPCPort is the default Agent OTLP/gRPC port.
	OTLPGRPCPort = 4317
	// AgentTracePort is the default Agent trace intake port (GET /info).
	AgentTracePort = 8126
)

// DefaultURL is the default Datadog Agent trace intake URL (health, ping,
// serverVersion use its GET /info).
const DefaultURL = "http://127.0.0.1:8126"

// DefaultProtocol is the default OTLP protocol of this package.
const DefaultProtocol = ProtocolHTTPProtobuf

// Vendor environment variables read after GROW_DATADOG_APM_<NAME>, in order.
var (
	vendorEnv            = VendorEnv{URL: []string{"DD_TRACE_AGENT_URL"}}
	vendorServiceEnv     = []string{"DD_SERVICE", "OTEL_SERVICE_NAME"}
	vendorVersionEnv     = []string{"DD_VERSION"}
	vendorEnvironmentEnv = []string{"DD_ENV"}
	vendorTagsEnv        = []string{"DD_TAGS"}
	vendorAPIKeyEnv      = []string{"DD_API_KEY"}
	vendorAppKeyEnv      = []string{"DD_APP_KEY", "DD_APPLICATION_KEY"}
	vendorSiteEnv        = []string{"DD_SITE"}
	vendorProtocolEnv    = []string{"OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"}
)

// Config configures a Client, InitTracing and DatadogSpansQuery. Build it with
// a struct literal or ConfigFromEnv; zero values mean "use the default". Call
// Validate to check it.
type Config struct {
	CommonConfig

	// OTLPEndpoint is the OTLP endpoint of the Agent. HTTP: a base URL
	// ("http://agent:4318", /v1/traces is appended when the URL has no path) or
	// the full traces URL; gRPC: "http://agent:4317". Empty: the host of URL with
	// port 4318 (HTTP) or 4317 (gRPC). See OTLPTracesEndpoint.
	OTLPEndpoint string
	// Protocol is the OTLP protocol: ProtocolHTTPProtobuf (default) or ProtocolGRPC.
	Protocol Protocol
	// ServiceName is "service" of unified service tagging (resource service.name).
	ServiceName string
	// ServiceVersion is "version" of unified service tagging (resource service.version).
	ServiceVersion string
	// Environment is "env" of unified service tagging (resource
	// deployment.environment.name).
	Environment string
	// Tags are extra resource attributes (DD_TAGS).
	Tags map[string]string
	// APIKey is the Datadog API key; needed only by the query API (DatadogSpansQuery).
	APIKey Secret
	// AppKey is the Datadog application key with apm_read; needed only by the query API.
	AppKey Secret
	// Site is the Datadog site, e.g. "datadoghq.com" (default) or "datadoghq.eu".
	Site string
	// APIURL is the query API base URL. Default "https://api.<site>".
	APIURL string
}

var tagSplit = regexp.MustCompile(`[\s,]+`)

// ParseDatadogTags parses Datadog tags (DD_TAGS): key:value pairs separated by
// commas and/or spaces. Entries without a ":" or with an empty key are ignored.
//
//	ParseDatadogTags("team:sdk, tier:web") // {"team": "sdk", "tier": "web"}
func ParseDatadogTags(raw string) map[string]string {
	out := map[string]string{}
	for _, part := range tagSplit.Split(raw, -1) {
		k, v, ok := strings.Cut(part, ":")
		if !ok || k == "" {
			continue
		}
		out[k] = v
	}
	return out
}

func isHTTPURL(value string) bool {
	u, err := url.Parse(value)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != ""
}

func hasPath(value string) bool {
	u, err := url.Parse(value)
	return err == nil && u.Path != "" && u.Path != "/"
}

// OTLPTracesURL returns the exporter URL for a protocol and an OTLP endpoint:
// for http/protobuf (or the empty protocol) an endpoint without a path gets
// "/v1/traces"; gRPC endpoints are used as they are.
func OTLPTracesURL(protocol Protocol, endpoint string) string {
	if protocol == ProtocolGRPC || hasPath(endpoint) {
		return endpoint
	}
	return strings.TrimRight(endpoint, "/") + "/v1/traces"
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_DATADOG_APM_* > vendor
// variables > defaults. env replaces the process environment when non-nil.
//
//	Field          GROW_DATADOG_APM_*                    Vendor variables
//	URL            URL                                   DD_TRACE_AGENT_URL, else http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}
//	OTLPEndpoint   OTLP_HTTP_ENDPOINT / OTLP_GRPC_ENDPOINT (by protocol), OTLP_ENDPOINT
//	                                                     OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, OTEL_EXPORTER_OTLP_ENDPOINT
//	Protocol       OTLP_PROTOCOL                         OTEL_EXPORTER_OTLP_TRACES_PROTOCOL, OTEL_EXPORTER_OTLP_PROTOCOL
//	ServiceName    SERVICE                               DD_SERVICE, OTEL_SERVICE_NAME
//	ServiceVersion VERSION                               DD_VERSION
//	Environment    ENV                                   DD_ENV
//	Tags           TAGS                                  DD_TAGS (explicit tags are merged over them)
//	APIKey         API_KEY                               DD_API_KEY
//	AppKey         APP_KEY                               DD_APP_KEY, DD_APPLICATION_KEY
//	Site           SITE                                  DD_SITE
//	APIURL         API_URL                               —
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	if explicit.URL == "" && c.URL == "" {
		agentURL, err := agentURLFromEnv(r)
		if err != nil {
			return Config{}, err
		}
		c.URL = agentURL
	}
	if c.Protocol == "" {
		if v, variable, ok := r.find("OTLP_PROTOCOL", vendorProtocolEnv); ok {
			switch p := Protocol(strings.ToLower(strings.TrimSpace(v))); p {
			case ProtocolGRPC, ProtocolHTTPProtobuf:
				c.Protocol = p
			default:
				return Config{}, ConfigError(variable + " must be grpc or http/protobuf")
			}
		}
	}
	if c.OTLPEndpoint == "" {
		c.OTLPEndpoint = otlpEndpointFromEnv(r, c.protocol())
	}
	setTrimmed := func(dst *string, name string, vendor []string) {
		if *dst == "" {
			if v, ok := r.String(name, vendor...); ok {
				*dst = strings.TrimSpace(v)
			}
		}
	}
	setTrimmed(&c.ServiceName, "SERVICE", vendorServiceEnv)
	setTrimmed(&c.ServiceVersion, "VERSION", vendorVersionEnv)
	setTrimmed(&c.Environment, "ENV", vendorEnvironmentEnv)
	setTrimmed(&c.Site, "SITE", vendorSiteEnv)
	setTrimmed(&c.APIURL, "API_URL", nil)
	if raw, ok := r.String("TAGS", vendorTagsEnv...); ok {
		tags := ParseDatadogTags(raw)
		for k, v := range explicit.Tags {
			tags[k] = v
		}
		c.Tags = tags
	}
	if c.APIKey.IsZero() {
		if v, ok := r.String("API_KEY", vendorAPIKeyEnv...); ok {
			c.APIKey = NewSecret(v)
		}
	}
	if c.AppKey.IsZero() {
		if v, ok := r.String("APP_KEY", vendorAppKeyEnv...); ok {
			c.AppKey = NewSecret(v)
		}
	}
	c = c.withServiceDefaults()
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// agentURLFromEnv builds http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT} ("" when neither is set).
func agentURLFromEnv(r *EnvReader) (string, error) {
	host, hasHost := r.String("", "DD_AGENT_HOST")
	host = strings.TrimSpace(host)
	port := AgentTracePort
	rawPort, variable, hasPort := r.find("", []string{"DD_TRACE_AGENT_PORT"})
	if hasPort {
		p, err := strconv.Atoi(strings.TrimSpace(rawPort))
		if err != nil || p < 1 || p > 65535 || !isDigits(strings.TrimSpace(rawPort)) {
			return "", ConfigError(variable + " must be a port number")
		}
		port = p
	}
	if (!hasHost || host == "") && !hasPort {
		return "", nil
	}
	if host == "" {
		host = "127.0.0.1"
	}
	return "http://" + net.JoinHostPort(strings.Trim(host, "[]"), strconv.Itoa(port)), nil
}

func otlpEndpointFromEnv(r *EnvReader, protocol Protocol) string {
	specific := "OTLP_HTTP_ENDPOINT"
	if protocol == ProtocolGRPC {
		specific = "OTLP_GRPC_ENDPOINT"
	}
	if v, ok := r.String(specific, EnvPrefix+"OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"); ok {
		return strings.TrimSpace(v)
	}
	if v, ok := r.String("", "OTEL_EXPORTER_OTLP_ENDPOINT"); ok {
		v = strings.TrimSpace(v)
		if protocol == ProtocolGRPC {
			return v
		}
		return strings.TrimRight(v, "/") + "/v1/traces"
	}
	return ""
}

func (c Config) protocol() Protocol {
	if c.Protocol == "" {
		return DefaultProtocol
	}
	return c.Protocol
}

// withServiceDefaults applies the defaults of the common and the service fields.
func (c Config) withServiceDefaults() Config {
	c.CommonConfig = c.withDefaults(DefaultURL)
	c.Protocol = c.protocol()
	if strings.TrimSpace(c.Site) == "" {
		c.Site = DefaultSite
	}
	c.Site = NormalizeSite(c.Site)
	if c.APIURL == "" {
		c.APIURL = "https://api." + c.Site
	}
	return c
}

// OTLPTracesEndpoint returns the resolved OTLP traces URL for the configured
// protocol (see OTLPTracesURL): OTLPEndpoint when set, else the host of URL with
// port 4318 + /v1/traces (HTTP) or port 4317 (gRPC).
func (c Config) OTLPTracesEndpoint() string {
	c = c.withServiceDefaults()
	if c.OTLPEndpoint != "" {
		return OTLPTracesURL(c.Protocol, c.OTLPEndpoint)
	}
	port := OTLPHTTPPort
	if c.Protocol == ProtocolGRPC {
		port = OTLPGRPCPort
	}
	scheme, host := "http", "127.0.0.1"
	if u, err := url.Parse(c.URL); err == nil && u.Hostname() != "" {
		scheme, host = u.Scheme, u.Hostname()
	}
	return OTLPTracesURL(c.Protocol, scheme+"://"+net.JoinHostPort(host, strconv.Itoa(port)))
}

// HasQueryCredentials reports whether both the API key and the application key
// are set (the query API is usable).
func (c Config) HasQueryCredentials() bool { return !c.APIKey.IsZero() && !c.AppKey.IsZero() }

// KnownSecrets returns the credential values to redact from errors and logs.
func (c Config) KnownSecrets() []string {
	var out []string
	for _, s := range []Secret{c.APIKey, c.AppKey} {
		if !s.IsZero() {
			out = append(out, s.Reveal())
		}
	}
	return out
}

// Validate checks the configuration and returns a CONFIG error.
func (c Config) Validate() error {
	c = c.withServiceDefaults()
	if err := c.validate(); err != nil {
		return err
	}
	var problems []string
	if c.Protocol != ProtocolHTTPProtobuf && c.Protocol != ProtocolGRPC {
		problems = append(problems, "protocol must be http/protobuf or grpc")
	}
	if c.OTLPEndpoint != "" && !isHTTPURL(c.OTLPEndpoint) {
		problems = append(problems, "otlpEndpoint must be an http or https URL")
	}
	if !isHTTPURL(c.APIURL) {
		problems = append(problems, "apiUrl must be an http or https URL")
	}
	for _, f := range []struct{ name, value string }{
		{"serviceName", c.ServiceName}, {"serviceVersion", c.ServiceVersion}, {"environment", c.Environment},
	} {
		if f.value != "" && strings.TrimSpace(f.value) == "" {
			problems = append(problems, f.name+" must not be blank")
		}
	}
	if !c.APIKey.IsZero() && strings.TrimSpace(c.APIKey.Reveal()) == "" {
		problems = append(problems, "apiKey must not be blank")
	}
	if !c.AppKey.IsZero() && strings.TrimSpace(c.AppKey.Reveal()) == "" {
		problems = append(problems, "appKey must not be blank")
	}
	if len(problems) > 0 {
		return ConfigError(strings.Join(problems, "; "))
	}
	_, err := ValidateSite(c.Site)
	return err
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	d := c.withServiceDefaults()
	keys := make([]string, 0, len(c.Tags))
	for k := range c.Tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	tags := make([]string, len(keys))
	for i, k := range keys {
		tags[i] = k + "=" + c.Tags[k]
	}
	redacted := func(s Secret) string {
		if s.IsZero() {
			return "<unset>"
		}
		return Redacted
	}
	return fmt.Sprintf("Config{%s Protocol:%s OTLPEndpoint:%s ServiceName:%s ServiceVersion:%s Environment:%s Tags:{%s} Site:%s APIURL:%s APIKey:%s AppKey:%s}",
		d.describe(), d.Protocol, RedactURL(d.OTLPTracesEndpoint()), c.ServiceName, c.ServiceVersion, c.Environment,
		strings.Join(tags, " "), d.Site, RedactURL(d.APIURL), redacted(c.APIKey), redacted(c.AppKey))
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }
