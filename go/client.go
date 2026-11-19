package datadogapm

// The Datadog APM client (API.md §1). It talks to the local Datadog Agent
// (GET /info on the trace intake port 8126, no authentication) and gives access
// to the tracing setup and the SaaS spans query API.

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// AgentInfo is the Agent's GET /info document (fields this package reads).
type AgentInfo struct {
	// Version is the Agent version, e.g. "7.85.4".
	Version string `json:"version"`
	// GitCommit is the Agent build commit.
	GitCommit string `json:"git_commit"`
	// Endpoints are the trace intake endpoints the Agent serves.
	Endpoints []string `json:"endpoints"`
	// Config is the Agent's reported configuration.
	Config map[string]any `json:"config"`
	// Raw is the whole document.
	Raw map[string]any `json:"-"`
}

// Client is the Datadog APM client. Health, Ping and ServerVersion use the
// Datadog Agent's GET /info; InitTracing sets up OpenTelemetry exporting to the
// Agent; Spans is the SaaS query API (needs APIKey + AppKey). It is safe for
// concurrent use.
//
//	client, err := datadogapm.NewClientFromEnv()
//	if err != nil { return err }
//	defer client.Close() // flushes and shuts down the tracing it started
//	h, err := client.InitTracing(ctx, datadogapm.TracingConfig{ServiceName: "checkout"})
//	version, err := client.ServerVersion(ctx) // "7.85.4"
type Client struct {
	cfg       Config
	transport *httpTransport

	mu      sync.Mutex
	handles []*DatadogTracingHandle
	spans   *DatadogSpansQuery
	closeMu sync.Mutex
	done    bool
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg = cfg.withServiceDefaults()
	t, err := newHTTPTransport(cfg.CommonConfig)
	if err != nil {
		return nil, err
	}
	t.addSecret(cfg.KnownSecrets()...)
	return &Client{cfg: cfg, transport: t}, nil
}

// NewClientFromEnv builds a client from GROW_DATADOG_APM_* and DD_* variables
// (see ConfigFromEnvWith).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// Config returns the effective configuration.
func (c *Client) Config() Config { return c.cfg }

// Spans returns the SaaS spans query API (created on first use, closed by
// Close). It is a CONFIG error when APIKey or AppKey is missing.
func (c *Client) Spans() (*DatadogSpansQuery, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.spans == nil {
		q, err := NewDatadogSpansQuery(c.cfg)
		if err != nil {
			return nil, err
		}
		c.spans = q
	}
	return c.spans, nil
}

// AgentInfo returns GET /info of the Agent.
func (c *Client) AgentInfo(ctx context.Context) (AgentInfo, error) {
	return c.agentInfo(ctx, false)
}

func (c *Client) agentInfo(ctx context.Context, noRetry bool) (AgentInfo, error) {
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/info", Operation: "agentInfo", NoRetry: noRetry})
	if err != nil {
		return AgentInfo{}, err
	}
	var info AgentInfo
	if len(res.Body) == 0 {
		return info, nil
	}
	if err := res.decodeJSON("agentInfo", &info); err != nil {
		return AgentInfo{}, err
	}
	_ = res.decodeJSON("agentInfo", &info.Raw)
	return info, nil
}

// Health checks Agent liveness with GET /info: healthy with status "ok" and
// details version, git_commit when the Agent answers; "unreachable" on network
// errors, "unhealthy" on HTTP errors (details httpStatus, error). It fails only
// for CANCELLED and CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	info, err := c.agentInfo(ctx, true)
	if err != nil {
		var e *Error
		if !errors.As(err, &e) {
			return HealthStatus{Status: "unreachable", Details: map[string]string{"error": string(ErrorCodeUnavailable)}}, nil
		}
		switch e.Code {
		case ErrorCodeCancelled, ErrorCodeConfig:
			return HealthStatus{}, err
		}
		if e.HTTPStatus != 0 {
			return HealthStatus{Status: "unhealthy", Details: map[string]string{
				"httpStatus": strconv.Itoa(e.HTTPStatus), "error": string(e.Code),
			}}, nil
		}
		return HealthStatus{Status: "unreachable", Details: map[string]string{"error": string(e.Code)}}, nil
	}
	details := map[string]string{}
	if info.Version != "" {
		details["version"] = info.Version
	}
	if info.GitCommit != "" {
		details["git_commit"] = info.GitCommit
	}
	return HealthStatus{Healthy: true, Status: "ok", Details: details}, nil
}

// Ping makes a round trip to the Agent (GET /info) and returns its latency. The
// Agent has no authentication; to check Datadog API credentials use
// Spans() and DatadogSpansQuery.Ping.
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	if _, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/info", Operation: "ping"}); err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// ServerVersion returns the Datadog Agent version from GET /info (e.g.
// "7.85.4"); UNSUPPORTED when the Agent reports none.
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	info, err := c.AgentInfo(ctx)
	if err != nil {
		return "", err
	}
	if info.Version == "" {
		return "", UnsupportedError("serverVersion", "the Datadog Agent did not report a version")
	}
	return info.Version, nil
}

// InitTracing is the package-level InitTracing with this client's
// configuration as the defaults (Agent host, OTLP endpoint and protocol,
// service/env/version, tags). Close shuts the handle down.
func (c *Client) InitTracing(ctx context.Context, cfg TracingConfig) (*DatadogTracingHandle, error) {
	return c.InitTracingWithOptions(ctx, cfg, InitTracingOptions{})
}

// InitTracingWithOptions is InitTracing with extra span processors (the Config
// and Env options are replaced by the client's configuration).
func (c *Client) InitTracingWithOptions(ctx context.Context, cfg TracingConfig, opts InitTracingOptions) (*DatadogTracingHandle, error) {
	apm := c.cfg
	opts.Config = &apm
	opts.Env = nil
	h, err := InitTracingWithOptions(ctx, cfg, opts)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	c.handles = append(c.handles, h)
	c.mu.Unlock()
	return h, nil
}

// Close flushes and shuts down every tracing handle started with InitTracing,
// closes the query API and releases connections. It is idempotent: later
// calls return nil.
func (c *Client) Close() error {
	c.closeMu.Lock()
	defer c.closeMu.Unlock()
	if c.done {
		return nil
	}
	c.done = true
	c.mu.Lock()
	handles, spans := c.handles, c.spans
	c.handles = nil
	c.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), c.cfg.TotalTimeout)
	defer cancel()
	var errs []error
	for _, h := range handles {
		if err := h.Shutdown(ctx); err != nil {
			errs = append(errs, err)
		}
	}
	if spans != nil {
		_ = spans.Close()
	}
	c.transport.close()
	return errors.Join(errs...)
}
