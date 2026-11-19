package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.DatadogApmException;
import codes.grow.packages.datadogapm.core.ErrorCode;
import codes.grow.packages.datadogapm.core.HttpTransport;
import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.core.PermissionDeniedException;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.core.TimeoutException;
import codes.grow.packages.datadogapm.core.TransportRequest;
import codes.grow.packages.datadogapm.core.TransportResponse;
import codes.grow.packages.datadogapm.core.UnavailableException;
import codes.grow.packages.datadogapm.core.UnsupportedException;
import codes.grow.packages.datadogapm.core.ValidationException;
import codes.grow.packages.datadogapm.tracing.query.DependencyLink;
import codes.grow.packages.datadogapm.tracing.query.Span;
import codes.grow.packages.datadogapm.tracing.query.Trace;
import codes.grow.packages.datadogapm.tracing.query.TraceIds;
import codes.grow.packages.datadogapm.tracing.query.TraceQueryClient;
import codes.grow.packages.datadogapm.tracing.query.TraceSearchQuery;
import codes.grow.packages.datadogapm.tracing.query.TraceSummary;
import codes.grow.packages.datadogapm.tracing.query.Traces;
import codes.grow.packages.datadogapm.tracing.query.WaitForTraceOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The Datadog spans query client: the SaaS API at {@code https://api.<site>} with the headers
 * {@code DD-API-KEY} and {@code DD-APPLICATION-KEY} (the application key needs {@code apm_read}).
 * It implements {@link TraceQueryClient} on top of the spans search and aggregate APIs, plus the
 * Datadog-specific {@link #searchSpans}, {@link #aggregateSpans} and {@link #ping}. Thread-safe. No
 * container can serve these endpoints, so they are unit-tested against Datadog's documented
 * examples and verified by the live sandbox job (API.md section 4).
 *
 * <p>Errors: 400/413/422 {@code VALIDATION}, 401 {@code AUTHENTICATION}, 403 {@code
 * PERMISSION_DENIED} ({@code AUTHENTICATION} from {@link #ping}), 404 {@code NOT_FOUND}, 408 {@code
 * TIMEOUT}, 429 {@code RATE_LIMITED} (waits {@code Retry-After} / {@code X-RateLimit-Reset} within
 * the retry budget), 5xx {@code UNAVAILABLE}.
 *
 * <pre>{@code
 * try (DatadogSpansQuery q = DatadogSpansQuery.fromEnv()) {
 *   for (DatadogSpan s : q.searchSpans(SearchSpansOptions.builder().query("service:checkout").build())) {
 *     System.out.println(s.span().name());
 *   }
 * }
 * }</pre>
 */
public final class DatadogSpansQuery implements TraceQueryClient, AutoCloseable {
  /** Default polling interval of {@link #waitForTrace(String)} (Datadog allows 50 searches/min). */
  public static final Duration DATADOG_WAIT_INTERVAL = Duration.ofSeconds(2);

  static final Duration DEFAULT_QUERY_WINDOW = Duration.ofMinutes(15);
  static final Duration TRACE_QUERY_WINDOW = Duration.ofHours(24);
  static final int MAX_SPANS_PAGE = 1000;
  static final int SEARCH_TRACES_MAX_PAGES = 5;
  private static final Pattern PLAIN_QUERY_VALUE = Pattern.compile("^[A-Za-z0-9_.\\-/*@]+$");

  private final DatadogApmConfig config;
  private final HttpTransport transport;
  private final Clock clock;

  DatadogSpansQuery(DatadogApmConfig config, Clock clock) {
    config.validate();
    if (!config.hasQueryCredentials()) {
      throw new ConfigException(
          "the Datadog query API needs apiKey (DD_API_KEY) and appKey (DD_APP_KEY)");
    }
    this.config = config;
    this.clock = clock;
    this.transport = new HttpTransport(config.queryTransportConfig());
  }

  /**
   * Creates a query client.
   *
   * @param config configuration with {@code apiKey} and {@code appKey}
   * @return client
   * @throws ConfigException unless both keys are configured
   */
  public static DatadogSpansQuery create(DatadogApmConfig config) {
    return new DatadogSpansQuery(config, Clock.systemUTC());
  }

  /**
   * Creates a query client from the environment ({@code DD_API_KEY}, {@code DD_APP_KEY}, {@code
   * DD_SITE}, ...; see {@link DatadogApmConfig}).
   *
   * @return client
   */
  public static DatadogSpansQuery fromEnv() {
    return create(DatadogApmConfig.fromEnv());
  }

  /**
   * The effective configuration.
   *
   * @return configuration
   */
  public DatadogApmConfig config() {
    return config;
  }

  // ---------------------------------------------------------------- transport

  private CompletableFuture<TransportResponse> send(TransportRequest.Builder b, int... accept) {
    b.header("DD-API-KEY", config.apiKey().orElseThrow().reveal())
        .header("DD-APPLICATION-KEY", config.appKey().orElseThrow().reveal())
        .acceptStatus(429)
        .acceptStatus(accept);
    TransportRequest request;
    try {
      request = b.build();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return attempt(request, 1);
  }

  /** Sends once; on 429 waits Retry-After or X-RateLimit-Reset (seconds) within the budget. */
  private CompletableFuture<TransportResponse> attempt(TransportRequest request, int n) {
    CompletableFuture<TransportResponse> sent;
    try {
      sent = transport.sendAsync(request);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return sent.thenCompose(
        r -> {
          if (r.status() != 429) {
            return CompletableFuture.completedFuture(r);
          }
          RetryPolicy retry = config.retry();
          Instant now = clock.instant();
          Optional<Duration> hinted =
              RetryPolicy.parseRetryAfter(r.header("Retry-After").orElse(null), now)
                  .or(
                      () ->
                          RetryPolicy.parseRetryAfter(
                              r.header("X-RateLimit-Reset").orElse(null), now));
          DatadogApmException error =
              DatadogApmException.of(
                  ErrorCode.RATE_LIMITED,
                  HttpTransport.errorMessage(
                      request.operation(), 429, r.headers(), r.body(), config.knownSecrets()),
                  429,
                  r.requestId().orElse(null),
                  hinted.orElse(null),
                  null);
          Duration wait = hinted.orElseGet(() -> retry.backoff(n - 1));
          if (n > retry.maxRetries() || wait.compareTo(retry.maxRetryAfter()) > 0) {
            return CompletableFuture.failedFuture(error);
          }
          return CompletableFuture.supplyAsync(
                  () -> null,
                  CompletableFuture.delayedExecutor(wait.toNanos(), TimeUnit.NANOSECONDS))
              .thenCompose(ignored -> attempt(request, n + 1));
        });
  }

  private static JsonNode body(TransportResponse r, String operation) {
    if (r.body().length == 0) {
      return MissingNode.getInstance();
    }
    try {
      return Json.tree(r.body());
    } catch (ValidationException e) {
      throw new UnavailableException(
          operation + ": invalid JSON response", r.status(), r.requestId().orElse(null), null, e);
    }
  }

  private String[] window(
      Optional<DatadogTime> from, Optional<DatadogTime> to, Duration defaultSpan) {
    Instant now = clock.instant();
    return new String[] {
      from.orElseGet(() -> DatadogTime.at(now.minus(defaultSpan))).toString(),
      to.orElseGet(() -> DatadogTime.at(now)).toString()
    };
  }

  private static <T> T await(CompletableFuture<T> f, String operation) {
    return HttpTransport.await(f, operation);
  }

  private static List<DatadogQueryWarning> warnings(JsonNode meta) {
    List<DatadogQueryWarning> out = new ArrayList<>();
    JsonNode w = meta.path("warnings");
    if (w.isArray()) {
      for (JsonNode it : w) {
        out.add(
            new DatadogQueryWarning(
                text(it.get("code")), text(it.get("title")), text(it.get("detail"))));
      }
    }
    return out;
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }

  private static Optional<String> nonEmpty(String s) {
    return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s);
  }

  // ---------------------------------------------------------------- search

  private CompletableFuture<DatadogSpansPage> page(
      SearchSpansOptions opts, String from, String to, String cursor) {
    int limit = opts.limit();
    if (limit < 1 || limit > MAX_SPANS_PAGE) {
      return CompletableFuture.failedFuture(
          new ConfigException("searchSpans: limit must be an integer between 1 and 1000"));
    }
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("limit", limit);
    if (cursor != null) {
      page.put("cursor", cursor);
    }
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("query", opts.query());
    filter.put("from", from);
    filter.put("to", to);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("filter", filter);
    attributes.put("options", Map.of("timezone", opts.timezone()));
    attributes.put("page", page);
    attributes.put("sort", opts.sort());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "search_request");
    data.put("attributes", attributes);
    return send(TransportRequest.post("/api/v2/spans/events/search")
            .json(Map.of("data", data))
            .idempotent(true)
            .operation("searchSpans"))
        .thenApply(r -> parsePage(r));
  }

  private static DatadogSpansPage parsePage(TransportResponse r) {
    JsonNode doc = body(r, "searchSpans");
    JsonNode meta = doc.path("meta");
    List<DatadogSpan> spans = new ArrayList<>();
    JsonNode items = doc.path("data");
    if (items.isArray()) {
      for (JsonNode item : items) {
        JsonNode attrs = item.path("attributes");
        if (!attrs.isObject()) {
          attrs = Json.mapper().createObjectNode();
        }
        spans.add(
            new DatadogSpan(text(item.get("id")), DatadogMapping.searchSpanToSpan(attrs), attrs));
      }
    }
    String status = text(meta.get("status"));
    String reqId = text(meta.get("request_id"));
    return new DatadogSpansPage(
        spans,
        nonEmpty(text(meta.path("page").get("after"))),
        status == null || status.isEmpty() ? "done" : status,
        warnings(meta),
        nonEmpty(reqId).or(r::requestId));
  }

  /**
   * Returns one page of {@code POST /api/v2/spans/events/search}. Pass {@link
   * DatadogSpansPage#nextCursor()} back as the cursor with the same absolute window for the next
   * page. Status {@code timeout} or warnings mark a partial result.
   *
   * @param options search options
   * @return the page
   */
  public DatadogSpansPage searchSpansPage(SearchSpansOptions options) {
    return await(searchSpansPageAsync(options), "searchSpans");
  }

  /**
   * Asynchronous {@link #searchSpansPage}.
   *
   * @param options search options
   * @return future page
   */
  public CompletableFuture<DatadogSpansPage> searchSpansPageAsync(SearchSpansOptions options) {
    String[] w = window(options.from(), options.to(), DEFAULT_QUERY_WINDOW);
    return page(options, w[0], w[1], options.cursor().orElse(null));
  }

  private static DatadogApmException timedOut() {
    return new TimeoutException("searchSpans: the Datadog query timed out without results");
  }

  /**
   * Iterates over every span matching the search, following the cursor page by page (same absolute
   * window for every page; pages are fetched lazily). A {@link TimeoutException} is thrown when
   * Datadog reports a timed-out query that returned no spans (never a silently empty result).
   *
   * <pre>{@code
   * for (DatadogSpan s : q.searchSpans(SearchSpansOptions.builder().query("@grow.run:abc").build())) {
   *   System.out.println(s.span().name() + " " + s.span().durationMicros());
   * }
   * }</pre>
   *
   * @param options search options
   * @return lazy iterable (each {@code iterator()} starts a new search)
   */
  public Iterable<DatadogSpan> searchSpans(SearchSpansOptions options) {
    return () -> new SpanIterator(options);
  }

  /**
   * Asynchronous {@link #searchSpans}: every page collected into one list.
   *
   * @param options search options
   * @return future spans
   */
  public CompletableFuture<List<DatadogSpan>> searchSpansAsync(SearchSpansOptions options) {
    String[] w = window(options.from(), options.to(), DEFAULT_QUERY_WINDOW);
    List<DatadogSpan> out = new ArrayList<>();
    return collect(options, w[0], w[1], options.cursor().orElse(null), out).thenApply(v -> out);
  }

  private CompletableFuture<Void> collect(
      SearchSpansOptions opts, String from, String to, String cursor, List<DatadogSpan> out) {
    return page(opts, from, to, cursor)
        .thenCompose(
            p -> {
              if (p.status().equals("timeout") && p.spans().isEmpty()) {
                throw timedOut();
              }
              out.addAll(p.spans());
              String next = p.nextCursor().orElse(null);
              if (next == null || next.equals(cursor)) {
                return CompletableFuture.completedFuture(null);
              }
              return collect(opts, from, to, next, out);
            });
  }

  /** Lazy cursor iterator of {@link #searchSpans}. */
  private final class SpanIterator implements Iterator<DatadogSpan> {
    private final SearchSpansOptions opts;
    private final String from;
    private final String to;
    private String cursor;
    private Iterator<DatadogSpan> current = List.<DatadogSpan>of().iterator();
    private boolean done;

    SpanIterator(SearchSpansOptions opts) {
      this.opts = opts;
      String[] w = window(opts.from(), opts.to(), DEFAULT_QUERY_WINDOW);
      this.from = w[0];
      this.to = w[1];
      this.cursor = opts.cursor().orElse(null);
    }

    @Override
    public boolean hasNext() {
      while (!current.hasNext()) {
        if (done) {
          return false;
        }
        DatadogSpansPage p = await(page(opts, from, to, cursor), "searchSpans");
        if (p.status().equals("timeout") && p.spans().isEmpty()) {
          done = true;
          throw timedOut();
        }
        String next = p.nextCursor().orElse(null);
        done = next == null || next.equals(cursor);
        cursor = next;
        current = p.spans().iterator();
      }
      return true;
    }

    @Override
    public DatadogSpan next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return current.next();
    }
  }

  // ---------------------------------------------------------------- aggregate

  /**
   * {@link #aggregateSpans(List, List, AggregateSpansOptions)} over all spans of the last 15
   * minutes.
   *
   * @param compute computations (results keyed {@code c0}, {@code c1}, ...)
   * @param groupBy group-by facets (may be empty or {@code null})
   * @return result
   */
  public SpanAggregateResult aggregateSpans(List<SpanCompute> compute, List<SpanGroupBy> groupBy) {
    return aggregateSpans(compute, groupBy, AggregateSpansOptions.defaults());
  }

  /**
   * Calls {@code POST /api/v2/spans/analytics/aggregate}: computes {@code compute} per group of
   * {@code groupBy}.
   *
   * <pre>{@code
   * q.aggregateSpans(List.of(SpanCompute.of("pc90", "@duration")), List.of(SpanGroupBy.of("service")),
   *     AggregateSpansOptions.of("env:prod", null, null));
   * }</pre>
   *
   * @param compute computations (results keyed {@code c0}, {@code c1}, ...)
   * @param groupBy group-by facets (may be empty or {@code null})
   * @param options filter
   * @return result
   * @throws ConfigException when {@code compute} is empty
   */
  public SpanAggregateResult aggregateSpans(
      List<SpanCompute> compute, List<SpanGroupBy> groupBy, AggregateSpansOptions options) {
    return await(aggregateSpansAsync(compute, groupBy, options), "aggregateSpans");
  }

  /**
   * Asynchronous {@link #aggregateSpans(List, List, AggregateSpansOptions)}.
   *
   * @param compute computations
   * @param groupBy group-by facets
   * @param options filter
   * @return future result
   */
  public CompletableFuture<SpanAggregateResult> aggregateSpansAsync(
      List<SpanCompute> compute, List<SpanGroupBy> groupBy, AggregateSpansOptions options) {
    if (compute == null || compute.isEmpty()) {
      return CompletableFuture.failedFuture(
          new ConfigException("aggregateSpans: compute must not be empty"));
    }
    String[] w = window(options.from(), options.to(), DEFAULT_QUERY_WINDOW);
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("query", options.query());
    filter.put("from", w[0]);
    filter.put("to", w[1]);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("compute", compute.stream().map(SpanCompute::toJson).toList());
    attributes.put("filter", filter);
    attributes.put("options", Map.of("timezone", options.timezone()));
    if (groupBy != null && !groupBy.isEmpty()) {
      attributes.put("group_by", groupBy.stream().map(SpanGroupBy::toJson).toList());
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", "aggregate_request");
    data.put("attributes", attributes);
    return send(TransportRequest.post("/api/v2/spans/analytics/aggregate")
            .json(Map.of("data", data))
            .idempotent(true)
            .operation("aggregateSpans"))
        .thenApply(DatadogSpansQuery::parseAggregate);
  }

  private static SpanAggregateResult parseAggregate(TransportResponse r) {
    JsonNode doc = body(r, "aggregateSpans");
    JsonNode meta = doc.path("meta");
    List<SpanAggregateBucket> buckets = new ArrayList<>();
    JsonNode data = doc.path("data");
    if (data.isArray()) {
      for (JsonNode b : data) {
        Map<String, Object> by = new LinkedHashMap<>();
        b.path("attributes")
            .path("by")
            .properties()
            .forEach(
                e -> {
                  Object v = DatadogMapping.attrValue(e.getValue());
                  if (v != null) {
                    by.put(e.getKey(), v);
                  }
                });
        Map<String, Object> computes = new LinkedHashMap<>();
        b.path("attributes")
            .path("compute")
            .properties()
            .forEach(
                e -> {
                  Object v = computeValue(e.getValue());
                  if (v != null) {
                    computes.put(e.getKey(), v);
                  }
                });
        buckets.add(new SpanAggregateBucket(by, computes));
      }
    }
    String status = text(meta.get("status"));
    return new SpanAggregateResult(
        buckets,
        status == null || status.isEmpty() ? "done" : status,
        warnings(meta),
        nonEmpty(text(meta.get("request_id"))).or(r::requestId));
  }

  private static Object computeValue(JsonNode v) {
    if (v.isArray()) {
      List<SpanTimeseriesPoint> points = new ArrayList<>();
      for (JsonNode it : v) {
        JsonNode value = it.path("value");
        points.add(
            new SpanTimeseriesPoint(
                DatadogMapping.str(it.get("time")), value.isNumber() ? value.doubleValue() : 0));
      }
      return points;
    }
    if (v.isNumber()) {
      return v.doubleValue();
    }
    return DatadogMapping.attrValue(v);
  }

  // ---------------------------------------------------------------- traces

  /**
   * Reads a trace by searching the indexed spans with {@code trace_id:<id>} over the last 24 hours
   * (all pages, merged by span id): only spans kept by retention filters, so the trace may be
   * partial. See {@link #getTrace(String, GetTraceOptions)} for the preview trace-by-id API.
   *
   * @param traceId trace id (hex, 64- or 128-bit)
   * @return the trace, or empty when nothing is found
   */
  @Override
  public Optional<Trace> getTrace(String traceId) {
    return getTrace(traceId, GetTraceOptions.defaults());
  }

  /**
   * {@link #getTrace(String)} with a search window or, with {@code preview}, the trace-by-id
   * endpoint {@code GET /api/v2/trace/{id}} (full trace, preview API; 404 gives empty).
   *
   * @param traceId trace id
   * @param options options
   * @return the trace, or empty
   */
  public Optional<Trace> getTrace(String traceId, GetTraceOptions options) {
    return await(getTraceAsync(traceId, options), "getTrace");
  }

  @Override
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId) {
    return getTraceAsync(traceId, GetTraceOptions.defaults());
  }

  /**
   * Asynchronous {@link #getTrace(String, GetTraceOptions)}.
   *
   * @param traceId trace id
   * @param options options
   * @return future trace
   */
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId, GetTraceOptions options) {
    String id;
    try {
      id = TraceIds.normalizeTraceId(traceId);
    } catch (ValidationException e) {
      return CompletableFuture.failedFuture(e);
    }
    if (options.preview()) {
      return getTracePreview(id);
    }
    String[] w = window(options.from(), options.to(), TRACE_QUERY_WINDOW);
    SearchSpansOptions search =
        SearchSpansOptions.builder()
            .query("trace_id:" + DatadogIds.datadogTraceIdString(id))
            .limit(MAX_SPANS_PAGE)
            .build();
    List<DatadogSpan> out = new ArrayList<>();
    return collect(search, w[0], w[1], null, out)
        .thenApply(
            v -> {
              Map<String, Span> bySpanId = new LinkedHashMap<>();
              for (DatadogSpan s : out) {
                bySpanId.put(s.span().spanId(), s.span());
              }
              return bySpanId.isEmpty()
                  ? Optional.<Trace>empty()
                  : Optional.of(new Trace(id, new ArrayList<>(bySpanId.values())));
            });
  }

  private CompletableFuture<Optional<Trace>> getTracePreview(String id) {
    return send(
            TransportRequest.get("/api/v2/trace/" + DatadogIds.traceIdForPath(id))
                .operation("getTrace"),
            404)
        .thenApply(
            r -> {
              if (r.status() == 404 || r.text().isBlank()) {
                return Optional.<Trace>empty();
              }
              JsonNode spans = body(r, "getTrace").path("data").path("attributes").path("spans");
              List<Span> out = new ArrayList<>();
              if (spans.isArray()) {
                for (JsonNode raw : spans) {
                  out.add(DatadogMapping.traceSpanToSpan(raw));
                }
              }
              return out.isEmpty() ? Optional.<Trace>empty() : Optional.of(new Trace(id, out));
            });
  }

  /**
   * Calls {@code GET /api/v2/apm/services?filter[env]=...} with the configured environment (else
   * {@code *}, all) and returns the sorted names of the services that reported traces.
   *
   * @return service names
   */
  @Override
  public List<String> listServices() {
    return listServices(null);
  }

  /**
   * {@link #listServices()} with an explicit environment filter.
   *
   * @param env environment, {@code *} for all, {@code null} for the default
   * @return service names
   */
  public List<String> listServices(String env) {
    return await(listServicesAsync(env), "listServices");
  }

  @Override
  public CompletableFuture<List<String>> listServicesAsync() {
    return listServicesAsync(null);
  }

  /**
   * Asynchronous {@link #listServices(String)}.
   *
   * @param env environment filter or {@code null}
   * @return future names
   */
  public CompletableFuture<List<String>> listServicesAsync(String env) {
    String filter = env != null && !env.isEmpty() ? env : config.environment().orElse("*");
    return send(TransportRequest.get("/api/v2/apm/services")
            .query("filter[env]", filter)
            .operation("listServices"))
        .thenApply(
            r -> {
              JsonNode services =
                  body(r, "listServices").path("data").path("attributes").path("services");
              TreeSet<String> out = new TreeSet<>();
              if (services.isArray()) {
                for (JsonNode s : services) {
                  if (s.isTextual()) {
                    out.add(s.asText());
                  }
                }
              }
              return List.copyOf(out);
            });
  }

  /**
   * The distinct span names (Datadog {@code resource_name}) of {@code service} in the last 24
   * hours, via {@link #aggregateSpans} grouped by {@code resource_name} (up to 1000), sorted.
   *
   * @param service service name
   * @return operation names
   */
  @Override
  public List<String> listOperations(String service) {
    return await(listOperationsAsync(service), "listOperations");
  }

  @Override
  public CompletableFuture<List<String>> listOperationsAsync(String service) {
    Instant now = clock.instant();
    return aggregateSpansAsync(
            List.of(SpanCompute.of(SpanCompute.AGGREGATION_COUNT)),
            List.of(SpanGroupBy.of("resource_name", 1000)),
            AggregateSpansOptions.of(
                "service:" + quoteQueryValue(service),
                DatadogTime.at(now.minus(TRACE_QUERY_WINDOW)),
                DatadogTime.at(now)))
        .thenApply(
            result -> {
              TreeSet<String> out = new TreeSet<>();
              for (SpanAggregateBucket b : result.buckets()) {
                Object v = b.by().get("resource_name");
                if (v instanceof String && !((String) v).isEmpty()) {
                  out.add((String) v);
                }
              }
              return List.copyOf(out);
            });
  }

  /**
   * Quotes a value for the Datadog query syntax when it contains spaces or special characters.
   *
   * @param value value
   * @return the value, quoted and escaped when needed
   */
  public static String quoteQueryValue(String value) {
    if (PLAIN_QUERY_VALUE.matcher(value).matches()) {
      return value;
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * The Datadog query string for a SPEC trace search: {@code service:}, {@code resource_name:},
   * {@code @<tag>:} and {@code @duration:>=} / {@code <=} (nanoseconds). An empty query is {@code
   * *}.
   *
   * @param query trace search query
   * @return query string
   */
  public static String traceSearchQueryString(TraceSearchQuery query) {
    List<String> parts = new ArrayList<>();
    query
        .service()
        .filter(s -> !s.isEmpty())
        .ifPresent(s -> parts.add("service:" + quoteQueryValue(s)));
    query
        .operation()
        .filter(s -> !s.isEmpty())
        .ifPresent(s -> parts.add("resource_name:" + quoteQueryValue(s)));
    new TreeMap<>(query.tags()).forEach((k, v) -> parts.add("@" + k + ":" + quoteQueryValue(v)));
    long min = query.minDurationMicros().orElse(0);
    if (min > 0) {
      parts.add("@duration:>=" + (min * 1000));
    }
    long max = query.maxDurationMicros().orElse(0);
    if (max > 0) {
      parts.add("@duration:<=" + (max * 1000));
    }
    return parts.isEmpty() ? "*" : String.join(" ", parts);
  }

  /**
   * Finds traces with spans matching the query (service, span name, {@code @tag} values, duration)
   * in start..end (default: the last 15 minutes), newest first, up to the limit of distinct trace
   * ids (at most 5 pages). Summaries are built from the matching spans only.
   *
   * @param query trace search query
   * @return summaries
   */
  @Override
  public List<TraceSummary> searchTraces(TraceSearchQuery query) {
    return await(searchTracesAsync(query), "searchTraces");
  }

  @Override
  public CompletableFuture<List<TraceSummary>> searchTracesAsync(TraceSearchQuery query) {
    String[] w =
        window(
            query.start().map(DatadogTime::at),
            query.end().map(DatadogTime::at),
            DEFAULT_QUERY_WINDOW);
    int pageLimit = Math.min(MAX_SPANS_PAGE, Math.max(query.limit() * 10, 100));
    SearchSpansOptions opts =
        SearchSpansOptions.builder()
            .query(traceSearchQueryString(query))
            .limit(pageLimit)
            .sort("-timestamp")
            .build();
    Map<String, List<Span>> traces = new LinkedHashMap<>();
    return tracePages(opts, w[0], w[1], null, 0, query.limit(), traces)
        .thenApply(v -> summaries(traces));
  }

  private CompletableFuture<Void> tracePages(
      SearchSpansOptions opts,
      String from,
      String to,
      String cursor,
      int pageNo,
      int limit,
      Map<String, List<Span>> traces) {
    if (pageNo >= SEARCH_TRACES_MAX_PAGES || traces.size() >= limit) {
      return CompletableFuture.completedFuture(null);
    }
    return page(opts, from, to, cursor)
        .thenCompose(
            p -> {
              for (DatadogSpan s : p.spans()) {
                String id = s.span().traceId();
                List<Span> list = traces.get(id);
                if (list != null) {
                  list.add(s.span());
                } else if (traces.size() < limit) {
                  traces.put(id, new ArrayList<>(List.of(s.span())));
                }
              }
              Optional<String> next = p.nextCursor();
              if (next.isEmpty()) {
                return CompletableFuture.completedFuture(null);
              }
              return tracePages(opts, from, to, next.get(), pageNo + 1, limit, traces);
            });
  }

  private static List<TraceSummary> summaries(Map<String, List<Span>> traces) {
    List<TraceSummary> out = new ArrayList<>();
    for (Map.Entry<String, List<Span>> e : traces.entrySet()) {
      List<Span> spans = Traces.sortSpans(e.getValue());
      TreeSet<String> ids = new TreeSet<>();
      for (Span s : spans) {
        ids.add(s.spanId());
      }
      Span root = spans.get(0);
      for (Span s : spans) {
        if (s.parentSpanId().isEmpty() || !ids.contains(s.parentSpanId().get())) {
          root = s;
          break;
        }
      }
      long start = Long.MAX_VALUE;
      long end = Long.MIN_VALUE;
      for (Span s : spans) {
        start = Math.min(start, s.startMicros());
        end = Math.max(end, s.endMicros());
      }
      out.add(
          new TraceSummary(
              e.getKey(), root.serviceName(), root.name(), start, end - start, spans.size()));
    }
    return out;
  }

  /**
   * Not supported: Datadog's public API offers no service dependency graph.
   *
   * @param start ignored
   * @param end ignored
   * @return never
   * @throws UnsupportedException always
   */
  @Override
  public List<DependencyLink> getDependencies(Instant start, Instant end) {
    throw new UnsupportedException(
        "traces.getDependencies: the Datadog public API has no service dependency endpoint");
  }

  /**
   * Polls {@link #getTrace(String)} every 2 s (Datadog allows 50 searches per minute) for up to 30
   * s until the trace has a span.
   *
   * @param traceId trace id
   * @return the trace
   * @throws TimeoutException when it does not appear in time
   */
  @Override
  public Trace waitForTrace(String traceId) {
    return waitForTrace(traceId, new WaitForTraceOptions(null, DATADOG_WAIT_INTERVAL, 1));
  }

  // ---------------------------------------------------------------- ping

  /**
   * Authenticated round trip {@code GET /api/v2/validate_keys} (API key + application key).
   *
   * @return latency
   * @throws codes.grow.packages.datadogapm.core.AuthenticationException for invalid keys (Datadog
   *     answers 401 or 403)
   */
  public Duration ping() {
    return await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return future latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    return send(TransportRequest.get("/api/v2/validate_keys").operation("ping"))
        .handle(
            (r, err) -> {
              if (err != null) {
                DatadogApmException e = HttpTransport.asSdkException(err, "ping");
                if (e instanceof PermissionDeniedException) {
                  throw DatadogApmException.of(
                      ErrorCode.AUTHENTICATION,
                      e.getMessage(),
                      e.httpStatus().isPresent() ? e.httpStatus().getAsInt() : null,
                      e.requestId().orElse(null),
                      null,
                      e);
                }
                throw e;
              }
              return Duration.ofNanos(System.nanoTime() - start);
            });
  }

  /** Releases connections; idempotent. */
  @Override
  public void close() {
    transport.close();
  }

  @Override
  public String toString() {
    return "DatadogSpansQuery{" + config + "}";
  }
}
