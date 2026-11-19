package datadogapm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	testAPIKey = "api-key-SECRET-0001"
	testAppKey = "app-key-SECRET-0002"
)

var fixedNow = time.Date(2026, 11, 18, 12, 0, 0, 0, time.UTC)

func newTestQuery(t *testing.T, url string, mutate func(*Config)) *DatadogSpansQuery {
	t.Helper()
	cfg := Config{CommonConfig: CommonConfig{Retry: fastRetry(3)}, APIURL: url, APIKey: NewSecret(testAPIKey), AppKey: NewSecret(testAppKey)}
	if mutate != nil {
		mutate(&cfg)
	}
	q, err := NewDatadogSpansQuery(cfg)
	if err != nil {
		t.Fatal(err)
	}
	q.now = func() time.Time { return fixedNow }
	t.Cleanup(func() { _ = q.Close() })
	return q
}

type recorded struct {
	mu     sync.Mutex
	bodies []map[string]any
}

func (r *recorded) add(req *http.Request) {
	data, _ := io.ReadAll(req.Body)
	var m map[string]any
	_ = json.Unmarshal(data, &m)
	r.mu.Lock()
	r.bodies = append(r.bodies, m)
	r.mu.Unlock()
}

func (r *recorded) attrs(i int) map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	return recordOf(recordOf(r.bodies[i]["data"])["attributes"])
}

func fixtureBody(t *testing.T, name string) any {
	t.Helper()
	var doc struct {
		Body any `json:"body"`
	}
	loadFixture(t, name, &doc)
	return doc.Body
}

func searchSpan(traceID, spanID, parentID, name string, startSec int) map[string]any {
	return map[string]any{
		"id": "ev-" + spanID, "type": "spans",
		"attributes": map[string]any{
			"service": "web", "resource_name": name, "trace_id": traceID, "span_id": spanID, "parent_id": parentID,
			"start_timestamp": fixedNow.Add(time.Duration(startSec) * time.Second).Format(time.RFC3339Nano),
			"end_timestamp":   fixedNow.Add(time.Duration(startSec+1) * time.Second).Format(time.RFC3339Nano),
		},
	}
}

func TestSpansQueryConfig(t *testing.T) {
	for _, cfg := range []Config{{}, {APIKey: NewSecret("only-one-key")}, {AppKey: NewSecret("only-app")}} {
		if _, err := NewDatadogSpansQuery(cfg); !errors.Is(err, ErrConfig) {
			t.Fatalf("%s: %v", cfg, err)
		}
	}
	if _, err := NewDatadogSpansQuery(Config{APIURL: "nope", APIKey: NewSecret("a1234"), AppKey: NewSecret("b1234")}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
	if _, err := NewDatadogSpansQuery(Config{APIKey: NewSecret("a1234"), AppKey: NewSecret("b1234"), CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
	t.Setenv("DD_API_KEY", "env-api-key")
	t.Setenv("DD_APP_KEY", "env-app-key")
	t.Setenv("DD_SITE", "datadoghq.eu")
	q, err := DatadogSpansQueryFromEnv()
	if err != nil || q.Config().APIURL != "https://api.datadoghq.eu" {
		t.Fatal(err)
	}
	_ = q.Close()
	_ = q.Close()
	t.Setenv("DD_SITE", "https://bad")
	if _, err := DatadogSpansQueryFromEnv(); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
}

func TestSearchSpans(t *testing.T) {
	t.Run("searchSpansPage sends the documented request with both keys", func(t *testing.T) {
		rec := &recorded{}
		body := fixtureBody(t, "searchSpans.docs_example.json").(map[string]any)
		// give the docs example ids so it maps (the docs example has none)
		item := body["data"].([]any)[0].(map[string]any)
		attrs := item["attributes"].(map[string]any)
		attrs["trace_id"], attrs["span_id"] = "42", "1"
		body["meta"].(map[string]any)["request_id"] = "req-1"
		body["meta"].(map[string]any)["page"] = map[string]any{"after": "cur-2"}
		body["meta"].(map[string]any)["warnings"] = []any{map[string]any{"code": "w", "title": "t", "detail": "d"}}
		ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			rec.add(r)
			writeJSON(w, body)
		})
		q := newTestQuery(t, ts.URL, nil)
		page, err := q.SearchSpansPage(t.Context(), SearchSpansOptions{Query: "service:web*", Limit: 25, Cursor: "cur-1", Sort: "-timestamp", Timezone: "GMT"})
		if err != nil {
			t.Fatal(err)
		}
		req := ts.request(0)
		if req.Method != http.MethodPost || req.URL.Path != "/api/v2/spans/events/search" ||
			req.Header.Get("DD-API-KEY") != testAPIKey || req.Header.Get("DD-APPLICATION-KEY") != testAppKey ||
			!strings.HasPrefix(req.Header.Get("Content-Type"), "application/json") {
			t.Fatalf("request %s %s %v", req.Method, req.URL, req.Header)
		}
		a := rec.attrs(0)
		filter := recordOf(a["filter"])
		if filter["query"] != "service:web*" || filter["from"] != "2026-11-18T11:45:00.000Z" || filter["to"] != "2026-11-18T12:00:00.000Z" ||
			recordOf(a["page"])["limit"] != 25.0 || recordOf(a["page"])["cursor"] != "cur-1" || a["sort"] != "-timestamp" ||
			recordOf(a["options"])["timezone"] != "GMT" || recordOf(rec.bodies[0]["data"])["type"] != "search_request" {
			t.Fatalf("body %v", a)
		}
		if len(page.Spans) != 1 || page.Spans[0].ID != "AAAAAWgN8Xwgr1vKDQAAAABBV2dOOFh3ZzZobm1mWXJFYTR0OA" ||
			page.Spans[0].Span.ServiceName != "test-service" || page.Spans[0].Raw["env"] != "prod" ||
			page.NextCursor != "cur-2" || page.Status != "done" || page.RequestID != "req-1" || page.Warnings[0].Code != "w" {
			t.Fatalf("page %+v", page)
		}
		// defaults
		if _, err := q.SearchSpansPage(t.Context(), SearchSpansOptions{From: TimeExpr("now-1h"), To: TimeAt(fixedNow)}); err != nil {
			t.Fatal(err)
		}
		a = rec.attrs(1)
		if recordOf(a["filter"])["query"] != "*" || recordOf(a["filter"])["from"] != "now-1h" || recordOf(a["page"])["limit"] != 10.0 ||
			a["sort"] != "timestamp" || recordOf(a["options"])["timezone"] != "UTC" {
			t.Fatalf("defaults %v", a)
		}
		if _, has := recordOf(a["page"])["cursor"]; has {
			t.Fatal("no cursor expected")
		}
	})

	t.Run("searchSpans follows cursors with the same absolute window", func(t *testing.T) {
		rec := &recorded{}
		ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
			rec.add(r)
			switch n {
			case 1:
				writeJSON(w, map[string]any{"data": []any{searchSpan("42", "1", "0", "a", 0)}, "meta": map[string]any{"page": map[string]any{"after": "c2"}}})
			case 2:
				writeJSON(w, map[string]any{"data": []any{searchSpan("42", "2", "1", "b", 1)}, "meta": map[string]any{"page": map[string]any{"after": "c2"}}})
			default:
				writeJSON(w, map[string]any{"data": []any{}})
			}
		})
		q := newTestQuery(t, ts.URL, nil)
		var names []string
		for s, err := range q.SearchSpans(t.Context(), SearchSpansOptions{Query: "service:web"}) {
			if err != nil {
				t.Fatal(err)
			}
			names = append(names, s.Span.Name)
		}
		if strings.Join(names, ",") != "a,b" || ts.calls.Load() != 2 {
			t.Fatalf("names %v calls %d", names, ts.calls.Load())
		}
		if fmt.Sprint(rec.attrs(0)["filter"]) != fmt.Sprint(rec.attrs(1)["filter"]) || recordOf(rec.attrs(1)["page"])["cursor"] != "c2" {
			t.Fatal("same window")
		}
		// early break stops paging
		for _, err := range q.SearchSpans(t.Context(), SearchSpansOptions{}) {
			if err != nil {
				t.Fatal(err)
			}
			break
		}
	})

	t.Run("timed-out empty result, invalid limit and errors", func(t *testing.T) {
		ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			writeJSON(w, map[string]any{"data": []any{}, "meta": map[string]any{"status": "timeout"}})
		})
		q := newTestQuery(t, ts.URL, nil)
		for _, err := range q.SearchSpans(t.Context(), SearchSpansOptions{}) {
			if !errors.Is(err, ErrTimeout) {
				t.Fatal(err)
			}
		}
		for _, limit := range []int{-1, 1001} {
			if _, err := q.SearchSpansPage(t.Context(), SearchSpansOptions{Limit: limit}); !errors.Is(err, ErrConfig) {
				t.Fatal(err)
			}
		}
		badJSON := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("{oops")) })
		if _, err := newTestQuery(t, badJSON.URL, nil).SearchSpansPage(t.Context(), SearchSpansOptions{}); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
		badID := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			writeJSON(w, map[string]any{"data": []any{map[string]any{"id": "x", "type": "spans", "attributes": map[string]any{"trace_id": "zz"}}}})
		})
		if _, err := newTestQuery(t, badID.URL, nil).SearchSpansPage(t.Context(), SearchSpansOptions{}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
		empty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { w.WriteHeader(http.StatusOK) })
		page, err := newTestQuery(t, empty.URL, nil).SearchSpansPage(t.Context(), SearchSpansOptions{})
		if err != nil || len(page.Spans) != 0 || page.Status != "done" || page.Warnings == nil {
			t.Fatalf("%+v %v", page, err)
		}
		nilAttrs := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			writeJSON(w, map[string]any{"data": []any{map[string]any{"id": "x"}}})
		})
		if _, err := newTestQuery(t, nilAttrs.URL, nil).SearchSpansPage(t.Context(), SearchSpansOptions{}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
	})
}

func TestSpansErrors(t *testing.T) {
	t.Run("core.errors.mapping", func(t *testing.T) {
		fixture400 := fixtureBody(t, "searchSpans.400.docs_example.json")
		for status, want := range map[int]*Error{
			400: ErrValidation, 401: ErrAuthentication, 403: ErrPermissionDenied, 404: ErrNotFound, 408: ErrTimeout,
			413: ErrValidation, 422: ErrValidation, 500: ErrUnavailable, 503: ErrUnavailable,
		} {
			ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(status)
				if status == 400 {
					_ = json.NewEncoder(w).Encode(fixture400)
				} else {
					_, _ = w.Write([]byte(`{"errors":["Bad things with ` + testAPIKey + `"]}`))
				}
			})
			q := newTestQuery(t, ts.URL, func(c *Config) { c.Retry = fastRetry(0) })
			_, err := q.SearchSpansPage(t.Context(), SearchSpansOptions{})
			if !errors.Is(err, want) || strings.Contains(err.Error(), testAPIKey) || strings.Contains(err.Error(), testAppKey) {
				t.Fatalf("%d: %v", status, err)
			}
			if status == 400 && !strings.Contains(err.Error(), "Missing required attribute") {
				t.Fatalf("vendor text: %v", err)
			}
		}
	})

	t.Run("429 waits Retry-After / X-RateLimit-Reset, then gives up", func(t *testing.T) {
		var mu sync.Mutex
		var waits []time.Duration
		ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, n int) {
			switch n {
			case 1:
				w.Header().Set("X-RateLimit-Reset", "2")
				w.WriteHeader(http.StatusTooManyRequests)
			case 2:
				w.Header().Set("Retry-After", "1")
				w.WriteHeader(http.StatusTooManyRequests)
			default:
				writeJSON(w, map[string]any{"data": map[string]any{"attributes": map[string]any{"services": []any{"b", "a", 3}}}})
			}
		})
		q := newTestQuery(t, ts.URL, nil)
		q.transport.sleep = func(_ context.Context, d time.Duration) error {
			mu.Lock()
			waits = append(waits, d)
			mu.Unlock()
			return nil
		}
		services, err := q.ListServices(t.Context())
		if err != nil || strings.Join(services, ",") != "a,b" || len(waits) != 2 || waits[0] != 2*time.Second || waits[1] != time.Second {
			t.Fatalf("services %v err %v waits %v", services, err, waits)
		}
		always := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			w.Header().Set("X-RateLimit-Reset", "3600")
			w.WriteHeader(http.StatusTooManyRequests)
		})
		q2 := newTestQuery(t, always.URL, nil)
		_, err = q2.ListServices(t.Context())
		var e *Error
		if !errors.As(err, &e) || e.Code != ErrorCodeRateLimited || e.RetryAfter != time.Hour || always.calls.Load() != 1 {
			t.Fatalf("give up: %v", err)
		}
	})

	t.Run("cancellation is honoured", func(t *testing.T) {
		ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { writeJSON(w, map[string]any{}) })
		q := newTestQuery(t, ts.URL, nil)
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := q.SearchSpansPage(ctx, SearchSpansOptions{}); !errors.Is(err, ErrCancelled) {
			t.Fatal(err)
		}
		if _, err := q.GetTrace(ctx, "2a"); !errors.Is(err, ErrCancelled) {
			t.Fatal(err)
		}
	})
}

func TestAggregateSpans(t *testing.T) {
	rec := &recorded{}
	body := fixtureBody(t, "aggregateSpans.docs_example.json")
	ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
		rec.add(r)
		if n == 1 {
			writeJSON(w, body)
			return
		}
		writeJSON(w, map[string]any{
			"data": []any{
				map[string]any{"attributes": map[string]any{"by": map[string]any{"service": "web", "code": 500}, "compute": map[string]any{"c0": 19, "c1": 2.5, "c2": "x"}}},
				map[string]any{"attributes": map[string]any{"compute": map[string]any{"c0": []any{map[string]any{"time": "t1", "value": 3}, map[string]any{"time": "t2", "value": 1.5}}}}},
			},
			"meta": map[string]any{"status": "done", "request_id": "agg-1", "warnings": []any{map[string]any{"title": "partial"}}},
		})
	})
	q := newTestQuery(t, ts.URL, nil)
	res, err := q.AggregateSpans(t.Context(),
		[]SpanCompute{{Aggregation: AggregationPc90, Metric: "@duration", Type: "timeseries", Interval: "5m"}},
		[]SpanGroupBy{{Facet: "service", Limit: 20, Sort: &SpanGroupBySort{Aggregation: AggregationPc90, Metric: "@duration", Order: "desc", Type: "measure"}}},
		AggregateSpansOptions{Query: "service:web*", Timezone: "GMT"},
	)
	if err != nil {
		t.Fatal(err)
	}
	req := ts.request(0)
	a := rec.attrs(0)
	if req.URL.Path != "/api/v2/spans/analytics/aggregate" || recordOf(rec.bodies[0]["data"])["type"] != "aggregate_request" ||
		recordOf(a["filter"])["query"] != "service:web*" || recordOf(a["options"])["timezone"] != "GMT" ||
		fmt.Sprint(a["compute"]) != "[map[aggregation:pc90 interval:5m metric:@duration type:timeseries]]" ||
		fmt.Sprint(a["group_by"]) != "[map[facet:service limit:20 sort:map[aggregation:pc90 metric:@duration order:desc type:measure]]]" {
		t.Fatalf("body %v", a)
	}
	if len(res.Buckets) == 0 || res.Status != "done" || res.Warnings == nil {
		t.Fatalf("docs result %+v", res)
	}
	res, err = q.AggregateSpans(t.Context(), []SpanCompute{{Aggregation: AggregationCount}}, nil, AggregateSpansOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if _, has := rec.attrs(1)["group_by"]; has || recordOf(rec.attrs(1)["filter"])["query"] != "*" {
		t.Fatal("no group_by expected")
	}
	b0, b1 := res.Buckets[0], res.Buckets[1]
	if b0.By["service"] != "web" || b0.By["code"] != int64(500) || b0.Computes["c0"] != 19.0 || b0.Computes["c1"] != 2.5 || b0.Computes["c2"] != "x" ||
		res.RequestID != "agg-1" || res.Warnings[0].Title != "partial" {
		t.Fatalf("buckets %+v", res)
	}
	points, _ := b1.Computes["c0"].([]SpanTimeseriesPoint)
	if len(points) != 2 || points[0] != (SpanTimeseriesPoint{Time: "t1", Value: 3}) || points[1].Value != 1.5 || len(b1.By) != 0 {
		t.Fatalf("timeseries %+v", b1)
	}
	if _, err := q.AggregateSpans(t.Context(), nil, nil, AggregateSpansOptions{}); !errors.Is(err, ErrConfig) {
		t.Fatal(err)
	}
	bad := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("[")) })
	if _, err := newTestQuery(t, bad.URL, nil).AggregateSpans(t.Context(), []SpanCompute{{Aggregation: "count"}}, nil, AggregateSpansOptions{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	fail := newTestServer(t, status(http.StatusBadRequest))
	if _, err := newTestQuery(t, fail.URL, nil).AggregateSpans(t.Context(), []SpanCompute{{Aggregation: "count"}}, nil, AggregateSpansOptions{}); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	if _, err := newTestQuery(t, fail.URL, nil).ListOperations(t.Context(), "web"); !errors.Is(err, ErrValidation) {
		t.Fatal(err)
	}
	empty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { w.WriteHeader(http.StatusOK) })
	if r, err := newTestQuery(t, empty.URL, nil).AggregateSpans(t.Context(), []SpanCompute{{Aggregation: "count"}}, nil, AggregateSpansOptions{}); err != nil || r.Status != "done" {
		t.Fatal(err)
	}
}

func TestListServices(t *testing.T) {
	body := fixtureBody(t, "listServices.docs_example.json")
	ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { writeJSON(w, body) })
	q := newTestQuery(t, ts.URL, func(c *Config) { c.Environment = "prod" })
	services, err := q.ListServices(t.Context())
	if err != nil || len(services) != 1 || services[0] != "test-service" {
		t.Fatal(services, err)
	}
	if got := ts.request(0).URL.Query().Get("filter[env]"); got != "prod" || ts.request(0).URL.Path != "/api/v2/apm/services" {
		t.Fatal(got)
	}
	_, _ = newTestQuery(t, ts.URL, nil).ListServices(t.Context())
	if got := ts.request(1).URL.Query().Get("filter[env]"); got != "*" {
		t.Fatal(got)
	}
	_, _ = q.ListServicesWithOptions(t.Context(), ListServicesOptions{Env: "staging"})
	if got := ts.request(2).URL.Query().Get("filter[env]"); got != "staging" {
		t.Fatal(got)
	}
	bad := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { _, _ = w.Write([]byte("nope")) })
	if _, err := newTestQuery(t, bad.URL, nil).ListServices(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	fail := newTestServer(t, status(http.StatusForbidden))
	if _, err := newTestQuery(t, fail.URL, nil).ListServices(t.Context()); !errors.Is(err, ErrPermissionDenied) {
		t.Fatal(err)
	}
	empty := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { w.WriteHeader(http.StatusOK) })
	if s, err := newTestQuery(t, empty.URL, nil).ListServices(t.Context()); err != nil || s == nil || len(s) != 0 {
		t.Fatal(s, err)
	}
}

func TestGetTrace(t *testing.T) {
	t.Run("search by trace id (128-bit hex / 64-bit decimal), merging pages", func(t *testing.T) {
		rec := &recorded{}
		ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
			rec.add(r)
			switch n {
			case 1:
				writeJSON(w, map[string]any{"data": []any{searchSpan("0123456789abcdefffffffffffffffff", "2", "1", "child", 1)},
					"meta": map[string]any{"page": map[string]any{"after": "p2"}}})
			case 2:
				writeJSON(w, map[string]any{"data": []any{
					searchSpan("0123456789abcdefffffffffffffffff", "1", "0", "root", 0),
					searchSpan("0123456789abcdefffffffffffffffff", "2", "1", "child", 1),
				}})
			default:
				writeJSON(w, map[string]any{"data": []any{}})
			}
		})
		q := newTestQuery(t, ts.URL, nil)
		tr, err := q.GetTrace(t.Context(), "0123456789ABCDEFFFFFFFFFFFFFFFFF")
		if err != nil || tr == nil || len(tr.Spans) != 2 || tr.Spans[0].Name != "root" || tr.TraceID != "0123456789abcdefffffffffffffffff" {
			t.Fatalf("%+v %v", tr, err)
		}
		a := rec.attrs(0)
		filter := recordOf(a["filter"])
		if filter["query"] != "trace_id:0123456789abcdefffffffffffffffff" || filter["from"] != "2026-11-17T12:00:00.000Z" || recordOf(a["page"])["limit"] != 1000.0 {
			t.Fatalf("query %v", a)
		}
		missing, err := q.GetTraceWithOptions(t.Context(), "2a", GetTraceOptions{From: TimeExpr("now-1h"), To: TimeExpr("now")})
		if err != nil || missing != nil {
			t.Fatal(missing, err)
		}
		if f := recordOf(rec.attrs(2)["filter"]); f["query"] != "trace_id:42" || f["from"] != "now-1h" {
			t.Fatalf("64-bit query %v", f)
		}
		if _, err := q.GetTrace(t.Context(), "xyz"); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
		fail := newTestServer(t, status(http.StatusUnauthorized))
		if _, err := newTestQuery(t, fail.URL, nil).GetTrace(t.Context(), "2a"); !errors.Is(err, ErrAuthentication) {
			t.Fatal(err)
		}
	})

	t.Run("preview reads the trace-by-id API with exact ids", func(t *testing.T) {
		body := fixtureBody(t, "getTraceById.docs_example.json")
		ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			switch r.URL.Path {
			case "/api/v2/trace/0000000000000000abc1230000000000":
				// exact big integers as JSON numbers
				data, _ := json.Marshal(body)
				_, _ = w.Write(data)
			case "/api/v2/trace/9007199254740993":
				w.WriteHeader(http.StatusNotFound)
			case "/api/v2/trace/0000000000000000000000000000002a":
				_, _ = w.Write([]byte(`{"data":{"attributes":{"spans":[]}}}`))
			case "/api/v2/trace/0000000000000000000000000000002b":
				w.WriteHeader(http.StatusOK)
			case "/api/v2/trace/0000000000000000000000000000002e":
				_, _ = w.Write([]byte(`{bad`))
			case "/api/v2/trace/0000000000000000000000000000002d":
				_, _ = w.Write([]byte(`{"data":{"attributes":{"spans":[{"traceID":"x"}]}}}`))
			default:
				w.WriteHeader(http.StatusInternalServerError)
			}
		})
		q := newTestQuery(t, ts.URL, func(c *Config) { c.Retry = fastRetry(0) })
		tr, err := q.GetTraceWithOptions(t.Context(), "0000000000000000abc1230000000000", GetTraceOptions{Preview: true})
		if err != nil || tr == nil || len(tr.Spans) != 1 || tr.Spans[0].SpanID != "891087b8e3b70cb1" || tr.Spans[0].Name != "GET /products" {
			t.Fatalf("%+v %v", tr, err)
		}
		for _, id := range []string{"00000000000000000020000000000001", "2a", "2b"} {
			if tr, err := q.GetTraceWithOptions(t.Context(), id, GetTraceOptions{Preview: true}); err != nil || tr != nil {
				t.Fatalf("%s: %+v %v", id, tr, err)
			}
		}
		if _, err := q.GetTraceWithOptions(t.Context(), "2c", GetTraceOptions{Preview: true}); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
		if _, err := q.GetTraceWithOptions(t.Context(), "2d", GetTraceOptions{Preview: true}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
		if _, err := q.GetTraceWithOptions(t.Context(), "2e", GetTraceOptions{Preview: true}); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
	})

	t.Run("waitForTrace polls with the Datadog interval", func(t *testing.T) {
		ts := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, n int) {
			if n == 1 {
				writeJSON(w, map[string]any{"data": []any{}})
				return
			}
			writeJSON(w, map[string]any{"data": []any{searchSpan("42", "1", "0", "root", 0)}})
		})
		q := newTestQuery(t, ts.URL, nil)
		start := time.Now()
		tr, err := q.WaitForTrace(t.Context(), "2a", WaitForTraceOptions{Timeout: 10 * time.Second})
		if err != nil || tr == nil || time.Since(start) < DatadogWaitInterval {
			t.Fatalf("%+v %v", tr, err)
		}
		tr, err = q.WaitForTrace(t.Context(), "2a", WaitForTraceOptions{Interval: time.Millisecond})
		if err != nil || len(tr.Spans) != 1 {
			t.Fatal(err)
		}
	})
}

func TestTraceQueryHelpers(t *testing.T) {
	t.Run("listOperations groups by resource_name", func(t *testing.T) {
		rec := &recorded{}
		ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
			rec.add(r)
			writeJSON(w, map[string]any{"data": []any{
				map[string]any{"attributes": map[string]any{"by": map[string]any{"resource_name": "b"}}},
				map[string]any{"attributes": map[string]any{"by": map[string]any{"resource_name": "a"}}},
				map[string]any{"attributes": map[string]any{"by": map[string]any{"resource_name": ""}}},
				map[string]any{"attributes": map[string]any{"by": map[string]any{"resource_name": "a"}}},
			}})
		})
		ops, err := newTestQuery(t, ts.URL, nil).ListOperations(t.Context(), "my svc")
		if err != nil || strings.Join(ops, ",") != "a,b" {
			t.Fatal(ops, err)
		}
		a := rec.attrs(0)
		if recordOf(a["filter"])["query"] != `service:"my svc"` || fmt.Sprint(a["group_by"]) != "[map[facet:resource_name limit:1000]]" ||
			fmt.Sprint(a["compute"]) != "[map[aggregation:count]]" || recordOf(a["filter"])["from"] != "2026-11-17T12:00:00.000Z" {
			t.Fatalf("body %v", a)
		}
	})

	t.Run("searchTraces builds the query and summarizes distinct traces", func(t *testing.T) {
		rec := &recorded{}
		ts := newTestServer(t, func(w http.ResponseWriter, r *http.Request, n int) {
			rec.add(r)
			if n == 1 {
				writeJSON(w, map[string]any{"data": []any{
					searchSpan("aa", "2", "1", "child", 1),
					searchSpan("bb", "5", "0", "other", 3),
				}, "meta": map[string]any{"page": map[string]any{"after": "n2"}}})
				return
			}
			writeJSON(w, map[string]any{"data": []any{
				searchSpan("aa", "1", "0", "root", 0),
				searchSpan("cc", "9", "0", "third", 5),
			}})
		})
		q := newTestQuery(t, ts.URL, nil)
		sums, err := q.SearchTraces(t.Context(), TraceSearchQuery{
			Service: "web", Operation: "GET /x", Tags: map[string]string{"grow.run": "r1", "a": "b c"},
			MinDuration: time.Millisecond, MaxDuration: time.Second, Limit: 3,
			Start: fixedNow.Add(-time.Hour), End: fixedNow,
		})
		if err != nil || len(sums) != 3 {
			t.Fatalf("%+v %v", sums, err)
		}
		a := rec.attrs(0)
		if recordOf(a["filter"])["query"] != `service:web resource_name:"GET /x" @a:"b c" @grow.run:r1 @duration:>=1000000 @duration:<=1000000000` ||
			a["sort"] != "-timestamp" || recordOf(a["page"])["limit"] != 100.0 || recordOf(a["filter"])["from"] != "2026-11-18T11:00:00.000Z" {
			t.Fatalf("body %v", a)
		}
		first := sums[0]
		if first.TraceID != "000000000000000000000000000000aa" || first.SpanCount != 2 || first.RootName != "root" ||
			first.RootServiceName != "web" || first.DurationMicros != 2_000_000 || first.StartMicros != fixedNow.UnixMicro() {
			t.Fatalf("summary %+v", first)
		}
		if sums[1].TraceID != "000000000000000000000000000000bb" {
			t.Fatal(sums[1])
		}
		if TraceSearchQueryString(TraceSearchQuery{}) != "*" {
			t.Fatal("empty query")
		}
		fail := newTestServer(t, status(http.StatusBadRequest))
		if _, err := newTestQuery(t, fail.URL, nil).SearchTraces(t.Context(), TraceSearchQuery{}); !errors.Is(err, ErrValidation) {
			t.Fatal(err)
		}
		// orphan spans: the earliest span is the root
		orphan := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) {
			writeJSON(w, map[string]any{"data": []any{searchSpan("dd", "3", "2", "late", 2), searchSpan("dd", "4", "3", "later", 3)}})
		})
		sums, _ = newTestQuery(t, orphan.URL, nil).SearchTraces(t.Context(), TraceSearchQuery{})
		if len(sums) != 1 || sums[0].RootName != "late" {
			t.Fatalf("%+v", sums)
		}
	})

	t.Run("getDependencies is unsupported", func(t *testing.T) {
		q := newTestQuery(t, "http://127.0.0.1:1", nil)
		if _, err := q.GetDependencies(t.Context(), DependencyOptions{}); !errors.Is(err, ErrUnsupported) {
			t.Fatal(err)
		}
	})

	t.Run("ping validates the keys; 403 means invalid keys", func(t *testing.T) {
		body := fixtureBody(t, "ping.validate_keys.docs_example.json")
		ok := newTestServer(t, func(w http.ResponseWriter, _ *http.Request, _ int) { writeJSON(w, body) })
		if d, err := newTestQuery(t, ok.URL, nil).Ping(t.Context()); err != nil || d <= 0 || ok.request(0).URL.Path != "/api/v2/validate_keys" {
			t.Fatal(d, err)
		}
		for _, code := range []int{http.StatusUnauthorized, http.StatusForbidden} {
			ts := newTestServer(t, status(code))
			_, err := newTestQuery(t, ts.URL, nil).Ping(t.Context())
			var e *Error
			if !errors.As(err, &e) || e.Code != ErrorCodeAuthentication || e.HTTPStatus != code || errors.Is(err, ErrPermissionDenied) {
				t.Fatalf("%d: %v", code, err)
			}
		}
	})

	t.Run("quoteQueryValue", func(t *testing.T) {
		for in, want := range map[string]string{"web-1": "web-1", "a b": `"a b"`, `q"x\`: `"q\"x\\"`, "svc*": "svc*"} {
			if got := QuoteQueryValue(in); got != want {
				t.Fatalf("%s: %s", in, got)
			}
		}
	})

	t.Run("time helpers", func(t *testing.T) {
		if !(DatadogTime{}).IsZero() || TimeExpr("now").String() != "now" || TimeAt(fixedNow).IsZero() {
			t.Fatal("DatadogTime")
		}
	})
}
