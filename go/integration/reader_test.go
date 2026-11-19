package integration

// Test-only access to the spans the real Datadog Agent forwarded to fakeintake,
// decoded by the trace-reader sidecar (GET /spans?trace_id=<hex>&service=…).
// Not part of the package: the Datadog query API is SaaS-only, so the
// integration suite reads the Agent's output here and maps it with the
// package's own DatadogTraceSpanToSpan.

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"slices"
	"sort"
	"strings"
	"testing"
	"time"

	datadogapm "github.com/grow-codes-packages/datadog-apm/go"
)

// ReaderSpan is one span as the trace-reader returns it (Agent span format).
type ReaderSpan = map[string]any

func readerURL() string { return strings.TrimRight(os.Getenv("TRACE_READER_URL"), "/") }

// readerSpans calls GET /spans with exact-match filters.
func readerSpans(ctx context.Context, filters map[string]string) ([]ReaderSpan, error) {
	q := url.Values{}
	for k, v := range filters {
		q.Set(k, v)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, readerURL()+"/spans?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = res.Body.Close() }()
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("trace-reader: HTTP %d %s", res.StatusCode, data)
	}
	var doc struct {
		Spans       []ReaderSpan `json:"spans"`
		Undecodable int          `json:"undecodable_idx_payloads"`
	}
	if err := datadogapm.DecodeJSONPreservingNumbers(data, &doc); err != nil {
		return nil, err
	}
	if doc.Undecodable != 0 {
		return nil, fmt.Errorf("trace-reader: %d undecodable payloads", doc.Undecodable)
	}
	return doc.Spans, nil
}

// waitForSpans polls readerSpans until at least min spans match (the Agent flushes every ~4 s).
func waitForSpans(t *testing.T, filters map[string]string, minCount int) []ReaderSpan {
	t.Helper()
	deadline := time.Now().Add(45 * time.Second)
	var last []ReaderSpan
	for time.Now().Before(deadline) {
		spans, err := readerSpans(t.Context(), filters)
		if err != nil {
			t.Fatal(err)
		}
		last = spans
		if len(spans) >= minCount {
			return spans
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("trace-reader: %d/%d spans for %v", len(last), minCount, filters)
	return nil
}

func str(v any) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}

func meta(s ReaderSpan) map[string]any {
	m, _ := s["meta"].(map[string]any)
	return m
}

// TraceReaderQuery is a TraceQueryClient over the trace-reader, so the shared
// conformance scenarios run against what the real Agent made of the exported spans.
type TraceReaderQuery struct{}

var _ datadogapm.TraceQueryClient = (*TraceReaderQuery)(nil)

// GetTrace maps the reader's spans of traceID with DatadogTraceSpanToSpan.
func (q *TraceReaderQuery) GetTrace(ctx context.Context, traceID string) (*datadogapm.Trace, error) {
	id, err := datadogapm.NormalizeTraceID(traceID)
	if err != nil {
		return nil, err
	}
	raws, err := readerSpans(ctx, map[string]string{"trace_id": id})
	if err != nil || len(raws) == 0 {
		return nil, err
	}
	spans := make([]datadogapm.Span, 0, len(raws))
	for _, r := range raws {
		s, err := datadogapm.DatadogTraceSpanToSpan(r)
		if err != nil {
			return nil, err
		}
		spans = append(spans, s)
	}
	return &datadogapm.Trace{TraceID: id, Spans: datadogapm.SortSpans(spans)}, nil
}

// SearchTraces lists traces by service / resource.
func (q *TraceReaderQuery) SearchTraces(ctx context.Context, tq datadogapm.TraceSearchQuery) ([]datadogapm.TraceSummary, error) {
	filters := map[string]string{}
	if tq.Service != "" {
		filters["service"] = tq.Service
	}
	if tq.Operation != "" {
		filters["resource"] = tq.Operation
	}
	raws, err := readerSpans(ctx, filters)
	if err != nil {
		return nil, err
	}
	var ids []string
	for _, r := range raws {
		if id := str(r["trace_id_hex"]); !slices.Contains(ids, id) {
			ids = append(ids, id)
		}
	}
	var out []datadogapm.TraceSummary
	for _, id := range ids[:min(len(ids), tq.WithDefaults().Limit)] {
		tr, err := q.GetTrace(ctx, id)
		if err != nil {
			return nil, err
		}
		if tr != nil {
			out = append(out, datadogapm.TraceSummary{TraceID: id, StartMicros: tr.Spans[0].StartMicros, SpanCount: len(tr.Spans)})
		}
	}
	return out, nil
}

func (q *TraceReaderQuery) distinct(ctx context.Context, filters map[string]string, key string) ([]string, error) {
	raws, err := readerSpans(ctx, filters)
	if err != nil {
		return nil, err
	}
	set := map[string]bool{}
	for _, r := range raws {
		set[str(r[key])] = true
	}
	out := make([]string, 0, len(set))
	for k := range set {
		out = append(out, k)
	}
	sort.Strings(out)
	return out, nil
}

// ListServices returns the services the Agent forwarded.
func (q *TraceReaderQuery) ListServices(ctx context.Context) ([]string, error) {
	return q.distinct(ctx, nil, "service")
}

// ListOperations returns the resources (span names) of service.
func (q *TraceReaderQuery) ListOperations(ctx context.Context, service string) ([]string, error) {
	return q.distinct(ctx, map[string]string{"service": service}, "resource")
}

// GetDependencies is unsupported (as in DatadogSpansQuery).
func (q *TraceReaderQuery) GetDependencies(context.Context, datadogapm.DependencyOptions) ([]datadogapm.DependencyLink, error) {
	return nil, datadogapm.UnsupportedError("traceReader.getDependencies", "not offered")
}

// WaitForTrace polls GetTrace.
func (q *TraceReaderQuery) WaitForTrace(ctx context.Context, traceID string, opts datadogapm.WaitForTraceOptions) (*datadogapm.Trace, error) {
	return datadogapm.WaitForTrace(ctx, q, traceID, opts)
}
