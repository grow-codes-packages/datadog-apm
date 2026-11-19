package datadogapm

// Datadog header propagation (x-datadog-*) with ddtrace's conflict rules
// (API.md §3, DECISIONS.md #5). Implemented from Datadog's documented header
// format; no ddtrace code is used.

import (
	"context"
	"regexp"
	"strconv"
	"strings"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// Datadog propagation headers.
const (
	// DatadogTraceIDHeader carries the low 64 bits of the trace id, unsigned decimal.
	DatadogTraceIDHeader = "x-datadog-trace-id"
	// DatadogParentIDHeader carries the span id, unsigned decimal.
	DatadogParentIDHeader = "x-datadog-parent-id"
	// DatadogSamplingPriorityHeader is "1" (sampled) or "0" (not sampled).
	DatadogSamplingPriorityHeader = "x-datadog-sampling-priority"
	// DatadogTagsHeader carries propagated tags, e.g. "_dd.p.tid=<high 64 bits hex>".
	DatadogTagsHeader = "x-datadog-tags"
	// DatadogOriginHeader is the origin of the trace (synthetics, rum, …).
	DatadogOriginHeader = "x-datadog-origin"
)

// PropagatorDatadog is the TracingConfig.Propagators name of DatadogPropagator.
const PropagatorDatadog = "datadog"

func init() {
	RegisterPropagator(PropagatorDatadog, func() propagation.TextMapPropagator { return DatadogPropagator{} })
}

var traceparentRe = regexp.MustCompile(`^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$`)

// TerminatedContext is a W3C context that lost against Datadog headers in
// DatadogPropagator.Extract.
type TerminatedContext struct {
	// SpanContext is the W3C span context from traceparent.
	SpanContext trace.SpanContext
	// WinnerSpanID is the span id of the Datadog context that won (the parent of
	// the next span).
	WinnerSpanID string
}

type terminatedKey struct{}

func header(carrier propagation.TextMapCarrier, name string) string {
	return strings.TrimSpace(carrier.Get(name))
}

func parseTid(tags string) string {
	for _, part := range strings.Split(tags, ",") {
		k, v, ok := strings.Cut(part, "=")
		if !ok || strings.TrimSpace(k) != "_dd.p.tid" {
			continue
		}
		v = strings.ToLower(strings.TrimSpace(v))
		if isHex16(v) {
			return v
		}
		return ""
	}
	return ""
}

func parseTraceparent(value string) (trace.SpanContext, bool) {
	m := traceparentRe.FindStringSubmatch(strings.ToLower(value))
	if m == nil || m[1] == "ff" {
		return trace.SpanContext{}, false
	}
	tid, err1 := trace.TraceIDFromHex(m[2])
	sid, err2 := trace.SpanIDFromHex(m[3])
	if err1 != nil || err2 != nil {
		return trace.SpanContext{}, false
	}
	flags, _ := strconv.ParseUint(m[4], 16, 8)
	sc := trace.NewSpanContext(trace.SpanContextConfig{
		TraceID: tid, SpanID: sid, TraceFlags: trace.TraceFlags(flags) & trace.FlagsSampled, Remote: true,
	})
	return sc, sc.IsValid()
}

func isShortDigits(s string) bool { return len(s) <= 20 && isDigits(s) }

// ExtractDatadogContext reads the Datadog headers of a carrier into a remote span
// context; ok is false when they are missing or invalid. A missing sampling
// priority counts as sampled.
func ExtractDatadogContext(carrier propagation.TextMapCarrier) (sc trace.SpanContext, ok bool) {
	traceRaw := header(carrier, DatadogTraceIDHeader)
	parentRaw := header(carrier, DatadogParentIDHeader)
	if !isShortDigits(traceRaw) || !isShortDigits(parentRaw) {
		return trace.SpanContext{}, false
	}
	traceHex, err := FromDatadogTraceID(traceRaw, parseTid(header(carrier, DatadogTagsHeader)))
	if err != nil {
		return trace.SpanContext{}, false
	}
	spanHex, err := FromDatadogSpanID(parentRaw)
	if err != nil {
		return trace.SpanContext{}, false
	}
	tid, _ := trace.TraceIDFromHex(traceHex)
	sid, _ := trace.SpanIDFromHex(spanHex)
	flags := trace.FlagsSampled
	if p := header(carrier, DatadogSamplingPriorityHeader); p != "" {
		if n, err := strconv.Atoi(p); err == nil && n <= 0 {
			flags = 0
		}
	}
	sc = trace.NewSpanContext(trace.SpanContextConfig{TraceID: tid, SpanID: sid, TraceFlags: flags, Remote: true})
	return sc, sc.IsValid()
}

// DatadogPropagator injects and extracts x-datadog-trace-id (low 64 bits,
// decimal), x-datadog-parent-id (decimal), x-datadog-sampling-priority (1/0)
// and, for 128-bit ids, x-datadog-tags: _dd.p.tid=<high 64 bits hex>.
//
// Conflict rules on Extract (ddtrace's behaviour with style order
// datadog,tracecontext): when the carrier also holds a valid traceparent,
//   - same trace id → the W3C parent id wins (the context from traceparent is kept),
//   - different trace id → the Datadog context wins and the W3C context is
//     remembered in the returned context; DatadogSpanLinkProcessor (installed
//     by InitTracing) adds it to the next span as a link with
//     reason=terminated_context (see TerminatedContextLinks).
//
// The rules apply whatever position "datadog" has in the propagator list; put
// "tracecontext" before it (OrderPropagators) so an existing tracestate is kept.
type DatadogPropagator struct{}

var _ propagation.TextMapPropagator = DatadogPropagator{}

// Inject writes the Datadog headers of the span in ctx; no-op without a valid span context.
func (DatadogPropagator) Inject(ctx context.Context, carrier propagation.TextMapCarrier) {
	sc := trace.SpanContextFromContext(ctx)
	if !sc.IsValid() {
		return
	}
	id, _ := ToDatadogTraceID(sc.TraceID().String())
	span, _ := ToDatadogSpanID(sc.SpanID().String())
	carrier.Set(DatadogTraceIDHeader, id.Low64Decimal)
	carrier.Set(DatadogParentIDHeader, span)
	priority := "0"
	if sc.IsSampled() {
		priority = "1"
	}
	carrier.Set(DatadogSamplingPriorityHeader, priority)
	if id.Is128Bit {
		carrier.Set(DatadogTagsHeader, "_dd.p.tid="+id.High64Hex)
	}
}

// Extract reads the Datadog headers (with the conflict rules above) into ctx.
func (DatadogPropagator) Extract(ctx context.Context, carrier propagation.TextMapCarrier) context.Context {
	dd, ok := ExtractDatadogContext(carrier)
	if !ok {
		return ctx
	}
	w3c, ok := parseTraceparent(header(carrier, "traceparent"))
	if !ok {
		return trace.ContextWithRemoteSpanContext(ctx, dd)
	}
	if w3c.TraceID() == dd.TraceID() {
		// Same trace: ddtrace takes the W3C parent id; keep tracestate set by the W3C propagator.
		current := trace.SpanContextFromContext(ctx)
		if current.IsValid() && current.TraceID() == w3c.TraceID() && current.SpanID() == w3c.SpanID() {
			return trace.ContextWithRemoteSpanContext(ctx, current)
		}
		return trace.ContextWithRemoteSpanContext(ctx, w3c.WithTraceFlags(dd.TraceFlags()))
	}
	ctx = trace.ContextWithRemoteSpanContext(ctx, dd)
	return context.WithValue(ctx, terminatedKey{}, TerminatedContext{SpanContext: w3c, WinnerSpanID: dd.SpanID().String()})
}

// Fields returns the header names this propagator reads and writes.
func (DatadogPropagator) Fields() []string {
	return []string{DatadogTraceIDHeader, DatadogParentIDHeader, DatadogSamplingPriorityHeader, DatadogTagsHeader}
}

// TerminatedContextFrom returns the W3C context that lost against Datadog
// headers during Extract, if any.
func TerminatedContextFrom(ctx context.Context) (TerminatedContext, bool) {
	t, ok := ctx.Value(terminatedKey{}).(TerminatedContext)
	return t, ok
}

// TerminatedContextLinks returns the span links for a span started from an
// extracted context: one link to the terminated W3C context
// (reason=terminated_context, context_headers=tracecontext), or none. Pass them
// to WithSpanLinks when you start spans without InitTracing's processor.
func TerminatedContextLinks(ctx context.Context) []trace.Link {
	t, ok := TerminatedContextFrom(ctx)
	if !ok {
		return nil
	}
	return []trace.Link{{
		SpanContext: t.SpanContext,
		Attributes: []attribute.KeyValue{
			attribute.String("reason", "terminated_context"),
			attribute.String("context_headers", "tracecontext"),
		},
	}}
}

// DatadogSpanLinkProcessor is a span processor that adds the
// TerminatedContextLinks link to the first span started from an extracted
// context whose W3C context lost against Datadog headers. InitTracing installs it.
type DatadogSpanLinkProcessor struct{}

var _ sdktrace.SpanProcessor = DatadogSpanLinkProcessor{}

// OnStart adds the terminated-context link when the span's remote parent is the winning Datadog context.
func (DatadogSpanLinkProcessor) OnStart(parent context.Context, s sdktrace.ReadWriteSpan) {
	t, ok := TerminatedContextFrom(parent)
	p := s.Parent()
	if !ok || !p.IsRemote() || p.SpanID().String() != t.WinnerSpanID {
		return
	}
	for _, l := range TerminatedContextLinks(parent) {
		s.AddLink(l)
	}
}

// OnEnd does nothing.
func (DatadogSpanLinkProcessor) OnEnd(sdktrace.ReadOnlySpan) {}

// Shutdown does nothing.
func (DatadogSpanLinkProcessor) Shutdown(context.Context) error { return nil }

// ForceFlush does nothing.
func (DatadogSpanLinkProcessor) ForceFlush(context.Context) error { return nil }
