package datadogapm

import (
	"compress/gzip"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
	collectorpb "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	tracepb "go.opentelemetry.io/proto/otlp/trace/v1"
	"google.golang.org/protobuf/proto"
)

// otlpReceiver is a fake Agent OTLP/HTTP receiver.
type otlpReceiver struct {
	*httptest.Server
	mu       sync.Mutex
	requests []*collectorpb.ExportTraceServiceRequest
	headers  []http.Header
	paths    []string
}

func newOTLPReceiver(t *testing.T) *otlpReceiver {
	t.Helper()
	r := &otlpReceiver{}
	r.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		var body io.Reader = req.Body
		if req.Header.Get("Content-Encoding") == "gzip" {
			gz, err := gzip.NewReader(req.Body)
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			body = gz
		}
		data, _ := io.ReadAll(body)
		var msg collectorpb.ExportTraceServiceRequest
		if err := proto.Unmarshal(data, &msg); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		r.mu.Lock()
		r.requests = append(r.requests, &msg)
		r.headers = append(r.headers, req.Header.Clone())
		r.paths = append(r.paths, req.URL.Path)
		r.mu.Unlock()
		w.Header().Set("Content-Type", "application/x-protobuf")
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(r.Close)
	return r
}

type receivedSpan struct {
	span     *tracepb.Span
	resource map[string]string
}

func strAttrs(kvs []*commonpb.KeyValue) map[string]string {
	out := map[string]string{}
	for _, kv := range kvs {
		out[kv.Key] = kv.Value.GetStringValue()
	}
	return out
}

func (r *otlpReceiver) spans() map[string]receivedSpan {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]receivedSpan{}
	for _, req := range r.requests {
		for _, rs := range req.ResourceSpans {
			res := strAttrs(rs.Resource.Attributes)
			for _, ss := range rs.ScopeSpans {
				for _, s := range ss.Spans {
					out[s.Name] = receivedSpan{span: s, resource: res}
				}
			}
		}
	}
	return out
}

func TestInitTracingExportsToAgent(t *testing.T) {
	recv := newOTLPReceiver(t)
	cfg := Config{OTLPEndpoint: recv.URL, Tags: map[string]string{"team": "sdk"}, ServiceName: "from-config"}
	h, err := InitTracingWithOptions(t.Context(), TracingConfig{
		ServiceName: "checkout", ServiceVersion: "1.2.3", Environment: "prod",
		ResourceAttributes: map[string]any{"grow.run": "r1"}, Sampler: SamplerAlwaysOn, DisableSetGlobal: true,
		Headers: map[string]string{"X-Extra": "1"},
	}, InitTracingOptions{Config: &cfg})
	if err != nil {
		t.Fatal(err)
	}
	if h.Endpoint() != recv.URL+"/v1/traces" {
		t.Fatal(h.Endpoint())
	}
	tracer := h.Tracer("test")
	ctx, root := tracer.Start(t.Context(), "root", trace.WithSpanKind(trace.SpanKindServer))
	_ = Traced(ctx, "fails", func(context.Context) error { return errors.New("boom") }, WithTracer(tracer))
	_, s := tracer.Start(ctx, "status-only")
	s.SetStatus(codes.Error, "explicit failure")
	s.End()
	_, s = tracer.Start(ctx, "already-set")
	s.SetAttributes(attribute.String(AttrErrorType, "Mine"))
	s.RecordError(errors.New("x"))
	s.End()
	_, s = tracer.Start(ctx, "with-stack")
	s.RecordError(errors.New("y"), trace.WithStackTrace(true))
	s.End()
	root.End()
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}
	spans := recv.spans()
	r := spans["root"]
	if r.span == nil || r.resource["service.name"] != "checkout" || r.resource["service.version"] != "1.2.3" ||
		r.resource["deployment.environment.name"] != "prod" || r.resource["deployment.environment"] != "prod" ||
		r.resource["team"] != "sdk" || r.resource["grow.run"] != "r1" {
		t.Fatalf("resource %v", r.resource)
	}
	fail := strAttrs(spans["fails"].span.Attributes)
	if fail[AttrErrorType] != "*errors.errorString" || fail[AttrErrorMessage] != "boom" {
		t.Fatalf("fail attrs %v", fail)
	}
	if got := strAttrs(spans["status-only"].span.Attributes); got[AttrErrorMessage] != "explicit failure" || got[AttrErrorType] != "" {
		t.Fatalf("status-only %v", got)
	}
	if got := strAttrs(spans["already-set"].span.Attributes); got[AttrErrorType] != "Mine" || got[AttrErrorMessage] != "" {
		t.Fatalf("already-set %v", got)
	}
	if got := strAttrs(spans["with-stack"].span.Attributes); got[AttrErrorStack] == "" {
		t.Fatalf("with-stack %v", got)
	}
	recv.mu.Lock()
	if recv.paths[0] != "/v1/traces" || recv.headers[0].Get("X-Extra") != "1" || recv.headers[0].Get("Content-Type") != "application/x-protobuf" {
		t.Fatalf("request %v %v", recv.paths, recv.headers[0])
	}
	recv.mu.Unlock()
	fields := DatadogLogFields(t.Context(), DatadogLogFieldsOptions{})
	if fields[LogFieldService] != "checkout" || fields[LogFieldEnv] != "prod" || fields[LogFieldVersion] != "1.2.3" {
		t.Fatalf("log defaults %v", fields)
	}
	if err := h.Shutdown(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := h.Shutdown(t.Context()); err != nil {
		t.Fatal("shutdown twice")
	}
}

func TestInitTracingConfigSources(t *testing.T) {
	// environment for every unset field
	h, err := InitTracingWithOptions(t.Context(), TracingConfig{DisableSetGlobal: true}, InitTracingOptions{Env: map[string]string{
		"DD_SERVICE": "env-svc", "DD_ENV": "staging", "DD_VERSION": "2.0", "DD_AGENT_HOST": "agent", "DD_TAGS": "a:b",
	}})
	if err != nil {
		t.Fatal(err)
	}
	c := h.Config()
	if c.ServiceName != "env-svc" || c.Environment != "staging" || c.ServiceVersion != "2.0" || h.Endpoint() != "http://agent:4318/v1/traces" ||
		c.ResourceAttributes["a"] != "b" || c.ResourceAttributes["deployment.environment.name"] != "staging" ||
		!slices.Equal(c.Propagators, []string{"tracecontext", "datadog", "baggage"}) || c.Protocol != ProtocolHTTPProtobuf {
		t.Fatalf("env config %s %v", c, c.ResourceAttributes)
	}
	_ = h.Shutdown(t.Context())

	// gRPC derived from the Agent URL; an explicit endpoint; explicit TLS kept
	apm := Config{CommonConfig: CommonConfig{URL: "http://agent:8126", TLS: TLSConfig{InsecureSkipVerify: true}}}
	h, err = InitTracingWithOptions(t.Context(), TracingConfig{ServiceName: "s", Protocol: ProtocolGRPC, DisableSetGlobal: true,
		ResourceAttributes: map[string]any{"deployment.environment.name": "mine"}, Environment: "e"}, InitTracingOptions{Config: &apm})
	if err != nil {
		t.Fatal(err)
	}
	if h.Endpoint() != "http://agent:4317" || !h.Config().TLS.InsecureSkipVerify || h.Config().ResourceAttributes["deployment.environment.name"] != "mine" {
		t.Fatalf("grpc %s", h.Endpoint())
	}
	_ = h.Shutdown(t.Context())
	h, err = InitTracingWithOptions(t.Context(), TracingConfig{ServiceName: "s", Endpoint: "http://collector:4318", DisableSetGlobal: true,
		Propagators: []string{"b3"}, TLS: TLSConfig{CACertPEM: ""}}, InitTracingOptions{IgnoreEnv: true})
	if err != nil {
		t.Fatal(err)
	}
	if h.Endpoint() != "http://collector:4318/v1/traces" || !slices.Equal(h.Config().Propagators, []string{"b3"}) {
		t.Fatalf("explicit %s", h.Endpoint())
	}
	_ = h.Shutdown(t.Context())

	// global setup through InitTracing (process environment)
	t.Setenv("DD_SERVICE", "global-svc")
	h, err = InitTracing(t.Context(), TracingConfig{})
	if err != nil {
		t.Fatal(err)
	}
	if h.Config().ServiceName != "global-svc" {
		t.Fatal(h.Config().ServiceName)
	}
	_ = h.Shutdown(t.Context())
}

func TestInitTracingErrors(t *testing.T) {
	for _, tc := range []struct {
		cfg  TracingConfig
		opts InitTracingOptions
	}{
		{TracingConfig{}, InitTracingOptions{IgnoreEnv: true}},
		{TracingConfig{ServiceName: "s", Protocol: ProtocolNative}, InitTracingOptions{IgnoreEnv: true}},
		{TracingConfig{ServiceName: "s", Protocol: "thrift"}, InitTracingOptions{IgnoreEnv: true}},
		{TracingConfig{ServiceName: "s"}, InitTracingOptions{Env: map[string]string{"DD_TRACE_AGENT_PORT": "x"}}},
		{TracingConfig{ServiceName: "s"}, InitTracingOptions{Config: &Config{Site: "http://bad"}}},
		{TracingConfig{ServiceName: "s", Propagators: []string{"nope"}}, InitTracingOptions{IgnoreEnv: true}},
		{TracingConfig{ServiceName: "s", TLS: TLSConfig{CACertPEM: "bad"}, Endpoint: "https://x:1"}, InitTracingOptions{IgnoreEnv: true}},
	} {
		if _, err := InitTracingWithOptions(t.Context(), tc.cfg, tc.opts); !errors.Is(err, ErrConfig) {
			t.Fatalf("%s: %v", tc.cfg, err)
		}
	}
	if _, err := DatadogExporterFactory(t.Context(), TracingConfig{Protocol: ProtocolNative}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
	for _, p := range []Protocol{"", ProtocolGRPC} {
		exp, err := DatadogExporterFactory(t.Context(), TracingConfig{Protocol: p})
		if err != nil {
			t.Fatal(err)
		}
		_ = exp.Shutdown(t.Context())
	}
}

func TestPropagationThroughGlobal(t *testing.T) {
	prevTP, prevProp := otel.GetTracerProvider(), otel.GetTextMapPropagator()
	t.Cleanup(func() { otel.SetTracerProvider(prevTP); otel.SetTextMapPropagator(prevProp) })
	mem := tracetest.NewInMemoryExporter()
	h, err := InitTracingWithOptions(t.Context(), TracingConfig{ServiceName: "prop", Sampler: SamplerAlwaysOn},
		InitTracingOptions{IgnoreEnv: true, SpanProcessors: []sdktrace.SpanProcessor{sdktrace.NewSimpleSpanProcessor(mem)}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = h.Shutdown(context.Background()) }()

	ctx, parent := h.Tracer("t").Start(t.Context(), "upstream")
	carrier := map[string]string{}
	Inject(ctx, carrier)
	sc := parent.SpanContext()
	dd, _ := ToDatadogTraceID(sc.TraceID().String())
	spanDec, _ := ToDatadogSpanID(sc.SpanID().String())
	if carrier[DatadogTraceIDHeader] != dd.Low64Decimal || carrier[DatadogParentIDHeader] != spanDec ||
		carrier[DatadogSamplingPriorityHeader] != "1" || !strings.Contains(carrier["traceparent"], sc.TraceID().String()) {
		t.Fatalf("carrier %v", carrier)
	}
	parent.End()

	// Datadog-only headers continue the trace
	ddOnly := map[string]string{DatadogTraceIDHeader: carrier[DatadogTraceIDHeader], DatadogParentIDHeader: carrier[DatadogParentIDHeader],
		DatadogTagsHeader: carrier[DatadogTagsHeader]}
	down := Extract(t.Context(), ddOnly)
	if trace.SpanContextFromContext(down).TraceID() != sc.TraceID() {
		t.Fatal("datadog extract")
	}
	// conflicting W3C context: Datadog wins, the W3C context becomes a link on the next span
	hdr := http.Header{}
	for k, v := range ddOnly {
		hdr.Set(k, v)
	}
	hdr.Set("traceparent", "00-11111111111111112222222222222222-0000000000000abc-01")
	conflict := ExtractHeaders(t.Context(), hdr)
	if len(TerminatedContextLinks(conflict)) != 1 {
		t.Fatal("terminated link")
	}
	_, child := h.Tracer("t").Start(conflict, "conflict")
	_, grand := h.Tracer("t").Start(trace.ContextWithSpan(conflict, child), "grandchild")
	grand.End()
	child.End()
	out := http.Header{}
	InjectHeaders(trace.ContextWithSpan(t.Context(), child), out)
	if out.Get(DatadogParentIDHeader) == "" {
		t.Fatal("InjectHeaders")
	}
	var conflictSpan, grandSpan tracetest.SpanStub
	for _, s := range mem.GetSpans() {
		switch s.Name {
		case "conflict":
			conflictSpan = s
		case "grandchild":
			grandSpan = s
		}
	}
	if conflictSpan.Parent.SpanID() != sc.SpanID() || len(conflictSpan.Links) != 1 ||
		conflictSpan.Links[0].SpanContext.TraceID().String() != "11111111111111112222222222222222" || len(grandSpan.Links) != 0 {
		t.Fatalf("links %+v / %+v", conflictSpan.Links, grandSpan.Links)
	}
	proc := DatadogSpanLinkProcessor{}
	proc.OnEnd(nil)
	if proc.Shutdown(t.Context()) != nil || proc.ForceFlush(t.Context()) != nil {
		t.Fatal("no-op processor")
	}
}

func TestOrderPropagators(t *testing.T) {
	for _, tc := range []struct{ in, want []string }{
		{[]string{"datadog", "tracecontext", "baggage"}, []string{"tracecontext", "datadog", "baggage"}},
		{[]string{" TraceContext ", "Datadog"}, []string{"tracecontext", "datadog"}},
		{[]string{"b3", "datadog", "baggage", "tracecontext"}, []string{"b3", "tracecontext", "datadog", "baggage"}},
		{[]string{"datadog"}, []string{"datadog"}},
		{[]string{"tracecontext", "baggage"}, []string{"tracecontext", "baggage"}},
	} {
		if got := OrderPropagators(tc.in); !slices.Equal(got, tc.want) {
			t.Fatalf("%v: %v", tc.in, got)
		}
	}
	if !slices.Equal(DatadogDefaultPropagators, []string{"datadog", "tracecontext", "baggage"}) {
		t.Fatal("defaults")
	}
}

func TestSpanErrorHelpers(t *testing.T) {
	mem := tracetest.NewInMemoryExporter()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSyncer(mem))
	defer func() { _ = tp.Shutdown(context.Background()) }()
	tracer := tp.Tracer("t")

	ctx, s := tracer.Start(t.Context(), "recorded")
	RecordDatadogException(ctx, errors.New("bad type"))
	RecordDatadogException(ctx, nil)
	s.End()
	ctx, s = tracer.Start(t.Context(), "set-error")
	SetDatadogError(ctx, errors.New("explicit failure"))
	SetDatadogError(ctx, nil)
	s.End()
	ctx, s = tracer.Start(t.Context(), "attrs-only")
	SetErrorAttributes(ctx, errors.New("only attrs"))
	SetErrorAttributes(ctx, nil)
	s.End()
	got := map[string]tracetest.SpanStub{}
	for _, st := range mem.GetSpans() {
		got[st.Name] = st
	}
	attr := func(st tracetest.SpanStub, key string) string {
		for _, kv := range st.Attributes {
			if string(kv.Key) == key {
				return kv.Value.AsString()
			}
		}
		return ""
	}
	r := got["recorded"]
	if attr(r, AttrErrorType) != "*errors.errorString" || attr(r, AttrErrorMessage) != "bad type" || !strings.Contains(attr(r, AttrErrorStack), "goroutine") ||
		len(r.Events) != 1 || r.Status.Code != codes.Unset {
		t.Fatalf("recorded %+v", r)
	}
	se := got["set-error"]
	if se.Status.Code != codes.Error || se.Status.Description != "explicit failure" || attr(se, AttrErrorMessage) != "explicit failure" {
		t.Fatalf("set-error %+v", se)
	}
	if ao := got["attrs-only"]; len(ao.Events) != 0 || attr(ao, AttrErrorMessage) != "only attrs" {
		t.Fatalf("attrs-only %+v", ao)
	}
	if len(DatadogErrorAttributes(nil)) != 0 {
		t.Fatal("nil error")
	}
	if a := DatadogErrorAttributes(&customErr{}); a[AttrErrorType] != "*datadogapm.customErr" && a[AttrErrorType] != "github.com/grow-codes-packages/datadog-apm/go.customErr" {
		t.Fatal(a[AttrErrorType])
	}
	if a := DatadogErrorAttributes(valueErr{}); a[AttrErrorType] != "github.com/grow-codes-packages/datadog-apm/go.valueErr" {
		t.Fatal(a[AttrErrorType])
	}

	// WithDatadogErrorAttributes on stubs
	plain := tracetest.SpanStub{Name: "ok"}.Snapshot()
	if _, wrapped := WithDatadogErrorAttributes(plain).(errorSpan); wrapped {
		t.Fatal("no error → unchanged")
	}
	stub := tracetest.SpanStub{Name: "ev", Events: []sdktrace.Event{
		{Name: "exception", Attributes: []attribute.KeyValue{attribute.String("exception.type", "Old")}},
		{Name: "other"},
		{Name: "exception", Attributes: []attribute.KeyValue{
			attribute.String("exception.type", "T"), attribute.String("exception.message", "M"),
			attribute.String("exception.stacktrace", "S"), attribute.Int("exception.code", 3),
		}},
	}}.Snapshot()
	attrs := WithDatadogErrorAttributes(stub).Attributes()
	if len(attrs) != 3 || attrs[0].Value.AsString() != "T" || attrs[1].Value.AsString() != "M" || attrs[2].Value.AsString() != "S" {
		t.Fatalf("attrs %v", attrs)
	}
	errStub := tracetest.SpanStub{Name: "st", Status: sdktrace.Status{Code: codes.Error}}.Snapshot()
	if _, wrapped := WithDatadogErrorAttributes(errStub).(errorSpan); wrapped {
		t.Fatal("error status without description → unchanged")
	}
}

type customErr struct{}

func (*customErr) Error() string { return "custom" }

type valueErr struct{}

func (valueErr) Error() string { return "value" }

func TestDatadogLogFields(t *testing.T) {
	var vectors []struct {
		TraceID string            `json:"trace_id_hex"`
		SpanID  string            `json:"span_id_hex"`
		Want    map[string]string `json:"ddtrace_log_correlation"`
	}
	loadFixture(t, "traceFields.ddtrace_log_correlation.json", &vectors)
	for _, v := range vectors {
		ctx := trace.ContextWithSpanContext(context.Background(), mustSpanContext(t, v.TraceID, v.SpanID, true))
		got := DatadogLogFields(ctx, DatadogLogFieldsOptions{Service: v.Want["dd.service"], Env: v.Want["dd.env"], Version: v.Want["dd.version"]})
		if len(got) != 5 {
			t.Fatalf("got %v", got)
		}
		for k, want := range v.Want {
			if got[k] != want {
				t.Fatalf("%s: %q want %q", k, got[k], want)
			}
		}
	}
	SetDatadogLogDefaults(DatadogLogFieldsOptions{})
	if got := DatadogLogFields(context.Background(), DatadogLogFieldsOptions{}); len(got) != 0 {
		t.Fatalf("empty %v", got)
	}
	// resource values of the active SDK span win over the defaults
	SetDatadogLogDefaults(DatadogLogFieldsOptions{Service: "default", Env: "default", Version: "default"})
	defer SetDatadogLogDefaults(DatadogLogFieldsOptions{})
	mem := tracetest.NewInMemoryExporter()
	h, err := InitTracingWithOptions(t.Context(), TracingConfig{ServiceName: "res-svc", Environment: "res-env", ServiceVersion: "9", DisableSetGlobal: true},
		InitTracingOptions{IgnoreEnv: true, SpanProcessors: []sdktrace.SpanProcessor{sdktrace.NewSimpleSpanProcessor(mem)}})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = h.Shutdown(context.Background()) }()
	SetDatadogLogDefaults(DatadogLogFieldsOptions{Service: "default", Env: "default", Version: "default"})
	ctx, span := h.Tracer("t").Start(t.Context(), "s")
	got := DatadogLogFields(ctx, DatadogLogFieldsOptions{})
	span.End()
	if got[LogFieldService] != "res-svc" || got[LogFieldEnv] != "res-env" || got[LogFieldVersion] != "9" || got[LogFieldTraceID] == "" {
		t.Fatalf("resource %v", got)
	}
}

func TestInitTracingWithOptionsPassesConfigProcessorsAndSampler(t *testing.T) {
	prevTP, prevProp := otel.GetTracerProvider(), otel.GetTextMapPropagator()
	t.Cleanup(func() { otel.SetTracerProvider(prevTP); otel.SetTextMapPropagator(prevProp) })
	fromOpts, fromCfg := tracetest.NewInMemoryExporter(), tracetest.NewInMemoryExporter()
	h, err := InitTracingWithOptions(t.Context(),
		TracingConfig{ServiceName: "pass", Sampler: SamplerAlwaysOn, SpanProcessors: []sdktrace.SpanProcessor{sdktrace.NewSimpleSpanProcessor(fromCfg)}},
		InitTracingOptions{IgnoreEnv: true, SpanProcessors: []sdktrace.SpanProcessor{sdktrace.NewSimpleSpanProcessor(fromOpts)}})
	if err != nil {
		t.Fatal(err)
	}
	_, span := h.Tracer("t").Start(t.Context(), "both")
	span.End()
	// in-memory exporters forget their spans on Shutdown: count before
	if len(fromOpts.GetSpans()) != 1 || len(fromCfg.GetSpans()) != 1 {
		t.Fatalf("opts %d cfg %d", len(fromOpts.GetSpans()), len(fromCfg.GetSpans()))
	}
	_ = h.Shutdown(context.Background())

	never := tracetest.NewInMemoryExporter()
	h2, err := InitTracingWithOptions(t.Context(),
		TracingConfig{ServiceName: "never", Sampler: SamplerAlwaysOn, CustomSampler: sdktrace.NeverSample()},
		InitTracingOptions{IgnoreEnv: true, SpanProcessors: []sdktrace.SpanProcessor{sdktrace.NewSimpleSpanProcessor(never)}})
	if err != nil {
		t.Fatal(err)
	}
	_, span = h2.Tracer("t").Start(t.Context(), "dropped")
	span.End()
	if n := len(never.GetSpans()); n != 0 {
		t.Fatalf("custom sampler ignored: %d spans", n)
	}
	_ = h2.Shutdown(context.Background())
}
