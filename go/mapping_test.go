package datadogapm

import (
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"
)

func mapAll(t *testing.T, raws []map[string]any) []Span {
	t.Helper()
	out := make([]Span, len(raws))
	for i, r := range raws {
		s, err := DatadogTraceSpanToSpan(r)
		if err != nil {
			t.Fatal(err)
		}
		out[i] = s
	}
	return out
}

func TestDatadogTraceSpanToSpanAgentOutput(t *testing.T) {
	var raws []map[string]any
	loadFixture(t, "initTracing.otlp_http_json.agent_spans.json", &raws)
	spans := mapAll(t, raws)
	byName := map[string]Span{}
	var names []string
	for _, s := range spans {
		byName[s.Name] = s
		names = append(names, s.Name)
	}
	if strings.Join(names, "|") != "GET /items/{id}|POST|compute|orders publish|orders process" {
		t.Fatal(names)
	}
	root := byName["GET /items/{id}"]
	if root.TraceID != "0123456789abcdefffffffffffffffff" || root.SpanID != "00000000000000ff" || root.Kind != SpanKindServer ||
		root.StartMicros != 1795035814558726 || root.DurationMicros != 9000 || root.EndMicros != 1795035814567726 ||
		root.Status.Code != SpanStatusUnset || root.ParentSpanID != "" {
		t.Fatalf("root %+v", root)
	}
	if root.Attributes["operation_name"] != "http.server.request" || root.Attributes["resource_name"] != "GET /items/{id}" ||
		root.Attributes["span.type"] != "web" || root.Attributes["grow.ratio"] != 0.5 {
		t.Fatalf("root attrs %v", root.Attributes)
	}
	post := byName["POST"]
	if post.Kind != SpanKindClient || post.ParentSpanID != "00000000000000ff" || post.Status != (SpanStatus{Code: SpanStatusError, Message: "boom"}) {
		t.Fatalf("post %+v", post)
	}
	if byName["orders process"].Status.Code != SpanStatusOK || byName["orders publish"].Kind != SpanKindProducer {
		t.Fatal("status / kind")
	}
	tree := BuildTree(spans)
	if len(tree) != 1 || len(tree[0].Children) != 3 {
		t.Fatalf("tree %d", len(tree))
	}
	compute := byName["compute"]
	if len(compute.Events) != 1 || compute.Events[0].Name != "cache.miss" || compute.Events[0].TimeMicros == 0 ||
		!reflect.DeepEqual(compute.Events[0].Attributes, map[string]any{"key": "k1", "n": int64(3)}) {
		t.Fatalf("events %+v", compute.Events)
	}
	if len(compute.Links) != 1 || compute.Links[0].TraceID != "a3ce929d0e0e47364bf92f3577b34da6" ||
		compute.Links[0].SpanID != "00f067aa0ba902b7" || compute.Links[0].Attributes["link.kind"] != "follows" {
		t.Fatalf("links %+v", compute.Links)
	}
	if _, ok := compute.Attributes["events"]; ok {
		t.Fatal("events must not be an attribute")
	}

	var reader struct {
		Spans []map[string]any `json:"spans"`
	}
	loadFixture(t, "traceReader.spans.ok.json", &reader)
	probe := mapAll(t, reader.Spans)[0]
	if probe.TraceID != "69a2af8e68444220f1bf29ab7bd501bf" || probe.SpanID != "4445fd1b19708db1" || probe.Name != "bootstrap.probe" ||
		probe.ServiceName != "grow-bootstrap-probe" || probe.Kind != SpanKindInternal || probe.Attributes["_top_level"] != 1.0 {
		t.Fatalf("probe %+v", probe)
	}
	var sdk []map[string]any
	loadFixture(t, "initTracing.otel_sdk_http.agent_spans.json", &sdk)
	sdkSpans := mapAll(t, sdk)
	if sdkSpans[0].Attributes["n"] != 7.0 || sdkSpans[1].Events[0].Name != "rows" {
		t.Fatalf("sdk %+v", sdkSpans[0].Attributes)
	}
}

func TestDatadogTraceSpanToSpanByIDAPI(t *testing.T) {
	var doc struct {
		Body struct {
			Data struct {
				Attributes struct {
					Spans []map[string]any `json:"spans"`
				} `json:"attributes"`
			} `json:"data"`
		} `json:"body"`
	}
	loadFixture(t, "getTraceById.docs_example.json", &doc)
	s, err := DatadogTraceSpanToSpan(doc.Body.Data.Attributes.Spans[0])
	if err != nil {
		t.Fatal(err)
	}
	if s.TraceID != "0000000000000000abc1230000000000" || s.SpanID != "891087b8e3b70cb1" || s.Name != "GET /products" ||
		s.ServiceName != "web-store" || s.StartMicros != 1716800000000000 || s.EndMicros != 1716800000500000 ||
		s.DurationMicros != 500000 || s.Kind != SpanKindUnspecified || s.Attributes["http.method"] != "GET" ||
		s.Attributes["http.status_code"] != int64(200) {
		t.Fatalf("%+v", s)
	}
}

func TestDatadogTraceSpanToSpanFallbacks(t *testing.T) {
	raw := `{
	  "traceID": "1", "spanID": 2, "parentID": "3", "startTime": "1000000", "endTime": "4000000",
	  "name": "op", "service": "s", "error": 1,
	  "meta": {"_dd.p.tid": "ffffffffffffffff", "otel.status_description": "desc", "span.kind": "SERVER", "num": 5, "events": "not json"},
	  "metrics": {"m": "2.5", "bad": "x", "empty": ""},
	  "span_events": [
	    {"time_unix_nano": 5000, "name": "native", "attributes": {
	      "s": {"type": 0, "string_value": "v"}, "i": {"type": 2, "int_value": 3},
	      "a": {"type": 4, "array_value": {"values": [{"type": 0, "string_value": "x"}]}},
	      "e": {"type": 4, "array_value": {}}, "raw": "plain"}},
	    {"name": "no-time"}
	  ],
	  "span_links": [
	    {"trace_id": "1", "trace_id_high": "ffffffffffffffff", "span_id": "2", "attributes": {}},
	    {"trace_id": "1", "trace_id_high": "255", "span_id": "2"},
	    {"trace_id": "00f067aa0ba902b7", "span_id": "00f067aa0ba902b7"},
	    {"trace_id": "zz-bad", "span_id": "1"},
	    {"trace_id": "1", "trace_id_high": "not-a-number", "span_id": "1"},
	    {"trace_id": "1", "span_id": "x-bad"},
	    {"trace_id": "1", "span_id": "0"},
	    {"span_id": "1"},
	    {"trace_id": "1"}
	  ]
	}`
	var m map[string]any
	if err := DecodeJSONPreservingNumbers([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	s, err := DatadogTraceSpanToSpan(m)
	if err != nil {
		t.Fatal(err)
	}
	if s.TraceID != "ffffffffffffffff0000000000000001" || s.SpanID != "0000000000000002" || s.ParentSpanID != "0000000000000003" ||
		s.StartMicros != 1000 || s.EndMicros != 4000 || s.DurationMicros != 3000 || s.Name != "op" || s.Kind != SpanKindServer ||
		s.Status != (SpanStatus{Code: SpanStatusError, Message: "desc"}) {
		t.Fatalf("%+v", s)
	}
	if s.Attributes["m"] != 2.5 || s.Attributes["operation_name"] != "op" {
		t.Fatalf("attrs %v", s.Attributes)
	}
	for _, k := range []string{"num", "bad", "empty"} {
		if _, ok := s.Attributes[k]; ok {
			t.Fatalf("attr %s", k)
		}
	}
	wantEvents := []SpanEvent{
		{Name: "native", TimeMicros: 5, Attributes: map[string]any{"s": "v", "i": int64(3), "a": []any{"x"}, "e": []any{}, "raw": "plain"}},
		{Name: "no-time", TimeMicros: 0, Attributes: map[string]any{}},
	}
	if !reflect.DeepEqual(s.Events, wantEvents) {
		t.Fatalf("events %#v", s.Events)
	}
	var linkIDs []string
	for _, l := range s.Links {
		linkIDs = append(linkIDs, l.TraceID)
	}
	if strings.Join(linkIDs, ",") != "ffffffffffffffff0000000000000001,00000000000000ff0000000000000001,000000000000000000f067aa0ba902b7" {
		t.Fatal(linkIDs)
	}

	minimal, err := DatadogTraceSpanToSpan(map[string]any{"trace_id": "5", "span_id": "6", "start": 2000.0, "duration": 1000})
	if err != nil || minimal.Name != "" || minimal.ServiceName != "" || minimal.EndMicros != 3 || minimal.Status.Code != SpanStatusUnset {
		t.Fatalf("minimal %+v %v", minimal, err)
	}
	noDuration, err := DatadogTraceSpanToSpan(map[string]any{"trace_id": "5", "span_id": "0", "error": "1", "meta": []any{}, "end": int64(9000)})
	if err != nil || noDuration.DurationMicros != 9 || noDuration.Status.Code != SpanStatusError || noDuration.Status.Message != "" || noDuration.SpanID != zeroHigh {
		t.Fatalf("noDuration %+v %v", noDuration, err)
	}
	full, err := DatadogTraceSpanToSpan(map[string]any{"trace_id_hex": "abc", "span_id": "1", "metrics": map[string]any{"i": 3, "f": 1.5}})
	if err != nil || full.TraceID != "00000000000000000000000000000abc" || full.Attributes["i"] != int64(3) || full.Attributes["f"] != 1.5 {
		t.Fatalf("full %+v %v", full, err)
	}
	for _, bad := range []map[string]any{
		{},
		{"trace_id": "1", "span_id": "x"},
		{"trace_id": "1", "span_id": "1", "parent_id": "y"},
		{"traceIDFull": "zz", "span_id": "1"},
	} {
		if _, err := DatadogTraceSpanToSpan(bad); !errors.Is(err, ErrValidation) {
			t.Fatalf("%v: %v", bad, err)
		}
	}
	if DatadogKind("Client") != SpanKindClient || DatadogKind("weird") != SpanKindUnspecified || DatadogKind(3) != SpanKindUnspecified {
		t.Fatal("kind")
	}
}

func TestDatadogSearchSpanToSpan(t *testing.T) {
	raw := `{"id": "AAAA", "type": "spans", "attributes": {
	  "service": "web", "env": "prod", "host": "h1", "resource_name": "GET /x", "operation_name": "http.server.request",
	  "type": "web", "trace_id": "0123456789abcdefffffffffffffffff", "span_id": "255", "parent_id": "0",
	  "start_timestamp": "2026-11-18T10:00:00.123456Z", "end_timestamp": "2026-11-18T10:00:00.223456Z",
	  "tags": ["team:sdk", "bare", 5],
	  "attributes": {"http": {"status_code": 500}, "span": {"kind": "server"}, "duration": 100000000, "n": null},
	  "custom": {"grow": {"run": "abc"}, "list": [1, null, {"a": 1}]},
	  "status": "error", "ingestion_reason": "rule"}}`
	var m map[string]any
	if err := DecodeJSONPreservingNumbers([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	s, err := DatadogSearchSpanToSpan(m)
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 11, 18, 10, 0, 0, 0, time.UTC).UnixMicro() + 123456
	if s.TraceID != "0123456789abcdefffffffffffffffff" || s.SpanID != "00000000000000ff" || s.Name != "GET /x" || s.Kind != SpanKindServer ||
		s.ServiceName != "web" || s.DurationMicros != 100000 || s.Status.Code != SpanStatusError || s.StartMicros != start ||
		s.EndMicros-s.StartMicros != 100000 || s.ParentSpanID != "" {
		t.Fatalf("%+v", s)
	}
	want := map[string]any{
		"team": "sdk", "http.status_code": int64(500), "grow.run": "abc", "list": []any{int64(1), map[string]any{"a": int64(1)}},
		"env": "prod", "host": "h1", "span.type": "web", "ingestion_reason": "rule",
		"operation_name": "http.server.request", "resource_name": "GET /x",
	}
	for k, v := range want {
		if !reflect.DeepEqual(s.Attributes[k], v) {
			t.Fatalf("%s: %#v want %#v", k, s.Attributes[k], v)
		}
	}

	var doc struct {
		Body struct {
			Data []map[string]any `json:"data"`
		} `json:"body"`
	}
	loadFixture(t, "searchSpans.docs_example.json", &doc)
	if _, err := DatadogSearchSpanToSpan(doc.Body.Data[0]); err == nil {
		t.Fatal("docs example has no ids")
	}
	ok, err := DatadogSearchSpanToSpan(map[string]any{
		"trace_id": "42", "span_id": "1", "parent_id": "7",
		"start_timestamp": "2026-11-18T10:00:00Z", "end_timestamp": "2026-11-18T10:00:01+00:00",
		"attributes": map[string]any{"error": map[string]any{"message": "nested"}, "otel.status_code": "Ok"},
	})
	if err != nil || ok.TraceID != "0000000000000000000000000000002a" || ok.ParentSpanID != "0000000000000007" ||
		ok.DurationMicros != 1_000_000 || ok.Status.Code != SpanStatusOK || ok.Name != "" {
		t.Fatalf("ok %+v %v", ok, err)
	}
	errored, err := DatadogSearchSpanToSpan(map[string]any{
		"trace_id": "42", "span_id": "1", "start_timestamp": "bad",
		"attributes": map[string]any{"error": json.Number("1"), "error.msg": "m"},
	})
	if err != nil || errored.StartMicros != 0 || errored.EndMicros != 0 || errored.Status != (SpanStatus{Code: SpanStatusError, Message: "m"}) {
		t.Fatalf("errored %+v %v", errored, err)
	}
	noEnd, err := DatadogSearchSpanToSpan(map[string]any{
		"trace_id": "42", "span_id": "1", "start_timestamp": "2026-11-18T10:00:00", "operation_name": "op",
		"attributes": map[string]any{"duration": "2000", "status": "Error", "error": "true"},
	})
	if err != nil || noEnd.EndMicros-noEnd.StartMicros != 2 || noEnd.Name != "op" || noEnd.Status.Code != SpanStatusError {
		t.Fatalf("noEnd %+v %v", noEnd, err)
	}
	for _, bad := range []map[string]any{{"span_id": "1", "trace_id": "zz"}, {"trace_id": "1", "span_id": "x"}, {"trace_id": "1", "span_id": "1", "parent_id": "x"}} {
		if _, err := DatadogSearchSpanToSpan(bad); err == nil {
			t.Fatalf("%v", bad)
		}
	}
}

func TestMappingHelpers(t *testing.T) {
	for _, tc := range []struct {
		in   any
		want string
	}{{1.0, "1"}, {1.5, "1.5"}, {1e300, "1e+300"}, {7, "7"}, {int64(8), "8"}, {uint64(9), "9"}, {true, ""}} {
		if got := strOf(tc.in); got != tc.want {
			t.Fatalf("strOf(%v) = %q", tc.in, got)
		}
	}
	for _, tc := range []struct {
		in   any
		want bool
	}{{nil, false}, {true, true}, {"", false}, {"abc", true}, {"0", false}, {map[string]any{}, false}, {int64(2), true}, {0.0, false}, {struct{}{}, false}} {
		if got := numericTruthy(tc.in); got != tc.want {
			t.Fatalf("numericTruthy(%v) = %v", tc.in, got)
		}
	}
	if n, ok := int64Of(json.Number("1.5e3")); !ok || n != 1500 {
		t.Fatal(n)
	}
	for _, v := range []any{"x", json.Number("x"), true} {
		if _, ok := int64Of(v); ok {
			t.Fatalf("int64Of(%v)", v)
		}
	}
	if _, ok := attrValue(struct{}{}); ok {
		t.Fatal("attrValue struct")
	}
	if a, _ := attrValue(uint64(5)); a != "5" {
		t.Fatal(a)
	}
	if nativeAttr("plain") != "plain" {
		t.Fatal("nativeAttr")
	}
}
