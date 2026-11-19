package integration

// Integration tests of the tracing half against the real Datadog Agent: spans
// are exported over OTLP (HTTP/protobuf and gRPC), converted by the Agent,
// forwarded to fakeintake and read back through the trace-reader sidecar.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"

	datadogapm "github.com/grow-codes-packages/datadog-apm/go"
)

type typeError struct{ msg string }

func (e *typeError) Error() string { return e.msg }

func initIT(t *testing.T, service string) *datadogapm.DatadogTracingHandle {
	t.Helper()
	h, err := datadogapm.InitTracing(t.Context(), datadogapm.TracingConfig{
		ServiceName: service, ServiceVersion: "1.2.3", Environment: "it",
		ResourceAttributes: map[string]any{"grow.run": service}, Sampler: datadogapm.SamplerAlwaysOn,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = h.Shutdown(context.Background())
		_ = h.Shutdown(context.Background())
	})
	return h
}

func itService(kind string) string {
	return fmt.Sprintf("grow-go-it-%s-%s-%06x", kind, runID(), rand.IntN(1<<24))
}

func byResource(spans []ReaderSpan) map[string]ReaderSpan {
	out := map[string]ReaderSpan{}
	for _, s := range spans {
		out[str(s["resource"])] = s
	}
	return out
}

func TestInitTracingHTTPToAgent(t *testing.T) {
	requireService(t)
	service := itService("http")
	h := initIT(t, service)
	if h.Endpoint() != os.Getenv("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT")+"/v1/traces" {
		t.Fatal(h.Endpoint())
	}
	link := trace.NewSpanContext(trace.SpanContextConfig{
		TraceID: mustTraceID(t, "a3ce929d0e0e47364bf92f3577b34da6"), SpanID: mustSpanID(t, "00f067aa0ba902b7"), TraceFlags: trace.FlagsSampled,
	})
	var fields, ids map[string]string
	var curTrace, curSpan string
	traceID, err := datadogapm.WithSpan(t.Context(), "it-root", func(ctx context.Context) (string, error) {
		fields = datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{})
		ids = datadogapm.TraceFields(ctx)
		curTrace, _ = datadogapm.CurrentTraceID(ctx)
		curSpan, _ = datadogapm.CurrentSpanID(ctx)
		datadogapm.AddEvent(ctx, "checkpoint", map[string]any{"step": 1})
		_, _ = datadogapm.WithSpan(ctx, "it-child-ok", func(context.Context) (int, error) { return 0, nil },
			datadogapm.WithSpanAttributes(map[string]any{"n": 7, "flag": true}))
		_ = datadogapm.Traced(ctx, "it-child-fail", func(context.Context) error { return &typeError{"value out of range"} })
		_ = datadogapm.Traced(ctx, "it-recorded", func(ctx context.Context) error {
			datadogapm.RecordDatadogException(ctx, &typeError{"bad type"})
			return nil
		})
		_ = datadogapm.Traced(ctx, "it-set-error", func(ctx context.Context) error {
			datadogapm.SetDatadogError(ctx, errors.New("explicit failure"))
			return nil
		})
		_ = datadogapm.Traced(ctx, "it-generic", func(ctx context.Context) error {
			datadogapm.RecordException(ctx, errors.New("generic"))
			datadogapm.SetError(ctx, errors.New("generic status"))
			return nil
		})
		_ = datadogapm.Traced(ctx, "it-attrs", func(ctx context.Context) error {
			datadogapm.SetErrorAttributes(ctx, &typeError{"attrs only"})
			return nil
		})
		_ = datadogapm.Traced(ctx, "it-linked", func(context.Context) error { return nil }, datadogapm.WithSpanLinks(trace.Link{SpanContext: link}))
		return datadogapm.CurrentSpan(ctx).SpanContext().TraceID().String(), nil
	}, datadogapm.WithSpanKind(trace.SpanKindServer))
	if err != nil {
		t.Fatal(err)
	}
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}
	spans := waitForSpans(t, map[string]string{"trace_id": traceID}, 8)
	res := byResource(spans)
	root := res["it-root"]
	m := meta(root)
	if root == nil || str(root["service"]) != service || m["env"] != "it" || m["version"] != "1.2.3" || m["grow.run"] != service ||
		m["span.kind"] != "server" || str(root["name"]) != "server.request" || str(root["trace_id_hex"]) != traceID {
		t.Fatalf("root %v", root)
	}

	// id helpers and log correlation against the Agent's own ids
	dd, _ := datadogapm.ToDatadogTraceID(traceID)
	if str(root["trace_id"]) != dd.Low64Decimal || m["otel.trace_id"] != traceID {
		t.Fatalf("trace id %v", root["trace_id"])
	}
	if back, _ := datadogapm.FromDatadogTraceID(str(root["trace_id"]), dd.High64Hex); back != traceID {
		t.Fatal(back)
	}
	if hexID, _ := datadogapm.FromDatadogSpanID(str(root["span_id"])); hexID != str(root["span_id_hex"]) {
		t.Fatal(hexID)
	}
	if dec, _ := datadogapm.ToDatadogSpanID(str(root["span_id_hex"])); dec != str(root["span_id"]) {
		t.Fatal(dec)
	}
	wantDDTrace := str(root["trace_id"])
	if dd.Is128Bit {
		wantDDTrace = traceID
	}
	if ddStr, _ := datadogapm.DatadogTraceIDString(traceID); ddStr != wantDDTrace {
		t.Fatal(ddStr)
	}
	if fields["dd.trace_id"] != wantDDTrace || fields["dd.span_id"] != str(root["span_id"]) || fields["dd.service"] != service ||
		fields["dd.env"] != "it" || fields["dd.version"] != "1.2.3" {
		t.Fatalf("log fields %v", fields)
	}
	if ids["trace_id"] != traceID || ids["span_id"] != str(root["span_id_hex"]) || curTrace != traceID || curSpan != str(root["span_id_hex"]) {
		t.Fatalf("trace fields %v %s %s", ids, curTrace, curSpan)
	}

	fail := meta(res["it-child-fail"])
	if str(res["it-child-fail"]["error"]) != "1" || fail[datadogapm.AttrErrorType] != "*integration.typeError" ||
		fail[datadogapm.AttrErrorMessage] != "value out of range" {
		t.Fatalf("fail %v", res["it-child-fail"])
	}
	if rec := meta(res["it-recorded"]); rec[datadogapm.AttrErrorType] != "*integration.typeError" || !strings.Contains(str(rec[datadogapm.AttrErrorStack]), "goroutine") {
		t.Fatalf("recorded %v", rec)
	}
	if se := res["it-set-error"]; str(se["error"]) != "1" || meta(se)["error.msg"] != "explicit failure" {
		t.Fatalf("set-error %v", se)
	}
	if g := meta(res["it-generic"]); g[datadogapm.AttrErrorMessage] != "generic status" || g[datadogapm.AttrErrorType] != "*errors.errorString" {
		t.Fatalf("generic %v", g)
	}
	if a := meta(res["it-attrs"]); a[datadogapm.AttrErrorMessage] != "attrs only" {
		t.Fatalf("attrs %v", a)
	}
	ok := res["it-child-ok"]
	if metrics, _ := ok["metrics"].(map[string]any); str(metrics["n"]) != "7" && str(metrics["n"]) != "7.0" {
		t.Fatalf("metrics %v", ok["metrics"])
	}
	if meta(ok)["flag"] != "true" {
		t.Fatalf("flag %v", meta(ok))
	}

	// normalized model from the Agent's span format
	model := map[string]datadogapm.Span{}
	for _, s := range spans {
		span, err := datadogapm.DatadogTraceSpanToSpan(s)
		if err != nil {
			t.Fatal(err)
		}
		model[span.Name] = span
	}
	mRoot := model["it-root"]
	if mRoot.Kind != datadogapm.SpanKindServer || len(mRoot.Events) != 1 || mRoot.Events[0].Name != "checkpoint" || mRoot.Events[0].Attributes["step"] != int64(1) {
		t.Fatalf("model root %+v", mRoot)
	}
	for name, s := range model {
		if name != "it-root" && s.ParentSpanID != mRoot.SpanID {
			t.Fatalf("%s parent %s", name, s.ParentSpanID)
		}
	}
	linked := model["it-linked"]
	if len(linked.Links) != 1 || linked.Links[0].TraceID != "a3ce929d0e0e47364bf92f3577b34da6" || linked.Links[0].SpanID != "00f067aa0ba902b7" {
		t.Fatalf("links %+v", linked.Links)
	}
	if model["it-child-fail"].Status.Code != datadogapm.SpanStatusError {
		t.Fatal("status")
	}
}

func TestInitTracingGRPCViaClient(t *testing.T) {
	requireService(t)
	service := itService("grpc")
	c, err := datadogapm.NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	h, err := c.InitTracing(t.Context(), datadogapm.TracingConfig{ServiceName: service, Protocol: datadogapm.ProtocolGRPC, DisableSetGlobal: true})
	if err != nil {
		t.Fatal(err)
	}
	if h.Endpoint() != os.Getenv("GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT") {
		t.Fatal(h.Endpoint())
	}
	_, span := h.Tracer("it").Start(t.Context(), "it-grpc-span", trace.WithAttributes(datadogapm.OTelAttribute("via", "grpc")))
	traceID := span.SpanContext().TraceID().String()
	span.End()
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}
	spans := waitForSpans(t, map[string]string{"trace_id": traceID}, 1)
	if str(spans[0]["service"]) != service || str(spans[0]["resource"]) != "it-grpc-span" || meta(spans[0])["via"] != "grpc" {
		t.Fatalf("grpc span %v", spans[0])
	}
	if c.Close() != nil || c.Close() != nil {
		t.Fatal("close")
	}
}

func TestDatadogPropagationThroughAgent(t *testing.T) {
	requireService(t)
	h := initIT(t, itService("prop"))
	ctx, parent := h.Tracer("it").Start(t.Context(), "it-upstream", trace.WithNewRoot())
	headers := map[string]string{}
	datadogapm.Inject(ctx, headers)
	ddHeaders := http.Header{}
	datadogapm.DatadogPropagator{}.Inject(ctx, propagation.HeaderCarrier(ddHeaders))
	sc := parent.SpanContext()
	dd, _ := datadogapm.ToDatadogTraceID(sc.TraceID().String())
	spanDec, _ := datadogapm.ToDatadogSpanID(sc.SpanID().String())
	wantTags := ""
	if dd.Is128Bit {
		wantTags = "_dd.p.tid=" + dd.High64Hex
	}
	if headers["x-datadog-trace-id"] != dd.Low64Decimal || headers["x-datadog-parent-id"] != spanDec ||
		headers["x-datadog-sampling-priority"] != "1" || headers["x-datadog-tags"] != wantTags ||
		!strings.Contains(headers["traceparent"], sc.TraceID().String()) {
		t.Fatalf("headers %v", headers)
	}
	if ddHeaders.Get("x-datadog-trace-id") != dd.Low64Decimal || ddHeaders.Get("traceparent") != "" {
		t.Fatalf("dd headers %v", ddHeaders)
	}

	// downstream: only Datadog headers (what a ddtrace service sends)
	ddOnly := map[string]string{
		"x-datadog-trace-id": headers["x-datadog-trace-id"], "x-datadog-parent-id": headers["x-datadog-parent-id"],
		"x-datadog-sampling-priority": "1",
	}
	if wantTags != "" {
		ddOnly["x-datadog-tags"] = wantTags
	}
	if got, ok := datadogapm.ExtractDatadogContext(propagation.MapCarrier(ddOnly)); !ok || got.TraceID() != sc.TraceID() {
		t.Fatal("ExtractDatadogContext")
	}
	downstream := datadogapm.Extract(t.Context(), ddOnly)
	if trace.SpanContextFromContext(downstream).TraceID() != sc.TraceID() {
		t.Fatal("extract")
	}
	_ = datadogapm.Traced(downstream, "it-downstream", func(context.Context) error { return nil }, datadogapm.WithSpanKind(trace.SpanKindServer))

	// conflicting W3C context: Datadog wins, W3C becomes a span link
	conflictHeaders := http.Header{}
	for k, v := range ddOnly {
		conflictHeaders.Set(k, v)
	}
	conflictHeaders.Set("traceparent", "00-11111111111111112222222222222222-0000000000000abc-01")
	conflicting := datadogapm.ExtractHeaders(t.Context(), conflictHeaders)
	if len(datadogapm.TerminatedContextLinks(conflicting)) != 1 {
		t.Fatal("terminated link")
	}
	if tc, ok := datadogapm.TerminatedContextFrom(conflicting); !ok || tc.WinnerSpanID != sc.SpanID().String() {
		t.Fatal("TerminatedContextFrom")
	}
	_ = datadogapm.Traced(conflicting, "it-conflict", func(context.Context) error { return nil })
	out := http.Header{}
	datadogapm.InjectHeaders(conflicting, out)
	if out.Get("x-datadog-parent-id") != spanDec {
		t.Fatalf("InjectHeaders %v", out)
	}
	parent.End()
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}

	spans := waitForSpans(t, map[string]string{"trace_id": sc.TraceID().String()}, 3)
	res := byResource(spans)
	up, down, conflict := res["it-upstream"], res["it-downstream"], res["it-conflict"]
	if str(down["parent_id"]) != str(up["span_id"]) || str(down["trace_id"]) != str(up["trace_id"]) || str(conflict["parent_id"]) != str(up["span_id"]) {
		t.Fatalf("parents up=%v down=%v conflict=%v", up["span_id"], down["parent_id"], conflict["parent_id"])
	}
	var links []struct {
		TraceID    string            `json:"trace_id"`
		SpanID     string            `json:"span_id"`
		Attributes map[string]string `json:"attributes"`
	}
	if err := json.Unmarshal([]byte(str(meta(conflict)["_dd.span_links"])), &links); err != nil {
		t.Fatal(err)
	}
	if len(links) != 1 || links[0].TraceID != "11111111111111112222222222222222" || links[0].SpanID != "0000000000000abc" ||
		links[0].Attributes["reason"] != "terminated_context" {
		t.Fatalf("links %+v", links)
	}
}

func TestHTTPAdaptersThroughAgent(t *testing.T) {
	requireService(t)
	service := itService("adapters")
	h := initIT(t, service)
	server := httptest.NewServer(datadogapm.HTTPMiddleware("it-server")(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})))
	defer server.Close()
	client := &http.Client{Transport: datadogapm.HTTPTransport(nil)}
	traceID, err := datadogapm.WithSpan(t.Context(), "it-fetch-parent", func(ctx context.Context) (string, error) {
		for _, target := range []string{os.Getenv("GROW_DATADOG_APM_URL") + "/info", server.URL + "/hello"} {
			req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
			if err != nil {
				return "", err
			}
			res, err := client.Do(req)
			if err != nil {
				return "", err
			}
			_ = res.Body.Close()
			if res.StatusCode >= 300 {
				return "", fmt.Errorf("%s: HTTP %d", target, res.StatusCode)
			}
		}
		id, _ := datadogapm.CurrentTraceID(ctx)
		return id, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}
	spans := waitForSpans(t, map[string]string{"trace_id": traceID}, 4)
	var clients, servers int
	for _, s := range spans {
		switch meta(s)["span.kind"] {
		case "client":
			clients++
			if m := meta(s); m["http.method"] != "GET" && m["http.request.method"] != "GET" {
				t.Fatalf("client span %v", m)
			}
		case "server":
			servers++
		}
	}
	if clients != 2 || servers != 1 {
		t.Fatalf("clients=%d servers=%d %v", clients, servers, spans)
	}
	all, err := readerSpans(t.Context(), map[string]string{"trace_id": traceID, "service": service})
	if err != nil || len(all) != len(spans) {
		t.Fatalf("service filter %d/%d %v", len(all), len(spans), err)
	}
}

func mustTraceID(t *testing.T, s string) trace.TraceID {
	t.Helper()
	id, err := trace.TraceIDFromHex(s)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func mustSpanID(t *testing.T, s string) trace.SpanID {
	t.Helper()
	id, err := trace.SpanIDFromHex(s)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
