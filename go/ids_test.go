package datadogapm

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

// loadFixture decodes testdata/<name> (numbers kept as json.Number).
func loadFixture(t *testing.T, name string, out any) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	if err := DecodeJSONPreservingNumbers(data, out); err != nil {
		t.Fatal(err)
	}
}

func mustSpanContext(t *testing.T, traceHex, spanHex string, sampled bool) trace.SpanContext {
	t.Helper()
	tid, err := trace.TraceIDFromHex(traceHex)
	if err != nil {
		t.Fatal(err)
	}
	sid, err := trace.SpanIDFromHex(spanHex)
	if err != nil {
		t.Fatal(err)
	}
	var flags trace.TraceFlags
	if sampled {
		flags = trace.FlagsSampled
	}
	return trace.NewSpanContext(trace.SpanContextConfig{TraceID: tid, SpanID: sid, TraceFlags: flags})
}

func TestTraceIDVectors(t *testing.T) {
	var vectors []struct {
		Hex  string  `json:"otel_trace_id_hex"`
		Low  string  `json:"datadog_trace_id_low64_decimal"`
		High string  `json:"datadog_trace_id_high64_hex"`
		Tags *string `json:"x-datadog-tags"`
	}
	loadFixture(t, "toDatadogTraceId.vectors.json", &vectors)
	if len(vectors) < 5 {
		t.Fatalf("vectors: %d", len(vectors))
	}
	for _, v := range vectors {
		id, err := ToDatadogTraceID(v.Hex)
		if err != nil || id.Low64Decimal != v.Low || id.High64Hex != v.High || id.Is128Bit != (v.Tags != nil) {
			t.Fatalf("%s: %+v %v", v.Hex, id, err)
		}
		back, err := FromDatadogTraceID(v.Low, v.High)
		if err != nil || back != v.Hex {
			t.Fatalf("from %s: %s %v", v.Low, back, err)
		}
	}
}

func TestIDHelpers(t *testing.T) {
	id, err := ToDatadogTraceID("2A")
	if err != nil || id.Low64Decimal != "42" || id.Is128Bit {
		t.Fatalf("short: %+v %v", id, err)
	}
	if s, _ := FromDatadogTraceID("42", ""); s != "0000000000000000000000000000002a" {
		t.Fatal(s)
	}
	if s, _ := FromDatadogTraceID(" 1 ", "FFFFFFFFFFFFFFFF"); s != "ffffffffffffffff0000000000000001" {
		t.Fatal(s)
	}
	if s, _ := DatadogTraceIDString("2a"); s != "42" {
		t.Fatal(s)
	}
	if s, _ := DatadogTraceIDString("A3CE929D0E0E47364BF92F3577B34DA6"); s != "a3ce929d0e0e47364bf92f3577b34da6" {
		t.Fatal(s)
	}
	for _, bad := range []func() error{
		func() error { _, err := ToDatadogTraceID(""); return err },
		func() error { _, err := ToDatadogTraceID("xyz"); return err },
		func() error { _, err := ToDatadogTraceID("0000"); return err },
		func() error { _, err := ToDatadogTraceID("123456789012345678901234567890123"); return err },
		func() error { _, err := FromDatadogTraceID("-1", ""); return err },
		func() error { _, err := FromDatadogTraceID("18446744073709551616", ""); return err },
		func() error { _, err := FromDatadogTraceID("1", "abc"); return err },
		func() error { _, err := FromDatadogTraceID("0", ""); return err },
		func() error { _, err := ToDatadogSpanID("g"); return err },
		func() error { _, err := ToDatadogSpanID("12345678901234567"); return err },
		func() error { _, err := FromDatadogSpanID("x"); return err },
		func() error { _, err := DatadogTraceIDString("zz"); return err },
		func() error { _, err := NormalizeDatadogTraceID("not-an-id"); return err },
		func() error { _, err := NormalizeDatadogSpanID("abc"); return err },
		func() error { _, err := TraceIDForPath(""); return err },
	} {
		if err := bad(); !errors.Is(err, ErrValidation) {
			t.Fatalf("want VALIDATION, got %v", err)
		}
	}
	if s, _ := ToDatadogSpanID("00000000000000FF"); s != "255" {
		t.Fatal(s)
	}
	if s, _ := FromDatadogSpanID("255"); s != "00000000000000ff" {
		t.Fatal(s)
	}
	if s, _ := FromDatadogSpanID("18446744073709551615"); s != "ffffffffffffffff" {
		t.Fatal(s)
	}
	for in, want := range map[string]string{
		"0123456789ABCDEFFFFFFFFFFFFFFFFF": "0123456789abcdefffffffffffffffff",
		"42":                               "0000000000000000000000000000002a",
		"abc":                              "00000000000000000000000000000abc",
		"18446744073709551615":             "0000000000000000ffffffffffffffff",
	} {
		if got, err := NormalizeDatadogTraceID(in); err != nil || got != want {
			t.Fatalf("normalize %s: %s %v", in, got, err)
		}
	}
	for in, want := range map[string]string{"": "", "0": "", "000": "", "255": "00000000000000ff"} {
		if got, err := NormalizeDatadogSpanID(in); err != nil || got != want {
			t.Fatalf("span %q: %q %v", in, got, err)
		}
	}
	if p, _ := TraceIDForPath("0000000000000000abc1230000000000"); p != "0000000000000000abc1230000000000" {
		t.Fatal(p)
	}
	if p, _ := TraceIDForPath("00000000000000000020000000000001"); p != "9007199254740993" {
		t.Fatal(p)
	}
	if p, _ := TraceIDForPath("10000000000000000000000000000000"); p != "21267647932558653966460912964485513216" {
		t.Fatal(p)
	}
}

func TestSites(t *testing.T) {
	if s, err := ValidateSite(" DatadogHQ.EU "); err != nil || s != "datadoghq.eu" {
		t.Fatal(s, err)
	}
	for _, bad := range []string{"https://app.datadoghq.com", "datadoghq.com/path", "datadoghq.com:443", "localhost", ""} {
		if _, err := ValidateSite(bad); !errors.Is(err, ErrConfig) {
			t.Fatalf("%q: %v", bad, err)
		}
	}
	if !IsDatadogSite("US3.datadoghq.com") || IsDatadogSite("custom.example.org") {
		t.Fatal("IsDatadogSite")
	}
	if u, _ := APIURLForSite(""); u != "https://api.datadoghq.com" {
		t.Fatal(u)
	}
	if u, _ := APIURLForSite("datadoghq.eu"); u != "https://api.datadoghq.eu" {
		t.Fatal(u)
	}
	if _, err := APIURLForSite("http://x"); err == nil {
		t.Fatal("bad site")
	}
	if len(DatadogSites) != 9 {
		t.Fatal("sites")
	}
}

func TestDatadogPropagatorVectors(t *testing.T) {
	var inject []struct {
		Context struct {
			TraceID  string `json:"trace_id_hex"`
			SpanID   string `json:"span_id_hex"`
			Priority int    `json:"sampling_priority"`
		} `json:"context"`
		Reference map[string]string `json:"reference_headers"`
	}
	loadFixture(t, "propagation.datadog_inject.vectors.json", &inject)
	p := DatadogPropagator{}
	for _, v := range inject {
		sc := mustSpanContext(t, v.Context.TraceID, v.Context.SpanID, v.Context.Priority > 0)
		carrier := propagation.MapCarrier{}
		p.Inject(trace.ContextWithSpanContext(context.Background(), sc), carrier)
		if len(carrier) != len(v.Reference) {
			t.Fatalf("%s: %v want %v", v.Context.TraceID, carrier, v.Reference)
		}
		for k, want := range v.Reference {
			if carrier[k] != want {
				t.Fatalf("%s %s: %q want %q", v.Context.TraceID, k, carrier[k], want)
			}
		}
	}

	var extract []struct {
		Headers map[string]string `json:"headers"`
		Context struct {
			TraceID  string `json:"trace_id_hex"`
			SpanID   string `json:"span_id_hex"`
			Priority *int   `json:"sampling_priority"`
		} `json:"ddtrace_context"`
		Links []string `json:"span_links"`
	}
	loadFixture(t, "propagation.datadog_extract.vectors.json", &extract)
	for _, v := range extract {
		// tracecontext first (as OrderPropagators arranges), then datadog
		composite := propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, p)
		ctx := composite.Extract(context.Background(), propagation.MapCarrier(v.Headers))
		sc := trace.SpanContextFromContext(ctx)
		if sc.TraceID().String() != v.Context.TraceID || sc.SpanID().String() != v.Context.SpanID || !sc.IsRemote() {
			t.Fatalf("extract %v: %s/%s", v.Headers, sc.TraceID(), sc.SpanID())
		}
		if v.Context.Priority != nil && sc.IsSampled() != (*v.Context.Priority > 0) {
			t.Fatalf("priority %v", v.Headers)
		}
		links := TerminatedContextLinks(ctx)
		if len(links) != len(v.Links) {
			t.Fatalf("links %v: %v", v.Headers, links)
		}
		if len(links) == 1 && (links[0].SpanContext.TraceID().String() != "11111111111111112222222222222222" ||
			links[0].SpanContext.SpanID().String() != "0000000000000abc" || links[0].Attributes[0].Value.AsString() != "terminated_context") {
			t.Fatalf("link %+v", links[0])
		}
	}
}

func TestDatadogPropagatorEdges(t *testing.T) {
	p := DatadogPropagator{}
	bg := context.Background()
	for _, h := range []map[string]string{
		{},
		{DatadogTraceIDHeader: "1"},
		{DatadogTraceIDHeader: "abc", DatadogParentIDHeader: "1"},
		{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "x"},
		{DatadogTraceIDHeader: "0", DatadogParentIDHeader: "1"},
		{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "0"},
		{DatadogTraceIDHeader: "99999999999999999999", DatadogParentIDHeader: "1"},
		{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "99999999999999999999"},
	} {
		if _, ok := ExtractDatadogContext(propagation.MapCarrier(h)); ok {
			t.Fatalf("should be invalid: %v", h)
		}
		if ctx := p.Extract(bg, propagation.MapCarrier(h)); trace.SpanContextFromContext(ctx).IsValid() {
			t.Fatalf("extract %v", h)
		}
	}
	// lenient tags / priority parsing and http.Header carriers
	hdr := http.Header{}
	hdr.Set(DatadogTraceIDHeader, " 1 ")
	hdr.Set(DatadogParentIDHeader, "2")
	hdr.Set(DatadogSamplingPriorityHeader, "-1")
	hdr.Set(DatadogTagsHeader, "_dd.p.dm=-4, bad,=x, _dd.p.tid=FFFFFFFFFFFFFFFF")
	sc, ok := ExtractDatadogContext(propagation.HeaderCarrier(hdr))
	if !ok || sc.TraceID().String() != "ffffffffffffffff0000000000000001" || sc.IsSampled() {
		t.Fatalf("lenient: %v %v", sc, ok)
	}
	hdr.Set(DatadogTagsHeader, "_dd.p.tid=zz,_dd.p.tid=ffffffffffffffff")
	hdr.Set(DatadogSamplingPriorityHeader, "2")
	sc, _ = ExtractDatadogContext(propagation.HeaderCarrier(hdr))
	if sc.TraceID().String() != "00000000000000000000000000000001" || !sc.IsSampled() {
		t.Fatalf("invalid tid ignored: %v", sc)
	}
	// invalid traceparent: Datadog only, no link
	ctx := p.Extract(bg, propagation.MapCarrier{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "2", "traceparent": "ff-bad"})
	if len(TerminatedContextLinks(ctx)) != 0 || !trace.SpanContextFromContext(ctx).IsValid() {
		t.Fatal("invalid traceparent")
	}
	ctx = p.Extract(bg, propagation.MapCarrier{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "2",
		"traceparent": "ff-00000000000000000000000000000001-0000000000000003-01"})
	if trace.SpanContextFromContext(ctx).SpanID().String() != "0000000000000002" {
		t.Fatal("version ff must be ignored")
	}
	// same trace id without a prior W3C extract: W3C parent with Datadog flags
	ctx = p.Extract(bg, propagation.MapCarrier{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "2", DatadogSamplingPriorityHeader: "0",
		"traceparent": "00-00000000000000000000000000000001-0000000000000003-01"})
	if got := trace.SpanContextFromContext(ctx); got.SpanID().String() != "0000000000000003" || got.IsSampled() {
		t.Fatalf("same trace: %v", got)
	}
	// an existing W3C context (with tracestate) is kept
	ts, _ := trace.ParseTraceState("vendor=x")
	w3c := mustSpanContext(t, "00000000000000000000000000000001", "0000000000000003", true).WithTraceState(ts).WithRemote(true)
	ctx = p.Extract(trace.ContextWithRemoteSpanContext(bg, w3c), propagation.MapCarrier{DatadogTraceIDHeader: "1", DatadogParentIDHeader: "2",
		"traceparent": "00-00000000000000000000000000000001-0000000000000003-01"})
	if got := trace.SpanContextFromContext(ctx); got.TraceState().Get("vendor") != "x" {
		t.Fatalf("kept: %v", got)
	}
	// inject without a span is a no-op
	carrier := propagation.MapCarrier{}
	p.Inject(bg, carrier)
	if len(carrier) != 0 {
		t.Fatal("no-op inject")
	}
	if !slices.Equal(p.Fields(), []string{DatadogTraceIDHeader, DatadogParentIDHeader, DatadogSamplingPriorityHeader, DatadogTagsHeader}) {
		t.Fatal("fields")
	}
	if _, ok := TerminatedContextFrom(bg); ok || TerminatedContextLinks(bg) != nil {
		t.Fatal("no terminated context")
	}
}
