package integration

// Integration tests of the Client against the real Datadog Agent (docker/compose.yml).

import (
	"errors"
	"fmt"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	datadogapm "github.com/grow-codes-packages/datadog-apm/go"
)

func newClient(t *testing.T) *datadogapm.Client {
	t.Helper()
	c, err := datadogapm.NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func TestClientHealth(t *testing.T) {
	requireService(t)
	hs, err := newClient(t).Health(t.Context())
	if err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["version"] != os.Getenv("DD_AGENT_VERSION") {
		t.Fatalf("health %+v %v", hs, err)
	}
}

func TestClientPing(t *testing.T) {
	requireService(t)
	d, err := newClient(t).Ping(t.Context())
	if err != nil || d <= 0 {
		t.Fatalf("ping %s %v", d, err)
	}
}

func TestClientServerVersionAndAgentInfo(t *testing.T) {
	requireService(t)
	c := newClient(t)
	v, err := c.ServerVersion(t.Context())
	if err != nil || v != os.Getenv("DD_AGENT_VERSION") {
		t.Fatalf("version %q %v", v, err)
	}
	info, err := c.AgentInfo(t.Context())
	if err != nil || info.Version != v || !slices.Contains(info.Endpoints, "/v0.4/traces") {
		t.Fatalf("info %+v %v", info, err)
	}
	if c.Close() != nil || c.Close() != nil {
		t.Fatal("close twice")
	}
	if _, err := c.Ping(t.Context()); !errors.Is(err, datadogapm.ErrCancelled) {
		t.Fatalf("after close: %v", err)
	}
}

func TestClientHealthUnreachable(t *testing.T) {
	requireService(t)
	c, err := datadogapm.NewClient(datadogapm.Config{CommonConfig: datadogapm.CommonConfig{
		URL: "http://127.0.0.1:1", ConnectTimeout: time.Second, Retry: &datadogapm.RetryPolicy{MaxRetries: 0},
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	hs, err := c.Health(t.Context())
	if err != nil || hs.Healthy || hs.Status != "unreachable" {
		t.Fatalf("%+v %v", hs, err)
	}
}

func TestConfigFromBootstrapEnv(t *testing.T) {
	requireService(t)
	cfg, err := datadogapm.ConfigFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.URL != os.Getenv("GROW_DATADOG_APM_URL") ||
		cfg.OTLPTracesEndpoint() != os.Getenv("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT")+"/v1/traces" ||
		cfg.Site != "datadoghq.com" || !cfg.HasQueryCredentials() {
		t.Fatalf("config %s", cfg)
	}
	grpc, err := datadogapm.ConfigFromEnvWith(datadogapm.Config{Protocol: datadogapm.ProtocolGRPC}, nil)
	if err != nil || grpc.OTLPTracesEndpoint() != os.Getenv("GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT") {
		t.Fatalf("grpc %s %v", grpc.OTLPTracesEndpoint(), err)
	}
	forms := fmt.Sprintf("%v %+v %#v", cfg, cfg, cfg)
	if key := os.Getenv("DD_API_KEY"); key == "" || strings.Contains(forms, key) {
		t.Fatal("API key must be redacted")
	}
	// The query API exists but is SaaS-only (unit-tested); constructing it needs both keys.
	q, err := newClient(t).Spans()
	if err != nil || q.Config().APIURL != "https://api.datadoghq.com" {
		t.Fatal(err)
	}
	if _, err := datadogapm.NewDatadogSpansQuery(datadogapm.Config{APIKey: datadogapm.NewSecret("only-one-key")}); !errors.Is(err, datadogapm.ErrConfig) {
		t.Fatal(err)
	}
	if _, err := datadogapm.DatadogSpansQueryFromEnv(); err != nil {
		t.Fatal(err)
	}
}
