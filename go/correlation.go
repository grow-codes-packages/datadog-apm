package datadogapm

// Datadog log correlation (DECISIONS.md #3). Same field format as ddtrace's
// log injection: dd.trace_id is 32 lower-case hex for a 128-bit id and the
// decimal low 64 bits for a 64-bit id; dd.span_id is decimal.

import (
	"context"
	"sync"

	"go.opentelemetry.io/otel/sdk/resource"
)

// Keys of DatadogLogFields.
const (
	LogFieldTraceID = "dd.trace_id"
	LogFieldSpanID  = "dd.span_id"
	LogFieldService = "dd.service"
	LogFieldEnv     = "dd.env"
	LogFieldVersion = "dd.version"
)

// DatadogLogFieldsOptions are explicit unified-service-tagging values for
// DatadogLogFields (empty = not given).
type DatadogLogFieldsOptions struct {
	Service string
	Env     string
	Version string
}

var (
	logDefaultsMu sync.RWMutex
	logDefaults   DatadogLogFieldsOptions
)

// SetDatadogLogDefaults sets the service/env/version DatadogLogFields uses when
// the active span carries no resource. InitTracing calls it with its effective
// config; call it yourself when spans come from another tracer provider.
func SetDatadogLogDefaults(v DatadogLogFieldsOptions) {
	logDefaultsMu.Lock()
	defer logDefaultsMu.Unlock()
	logDefaults = v
}

func currentLogDefaults() DatadogLogFieldsOptions {
	logDefaultsMu.RLock()
	defer logDefaultsMu.RUnlock()
	return logDefaults
}

type resourceSpan interface{ Resource() *resource.Resource }

func resourceValue(res *resource.Resource, key string) string {
	if res == nil {
		return ""
	}
	for _, kv := range res.Attributes() {
		if string(kv.Key) == key {
			return kv.Value.String()
		}
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// DatadogLogFields returns the Datadog log correlation fields for the span in
// ctx, ready to merge into any structured log record: dd.trace_id, dd.span_id,
// dd.service, dd.env, dd.version. Service, env and version come from opts, else
// the span's resource (service.name, deployment.environment.name,
// service.version), else the last InitTracing config. Without a valid span only
// the service fields are returned; keys are absent when unknown.
//
//	slog.InfoContext(ctx, "checkout done", "dd", datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{}))
func DatadogLogFields(ctx context.Context, opts DatadogLogFieldsOptions) map[string]string {
	out := map[string]string{}
	span := CurrentSpan(ctx)
	if sc := span.SpanContext(); sc.IsValid() {
		out[LogFieldTraceID], _ = DatadogTraceIDString(sc.TraceID().String())
		out[LogFieldSpanID], _ = ToDatadogSpanID(sc.SpanID().String())
	}
	var res *resource.Resource
	if rs, ok := span.(resourceSpan); ok {
		res = rs.Resource()
	}
	d := currentLogDefaults()
	service := firstNonEmpty(opts.Service, resourceValue(res, "service.name"), d.Service)
	env := firstNonEmpty(opts.Env, resourceValue(res, "deployment.environment.name"), resourceValue(res, "deployment.environment"), d.Env)
	version := firstNonEmpty(opts.Version, resourceValue(res, "service.version"), d.Version)
	if service != "" {
		out[LogFieldService] = service
	}
	if env != "" {
		out[LogFieldEnv] = env
	}
	if version != "" {
		out[LogFieldVersion] = version
	}
	return out
}
