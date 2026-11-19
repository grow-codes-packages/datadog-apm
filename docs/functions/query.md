# Spans query

`DatadogSpansQuery` reads spans back from the **Datadog API** at `https://api.<site>` (the
`site` setting, default `datadoghq.com`). It implements the shared `TraceQueryClient` interface,
so code written for the other Grow Codes tracing SDKs works unchanged, and adds the Datadog spans
search and analytics endpoints.

It needs an **API key** (`DD_API_KEY`) and an **application key** with the `apm_read` scope
(`DD_APP_KEY` / `DD_APPLICATION_KEY`). Both are sent as `DD-API-KEY` / `DD-APPLICATION-KEY` and are
always redacted. Without them the constructor (and `client.spans`) raises `CONFIG`.

!!! warning "Indexed spans only"
    The spans API only sees **indexed** spans (kept by a retention filter) and indexing takes from
    seconds to about a minute. `getTrace` therefore may return part of a trace; use
    `waitForTrace` in tests and keep a retention filter for the services you query.

| Function | Datadog endpoint | Description |
| - | - | - |
| `searchSpans({query, from, to, limit, sort})` | `POST /api/v2/spans/events/search` | lazy iterator over every matching span; follows the cursor; the time window is fixed on the first page (default: the last 15 minutes, `-timestamp`) |
| `searchSpansPage(opts)` | same | one page: `spans`, `nextCursor`, `warnings`, `requestId`; `limit` 1–1000 |
| `aggregateSpans(compute, groupBy, {query, from, to})` | `POST /api/v2/spans/analytics/aggregate` | `count`, `cardinality`, `pc75`–`pc99`, `sum`, `min`, `max`, `avg`, `median`; `total` or `timeseries` |
| `getTrace(traceId)` | spans search `trace_id:<id>` | all pages merged, sorted; `null` when nothing is indexed; window: the last 24 hours |
| `getTrace(traceId, {preview: true})` | `GET /api/v2/trace/{id}` | the full trace from Datadog's preview trace-by-id API (unstable, 60 requests/minute) |
| `searchTraces(query)` | spans search | summaries of distinct traces by `service`, `operation`, `tags`, `minDuration`, `maxDuration`, `start`, `end`, `limit` (20); at most 5 pages |
| `listServices({env})` | `GET /api/v2/apm/services` | sorted service names; `env` defaults to the configured environment, else all |
| `listOperations(service)` | span analytics grouped by `resource_name` | sorted resource names of a service (last 15 minutes) |
| `waitForTrace(traceId, {timeout, interval, minSpans})` | `getTrace` in a loop | default timeout 30 s, interval **2 s** (Datadog allows 50 searches per minute); `TIMEOUT` when not found |
| `getDependencies(range)` | — | always `UNSUPPORTED`: Datadog has no public service dependency API |
| `ping()` | `GET /api/v2/validate_keys` | validates both keys, returns the latency; 401 and 403 → `AUTHENTICATION` |
| `quoteQueryValue(v)` / `traceSearchQueryString(q)` | — | Datadog query syntax: quote a value / build the query of a `TraceSearchQuery` (tags sorted, durations in ns, `*` when empty) |
| `close()` | — | releases connections; later calls raise `CANCELLED` |

Times (`from`, `to`) accept an absolute time, epoch milliseconds or a Datadog expression such as
`now-1h` (TypeScript `Date | number | string`; Rust `DatadogTime` from `SystemTime` or `&str`;
Go `TimeAt(t)` / `TimeExpr("now-1h")`; Python `datetime | int | str`; Java `DatadogTime.at(…)`,
`epochMillis(…)`, `expr(…)`). Spans come back in the normalized model (`Span`, ids in hex), with the
raw Datadog attributes next to them.

A `429` waits for `Retry-After` or `X-RateLimit-Reset` (within `totalTimeout`) before it raises
`RATE_LIMITED`; `400` is `VALIDATION` with Datadog's error text, `403` on queries is
`PERMISSION_DENIED` (missing `apm_read`).

=== "TypeScript"

    ```ts
    import { DatadogSpansQuery } from "@grow-codes/datadog-apm";

    const query = DatadogSpansQuery.fromEnv();           // or client.spans
    for await (const s of query.searchSpans({ query: "service:checkout @http.status_code:500", from: "now-1h", limit: 50 })) {
      console.log(s.span.name, s.span.durationMicros);
    }
    const p90 = await query.aggregateSpans(
      [{ aggregation: "pc90", metric: "@duration" }],
      [{ facet: "resource_name", limit: 10 }],
      { query: "service:checkout" },
    );
    const trace = await query.getTrace(traceId);         // or { preview: true }
    const recent = await query.searchTraces({ service: "checkout", limit: 10 });
    console.log(await query.listServices(), await query.listOperations("checkout"));
    const waited = await query.waitForTrace(traceId, { timeoutMs: 120_000 });
    await query.close();
    ```

=== "Rust"

    ```rust
    use futures::TryStreamExt;
    use grow_datadog_apm::tracing::{TraceQueryClient as _, TraceSearchQuery, WaitForTraceOptions};
    use grow_datadog_apm::{AggregateSpansOptions, DatadogSpansQuery, GetTraceOptions, SearchSpansOptions, SpanCompute, SpanGroupBy};

    let query = DatadogSpansQuery::from_env()?;          // or client.spans()?
    let spans: Vec<_> = query
        .search_spans(SearchSpansOptions::query("service:checkout").from("now-1h").limit(50))
        .try_collect()
        .await?;
    let p90 = query
        .aggregate_spans(
            &[SpanCompute::new("pc90").metric("@duration")],
            &[SpanGroupBy::facet("resource_name").limit(10)],
            &AggregateSpansOptions { query: Some("service:checkout".into()), ..Default::default() },
        )
        .await?;
    let trace = query.get_trace(&trace_id).await?;
    let full = query
        .get_trace_with_options(&trace_id, &GetTraceOptions { preview: true, ..Default::default() })
        .await?;
    let recent = query.search_traces(&TraceSearchQuery::service("checkout").limit(10)).await?;
    let services = query.list_services().await?;
    let waited = query.wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
    query.close();
    ```

=== "Go"

    ```go
    query, err := datadogapm.DatadogSpansQueryFromEnv() // or client.Spans()
    if err != nil {
        return err
    }
    defer query.Close()
    for s, err := range query.SearchSpans(ctx, datadogapm.SearchSpansOptions{
        Query: "service:checkout", From: datadogapm.TimeExpr("now-1h"), Limit: 50,
    }) {
        if err != nil {
            return err
        }
        fmt.Println(s.Span.Name, s.Span.DurationMicros)
    }
    p90, err := query.AggregateSpans(ctx,
        []datadogapm.SpanCompute{{Aggregation: datadogapm.AggregationPc90, Metric: "@duration"}},
        []datadogapm.SpanGroupBy{{Facet: "resource_name", Limit: 10}},
        datadogapm.AggregateSpansOptions{Query: "service:checkout"})
    trace, err := query.GetTrace(ctx, traceID)
    full, err := query.GetTraceWithOptions(ctx, traceID, datadogapm.GetTraceOptions{Preview: true})
    recent, err := query.SearchTraces(ctx, datadogapm.TraceSearchQuery{Service: "checkout", Limit: 10})
    services, err := query.ListServices(ctx)
    waited, err := query.WaitForTrace(ctx, traceID, datadogapm.WaitForTraceOptions{Timeout: 2 * time.Minute})
    ```

=== "Python"

    ```python
    from grow_datadog_apm import DatadogSpansQuery, SpanCompute, SpanGroupBy, TraceSearchQuery

    with DatadogSpansQuery.from_env() as query:          # or client.spans
        for s in query.search_spans(query="service:checkout", from_="now-1h", limit=50):
            print(s.span.name, s.span.duration_micros)
        p90 = query.aggregate_spans(
            [SpanCompute("pc90", "@duration")],
            [SpanGroupBy("resource_name", limit=10)],
            query="service:checkout",
        )
        trace = query.get_trace(trace_id)                # or preview=True
        recent = query.search_traces(TraceSearchQuery(service="checkout", limit=10))
        print(query.list_services(), query.list_operations("checkout"))
        waited = query.wait_for_trace(trace_id, timeout_s=120)
    ```

    `AsyncDatadogSpansQuery` has the same methods as coroutines (`async for` over
    `search_spans`).

=== "Java"

    ```java
    try (DatadogSpansQuery query = DatadogSpansQuery.fromEnv()) { // or client.spans()
        for (DatadogSpan s : query.searchSpans(SearchSpansOptions.builder()
                .query("service:checkout").from(DatadogTime.expr("now-1h")).limit(50).build())) {
            System.out.println(s.span().name() + " " + s.span().durationMicros());
        }
        SpanAggregateResult p90 = query.aggregateSpans(
            List.of(SpanCompute.of("pc90", "@duration")),
            List.of(SpanGroupBy.of("resource_name", 10)),
            AggregateSpansOptions.of("service:checkout", null, null));
        Optional<Trace> trace = query.getTrace(traceId);
        Optional<Trace> full = query.getTrace(traceId, GetTraceOptions.previewApi());
        List<TraceSummary> recent = query.searchTraces(TraceSearchQuery.forService("checkout"));
        List<String> services = query.listServices();
        Trace waited = query.waitForTrace(traceId);
    }
    ```

    Every call has an `…Async` variant returning a `CompletableFuture`.

## Mapping to the normalized model

| Datadog search span | `Span` |
| - | - |
| `attributes.trace_id` (decimal 64-bit or 32 hex) | `traceId` (32 hex) |
| `attributes.span_id` / `parent_id` (decimal) | `spanId` / `parentSpanId` (16 hex; `0` = root); a missing or `0` span id is `VALIDATION` |
| `resource_name`, else `operation_name` | `name` (both also kept as attributes) |
| `start_timestamp` / `end_timestamp` (ISO-8601) | `startMicros` / `endMicros` / `durationMicros` |
| `attributes.error` / `status` | `status` (`error` with `error.message` / `error.msg`, `ok` for `otel.status_code=Ok`) |
| `span.kind` | `kind` |
| `service` | `serviceName` |

Large integers (ids, nanosecond times above 2^53) are parsed without loss in every language
(TypeScript `parseJsonPreservingBigInts`, Go `DecodeJSONPreservingNumbers`).
