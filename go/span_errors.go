package datadogapm

// Datadog error attributes (DECISIONS.md #2). The Datadog Agent keeps an OTLP
// exception only inside the events JSON and does not fill error.type /
// error.stack, so this package sets error.type, error.message and error.stack
// span attributes itself, which Datadog Error Tracking reads.

import (
	"context"
	"fmt"
	"reflect"
	"runtime/debug"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// Datadog error span attributes.
const (
	// AttrErrorType is the error.type span attribute (error type name).
	AttrErrorType = "error.type"
	// AttrErrorMessage is the error.message span attribute.
	AttrErrorMessage = "error.message"
	// AttrErrorStack is the error.stack span attribute.
	AttrErrorStack = "error.stack"
)

// errorTypeName names an error type the way the OpenTelemetry SDK names
// exception.type ("*errors.errorString", "io/fs.PathError").
func errorTypeName(err error) string {
	t := reflect.TypeOf(err)
	if t.PkgPath() == "" && t.Name() == "" {
		return t.String()
	}
	return fmt.Sprintf("%s.%s", t.PkgPath(), t.Name())
}

// DatadogErrorAttributes returns the Datadog error attributes for err:
// error.type (type name), error.message (err.Error()) and error.stack (the
// stack of the calling goroutine; Go errors carry no stack). A nil error gives
// an empty map.
func DatadogErrorAttributes(err error) map[string]string {
	if err == nil {
		return map[string]string{}
	}
	return map[string]string{
		AttrErrorType:    errorTypeName(err),
		AttrErrorMessage: err.Error(),
		AttrErrorStack:   string(debug.Stack()),
	}
}

func setSpanErrorAttributes(span trace.Span, err error) {
	if err == nil {
		return
	}
	attrs := DatadogErrorAttributes(err)
	span.SetAttributes(
		attribute.String(AttrErrorType, attrs[AttrErrorType]),
		attribute.String(AttrErrorMessage, attrs[AttrErrorMessage]),
		attribute.String(AttrErrorStack, attrs[AttrErrorStack]),
	)
}

// SetErrorAttributes sets the Datadog error attributes (error.type,
// error.message, error.stack) for err on the span in ctx. No-op without a
// recording span or for a nil error.
func SetErrorAttributes(ctx context.Context, err error) {
	setSpanErrorAttributes(CurrentSpan(ctx), err)
}

// RecordDatadogException records err as an "exception" event on the span in ctx
// (like RecordException) and also sets the Datadog error attributes. The span
// status is unchanged. A nil error is ignored.
//
// RecordException and SetError (the generic helpers) also get the Datadog
// attributes when the spans are exported through InitTracing: its exporter
// fills them from the exception event.
func RecordDatadogException(ctx context.Context, err error) {
	if err == nil {
		return
	}
	RecordException(ctx, err)
	SetErrorAttributes(ctx, err)
}

// SetDatadogError sets ERROR status (with the error message) on the span in ctx,
// records the exception event (like SetError) and sets the Datadog error
// attributes. A nil error is ignored.
func SetDatadogError(ctx context.Context, err error) {
	if err == nil {
		return
	}
	SetError(ctx, err)
	SetErrorAttributes(ctx, err)
}

// DatadogErrorExporter wraps a span exporter and fills the Datadog error
// attributes of every exported span that has none yet: from its last
// "exception" event (exception.type, exception.message, exception.stacktrace)
// or, for an ERROR status without an event, error.message from the status
// description. It covers WithSpan, Traced, the HTTP adapters and plain
// OpenTelemetry RecordError calls. InitTracing installs it (the Go SDK has no
// span processor hook that may still change an ending span).
type DatadogErrorExporter struct {
	next sdktrace.SpanExporter
}

var _ sdktrace.SpanExporter = (*DatadogErrorExporter)(nil)

// NewDatadogErrorExporter wraps next.
func NewDatadogErrorExporter(next sdktrace.SpanExporter) *DatadogErrorExporter {
	return &DatadogErrorExporter{next: next}
}

// ExportSpans adds the Datadog error attributes and exports the spans with the wrapped exporter.
func (e *DatadogErrorExporter) ExportSpans(ctx context.Context, spans []sdktrace.ReadOnlySpan) error {
	out := make([]sdktrace.ReadOnlySpan, len(spans))
	for i, s := range spans {
		out[i] = WithDatadogErrorAttributes(s)
	}
	return e.next.ExportSpans(ctx, out)
}

// Shutdown shuts the wrapped exporter down.
func (e *DatadogErrorExporter) Shutdown(ctx context.Context) error { return e.next.Shutdown(ctx) }

// errorSpan is a ReadOnlySpan with extra attributes.
type errorSpan struct {
	sdktrace.ReadOnlySpan
	attrs []attribute.KeyValue
}

// Attributes returns the span's attributes plus the Datadog error attributes.
func (s errorSpan) Attributes() []attribute.KeyValue { return s.attrs }

// WithDatadogErrorAttributes returns s with the Datadog error attributes derived
// from its exception event or ERROR status (see DatadogErrorExporter), or s
// itself when it already has them or has no error.
func WithDatadogErrorAttributes(s sdktrace.ReadOnlySpan) sdktrace.ReadOnlySpan {
	attrs := s.Attributes()
	for _, kv := range attrs {
		if kv.Key == AttrErrorType || kv.Key == AttrErrorMessage {
			return s
		}
	}
	add := map[attribute.Key]string{}
	events := s.Events()
	for i := len(events) - 1; i >= 0; i-- {
		if events[i].Name != "exception" {
			continue
		}
		for _, kv := range events[i].Attributes {
			if kv.Value.Type() != attribute.STRING {
				continue
			}
			switch kv.Key {
			case "exception.type":
				add[AttrErrorType] = kv.Value.AsString()
			case "exception.message":
				add[AttrErrorMessage] = kv.Value.AsString()
			case "exception.stacktrace":
				add[AttrErrorStack] = kv.Value.AsString()
			}
		}
		break
	}
	if st := s.Status(); st.Code == codes.Error && add[AttrErrorMessage] == "" && st.Description != "" {
		add[AttrErrorMessage] = st.Description
	}
	if len(add) == 0 {
		return s
	}
	out := append(make([]attribute.KeyValue, 0, len(attrs)+len(add)), attrs...)
	for _, k := range []attribute.Key{AttrErrorType, AttrErrorMessage, AttrErrorStack} {
		if v, ok := add[k]; ok {
			out = append(out, attribute.String(string(k), v))
		}
	}
	return errorSpan{ReadOnlySpan: s, attrs: out}
}
