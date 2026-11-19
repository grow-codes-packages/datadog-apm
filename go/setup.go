package datadogapm

// InitTracing for Datadog — the OpenTelemetry SDK exporting OTLP to the Datadog
// Agent (HTTP/protobuf on 4318 by default, or gRPC on 4317), unified service
// tagging, the Datadog + W3C propagators, the Datadog error attributes and the
// terminated-context links (API.md §2, DECISIONS.md #2, #5).

import (
	"context"
	"strings"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// DatadogDefaultPropagators are the default propagators of this package:
// Datadog headers, W3C trace context and baggage.
var DatadogDefaultPropagators = []string{PropagatorDatadog, PropagatorTraceContext, PropagatorBaggage}

// OrderPropagators puts "tracecontext" before "datadog" so the Datadog
// propagator sees the W3C result and applies the conflict rules (Datadog wins);
// every other name keeps its position. Names are trimmed and lower-cased.
func OrderPropagators(names []string) []string {
	list := make([]string, len(names))
	dd, tc := -1, -1
	for i, n := range names {
		list[i] = strings.ToLower(strings.TrimSpace(n))
		if list[i] == PropagatorDatadog && dd == -1 {
			dd = i
		}
		if list[i] == PropagatorTraceContext && tc == -1 {
			tc = i
		}
	}
	if dd == -1 || tc == -1 || tc < dd {
		return list
	}
	out := make([]string, 0, len(list))
	out = append(out, list[:dd]...)
	out = append(out, PropagatorTraceContext)
	for i := dd; i < len(list); i++ {
		if i != tc {
			out = append(out, list[i])
		}
	}
	return out
}

// DatadogExporterFactory is the ExporterFactory of this package: OTLP
// http/protobuf (otlptracehttp) or gRPC (otlptracegrpc) to cfg.Endpoint (default:
// the local Agent), wrapped in a DatadogErrorExporter. ProtocolNative is a
// CONFIG error (the package exports OTLP only).
func DatadogExporterFactory(ctx context.Context, cfg TracingConfig) (sdktrace.SpanExporter, error) {
	if cfg.Protocol == ProtocolNative {
		return nil, ConfigError("tracing: the Datadog package exports OTLP; use http/protobuf or grpc")
	}
	if cfg.Protocol == "" {
		cfg.Protocol = DefaultProtocol
	}
	if cfg.Endpoint == "" {
		cfg.Endpoint = Config{Protocol: cfg.Protocol}.OTLPTracesEndpoint()
	}
	cfg.Endpoint = OTLPTracesURL(cfg.Protocol, cfg.Endpoint)
	exp, err := NewOTLPExporter(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return NewDatadogErrorExporter(exp), nil
}

// InitTracingOptions are the extra options of InitTracingWithOptions.
type InitTracingOptions struct {
	// Config holds the Agent/service settings beneath the tracing config (used as
	// is). Nil: ConfigFromEnvWith(Config{Protocol: cfg.Protocol}, Env).
	Config *Config
	// Env replaces the process environment for ConfigFromEnvWith (nil = process env).
	Env map[string]string
	// IgnoreEnv builds the default Config without reading any environment.
	IgnoreEnv bool
	// SpanProcessors are added to the tracer provider after the Datadog ones.
	SpanProcessors []sdktrace.SpanProcessor
}

// DatadogTracingHandle is a running Datadog tracing setup. It embeds the
// generic *TracingHandle (Tracer, TracerProvider, ForceFlush, Shutdown, …).
type DatadogTracingHandle struct {
	*TracingHandle
	endpoint string
}

// Endpoint returns the OTLP traces URL spans are exported to.
func (h *DatadogTracingHandle) Endpoint() string { return h.endpoint }

// InitTracing sets up OpenTelemetry tracing that exports to the Datadog Agent
// (SPEC §5):
//
//   - OTLP http/protobuf to http://<agent>:4318/v1/traces by default, or grpc to
//     http://<agent>:4317 (the Agent needs its OTLP receiver enabled);
//   - unified service tagging: ServiceName / ServiceVersion / Environment →
//     resource service.name / service.version / deployment.environment.name
//     (Datadog service, version, env), plus DD_TAGS as resource attributes;
//   - propagators datadog, tracecontext, baggage by default (Datadog headers win
//     on conflict);
//   - the Datadog error attributes (DatadogErrorExporter) and the
//     terminated-context link (DatadogSpanLinkProcessor).
//
// Unset fields come from Config (ConfigFromEnv: DD_SERVICE, DD_VERSION, DD_ENV,
// DD_TAGS, DD_AGENT_HOST, GROW_DATADOG_APM_*, OTEL_EXPORTER_OTLP_*).
//
//	h, err := datadogapm.InitTracing(ctx, datadogapm.TracingConfig{ServiceName: "checkout", Environment: "prod"})
//	if err != nil { return err }
//	defer h.Shutdown(context.Background())
func InitTracing(ctx context.Context, cfg TracingConfig) (*DatadogTracingHandle, error) {
	return InitTracingWithOptions(ctx, cfg, InitTracingOptions{})
}

func apmConfigFor(protocol Protocol, opts InitTracingOptions) (Config, error) {
	if opts.Config != nil {
		c := opts.Config.withServiceDefaults()
		return c, c.Validate()
	}
	base := Config{Protocol: protocol}
	if opts.IgnoreEnv {
		c := base.withServiceDefaults()
		return c, c.Validate()
	}
	return ConfigFromEnvWith(base, opts.Env)
}

// InitTracingWithOptions is InitTracing with an explicit Config, environment
// or extra span processors.
func InitTracingWithOptions(ctx context.Context, cfg TracingConfig, opts InitTracingOptions) (*DatadogTracingHandle, error) {
	if cfg.Protocol == ProtocolNative {
		return nil, ConfigError("tracing: the Datadog package exports OTLP; use http/protobuf or grpc")
	}
	protocol := cfg.Protocol
	if protocol != "" && protocol != ProtocolGRPC && protocol != ProtocolHTTPProtobuf {
		return nil, cfg.Validate()
	}
	apm, err := apmConfigFor(protocol, opts)
	if err != nil {
		return nil, err
	}
	if protocol == "" {
		protocol = apm.Protocol
	}
	var endpoint string
	switch {
	case cfg.Endpoint != "":
		endpoint = OTLPTracesURL(protocol, cfg.Endpoint)
	case protocol == apm.Protocol:
		endpoint = apm.OTLPTracesEndpoint()
	default:
		endpoint = Config{CommonConfig: CommonConfig{URL: apm.URL}, Protocol: protocol}.OTLPTracesEndpoint()
	}
	effective := cfg
	effective.Protocol = protocol
	effective.Endpoint = endpoint
	effective.ServiceName = firstNonEmpty(cfg.ServiceName, apm.ServiceName)
	effective.ServiceVersion = firstNonEmpty(cfg.ServiceVersion, apm.ServiceVersion)
	effective.Environment = firstNonEmpty(cfg.Environment, apm.Environment)
	propagators := cfg.Propagators
	if len(propagators) == 0 {
		propagators = DatadogDefaultPropagators
	}
	effective.Propagators = OrderPropagators(propagators)
	attrs := make(map[string]any, len(apm.Tags)+len(cfg.ResourceAttributes)+1)
	for k, v := range apm.Tags {
		attrs[k] = v
	}
	for k, v := range cfg.ResourceAttributes {
		attrs[k] = v
	}
	if effective.Environment != "" {
		if _, set := attrs["deployment.environment.name"]; !set {
			attrs["deployment.environment.name"] = effective.Environment
		}
	}
	effective.ResourceAttributes = attrs
	if cfg.TLS == (TLSConfig{}) {
		effective.TLS = apm.TLS
	}

	h, err := InitTracingWith(ctx, effective, DatadogExporterFactory)
	if err != nil {
		return nil, err
	}
	provider := h.TracerProvider()
	provider.RegisterSpanProcessor(DatadogSpanLinkProcessor{})
	for _, p := range opts.SpanProcessors {
		provider.RegisterSpanProcessor(p)
	}
	setDatadogLogDefaults(DatadogLogFieldsOptions{
		Service: effective.ServiceName, Env: effective.Environment, Version: effective.ServiceVersion,
	})
	return &DatadogTracingHandle{TracingHandle: h, endpoint: endpoint}, nil
}
