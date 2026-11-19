package datadogapm

// DatadogSpansQuery — the Datadog SaaS spans query API (API.md §4). It needs an
// API key and an application key with apm_read. No container can serve these
// endpoints, so they are unit-tested against Datadog's documented examples and
// verified by the live sandbox job.

import (
	"context"
	"errors"
	"iter"
	"maps"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"
)

// DatadogWaitInterval is the default polling interval of
// DatadogSpansQuery.WaitForTrace (Datadog allows 50 searches per minute).
const DatadogWaitInterval = 2 * time.Second

const (
	defaultQueryWindow  = 15 * time.Minute
	traceQueryWindow    = 24 * time.Hour
	maxSpansPage        = 1000
	searchTracesMaxPage = 5
)

// DatadogTime is a point in time for a query: an absolute time (At) or a
// Datadog expression (Expr: "now-15m", ISO-8601, epoch milliseconds). The zero
// value means "use the default".
type DatadogTime struct {
	// At is an absolute time (sent as ISO-8601 in UTC).
	At time.Time
	// Expr is sent as is when At is zero.
	Expr string
}

// TimeAt returns an absolute DatadogTime.
func TimeAt(t time.Time) DatadogTime { return DatadogTime{At: t} }

// TimeExpr returns a DatadogTime from a Datadog date expression such as "now-15m".
func TimeExpr(expr string) DatadogTime { return DatadogTime{Expr: expr} }

// IsZero reports whether the time is unset.
func (t DatadogTime) IsZero() bool { return t.At.IsZero() && t.Expr == "" }

func (t DatadogTime) String() string {
	if !t.At.IsZero() {
		return t.At.UTC().Format("2006-01-02T15:04:05.000Z")
	}
	return t.Expr
}

// SearchSpansOptions configure SearchSpans and SearchSpansPage.
type SearchSpansOptions struct {
	// Query is a Datadog span query, e.g. "service:web @http.status_code:500". Default "*".
	Query string
	// From is the start of the window. Default: 15 minutes ago (absolute, fixed for all pages).
	From DatadogTime
	// To is the end of the window. Default: now (absolute, fixed for all pages).
	To DatadogTime
	// Limit is the page size, 1..1000. Default 10.
	Limit int
	// Sort is "timestamp" (oldest first, default) or "-timestamp" (newest first).
	Sort string
	// Cursor is the cursor of a previous page (NextCursor).
	Cursor string
	// Timezone for relative dates, e.g. "UTC" (default) or "Europe/Berlin".
	Timezone string
}

// DatadogQueryWarning is a warning attached to a partial query result.
type DatadogQueryWarning struct {
	Code   string `json:"code,omitempty"`
	Title  string `json:"title,omitempty"`
	Detail string `json:"detail,omitempty"`
}

// DatadogSpan is one span returned by the spans search API.
type DatadogSpan struct {
	// ID is the Datadog event id of the span.
	ID string
	// Span is the span in the SPEC §5 model (ids normalized to hex).
	Span Span
	// Raw holds the attributes exactly as Datadog returned them.
	Raw map[string]any
}

// DatadogSpansPage is one page of SearchSpansPage.
type DatadogSpansPage struct {
	Spans []DatadogSpan
	// NextCursor is the cursor of the next page, "" on the last page.
	NextCursor string
	// Status is "done", or "timeout" for a partial result.
	Status string
	// Warnings is non-empty when the result is partial or the query was adjusted.
	Warnings []DatadogQueryWarning
	// RequestID is meta.request_id, when present.
	RequestID string
}

// Aggregation functions of the spans aggregate API.
const (
	AggregationCount       = "count"
	AggregationCardinality = "cardinality"
	AggregationPc75        = "pc75"
	AggregationPc90        = "pc90"
	AggregationPc95        = "pc95"
	AggregationPc98        = "pc98"
	AggregationPc99        = "pc99"
	AggregationSum         = "sum"
	AggregationMin         = "min"
	AggregationMax         = "max"
	AggregationAvg         = "avg"
	AggregationMedian      = "median"
)

// SpanCompute is one computation of AggregateSpans.
type SpanCompute struct {
	// Aggregation is one of the Aggregation* constants.
	Aggregation string `json:"aggregation"`
	// Metric is the measure, e.g. "@duration" (not needed for count).
	Metric string `json:"metric,omitempty"`
	// Type is "total" (default) or "timeseries" (all computes must use the same type and interval).
	Type string `json:"type,omitempty"`
	// Interval is the time bucket for timeseries, e.g. "5m".
	Interval string `json:"interval,omitempty"`
}

// SpanGroupBySort orders the groups of a SpanGroupBy.
type SpanGroupBySort struct {
	Aggregation string `json:"aggregation,omitempty"`
	Metric      string `json:"metric,omitempty"`
	// Order is "asc" or "desc".
	Order string `json:"order,omitempty"`
	// Type is "alphabetical" or "measure".
	Type string `json:"type,omitempty"`
}

// SpanHistogram buckets a numeric facet.
type SpanHistogram struct {
	Interval float64 `json:"interval"`
	Min      float64 `json:"min"`
	Max      float64 `json:"max"`
}

// SpanGroupBy is one group-by facet of AggregateSpans.
type SpanGroupBy struct {
	// Facet, e.g. "service", "resource_name", "@http.status_code".
	Facet string `json:"facet"`
	// Limit is the maximum number of groups (Datadog default 10).
	Limit int              `json:"limit,omitempty"`
	Sort  *SpanGroupBySort `json:"sort,omitempty"`
	// Missing is the value used for spans without the facet (string or number).
	Missing any `json:"missing,omitempty"`
	// Total adds a total bucket (true, or the bucket's value).
	Total     any            `json:"total,omitempty"`
	Histogram *SpanHistogram `json:"histogram,omitempty"`
}

// AggregateSpansOptions filter AggregateSpans.
type AggregateSpansOptions struct {
	// Query defaults to "*".
	Query string
	// From defaults to 15 minutes ago.
	From DatadogTime
	// To defaults to now.
	To DatadogTime
	// Timezone defaults to "UTC".
	Timezone string
}

// SpanTimeseriesPoint is a point of a timeseries compute.
type SpanTimeseriesPoint struct {
	Time  string  `json:"time"`
	Value float64 `json:"value"`
}

// SpanAggregateBucket is one bucket of an aggregation.
type SpanAggregateBucket struct {
	// By holds the group-by values, e.g. {"service": "web"} (empty without groupBy).
	By map[string]any
	// Computes holds the results keyed "c0", "c1", … in the order of the compute
	// list: a float64 for total computes, []SpanTimeseriesPoint for timeseries.
	Computes map[string]any
}

// SpanAggregateResult is the result of AggregateSpans.
type SpanAggregateResult struct {
	Buckets   []SpanAggregateBucket
	Status    string
	Warnings  []DatadogQueryWarning
	RequestID string
}

// GetTraceOptions configure GetTraceWithOptions.
type GetTraceOptions struct {
	// Preview uses the preview trace-by-id endpoint GET /api/v2/trace/{id} (full
	// trace, x-unstable, 60 requests/minute) instead of searching indexed spans.
	Preview bool
	// From is the search window start (search mode). Default: 24 hours ago.
	From DatadogTime
	// To is the search window end (search mode). Default: now.
	To DatadogTime
}

// ListServicesOptions configure ListServicesWithOptions.
type ListServicesOptions struct {
	// Env is filter[env]; "*" = all environments. Default: the configured
	// Environment, else "*".
	Env string
}

// DatadogSpansQuery is the Datadog spans query client (SaaS API at
// https://api.<site>, headers DD-API-KEY and DD-APPLICATION-KEY). It implements
// TraceQueryClient on top of the spans search and aggregate APIs, plus the
// Datadog-specific SearchSpans, AggregateSpans and Ping. It is safe for
// concurrent use.
//
// Errors: 400/413/422 → VALIDATION, 401 → AUTHENTICATION, 403 →
// PERMISSION_DENIED (AUTHENTICATION from Ping), 404 → NOT_FOUND, 408 → TIMEOUT,
// 429 → RATE_LIMITED (waits Retry-After / X-RateLimit-Reset within the retry
// budget), 5xx → UNAVAILABLE.
type DatadogSpansQuery struct {
	cfg       Config
	transport *httpTransport
	now       func() time.Time
}

var _ TraceQueryClient = (*DatadogSpansQuery)(nil)

// rateLimitHint copies X-RateLimit-Reset (seconds until the window resets) into
// Retry-After on 429 responses without Retry-After, so the transport honours it.
type rateLimitHint struct{ next http.RoundTripper }

// RoundTrip implements http.RoundTripper.
func (r rateLimitHint) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := r.next.RoundTrip(req)
	if err == nil && resp.StatusCode == http.StatusTooManyRequests && strings.TrimSpace(resp.Header.Get("Retry-After")) == "" {
		if reset := strings.TrimSpace(resp.Header.Get("X-RateLimit-Reset")); reset != "" {
			resp.Header.Set("Retry-After", reset)
		}
	}
	return resp, err
}

// NewDatadogSpansQuery returns a query client. It is a CONFIG error unless both
// APIKey and AppKey are configured.
func NewDatadogSpansQuery(cfg Config) (*DatadogSpansQuery, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg = cfg.withServiceDefaults()
	if !cfg.HasQueryCredentials() {
		return nil, ConfigError("the Datadog query API needs apiKey (DD_API_KEY) and appKey (DD_APP_KEY)")
	}
	common := cfg.CommonConfig
	common.URL = cfg.APIURL
	t, err := newHTTPTransport(common)
	if err != nil {
		return nil, err
	}
	t.client.Transport = rateLimitHint{next: t.transport}
	t.addSecret(cfg.KnownSecrets()...)
	return &DatadogSpansQuery{cfg: cfg, transport: t, now: time.Now}, nil
}

// DatadogSpansQueryFromEnv builds a query client from the environment
// (DD_API_KEY, DD_APP_KEY, DD_SITE, …; see ConfigFromEnvWith).
func DatadogSpansQueryFromEnv() (*DatadogSpansQuery, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewDatadogSpansQuery(cfg)
}

// Config returns the effective configuration.
func (q *DatadogSpansQuery) Config() Config { return q.cfg }

func (q *DatadogSpansQuery) do(ctx context.Context, req *request) (*response, error) {
	h := http.Header{}
	h.Set("DD-API-KEY", q.cfg.APIKey.Reveal())
	h.Set("DD-APPLICATION-KEY", q.cfg.AppKey.Reveal())
	req.Header = h
	return q.transport.do(ctx, req)
}

func (q *DatadogSpansQuery) window(from, to DatadogTime, span time.Duration) (string, string) {
	now := q.now()
	if from.IsZero() {
		from = TimeAt(now.Add(-span))
	}
	if to.IsZero() {
		to = TimeAt(now)
	}
	return from.String(), to.String()
}

type searchResponse struct {
	Data []struct {
		ID         string         `json:"id"`
		Type       string         `json:"type"`
		Attributes map[string]any `json:"attributes"`
	} `json:"data"`
	Meta struct {
		Status    string                 `json:"status"`
		RequestID string                 `json:"request_id"`
		Page      struct{ After string } `json:"page"`
		Warnings  []DatadogQueryWarning  `json:"warnings"`
	} `json:"meta"`
}

func (q *DatadogSpansQuery) page(ctx context.Context, opts SearchSpansOptions, from, to, cursor string) (*DatadogSpansPage, error) {
	limit := opts.Limit
	if limit == 0 {
		limit = 10
	}
	if limit < 1 || limit > maxSpansPage {
		return nil, ConfigError("searchSpans: limit must be an integer between 1 and 1000")
	}
	page := map[string]any{"limit": limit}
	if cursor != "" {
		page["cursor"] = cursor
	}
	body := map[string]any{"data": map[string]any{
		"type": "search_request",
		"attributes": map[string]any{
			"filter":  map[string]any{"query": firstNonEmpty(opts.Query, "*"), "from": from, "to": to},
			"options": map[string]any{"timezone": firstNonEmpty(opts.Timezone, "UTC")},
			"page":    page,
			"sort":    firstNonEmpty(opts.Sort, "timestamp"),
		},
	}}
	res, err := q.do(ctx, &request{
		Method: http.MethodPost, Path: "/api/v2/spans/events/search", JSON: body, Idempotent: true, Operation: "searchSpans",
	})
	if err != nil {
		return nil, err
	}
	var doc searchResponse
	if len(res.Body) > 0 {
		if err := DecodeJSONPreservingNumbers(res.Body, &doc); err != nil {
			return nil, &Error{Code: ErrorCodeUnavailable, Message: "searchSpans: invalid JSON response", HTTPStatus: res.Status, RequestID: res.RequestID}
		}
	}
	out := &DatadogSpansPage{
		Spans:      make([]DatadogSpan, 0, len(doc.Data)),
		NextCursor: doc.Meta.Page.After,
		Status:     firstNonEmpty(doc.Meta.Status, "done"),
		Warnings:   doc.Meta.Warnings,
		RequestID:  firstNonEmpty(doc.Meta.RequestID, res.RequestID),
	}
	if out.Warnings == nil {
		out.Warnings = []DatadogQueryWarning{}
	}
	for _, item := range doc.Data {
		attrs := item.Attributes
		if attrs == nil {
			attrs = map[string]any{}
		}
		span, err := DatadogSearchSpanToSpan(attrs)
		if err != nil {
			return nil, err
		}
		out.Spans = append(out.Spans, DatadogSpan{ID: item.ID, Span: span, Raw: attrs})
	}
	return out, nil
}

// SearchSpansPage returns one page of POST /api/v2/spans/events/search. Pass
// NextCursor back as Cursor with the same absolute From/To for the next page.
// Status "timeout" or Warnings mark a partial result.
func (q *DatadogSpansQuery) SearchSpansPage(ctx context.Context, opts SearchSpansOptions) (*DatadogSpansPage, error) {
	from, to := q.window(opts.From, opts.To, defaultQueryWindow)
	return q.page(ctx, opts, from, to, opts.Cursor)
}

// SearchSpans iterates over every span matching the search, following the
// cursor page by page (same absolute window for every page). A TIMEOUT error is
// yielded when Datadog reports a timed-out query that returned no spans (never
// a silently empty result); iteration stops after the first error.
//
//	for s, err := range q.SearchSpans(ctx, datadogapm.SearchSpansOptions{Query: "service:checkout @grow.run:abc"}) {
//		if err != nil { return err }
//		fmt.Println(s.Span.Name, s.Span.DurationMicros)
//	}
func (q *DatadogSpansQuery) SearchSpans(ctx context.Context, opts SearchSpansOptions) iter.Seq2[DatadogSpan, error] {
	return func(yield func(DatadogSpan, error) bool) {
		from, to := q.window(opts.From, opts.To, defaultQueryWindow)
		cursor := opts.Cursor
		for {
			page, err := q.page(ctx, opts, from, to, cursor)
			if err == nil && page.Status == "timeout" && len(page.Spans) == 0 {
				err = &Error{Code: ErrorCodeTimeout, Message: "searchSpans: the Datadog query timed out without results", Retryable: true}
			}
			if err != nil {
				yield(DatadogSpan{}, err)
				return
			}
			for _, s := range page.Spans {
				if !yield(s, nil) {
					return
				}
			}
			if page.NextCursor == "" || page.NextCursor == cursor {
				return
			}
			cursor = page.NextCursor
		}
	}
}

type aggregateResponse struct {
	Data []struct {
		Attributes struct {
			By      map[string]any `json:"by"`
			Compute map[string]any `json:"compute"`
		} `json:"attributes"`
	} `json:"data"`
	Meta struct {
		Status    string                `json:"status"`
		RequestID string                `json:"request_id"`
		Warnings  []DatadogQueryWarning `json:"warnings"`
	} `json:"meta"`
}

func computeValue(v any) any {
	if items, ok := v.([]any); ok {
		points := make([]SpanTimeseriesPoint, 0, len(items))
		for _, it := range items {
			m := recordOf(it)
			p := SpanTimeseriesPoint{Time: strOf(m["time"])}
			if n, ok := numberOf(m["value"]); ok {
				p.Value = toFloat(n)
			}
			points = append(points, p)
		}
		return points
	}
	if n, ok := numberOf(v); ok {
		return toFloat(n)
	}
	return v
}

func toFloat(n any) float64 {
	if i, ok := n.(int64); ok {
		return float64(i)
	}
	f, _ := n.(float64)
	return f
}

// AggregateSpans calls POST /api/v2/spans/analytics/aggregate: computes compute
// (results keyed c0, c1, …) per group of groupBy (may be nil).
//
//	q.AggregateSpans(ctx, []datadogapm.SpanCompute{{Aggregation: "pc90", Metric: "@duration"}},
//		[]datadogapm.SpanGroupBy{{Facet: "service"}}, datadogapm.AggregateSpansOptions{})
func (q *DatadogSpansQuery) AggregateSpans(ctx context.Context, compute []SpanCompute, groupBy []SpanGroupBy, opts AggregateSpansOptions) (*SpanAggregateResult, error) {
	if len(compute) == 0 {
		return nil, ConfigError("aggregateSpans: compute must not be empty")
	}
	from, to := q.window(opts.From, opts.To, defaultQueryWindow)
	attributes := map[string]any{
		"compute": compute,
		"filter":  map[string]any{"query": firstNonEmpty(opts.Query, "*"), "from": from, "to": to},
		"options": map[string]any{"timezone": firstNonEmpty(opts.Timezone, "UTC")},
	}
	if len(groupBy) > 0 {
		attributes["group_by"] = groupBy
	}
	res, err := q.do(ctx, &request{
		Method: http.MethodPost, Path: "/api/v2/spans/analytics/aggregate", Idempotent: true, Operation: "aggregateSpans",
		JSON: map[string]any{"data": map[string]any{"type": "aggregate_request", "attributes": attributes}},
	})
	if err != nil {
		return nil, err
	}
	var doc aggregateResponse
	if len(res.Body) > 0 {
		if err := DecodeJSONPreservingNumbers(res.Body, &doc); err != nil {
			return nil, &Error{Code: ErrorCodeUnavailable, Message: "aggregateSpans: invalid JSON response", HTTPStatus: res.Status, RequestID: res.RequestID}
		}
	}
	out := &SpanAggregateResult{
		Buckets:   make([]SpanAggregateBucket, 0, len(doc.Data)),
		Status:    firstNonEmpty(doc.Meta.Status, "done"),
		Warnings:  doc.Meta.Warnings,
		RequestID: firstNonEmpty(doc.Meta.RequestID, res.RequestID),
	}
	if out.Warnings == nil {
		out.Warnings = []DatadogQueryWarning{}
	}
	for _, b := range doc.Data {
		by := map[string]any{}
		for k, v := range b.Attributes.By {
			if n, ok := numberOf(v); ok {
				by[k] = n
			} else {
				by[k] = v
			}
		}
		computes := map[string]any{}
		for k, v := range b.Attributes.Compute {
			computes[k] = computeValue(v)
		}
		out.Buckets = append(out.Buckets, SpanAggregateBucket{By: by, Computes: computes})
	}
	return out, nil
}

// GetTrace reads a trace by searching the indexed spans with trace_id:<id> over
// the last 24 hours (all pages, merged by span id) — only spans kept by
// retention filters, so the trace may be partial. It returns nil when nothing is
// found. See GetTraceWithOptions for the preview trace-by-id API.
func (q *DatadogSpansQuery) GetTrace(ctx context.Context, traceID string) (*Trace, error) {
	return q.GetTraceWithOptions(ctx, traceID, GetTraceOptions{})
}

// GetTraceWithOptions is GetTrace with a search window or, with Preview, the
// trace-by-id endpoint GET /api/v2/trace/{id} (full trace, preview API).
func (q *DatadogSpansQuery) GetTraceWithOptions(ctx context.Context, traceID string, opts GetTraceOptions) (*Trace, error) {
	id, err := NormalizeTraceID(traceID)
	if err != nil {
		return nil, err
	}
	if opts.Preview {
		return q.getTracePreview(ctx, id)
	}
	from, to := q.window(opts.From, opts.To, traceQueryWindow)
	ddID, _ := DatadogTraceIDString(id)
	bySpanID := map[string]Span{}
	search := SearchSpansOptions{Query: "trace_id:" + ddID, From: TimeExpr(from), To: TimeExpr(to), Limit: maxSpansPage}
	for s, err := range q.SearchSpans(ctx, search) {
		if err != nil {
			return nil, err
		}
		bySpanID[s.Span.SpanID] = s.Span
	}
	if len(bySpanID) == 0 {
		return nil, nil
	}
	return &Trace{TraceID: id, Spans: SortSpans(slices.Collect(maps.Values(bySpanID)))}, nil
}

func (q *DatadogSpansQuery) getTracePreview(ctx context.Context, id string) (*Trace, error) {
	pathID, _ := TraceIDForPath(id)
	res, err := q.do(ctx, &request{Method: http.MethodGet, Path: "/api/v2/trace/" + pathID, Operation: "getTrace"})
	if errors.Is(err, ErrNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(string(res.Body)) == "" {
		return nil, nil
	}
	var doc struct {
		Data struct {
			Attributes struct {
				Spans []map[string]any `json:"spans"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := DecodeJSONPreservingNumbers(res.Body, &doc); err != nil {
		return nil, &Error{Code: ErrorCodeUnavailable, Message: "getTrace: invalid JSON response", HTTPStatus: res.Status, RequestID: res.RequestID}
	}
	spans := make([]Span, 0, len(doc.Data.Attributes.Spans))
	for _, raw := range doc.Data.Attributes.Spans {
		s, err := DatadogTraceSpanToSpan(raw)
		if err != nil {
			return nil, err
		}
		spans = append(spans, s)
	}
	if len(spans) == 0 {
		return nil, nil
	}
	return &Trace{TraceID: id, Spans: SortSpans(spans)}, nil
}

// ListServices calls GET /api/v2/apm/services?filter[env]=… and returns the
// sorted names of the services that reported traces. The env filter is the
// configured Environment, else "*" (all).
func (q *DatadogSpansQuery) ListServices(ctx context.Context) ([]string, error) {
	return q.ListServicesWithOptions(ctx, ListServicesOptions{})
}

// ListServicesWithOptions is ListServices with an explicit env filter.
func (q *DatadogSpansQuery) ListServicesWithOptions(ctx context.Context, opts ListServicesOptions) ([]string, error) {
	res, err := q.do(ctx, &request{
		Method: http.MethodGet, Path: "/api/v2/apm/services", Operation: "listServices",
		Query: url.Values{"filter[env]": {firstNonEmpty(opts.Env, q.cfg.Environment, "*")}},
	})
	if err != nil {
		return nil, err
	}
	var doc struct {
		Data struct {
			Attributes struct {
				Services []any `json:"services"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if len(res.Body) > 0 {
		if err := res.decodeJSON("listServices", &doc); err != nil {
			return nil, err
		}
	}
	out := []string{}
	for _, s := range doc.Data.Attributes.Services {
		if name, ok := s.(string); ok {
			out = append(out, name)
		}
	}
	sort.Strings(out)
	return out, nil
}

// ListOperations returns the distinct span names (Datadog resource_name) of
// service in the last 24 hours, via AggregateSpans grouped by resource_name (up
// to 1000), sorted.
func (q *DatadogSpansQuery) ListOperations(ctx context.Context, service string) ([]string, error) {
	now := q.now()
	result, err := q.AggregateSpans(ctx,
		[]SpanCompute{{Aggregation: AggregationCount}},
		[]SpanGroupBy{{Facet: "resource_name", Limit: 1000}},
		AggregateSpansOptions{Query: "service:" + QuoteQueryValue(service), From: TimeAt(now.Add(-traceQueryWindow)), To: TimeAt(now)},
	)
	if err != nil {
		return nil, err
	}
	set := map[string]bool{}
	for _, b := range result.Buckets {
		if v := strOf(b.By["resource_name"]); v != "" {
			set[v] = true
		}
	}
	return slices.Sorted(maps.Keys(set)), nil
}

var plainQueryValue = regexp.MustCompile(`^[A-Za-z0-9_.\-/*@]+$`)

// QuoteQueryValue quotes a value for the Datadog query syntax when it contains
// spaces or special characters.
func QuoteQueryValue(value string) string {
	if plainQueryValue.MatchString(value) {
		return value
	}
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`)
	return `"` + r.Replace(value) + `"`
}

// TraceSearchQueryString builds the Datadog query string for a SPEC
// TraceSearchQuery: service:, resource_name:, @<tag>: and @duration:>= / <=
// (nanoseconds). An empty query is "*".
func TraceSearchQueryString(tq TraceSearchQuery) string {
	var parts []string
	if tq.Service != "" {
		parts = append(parts, "service:"+QuoteQueryValue(tq.Service))
	}
	if tq.Operation != "" {
		parts = append(parts, "resource_name:"+QuoteQueryValue(tq.Operation))
	}
	for _, k := range slices.Sorted(maps.Keys(tq.Tags)) {
		parts = append(parts, "@"+k+":"+QuoteQueryValue(tq.Tags[k]))
	}
	if tq.MinDuration > 0 {
		parts = append(parts, "@duration:>="+strconv.FormatInt(tq.MinDuration.Microseconds()*1000, 10))
	}
	if tq.MaxDuration > 0 {
		parts = append(parts, "@duration:<="+strconv.FormatInt(tq.MaxDuration.Microseconds()*1000, 10))
	}
	if len(parts) == 0 {
		return "*"
	}
	return strings.Join(parts, " ")
}

// SearchTraces finds traces with spans matching tq (service, span name, @tag
// values, duration) in Start..End (default: the last 15 minutes), newest first,
// up to Limit (default 20) distinct trace ids. Summaries are built from the
// matching spans only.
func (q *DatadogSpansQuery) SearchTraces(ctx context.Context, tq TraceSearchQuery) ([]TraceSummary, error) {
	tq = tq.WithDefaults()
	var from, to DatadogTime
	if !tq.Start.IsZero() {
		from = TimeAt(tq.Start)
	}
	if !tq.End.IsZero() {
		to = TimeAt(tq.End)
	}
	fromS, toS := q.window(from, to, defaultQueryWindow)
	pageLimit := min(maxSpansPage, max(tq.Limit*10, 100))
	traces := map[string][]Span{}
	var order []string
	cursor := ""
	for page := 0; page < searchTracesMaxPage && len(traces) < tq.Limit; page++ {
		res, err := q.page(ctx, SearchSpansOptions{Query: TraceSearchQueryString(tq), Limit: pageLimit, Sort: "-timestamp"}, fromS, toS, cursor)
		if err != nil {
			return nil, err
		}
		for _, s := range res.Spans {
			if list, ok := traces[s.Span.TraceID]; ok {
				traces[s.Span.TraceID] = append(list, s.Span)
			} else if len(traces) < tq.Limit {
				traces[s.Span.TraceID] = []Span{s.Span}
				order = append(order, s.Span.TraceID)
			}
		}
		if res.NextCursor == "" {
			break
		}
		cursor = res.NextCursor
	}
	out := make([]TraceSummary, 0, len(order))
	for _, id := range order {
		spans := SortSpans(traces[id])
		ids := map[string]bool{}
		for _, s := range spans {
			ids[s.SpanID] = true
		}
		root := spans[0]
		for _, s := range spans {
			if s.ParentSpanID == "" || !ids[s.ParentSpanID] {
				root = s
				break
			}
		}
		start, end := spans[0].StartMicros, spans[0].EndMicros
		for _, s := range spans {
			start = min(start, s.StartMicros)
			end = max(end, s.EndMicros)
		}
		out = append(out, TraceSummary{
			TraceID: id, RootServiceName: root.ServiceName, RootName: root.Name,
			StartMicros: start, DurationMicros: end - start, SpanCount: len(spans),
		})
	}
	return out, nil
}

// GetDependencies is UNSUPPORTED: Datadog's public API offers no service
// dependency graph.
func (q *DatadogSpansQuery) GetDependencies(context.Context, DependencyOptions) ([]DependencyLink, error) {
	return nil, UnsupportedError("traces.getDependencies", "the Datadog public API has no service dependency endpoint")
}

// WaitForTrace polls GetTrace until the trace has MinSpans spans (default
// timeout 30 s, interval 2 s because Datadog allows 50 searches per minute).
// TIMEOUT when it does not appear in time.
func (q *DatadogSpansQuery) WaitForTrace(ctx context.Context, traceID string, opts WaitForTraceOptions) (*Trace, error) {
	if opts.Interval <= 0 {
		opts.Interval = DatadogWaitInterval
	}
	return WaitForTrace(ctx, q, traceID, opts)
}

// Ping makes an authenticated round trip GET /api/v2/validate_keys (API key +
// application key) and returns its latency. Invalid keys → AUTHENTICATION
// (Datadog answers 401 or 403).
func (q *DatadogSpansQuery) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	_, err := q.do(ctx, &request{Method: http.MethodGet, Path: "/api/v2/validate_keys", Operation: "ping"})
	var e *Error
	if errors.As(err, &e) && e.Code == ErrorCodePermissionDenied {
		out := *e
		out.Code = ErrorCodeAuthentication
		out.Retryable = false
		out.Err = e.Err
		return 0, &out
	}
	if err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// Close releases connections. It is idempotent.
func (q *DatadogSpansQuery) Close() error {
	q.transport.close()
	return nil
}
