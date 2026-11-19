package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadogapm.core.AuthenticationException;
import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.core.MockServer;
import codes.grow.packages.datadogapm.core.RateLimitException;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.core.TimeoutException;
import codes.grow.packages.datadogapm.core.UnavailableException;
import codes.grow.packages.datadogapm.core.UnsupportedException;
import codes.grow.packages.datadogapm.core.ValidationException;
import codes.grow.packages.datadogapm.tracing.query.Trace;
import codes.grow.packages.datadogapm.tracing.query.TraceSearchQuery;
import codes.grow.packages.datadogapm.tracing.query.TraceSummary;
import codes.grow.packages.datadogapm.tracing.query.WaitForTraceOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogSpansQueryTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final String TRACE = "0123456789abcdefffffffffffffffff";

  private MockServer server;
  private DatadogSpansQuery query;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    query = query(cfg().build());
  }

  @AfterEach
  void stop() {
    query.close();
    server.close();
  }

  private DatadogApmConfig.Builder cfg() {
    return DatadogApmConfig.builder()
        .apiUrl(server.url())
        .apiKey("api-key-123")
        .appKey("app-key-456")
        .retry(
            RetryPolicy.builder()
                .maxRetries(2)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(2))
                .build());
  }

  private static DatadogSpansQuery query(DatadogApmConfig c) {
    return new DatadogSpansQuery(c, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static JsonNode body(MockServer.Recorded r) {
    return Json.tree(r.body().getBytes(StandardCharsets.UTF_8));
  }

  private static String span(
      String traceId, String spanId, String parent, String name, String start) {
    return "{\"id\":\"e"
        + spanId
        + "\",\"type\":\"spans\",\"attributes\":{\"service\":\"web\",\"resource_name\":\""
        + name
        + "\",\"trace_id\":\""
        + traceId
        + "\",\"span_id\":\""
        + spanId
        + "\",\"parent_id\":\""
        + parent
        + "\",\"start_timestamp\":\""
        + start
        + "\",\"end_timestamp\":\""
        + start.replace("00.000Z", "01.000Z")
        + "\"}}";
  }

  private static String page(String after, String... spans) {
    return "{\"data\":["
        + String.join(",", spans)
        + "],\"meta\":{\"status\":\"done\",\"request_id\":\"req-1\""
        + (after == null ? "" : ",\"page\":{\"after\":\"" + after + "\"}")
        + "}}";
  }

  @Test
  void searchSpansPageRequestAndResponse() {
    server.enqueue(
        200,
        "{\"data\":["
            + span(TRACE, "255", "0", "GET /x", "2026-11-18T11:59:00.000Z")
            + ",{\"id\":\"noattrs\",\"type\":\"spans\",\"attributes\":{\"trace_id\":\"42\",\"span_id\":\"1\"}}"
            + "],\"meta\":{\"status\":\"timeout\",\"page\":{\"after\":\"c2\"},"
            + "\"warnings\":[{\"code\":\"w\",\"title\":\"t\",\"detail\":\"d\"}]}}",
        "x-request-id",
        "hdr-req");
    DatadogSpansPage p =
        query.searchSpansPage(
            SearchSpansOptions.builder()
                .query("service:web")
                .from(DatadogTime.expr("now-1h"))
                .to(DatadogTime.epochMillis(0))
                .limit(25)
                .sort("-timestamp")
                .cursor("c1")
                .timezone("Europe/Berlin")
                .build());
    assertThat(p.spans()).hasSize(2);
    assertThat(p.spans().get(0).span().traceId()).isEqualTo(TRACE);
    assertThat(p.spans().get(0).id()).isEqualTo("e255");
    assertThat(p.spans().get(0).raw().get("service").asText()).isEqualTo("web");
    assertThat(p.nextCursor()).contains("c2");
    assertThat(p.status()).isEqualTo("timeout");
    assertThat(p.warnings()).containsExactly(new DatadogQueryWarning("w", "t", "d"));
    assertThat(p.requestId()).contains("hdr-req");
    MockServer.Recorded r = server.requests().get(0);
    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.uri()).isEqualTo("/api/v2/spans/events/search");
    assertThat(r.header("DD-API-KEY")).isEqualTo("api-key-123");
    assertThat(r.header("DD-APPLICATION-KEY")).isEqualTo("app-key-456");
    JsonNode a = body(r).at("/data/attributes");
    assertThat(body(r).at("/data/type").asText()).isEqualTo("search_request");
    assertThat(a.at("/filter/query").asText()).isEqualTo("service:web");
    assertThat(a.at("/filter/from").asText()).isEqualTo("now-1h");
    assertThat(a.at("/filter/to").asText()).isEqualTo("1970-01-01T00:00:00.000Z");
    assertThat(a.at("/page/limit").asInt()).isEqualTo(25);
    assertThat(a.at("/page/cursor").asText()).isEqualTo("c1");
    assertThat(a.at("/sort").asText()).isEqualTo("-timestamp");
    assertThat(a.at("/options/timezone").asText()).isEqualTo("Europe/Berlin");

    // defaults: query *, absolute 15 minute window, limit 10, timestamp, UTC
    server.enqueue(200, "");
    DatadogSpansPage empty = query.searchSpansPage(SearchSpansOptions.defaults());
    assertThat(empty.spans()).isEmpty();
    assertThat(empty.status()).isEqualTo("done");
    assertThat(empty.nextCursor()).isEmpty();
    JsonNode d = body(server.requests().get(1)).at("/data/attributes");
    assertThat(d.at("/filter/query").asText()).isEqualTo("*");
    assertThat(d.at("/filter/from").asText()).isEqualTo("2026-11-18T11:45:00.000Z");
    assertThat(d.at("/filter/to").asText()).isEqualTo("2026-11-18T12:00:00.000Z");
    assertThat(d.at("/page/limit").asInt()).isEqualTo(10);
    assertThat(d.at("/page").has("cursor")).isFalse();
    assertThat(d.at("/sort").asText()).isEqualTo("timestamp");
    assertThat(d.at("/options/timezone").asText()).isEqualTo("UTC");
  }

  @Test
  void searchSpansFollowsTheCursor() throws Exception {
    server.enqueue(200, page("c1", span(TRACE, "1", "0", "a", "2026-11-18T11:59:00.000Z")));
    server.enqueue(200, page("c2", span(TRACE, "2", "1", "b", "2026-11-18T11:59:00.000Z")));
    server.enqueue(200, page(null, span(TRACE, "3", "1", "c", "2026-11-18T11:59:00.000Z")));
    List<String> names = new ArrayList<>();
    Iterator<DatadogSpan> it =
        query.searchSpans(SearchSpansOptions.builder().query("@grow.run:x").build()).iterator();
    while (it.hasNext()) {
      names.add(it.next().span().name());
    }
    assertThat(names).containsExactly("a", "b", "c");
    assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    List<MockServer.Recorded> reqs = server.requests();
    assertThat(body(reqs.get(1)).at("/data/attributes/page/cursor").asText()).isEqualTo("c1");
    assertThat(body(reqs.get(2)).at("/data/attributes/filter/from").asText())
        .isEqualTo(body(reqs.get(0)).at("/data/attributes/filter/from").asText());

    server.enqueue(200, page("same", span(TRACE, "1", "0", "a", "2026-11-18T11:59:00.000Z")));
    server.enqueue(200, page("same", span(TRACE, "2", "0", "b", "2026-11-18T11:59:00.000Z")));
    List<DatadogSpan> all =
        query.searchSpansAsync(SearchSpansOptions.defaults()).get(5, TimeUnit.SECONDS);
    assertThat(all).hasSize(2);
    server.enqueue(200, page("same", span(TRACE, "1", "0", "a", "2026-11-18T11:59:00.000Z")));
    server.enqueue(200, page("same", span(TRACE, "2", "0", "b", "2026-11-18T11:59:00.000Z")));
    int count = 0;
    for (DatadogSpan ignored : query.searchSpans(SearchSpansOptions.defaults())) {
      count++;
    }
    assertThat(count).isEqualTo(2);

    server.enqueue(200, "{\"data\":[],\"meta\":{\"status\":\"timeout\"}}");
    assertThatThrownBy(() -> query.searchSpans(SearchSpansOptions.defaults()).iterator().hasNext())
        .isInstanceOf(TimeoutException.class);
    server.enqueue(200, "{\"data\":[],\"meta\":{\"status\":\"timeout\"}}");
    assertThatThrownBy(() -> query.searchSpansAsync(SearchSpansOptions.defaults()).join())
        .hasCauseInstanceOf(TimeoutException.class);
    assertThatThrownBy(
            () -> query.searchSpansPage(SearchSpansOptions.builder().limit(1001).build()))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> query.searchSpansPage(SearchSpansOptions.builder().limit(0).build()))
        .isInstanceOf(ConfigException.class);
    SearchSpansOptions copy =
        SearchSpansOptions.builder().query(null).sort("").timezone(null).build().toBuilder()
            .build();
    assertThat(copy.query()).isEqualTo("*");
    assertThat(copy.sort()).isEqualTo("timestamp");
    assertThat(copy.timezone()).isEqualTo("UTC");
    assertThat(copy.from()).isEmpty();
  }

  @Test
  void errorsAreMapped() {
    server.enqueue(400, Fixtures.body("searchSpans.400.docs_example.json"));
    assertThatThrownBy(() -> query.searchSpansPage(SearchSpansOptions.defaults()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Missing required attribute");
    server.enqueue(200, "{not json");
    assertThatThrownBy(() -> query.searchSpansPage(SearchSpansOptions.defaults()))
        .isInstanceOf(UnavailableException.class)
        .hasMessageContaining("invalid JSON");
    server.enqueue(200, "{\"data\":[{\"attributes\":{\"trace_id\":\"zz\",\"span_id\":\"1\"}}]}");
    assertThatThrownBy(() -> query.searchSpansPage(SearchSpansOptions.defaults()))
        .isInstanceOf(ValidationException.class);
    // the error message never contains a key
    server.enqueue(403, "{\"errors\":[\"Forbidden api-key-123\"]}");
    assertThatThrownBy(() -> query.listServices())
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("api-key-123"));
    assertThatThrownBy(() -> query.getDependencies(null, null))
        .isInstanceOf(UnsupportedException.class);
    assertThat(query.toString()).doesNotContain("api-key-123").doesNotContain("app-key-456");
  }

  @Test
  void rateLimits() {
    server.enqueue(429, "{\"errors\":[\"Too many requests\"]}", "X-RateLimit-Reset", "0");
    server.enqueue(429, "{}");
    server.enqueue(200, Fixtures.body("listServices.docs_example.json"));
    assertThat(query.listServices()).containsExactly("test-service");
    assertThat(server.requests()).hasSize(3);

    server.enqueue(429, "{}", "Retry-After", "0");
    server.enqueue(429, "{}", "Retry-After", "0");
    server.enqueue(429, "{\"errors\":[\"Too many requests\"]}", "Retry-After", "0");
    assertThatThrownBy(() -> query.listServices())
        .isInstanceOf(RateLimitException.class)
        .hasMessageContaining("Too many requests")
        .satisfies(e -> assertThat(((RateLimitException) e).retryAfter()).contains(Duration.ZERO));

    server.enqueue(429, "{}", "X-RateLimit-Reset", "3600");
    assertThatThrownBy(() -> query.listServices()).isInstanceOf(RateLimitException.class);
  }

  @Test
  void aggregateSpans() throws Exception {
    server.enqueue(200, Fixtures.body("aggregateSpans.docs_example.json"));
    SpanAggregateResult r =
        query.aggregateSpans(
            List.of(SpanCompute.timeseries(SpanCompute.AGGREGATION_PC90, "@duration", "5m")),
            List.of(
                new SpanGroupBy(
                    "service",
                    20,
                    new SpanGroupBy.Sort("pc90", "@duration", "desc", "measure"),
                    "none",
                    true,
                    new SpanGroupBy.Histogram(10, 0, 100))),
            AggregateSpansOptions.of(
                "service:web*", DatadogTime.expr("now-15m"), DatadogTime.expr("now")));
    assertThat(r.buckets())
        .singleElement()
        .satisfies(
            b -> {
              assertThat(b.by()).containsEntry("host", "my-hostname");
              assertThat(b.computes()).containsEntry("c0", 19.0);
            });
    assertThat(r.status()).isEqualTo("done");
    assertThat(r.warnings()).isEmpty();
    assertThat(r.requestId()).isEmpty();
    JsonNode a = body(server.requests().get(0)).at("/data/attributes");
    assertThat(body(server.requests().get(0)).at("/data/type").asText())
        .isEqualTo("aggregate_request");
    assertThat(a.at("/compute/0/aggregation").asText()).isEqualTo("pc90");
    assertThat(a.at("/compute/0/type").asText()).isEqualTo("timeseries");
    assertThat(a.at("/compute/0/interval").asText()).isEqualTo("5m");
    assertThat(a.at("/group_by/0/limit").asInt()).isEqualTo(20);
    assertThat(a.at("/group_by/0/sort/order").asText()).isEqualTo("desc");
    assertThat(a.at("/group_by/0/missing").asText()).isEqualTo("none");
    assertThat(a.at("/group_by/0/total").asBoolean()).isTrue();
    assertThat(a.at("/group_by/0/histogram/max").asDouble()).isEqualTo(100);
    assertThat(a.at("/filter/query").asText()).isEqualTo("service:web*");

    server.enqueue(
        200,
        "{\"data\":[{\"attributes\":{\"by\":{\"code\":500,\"n\":null},\"compute\":{"
            + "\"c0\":[{\"time\":\"2026-11-18T11:55:00Z\",\"value\":3},{\"time\":\"t\",\"value\":null}],"
            + "\"c1\":\"text\",\"c2\":null}}}],\"meta\":{\"status\":\"timeout\",\"request_id\":\"r\","
            + "\"warnings\":[{\"code\":\"c\"}]}}");
    SpanAggregateResult ts =
        query
            .aggregateSpansAsync(
                List.of(SpanCompute.of("count"), SpanCompute.of("avg", "@duration")),
                null,
                AggregateSpansOptions.defaults())
            .get(5, TimeUnit.SECONDS);
    SpanAggregateBucket b = ts.buckets().get(0);
    assertThat(b.by()).containsEntry("code", 500L).doesNotContainKey("n");
    assertThat(b.computes().get("c0"))
        .isEqualTo(
            List.of(
                new SpanTimeseriesPoint("2026-11-18T11:55:00Z", 3),
                new SpanTimeseriesPoint("t", 0)));
    assertThat(b.computes()).containsEntry("c1", "text").doesNotContainKey("c2");
    assertThat(ts.status()).isEqualTo("timeout");
    assertThat(ts.requestId()).contains("r");
    assertThat(ts.warnings()).singleElement().satisfies(w -> assertThat(w.title()).isNull());
    JsonNode d = body(server.requests().get(1)).at("/data/attributes");
    assertThat(d.has("group_by")).isFalse();
    assertThat(d.at("/filter/query").asText()).isEqualTo("*");

    server.enqueue(200, "");
    assertThat(query.aggregateSpans(List.of(SpanCompute.of("count")), List.of()).buckets())
        .isEmpty();
    assertThatThrownBy(() -> query.aggregateSpans(List.of(), null))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> query.aggregateSpans(null, null)).isInstanceOf(ConfigException.class);
    assertThat(new AggregateSpansOptions("", null, null, "").timezone()).isEqualTo("UTC");
  }

  @Test
  void getTraceBySearch() throws Exception {
    server.enqueue(
        200,
        page(
            "c1",
            span(TRACE, "1", "0", "root", "2026-11-18T11:00:00.000Z"),
            span(TRACE, "2", "1", "child", "2026-11-18T11:00:00.000Z")));
    server.enqueue(200, page(null, span(TRACE, "2", "1", "child", "2026-11-18T11:00:00.000Z")));
    Trace t = query.getTrace(TRACE).orElseThrow();
    assertThat(t.traceId()).isEqualTo(TRACE);
    assertThat(t.spans()).hasSize(2);
    assertThat(t.root().orElseThrow().name()).isEqualTo("root");
    JsonNode a = body(server.requests().get(0)).at("/data/attributes");
    assertThat(a.at("/filter/query").asText()).isEqualTo("trace_id:" + TRACE);
    assertThat(a.at("/filter/from").asText()).isEqualTo("2026-11-17T12:00:00.000Z");
    assertThat(a.at("/page/limit").asInt()).isEqualTo(1000);

    server.enqueue(200, page(null));
    assertThat(query.getTraceAsync("2a").get(5, TimeUnit.SECONDS)).isEmpty();
    assertThat(body(server.requests().get(2)).at("/data/attributes/filter/query").asText())
        .isEqualTo("trace_id:42");
    server.enqueue(200, page(null));
    assertThat(
            query.getTrace(
                "2a", GetTraceOptions.window(DatadogTime.expr("now-1h"), DatadogTime.expr("now"))))
        .isEmpty();
    assertThat(body(server.requests().get(3)).at("/data/attributes/filter/from").asText())
        .isEqualTo("now-1h");
    assertThatThrownBy(() -> query.getTrace("zz")).isInstanceOf(ValidationException.class);
  }

  @Test
  void getTracePreview() {
    server.enqueue(200, Fixtures.body("getTraceById.docs_example.json"));
    Trace t =
        query
            .getTrace("0000000000000000abc1230000000000", GetTraceOptions.previewApi())
            .orElseThrow();
    assertThat(t.spans())
        .singleElement()
        .satisfies(s -> assertThat(s.name()).isEqualTo("GET /products"));
    assertThat(server.requests().get(0).uri())
        .isEqualTo("/api/v2/trace/0000000000000000abc1230000000000");
    assertThat(server.requests().get(0).method()).isEqualTo("GET");
    server.enqueue(404, "{\"errors\":[\"not found\"]}");
    assertThat(query.getTrace("00000000000000000020000000000001", GetTraceOptions.previewApi()))
        .isEmpty();
    assertThat(server.requests().get(1).uri()).isEqualTo("/api/v2/trace/9007199254740993");
    server.enqueue(200, "  ");
    assertThat(query.getTrace("2a", GetTraceOptions.previewApi())).isEmpty();
    server.enqueue(200, "{\"data\":{\"attributes\":{\"spans\":[]}}}");
    assertThat(query.getTrace("2a", GetTraceOptions.previewApi())).isEmpty();
    server.enqueue(500, "{}");
    server.enqueue(500, "{}");
    server.enqueue(500, "{}");
    assertThatThrownBy(() -> query.getTrace("2a", GetTraceOptions.previewApi()))
        .isInstanceOf(UnavailableException.class);
    assertThat(GetTraceOptions.defaults().preview()).isFalse();
    assertThat(new GetTraceOptions(true, null, null).from()).isEmpty();
  }

  @Test
  void listServicesAndOperations() throws Exception {
    server.enqueue(200, Fixtures.body("listServices.docs_example.json"));
    assertThat(query.listServices()).containsExactly("test-service");
    assertThat(server.requests().get(0).uri()).isEqualTo("/api/v2/apm/services?filter%5Benv%5D=*");
    server.enqueue(200, "{\"data\":{\"attributes\":{\"services\":[\"b\",\"a\",3]}}}");
    assertThat(query.listServicesAsync("prod").get(5, TimeUnit.SECONDS)).containsExactly("a", "b");
    assertThat(server.requests().get(1).uri()).contains("prod");
    try (DatadogSpansQuery env = query(cfg().environment("staging").build())) {
      server.enqueue(200, "{}");
      assertThat(env.listServicesAsync().get(5, TimeUnit.SECONDS)).isEmpty();
      assertThat(server.requests().get(2).uri()).contains("staging");
      server.enqueue(200, "{}");
      assertThat(env.listServices("")).isEmpty();
      assertThat(server.requests().get(3).uri()).contains("staging");
    }
    server.enqueue(
        200,
        "{\"data\":[{\"attributes\":{\"by\":{\"resource_name\":\"b\"},\"compute\":{\"c0\":1}}},"
            + "{\"attributes\":{\"by\":{\"resource_name\":\"a\"},\"compute\":{\"c0\":1}}},"
            + "{\"attributes\":{\"by\":{\"resource_name\":\"\"}}},{\"attributes\":{\"by\":{}}}]}");
    assertThat(query.listOperations("my svc")).containsExactly("a", "b");
    JsonNode a = body(server.requests().get(4)).at("/data/attributes");
    assertThat(a.at("/filter/query").asText()).isEqualTo("service:\"my svc\"");
    assertThat(a.at("/group_by/0/facet").asText()).isEqualTo("resource_name");
    assertThat(a.at("/group_by/0/limit").asInt()).isEqualTo(1000);
    assertThat(a.at("/compute/0/aggregation").asText()).isEqualTo("count");
  }

  @Test
  void searchTraces() throws Exception {
    String other = "a3ce929d0e0e47364bf92f3577b34da6";
    server.enqueue(
        200,
        page(
            "c1",
            span(TRACE, "2", "1", "child", "2026-11-18T11:58:00.000Z"),
            span(other, "5", "0", "other-root", "2026-11-18T11:57:00.000Z")));
    server.enqueue(
        200,
        page(
            null,
            span(TRACE, "1", "0", "root", "2026-11-18T11:56:00.000Z"),
            span("2a", "9", "0", "third", "2026-11-18T11:56:00.000Z")));
    List<TraceSummary> out =
        query.searchTraces(
            TraceSearchQuery.builder()
                .service("web")
                .operation("GET /x")
                .tag("http.status_code", "500")
                .minDurationMicros(1000L)
                .maxDurationMicros(2000L)
                .start(NOW.minusSeconds(60))
                .end(NOW)
                .limit(3)
                .build());
    assertThat(out)
        .extracting(TraceSummary::traceId)
        .containsExactly(TRACE, other, "0".repeat(30) + "2a");
    TraceSummary first = out.get(0);
    assertThat(first.spanCount()).isEqualTo(2);
    assertThat(first.rootName()).contains("root");
    assertThat(first.rootServiceName()).contains("web");
    assertThat(first.durationMicros())
        .isEqualTo(Duration.ofMinutes(2).plusSeconds(1).toNanos() / 1000);
    JsonNode a = body(server.requests().get(0)).at("/data/attributes");
    assertThat(a.at("/filter/query").asText())
        .isEqualTo(
            "service:web resource_name:\"GET /x\" @http.status_code:500 @duration:>=1000000 @duration:<=2000000");
    assertThat(a.at("/sort").asText()).isEqualTo("-timestamp");
    assertThat(a.at("/page/limit").asInt()).isEqualTo(100);
    assertThat(a.at("/filter/from").asText()).isEqualTo("2026-11-18T11:59:00.000Z");

    server.enqueue(200, page("x", span(TRACE, "1", "0", "r", "2026-11-18T11:56:00.000Z")));
    assertThat(
            query
                .searchTracesAsync(TraceSearchQuery.builder().limit(1).build())
                .get(5, TimeUnit.SECONDS))
        .hasSize(1);
    assertThat(body(server.requests().get(2)).at("/data/attributes/filter/query").asText())
        .isEqualTo("*");
    assertThat(server.requests()).hasSize(3);
    // cycles: the parent is outside the result, every span is a root candidate
    server.enqueue(200, page(null, span(TRACE, "3", "4", "orphan", "2026-11-18T11:56:00.000Z")));
    assertThat(query.searchTraces(TraceSearchQuery.builder().build()).get(0).rootName())
        .contains("orphan");
    assertThat(DatadogSpansQuery.quoteQueryValue("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
  }

  @Test
  void waitForTrace() {
    server.enqueue(200, page(null));
    server.enqueue(200, page(null, span(TRACE, "1", "0", "root", "2026-11-18T11:00:00.000Z")));
    Trace t =
        query.waitForTrace(
            TRACE, new WaitForTraceOptions(Duration.ofSeconds(5), Duration.ofMillis(10), 1));
    assertThat(t.spans()).hasSize(1);
    server.enqueue(200, page(null, span(TRACE, "1", "0", "root", "2026-11-18T11:00:00.000Z")));
    assertThat(query.waitForTrace(TRACE).spans()).hasSize(1);
    assertThat(DatadogSpansQuery.DATADOG_WAIT_INTERVAL).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void ping() throws Exception {
    server.enqueue(200, Fixtures.body("ping.validate_keys.docs_example.json"));
    assertThat(query.ping()).isPositive();
    assertThat(server.requests().get(0).uri()).isEqualTo("/api/v2/validate_keys");
    server.enqueue(403, "{\"errors\":[\"Forbidden\"]}");
    assertThatThrownBy(() -> query.ping())
        .isInstanceOf(AuthenticationException.class)
        .satisfies(e -> assertThat(((AuthenticationException) e).httpStatus()).hasValue(403));
    server.enqueue(401, "{\"errors\":[\"Unauthorized\"]}");
    assertThatThrownBy(() -> query.pingAsync().join())
        .hasCauseInstanceOf(AuthenticationException.class);
  }

  @Test
  void construction() {
    assertThatThrownBy(
            () -> DatadogSpansQuery.create(DatadogApmConfig.builder().apiKey("only").build()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("DD_APP_KEY");
    try (DatadogSpansQuery q = DatadogSpansQuery.create(cfg().build())) {
      assertThat(q.config().apiUrl()).isEqualTo(server.url());
    }
    if (System.getenv("DD_API_KEY") == null && System.getenv("GROW_DATADOG_APM_API_KEY") == null) {
      assertThatThrownBy(DatadogSpansQuery::fromEnv).isInstanceOf(ConfigException.class);
    }
    query.close();
    assertThatThrownBy(() -> query.listServices()).isNotNull();
  }
}
