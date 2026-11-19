package datadogapm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newTestClient builds a client for url with fast retries; mutate adjusts the config.
func newTestClient(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	cfg := Config{CommonConfig: CommonConfig{URL: url, Retry: fastRetry(3)}}
	if mutate != nil {
		mutate(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func mustConfig(t *testing.T, explicit Config, env map[string]string) Config {
	t.Helper()
	c, err := ConfigFromEnvWith(explicit, env)
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	return c
}

func TestConfigDefaults(t *testing.T) {
	c := mustConfig(t, Config{}, map[string]string{})
	if c.URL != DefaultURL || DefaultURL != "http://127.0.0.1:8126" || c.Protocol != ProtocolHTTPProtobuf ||
		c.OTLPTracesEndpoint() != "http://127.0.0.1:4318/v1/traces" || c.Site != "datadoghq.com" ||
		c.APIURL != "https://api.datadoghq.com" || c.RequestTimeout != 30*time.Second || c.HasQueryCredentials() {
		t.Fatalf("defaults: %s", c)
	}
	if EnvPrefix != "GROW_DATADOG_APM_" {
		t.Fatal(EnvPrefix)
	}
	if (Config{}).Validate() != nil {
		t.Fatal("zero config must be valid")
	}
}

func TestConfigOTLPEndpoint(t *testing.T) {
	for _, tc := range []struct {
		cfg  Config
		want string
	}{
		{Config{CommonConfig: CommonConfig{URL: "http://agent:8126"}}, "http://agent:4318/v1/traces"},
		{Config{CommonConfig: CommonConfig{URL: "http://agent:8126"}, Protocol: ProtocolGRPC}, "http://agent:4317"},
		{Config{CommonConfig: CommonConfig{URL: "http://[::1]:8126"}}, "http://[::1]:4318/v1/traces"},
		{Config{OTLPEndpoint: "http://o:9/"}, "http://o:9/v1/traces"},
		{Config{OTLPEndpoint: "http://o:9/custom"}, "http://o:9/custom"},
	} {
		if got := tc.cfg.OTLPTracesEndpoint(); got != tc.want {
			t.Fatalf("%s: got %s want %s", tc.cfg, got, tc.want)
		}
	}
	if OTLPTracesURL(ProtocolGRPC, "http://g:4317") != "http://g:4317" || OTLPTracesURL("", "http://h:1") != "http://h:1/v1/traces" {
		t.Fatal("OTLPTracesURL")
	}
}

func TestConfigOptionalAndValidate(t *testing.T) {
	c := mustConfig(t, Config{
		CommonConfig: CommonConfig{Proxy: "http://p:1", UserAgentSuffix: "s"},
		APIKey:       NewSecret("api-key-value"), AppKey: NewSecret("app-key-value"),
		ServiceName: "svc", ServiceVersion: "1.0", Environment: "prod", Tags: map[string]string{"team": "sdk"},
		Site: "DatadogHQ.EU",
	}, map[string]string{})
	if c.Site != "datadoghq.eu" || c.APIURL != "https://api.datadoghq.eu" || !c.HasQueryCredentials() ||
		strings.Join(c.KnownSecrets(), ",") != "api-key-value,app-key-value" {
		t.Fatalf("optional: %s", c)
	}
	bad := []Config{
		{CommonConfig: CommonConfig{URL: "ftp://x"}},
		{APIKey: NewSecret("   ")},
		{AppKey: NewSecret("  ")},
		{Site: "https://app.datadoghq.com"},
		{Site: "datadoghq.com/path"},
		{APIURL: "nope"},
		{OTLPEndpoint: "tcp://x"},
		{Protocol: ProtocolNative},
		{ServiceName: " "},
	}
	for _, b := range bad {
		if err := b.Validate(); !errors.Is(err, ErrConfig) {
			t.Fatalf("%s: %v", b, err)
		}
		if _, err := NewClient(b); !errors.Is(err, ErrConfig) {
			t.Fatalf("NewClient %s: %v", b, err)
		}
	}
	if c := mustConfig(t, Config{Site: "custom.example.org"}, map[string]string{}); c.APIURL != "https://api.custom.example.org" {
		t.Fatal(c.APIURL)
	}
	if _, err := NewClient(Config{CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}); !errors.Is(err, ErrConfig) {
		t.Fatalf("bad TLS: %v", err)
	}
}

func TestConfigRedaction(t *testing.T) {
	t.Run("core.redaction.config", func(t *testing.T) {
		cfg := Config{
			CommonConfig: CommonConfig{URL: "https://h", Headers: map[string]string{"X-Api-Key": "hdr-xyz"}},
			APIKey:       NewSecret("api-xyz"), AppKey: NewSecret("app-xyz"), Tags: map[string]string{"b": "2", "a": "1"},
		}
		forms := fmt.Sprintf("%v|%+v|%#v|%s", cfg, cfg, cfg, cfg.GoString())
		for _, secret := range []string{"api-xyz", "app-xyz", "hdr-xyz"} {
			if strings.Contains(forms, secret) {
				t.Fatalf("leak %s: %s", secret, forms)
			}
		}
		if !strings.Contains(forms, "APIKey:"+Redacted) || !strings.Contains(forms, "Tags:{a=1 b=2}") {
			t.Fatalf("forms: %s", forms)
		}
		if !strings.Contains(Config{}.String(), "APIKey:<unset>") {
			t.Fatal("unset keys")
		}
	})
}

func TestParseDatadogTags(t *testing.T) {
	got := ParseDatadogTags("team:sdk, tier:web  bare :x url:http://a")
	if len(got) != 3 || got["team"] != "sdk" || got["tier"] != "web" || got["url"] != "http://a" {
		t.Fatal(got)
	}
}

func TestConfigEnvPrecedence(t *testing.T) {
	p := EnvPrefix
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			p + "URL": "http://grow:1", "DD_TRACE_AGENT_URL": "http://vendor:2", "DD_AGENT_HOST": "host3",
			"DD_API_KEY": "vendor-api-key", p + "APP_KEY": "grow-app-key", "DD_APP_KEY": "vendor-app-key",
			"DD_SERVICE": "dd-svc", "OTEL_SERVICE_NAME": "otel-svc", "DD_ENV": "dd-env", p + "VERSION": "9.9.9",
			"DD_VERSION": "1.0.0", "DD_TAGS": "team:sdk,tier:web", "DD_SITE": "datadoghq.eu",
			p + "REQUEST_TIMEOUT": "5s", p + "MAX_RETRIES": "7",
		}
		c := mustConfig(t, Config{}, env)
		if c.URL != "http://grow:1" || c.APIKey.Reveal() != "vendor-api-key" || c.AppKey.Reveal() != "grow-app-key" ||
			c.ServiceName != "dd-svc" || c.Environment != "dd-env" || c.ServiceVersion != "9.9.9" ||
			len(c.Tags) != 2 || c.Tags["tier"] != "web" || c.Site != "datadoghq.eu" || c.APIURL != "https://api.datadoghq.eu" ||
			c.RequestTimeout != 5*time.Second || c.Retry.MaxRetries != 7 || c.OTLPTracesEndpoint() != "http://grow:4318/v1/traces" {
			t.Fatalf("env: %s", c)
		}
		for envMap, want := range map[string]string{
			"DD_TRACE_AGENT_URL=http://vendor:2": "http://vendor:2",
			"DD_AGENT_HOST=h":                    "http://h:8126",
			"DD_TRACE_AGENT_PORT=9126":           "http://127.0.0.1:9126",
			"DD_AGENT_HOST=::1":                  "http://[::1]:8126",
		} {
			k, v, _ := strings.Cut(envMap, "=")
			if got := mustConfig(t, Config{}, map[string]string{k: v}).URL; got != want {
				t.Fatalf("%s: %s", envMap, got)
			}
		}
		if c := mustConfig(t, Config{}, map[string]string{"DD_APPLICATION_KEY": "k-app", "OTEL_SERVICE_NAME": "o"}); c.ServiceName != "o" || c.AppKey.Reveal() != "k-app" {
			t.Fatalf("fallbacks: %s", c)
		}
		explicit := mustConfig(t, Config{
			CommonConfig: CommonConfig{URL: "http://explicit:3"}, APIKey: NewSecret("explicit-key"),
			ServiceName: "x", Tags: map[string]string{"tier": "api"},
		}, env)
		if explicit.URL != "http://explicit:3" || explicit.APIKey.Reveal() != "explicit-key" || explicit.ServiceName != "x" ||
			explicit.Tags["team"] != "sdk" || explicit.Tags["tier"] != "api" || explicit.Retry.MaxRetries != 7 {
			t.Fatalf("explicit: %s", explicit)
		}
		if mustConfig(t, Config{}, map[string]string{}).URL != DefaultURL {
			t.Fatal("default")
		}
		for name, value := range map[string]string{
			p + "TOTAL_TIMEOUT":           "forever-and-secret",
			"DD_TRACE_AGENT_PORT":         "port-secret",
			p + "OTLP_PROTOCOL":           "thrift-secret",
			"OTEL_EXPORTER_OTLP_PROTOCOL": "zipkin-secret",
		} {
			_, err := ConfigFromEnvWith(Config{}, map[string]string{name: value})
			if !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), name) || strings.Contains(err.Error(), value) {
				t.Fatalf("%s: %v", name, err)
			}
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{"DD_TRACE_AGENT_PORT": "99999"}); !errors.Is(err, ErrConfig) {
			t.Fatal(err)
		}
		if _, err := ConfigFromEnvWith(Config{}, map[string]string{p + "URL": "ftp://nope"}); !errors.Is(err, ErrConfig) {
			t.Fatal(err)
		}
		t.Setenv(p+"URL", "http://process-env:9")
		if cfg, err := ConfigFromEnv(); err != nil || cfg.URL != "http://process-env:9" {
			t.Fatalf("process env: %v %s", err, cfg.URL)
		}
	})

	t.Run("otlp variables", func(t *testing.T) {
		all := map[string]string{
			p + "OTLP_HTTP_ENDPOINT": "http://h:4318", p + "OTLP_GRPC_ENDPOINT": "http://g:4317",
			p + "OTLP_ENDPOINT": "http://generic:4318", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "http://traces:1/v1/traces",
			"OTEL_EXPORTER_OTLP_ENDPOINT": "http://base:2",
		}
		check := func(explicit Config, env map[string]string, want string) {
			t.Helper()
			if got := mustConfig(t, explicit, env).OTLPTracesEndpoint(); got != want {
				t.Fatalf("got %s want %s", got, want)
			}
		}
		check(Config{}, all, "http://h:4318/v1/traces")
		check(Config{Protocol: ProtocolGRPC}, all, "http://g:4317")
		withProto := map[string]string{p + "OTLP_PROTOCOL": "GRPC"}
		for k, v := range all {
			withProto[k] = v
		}
		if mustConfig(t, Config{}, withProto).Protocol != ProtocolGRPC {
			t.Fatal("protocol env")
		}
		check(Config{}, map[string]string{"OTEL_EXPORTER_OTLP_PROTOCOL": "grpc"}, "http://127.0.0.1:4317")
		noHTTP := map[string]string{}
		for k, v := range all {
			if k != p+"OTLP_HTTP_ENDPOINT" {
				noHTTP[k] = v
			}
		}
		if got := mustConfig(t, Config{}, noHTTP).OTLPEndpoint; got != "http://generic:4318" {
			t.Fatal(got)
		}
		check(Config{}, map[string]string{"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "http://traces:1/v1/traces", "OTEL_EXPORTER_OTLP_ENDPOINT": "http://base:2"}, "http://traces:1/v1/traces")
		check(Config{}, map[string]string{"OTEL_EXPORTER_OTLP_ENDPOINT": "http://base:2/"}, "http://base:2/v1/traces")
		check(Config{Protocol: ProtocolGRPC}, map[string]string{"OTEL_EXPORTER_OTLP_ENDPOINT": "http://base:4317"}, "http://base:4317")
		if got := mustConfig(t, Config{}, map[string]string{p + "API_URL": "http://mock:1"}).APIURL; got != "http://mock:1" {
			t.Fatal(got)
		}
	})
}

func agentServer(t *testing.T) *testServer {
	t.Helper()
	info, err := os.ReadFile(filepath.Join("testdata", "health.info.ok.json"))
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Body map[string]any `json:"body"`
	}
	if err := DecodeJSONPreservingNumbers(info, &doc); err != nil {
		t.Fatal(err)
	}
	return newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		if r.URL.Path != "/info" {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte("404 page not found\n"))
			return
		}
		writeJSON(w, doc.Body)
	})
}

func TestClientAgent(t *testing.T) {
	ts := agentServer(t)

	t.Run("health ping version agentInfo", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		hs, err := c.Health(t.Context())
		if err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["version"] != "7.85.4" || hs.Details["git_commit"] != "52568a4d" || len(hs.Details) != 2 {
			t.Fatalf("health %+v %v", hs, err)
		}
		if d, err := c.Ping(t.Context()); err != nil || d <= 0 {
			t.Fatalf("ping %s %v", d, err)
		}
		if v, err := c.ServerVersion(t.Context()); err != nil || v != "7.85.4" {
			t.Fatalf("version %q %v", v, err)
		}
		info, err := c.AgentInfo(t.Context())
		if err != nil || info.Version != "7.85.4" || len(info.Endpoints) != 24 || info.Config["receiver_port"] == nil || info.Raw["span_events"] != true {
			t.Fatalf("info %+v %v", info, err)
		}
		if c.Config().URL != ts.URL || ts.request(0).Header.Get("User-Agent") != UserAgent {
			t.Fatal("config / UA")
		}
	})

	t.Run("serverVersion unsupported and errors", func(t *testing.T) {
		empty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte(`{"endpoints":[]}`)) })
		if _, err := newTestClient(t, empty.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("no version: %v", err)
		}
		blank := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { w.WriteHeader(http.StatusOK) })
		if _, err := newTestClient(t, blank.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnsupported) {
			t.Fatalf("empty body: %v", err)
		}
		bad := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("nope")) })
		if _, err := newTestClient(t, bad.URL, nil).ServerVersion(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("bad json: %v", err)
		}
		ts500 := newTestServer(t, statusHandler(http.StatusInternalServerError))
		c := newTestClient(t, ts500.URL, func(cfg *Config) { cfg.Retry = fastRetry(0) })
		if _, err := c.ServerVersion(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("500: %v", err)
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("ping 500: %v", err)
		}
	})

	t.Run("health reports unhealthy on HTTP errors", func(t *testing.T) {
		ts503 := newTestServer(t, statusHandler(http.StatusServiceUnavailable))
		hs, err := newTestClient(t, ts503.URL, nil).Health(t.Context())
		if err != nil || hs.Healthy || hs.Status != "unhealthy" || hs.Details["httpStatus"] != "503" || hs.Details["error"] != "UNAVAILABLE" {
			t.Fatalf("%+v %v", hs, err)
		}
		if ts503.calls.Load() != 1 {
			t.Fatal("health must not retry")
		}
	})

	t.Run("core.health.unreachable", func(t *testing.T) {
		c := newTestClient(t, "http://"+closedAddr(t), func(cfg *Config) { cfg.ConnectTimeout = time.Second })
		if hs, err := c.Health(t.Context()); err != nil || hs.Healthy || hs.Status != "unreachable" || hs.Details["error"] != "UNAVAILABLE" {
			t.Fatalf("hs=%+v err=%v", hs, err)
		}
	})

	t.Run("health rethrows cancellation", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := newTestClient(t, ts.URL, nil).Health(ctx); !errors.Is(err, ErrCancelled) {
			t.Fatal(err)
		}
	})

	t.Run("core.close.idempotent", func(t *testing.T) {
		c := newTestClient(t, ts.URL, nil)
		h, err := c.InitTracingWithOptions(t.Context(), TracingConfig{ServiceName: "svc", DisableSetGlobal: true}, InitTracingOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if h.Endpoint() != "http://127.0.0.1:4318/v1/traces" {
			t.Fatalf("endpoint %s", h.Endpoint())
		}
		if _, err := c.Spans(); !errors.Is(err, ErrConfig) {
			t.Fatal("spans without keys")
		}
		first, second := c.Close(), c.Close()
		if first != nil || second != nil {
			t.Fatal("close")
		}
		if err := h.Shutdown(t.Context()); err != nil {
			t.Fatal("handle already shut down must be a no-op")
		}
		if _, err := c.Ping(t.Context()); !errors.Is(err, ErrCancelled) {
			t.Fatalf("after close: %v", err)
		}
	})

	t.Run("spans needs query credentials and is reused", func(t *testing.T) {
		c := newTestClient(t, ts.URL, func(cfg *Config) { cfg.APIKey = NewSecret("api-k1"); cfg.AppKey = NewSecret("app-k1") })
		q1, err := c.Spans()
		if err != nil {
			t.Fatal(err)
		}
		q2, _ := c.Spans()
		if q1 != q2 || q1.Config().APIURL != "https://api.datadoghq.com" {
			t.Fatal("reuse")
		}
		if err := c.Close(); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("from env", func(t *testing.T) {
		t.Setenv(EnvPrefix+"URL", ts.URL)
		c, err := NewClientFromEnv()
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = c.Close() }()
		if _, err := c.Ping(t.Context()); err != nil {
			t.Fatal(err)
		}
		t.Setenv(EnvPrefix+"URL", "mailto:x")
		if _, err := NewClientFromEnv(); !errors.Is(err, ErrConfig) {
			t.Fatalf("bad env: %v", err)
		}
	})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
