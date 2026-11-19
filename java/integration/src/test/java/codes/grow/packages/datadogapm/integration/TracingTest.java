package codes.grow.packages.datadogapm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.grow.packages.datadogapm.DatadogApmClient;
import codes.grow.packages.datadogapm.DatadogApmConfig;
import codes.grow.packages.datadogapm.DatadogIds;
import codes.grow.packages.datadogapm.DatadogLogCorrelation;
import codes.grow.packages.datadogapm.DatadogMapping;
import codes.grow.packages.datadogapm.DatadogPropagator;
import codes.grow.packages.datadogapm.DatadogSpanErrors;
import codes.grow.packages.datadogapm.DatadogTracing;
import codes.grow.packages.datadogapm.DatadogTracingHandle;
import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.core.UnsupportedException;
import codes.grow.packages.datadogapm.tracing.SpanOptions;
import codes.grow.packages.datadogapm.tracing.Spans;
import codes.grow.packages.datadogapm.tracing.TracedHttpClient;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import codes.grow.packages.datadogapm.tracing.query.Span;
import codes.grow.packages.datadogapm.tracing.query.TraceSearchQuery;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The tracing half against the real Datadog Agent: spans are exported over OTLP (HTTP/protobuf
 * and gRPC), converted by the Agent, forwarded to fakeintake and read back through the
 * trace-reader sidecar.
 */
class TracingTest {
  /** A checked exception type with a known name. */
  static final class TypeError extends Exception {
    private static final long serialVersionUID = 1L;

    TypeError(String message) {
      super(message);
    }
  }

  private static String service(String kind) {
    return "grow-java-it-"
        + kind
        + "-"
        + TraceReader.runId()
        + "-"
        + Integer.toHexString(new SecureRandom().nextInt(1 << 24));
  }

  private static DatadogTracingHandle init(Map<String, String> env, String service) {
    return DatadogTracing.initTracing(
        TracingConfig.builder(service)
            .serviceVersion("1.2.3")
            .environment("it")
            .resourceAttributes(Map.of("grow.run", service))
            .sampler(TracingConfig.SamplerType.ALWAYS_ON)
            .setGlobal(false)
            .build(),
        DatadogApmConfig.fromEnv(env));
  }

  private static Map<String, JsonNode> byResource(List<JsonNode> spans) {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    for (JsonNode s : spans) {
      out.put(s.path("resource").asText(), s);
    }
    return out;
  }

  private static String meta(JsonNode span, String key) {
    JsonNode v = span.path("meta").get(key);
    return v == null ? null : v.asText();
  }

  private static SpanOptions in(DatadogTracingHandle h) {
    return SpanOptions.builder().tracer(h.tracer("it")).build();
  }

  @Test
  void initTracingHttpToAgent() throws Exception {
    Map<String, String> env = TraceReader.requireService();
    TraceReader reader = new TraceReader(env);
    String svc = service("http");
    DatadogTracingHandle h = init(env, svc);
    try {
      assertEquals(env.get("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT") + "/v1/traces", h.endpoint());
      SpanContext link =
          SpanContext.create(
              "a3ce929d0e0e47364bf92f3577b34da6",
              "00f067aa0ba902b7",
              TraceFlags.getSampled(),
              TraceState.getDefault());
      Map<String, String>[] captured = newMaps(2);
      String[] ids = new String[3];
      SpanOptions root = SpanOptions.builder().tracer(h.tracer("it")).kind(SpanKind.SERVER).build();
      Spans.withSpan(
          "it-root",
          root,
          () -> {
            captured[0] = DatadogLogCorrelation.datadogLogFields();
            captured[1] = Spans.traceFields();
            ids[0] = Spans.currentTraceId().orElseThrow();
            ids[1] = Spans.currentSpanId().orElseThrow();
            Spans.addEvent("checkpoint", Map.of("step", 1));
            Spans.withSpan(
                "it-child-ok",
                SpanOptions.builder().tracer(h.tracer("it")).attribute("n", 7).attribute("flag", true).build(),
                () -> {});
            try {
              Spans.withSpan(
                  "it-child-fail",
                  in(h),
                  () -> {
                    throw new TypeError("value out of range");
                  });
            } catch (TypeError expected) {
              // rethrown by withSpan
            }
            Spans.withSpan(
                "it-recorded",
                in(h),
                () -> DatadogSpanErrors.recordException(new TypeError("bad type")));
            Spans.withSpan(
                "it-set-error",
                in(h),
                () -> DatadogSpanErrors.setError(new IllegalStateException("explicit failure")));
            Spans.withSpan(
                "it-generic",
                in(h),
                () -> {
                  Spans.recordException(new RuntimeException("generic"));
                  Spans.setError(new IllegalArgumentException("generic status"));
                });
            Spans.withSpan(
                "it-attrs",
                in(h),
                () -> DatadogSpanErrors.setErrorAttributes(new TypeError("attrs only")));
            Spans.withSpan(
                "it-linked", SpanOptions.builder().tracer(h.tracer("it")).link(link).build(), () -> {});
            ids[2] = Spans.currentSpan().getSpanContext().getTraceId();
          });
      h.forceFlush();
      String traceId = ids[2];
      List<JsonNode> spans = reader.waitForSpans(Map.of("trace_id", traceId), 8);
      Map<String, JsonNode> res = byResource(spans);
      JsonNode rootSpan = res.get("it-root");
      assertNotNull(rootSpan, spans.toString());
      assertEquals(svc, rootSpan.path("service").asText());
      assertEquals("it", meta(rootSpan, "env"));
      assertEquals("1.2.3", meta(rootSpan, "version"));
      assertEquals(svc, meta(rootSpan, "grow.run"));
      assertEquals("server", meta(rootSpan, "span.kind"));
      assertEquals("server.request", rootSpan.path("name").asText());
      assertEquals(traceId, rootSpan.path("trace_id_hex").asText());

      // id helpers and log correlation against the Agent's own ids
      DatadogIds.DatadogTraceId dd = DatadogIds.toDatadogTraceId(traceId);
      assertEquals(dd.low64Decimal(), rootSpan.path("trace_id").asText());
      assertEquals(traceId, meta(rootSpan, "otel.trace_id"));
      assertEquals(traceId, DatadogIds.fromDatadogTraceId(rootSpan.path("trace_id").asText(), dd.high64Hex()));
      assertEquals(rootSpan.path("span_id_hex").asText(), DatadogIds.fromDatadogSpanId(rootSpan.path("span_id").asText()));
      assertEquals(rootSpan.path("span_id").asText(), DatadogIds.toDatadogSpanId(rootSpan.path("span_id_hex").asText()));
      String wantDdTrace = dd.is128Bit() ? traceId : rootSpan.path("trace_id").asText();
      assertEquals(wantDdTrace, DatadogIds.datadogTraceIdString(traceId));
      assertEquals(traceId, DatadogIds.normalizeDatadogTraceId(wantDdTrace));
      assertEquals(wantDdTrace, captured[0].get("dd.trace_id"));
      assertEquals(rootSpan.path("span_id").asText(), captured[0].get("dd.span_id"));
      assertEquals(svc, captured[0].get("dd.service"));
      assertEquals("it", captured[0].get("dd.env"));
      assertEquals("1.2.3", captured[0].get("dd.version"));
      assertEquals(traceId, captured[1].get("trace_id"));
      assertEquals(rootSpan.path("span_id_hex").asText(), captured[1].get("span_id"));
      assertEquals(traceId, ids[0]);
      assertEquals(rootSpan.path("span_id_hex").asText(), ids[1]);

      JsonNode fail = res.get("it-child-fail");
      assertEquals(1, fail.path("error").asInt());
      assertEquals(TypeError.class.getCanonicalName(), meta(fail, DatadogSpanErrors.ATTR_ERROR_TYPE));
      assertEquals("value out of range", meta(fail, DatadogSpanErrors.ATTR_ERROR_MESSAGE));
      JsonNode rec = res.get("it-recorded");
      assertEquals(TypeError.class.getCanonicalName(), meta(rec, DatadogSpanErrors.ATTR_ERROR_TYPE));
      assertTrue(meta(rec, DatadogSpanErrors.ATTR_ERROR_STACK).contains("TypeError"));
      JsonNode set = res.get("it-set-error");
      assertEquals(1, set.path("error").asInt());
      assertEquals("explicit failure", meta(set, "error.msg"));
      JsonNode generic = res.get("it-generic");
      assertEquals("generic status", meta(generic, DatadogSpanErrors.ATTR_ERROR_MESSAGE));
      assertEquals("java.lang.IllegalArgumentException", meta(generic, DatadogSpanErrors.ATTR_ERROR_TYPE));
      assertEquals("attrs only", meta(res.get("it-attrs"), DatadogSpanErrors.ATTR_ERROR_MESSAGE));
      JsonNode ok = res.get("it-child-ok");
      assertEquals(7.0, ok.path("metrics").path("n").asDouble());
      assertEquals("true", meta(ok, "flag"));

      // normalized model from the Agent's span format
      Map<String, Span> model = new HashMap<>();
      for (JsonNode s : spans) {
        Span span = DatadogMapping.traceSpanToSpan(s);
        model.put(span.name(), span);
      }
      Span mRoot = model.get("it-root");
      assertEquals(Span.Kind.SERVER, mRoot.kind());
      assertEquals(1, mRoot.events().size());
      assertEquals("checkpoint", mRoot.events().get(0).name());
      assertEquals(1L, mRoot.events().get(0).attributes().get("step"));
      model.forEach(
          (name, s) -> {
            if (!name.equals("it-root")) {
              assertEquals(Optional.of(mRoot.spanId()), s.parentSpanId(), name);
            }
          });
      Span linked = model.get("it-linked");
      assertEquals(1, linked.links().size());
      assertEquals("a3ce929d0e0e47364bf92f3577b34da6", linked.links().get(0).traceId());
      assertEquals("00f067aa0ba902b7", linked.links().get(0).spanId());
      assertEquals(Span.StatusCode.ERROR, model.get("it-child-fail").status().code());

      // the trace-reader as a TraceQueryClient (what the conformance runner uses)
      assertEquals(spans.size(), reader.getTrace(traceId).orElseThrow().spans().size());
      assertTrue(reader.listServices().contains(svc));
      assertTrue(reader.listOperations(svc).contains("it-root"));
      assertEquals(1, reader.searchTraces(TraceSearchQuery.builder().service(svc).build()).size());
      assertThrows(UnsupportedException.class, () -> reader.getDependencies(null, null));
    } finally {
      h.shutdown();
      h.shutdown();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String>[] newMaps(int n) {
    return (Map<String, String>[]) new Map<?, ?>[n];
  }

  @Test
  void initTracingGrpcViaClient() {
    Map<String, String> env = TraceReader.requireService();
    String svc = service("grpc");
    DatadogApmClient c = DatadogApmClient.builder().fromEnv(EnvReader.of(env)).build();
    DatadogTracingHandle h =
        c.initTracing(
            TracingConfig.builder(svc)
                .protocol(TracingConfig.Protocol.GRPC)
                .setGlobal(false)
                .build());
    assertEquals(env.get("GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT"), h.endpoint());
    io.opentelemetry.api.trace.Span span =
        h.tracer("it").spanBuilder("it-grpc-span").setAttribute("via", "grpc").startSpan();
    String traceId = span.getSpanContext().getTraceId();
    span.end();
    h.forceFlush();
    List<JsonNode> spans = new TraceReader(env).waitForSpans(Map.of("trace_id", traceId), 1);
    assertEquals(svc, spans.get(0).path("service").asText());
    assertEquals("it-grpc-span", spans.get(0).path("resource").asText());
    assertEquals("grpc", meta(spans.get(0), "via"));
    c.close();
    c.close();
    assertTrue(h.isShutdown());
  }

  @Test
  void initTracingFromProcessEnvAndClientDefaults() {
    Map<String, String> env = TraceReader.requireService();
    TraceReader reader = new TraceReader(env);
    // process env: only GROW_DATADOG_APM_URL -> OTLP on the same host, port 4318
    String svc = service("env");
    DatadogTracingHandle h =
        DatadogTracing.initTracing(TracingConfig.builder(svc).setGlobal(false).build());
    assertEquals(env.get("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT") + "/v1/traces", h.endpoint());
    io.opentelemetry.api.trace.Span s = h.tracer("it").spanBuilder("it-env").startSpan();
    String traceId = s.getSpanContext().getTraceId();
    s.end();
    h.forceFlush();
    h.shutdown();
    assertEquals(svc, reader.waitForSpans(Map.of("trace_id", traceId), 1).get(0).path("service").asText());
    assertThrows(ConfigException.class, DatadogTracing::initTracing); // no DD_SERVICE in the process env

    // every tracing setting from the client configuration (tracingConfig + initTracing())
    String svc2 = service("cfg");
    try (DatadogApmClient c =
        DatadogApmClient.builder()
            .fromEnv(EnvReader.of(env))
            .serviceName(svc2)
            .environment("it-cfg")
            .tag("team", "sdk")
            .build()) {
      TracingConfig cfg = DatadogTracing.tracingConfig(c.config()).setGlobal(false).build();
      DatadogTracingHandle h2 = c.initTracing(cfg);
      io.opentelemetry.api.trace.Span s2 = h2.tracer("it").spanBuilder("it-cfg").startSpan();
      String trace2 = s2.getSpanContext().getTraceId();
      s2.end();
      h2.forceFlush();
      JsonNode got = reader.waitForSpans(Map.of("trace_id", trace2), 1).get(0);
      assertEquals(svc2, got.path("service").asText());
      assertEquals("it-cfg", meta(got, "env"));
      assertEquals("sdk", meta(got, "team"));
    }
  }

  @Test
  void datadogPropagationThroughAgent() throws Exception {
    Map<String, String> env = TraceReader.requireService();
    DatadogTracingHandle h = init(env, service("prop"));
    try {
      io.opentelemetry.api.trace.Span parent =
          h.tracer("it").spanBuilder("it-upstream").setNoParent().startSpan();
      Context ctx = Context.root().with(parent);
      Map<String, String> headers = new HashMap<>();
      Spans.inject(ctx, headers, h.propagators());
      Map<String, String> ddHeaders = new HashMap<>();
      DatadogPropagator.getInstance().inject(ctx, ddHeaders, Map::put);
      SpanContext sc = parent.getSpanContext();
      DatadogIds.DatadogTraceId dd = DatadogIds.toDatadogTraceId(sc.getTraceId());
      String spanDec = DatadogIds.toDatadogSpanId(sc.getSpanId());
      assertEquals(dd.low64Decimal(), headers.get("x-datadog-trace-id"));
      assertEquals(spanDec, headers.get("x-datadog-parent-id"));
      assertEquals("1", headers.get("x-datadog-sampling-priority"));
      assertEquals(dd.is128Bit() ? "_dd.p.tid=" + dd.high64Hex() : null, headers.get("x-datadog-tags"));
      assertTrue(headers.get("traceparent").contains(sc.getTraceId()));
      assertEquals(dd.low64Decimal(), ddHeaders.get("x-datadog-trace-id"));
      assertTrue(!ddHeaders.containsKey("traceparent"));

      // downstream: only Datadog headers (what a ddtrace service sends)
      Map<String, String> ddOnly = new HashMap<>();
      ddOnly.put("x-datadog-trace-id", headers.get("x-datadog-trace-id"));
      ddOnly.put("x-datadog-parent-id", headers.get("x-datadog-parent-id"));
      ddOnly.put("x-datadog-sampling-priority", "1");
      if (dd.is128Bit()) {
        ddOnly.put("x-datadog-tags", "_dd.p.tid=" + dd.high64Hex());
      }
      assertEquals(
          sc.getTraceId(), DatadogPropagator.extractDatadogContext(ddOnly).orElseThrow().getTraceId());
      Context downstream = Spans.extract(ddOnly, h.propagators());
      assertEquals(sc.getTraceId(), io.opentelemetry.api.trace.Span.fromContext(downstream).getSpanContext().getTraceId());
      Spans.withSpan(
          "it-downstream",
          SpanOptions.builder().tracer(h.tracer("it")).kind(SpanKind.SERVER).parent(downstream).build(),
          () -> {});

      // conflicting W3C context: Datadog wins, W3C becomes a span link
      Map<String, List<String>> conflictHeaders = new HashMap<>();
      ddOnly.forEach((k, v) -> conflictHeaders.put(k, List.of(v)));
      conflictHeaders.put(
          "traceparent", List.of("00-11111111111111112222222222222222-0000000000000abc-01"));
      Context conflicting = Spans.extractHeaders(conflictHeaders, h.propagators());
      assertEquals(1, DatadogPropagator.terminatedContextLinks(conflicting).size());
      assertEquals(
          sc.getSpanId(),
          DatadogPropagator.terminatedContext(conflicting).orElseThrow().winnerSpanId());
      Scope scope = conflicting.makeCurrent();
      try {
        Spans.withSpan("it-conflict", in(h), () -> {});
      } finally {
        scope.close();
      }
      Map<String, List<String>> out = new HashMap<>();
      Spans.injectHeaders(conflicting, out, h.propagators());
      assertEquals(List.of(spanDec), out.get("x-datadog-parent-id"));
      parent.end();
      h.forceFlush();

      List<JsonNode> spans =
          new TraceReader(env).waitForSpans(Map.of("trace_id", sc.getTraceId()), 3);
      Map<String, JsonNode> res = byResource(spans);
      JsonNode up = res.get("it-upstream");
      JsonNode down = res.get("it-downstream");
      JsonNode conflict = res.get("it-conflict");
      assertEquals(up.path("span_id").asText(), down.path("parent_id").asText());
      assertEquals(up.path("trace_id").asText(), down.path("trace_id").asText());
      assertEquals(up.path("span_id").asText(), conflict.path("parent_id").asText());
      JsonNode links = Json.tree(meta(conflict, "_dd.span_links").getBytes(StandardCharsets.UTF_8));
      assertEquals(1, links.size(), links.toString());
      assertEquals("11111111111111112222222222222222", links.get(0).path("trace_id").asText());
      assertEquals("0000000000000abc", links.get(0).path("span_id").asText());
      assertEquals("terminated_context", links.get(0).path("attributes").path("reason").asText());
    } finally {
      h.shutdown();
    }
  }

  @Test
  void tracedHttpClientThroughAgent() throws Exception {
    Map<String, String> env = TraceReader.requireService();
    DatadogTracingHandle h = init(env, service("adapters"));
    try {
      TracedHttpClient client =
          TracedHttpClient.wrap(HttpClient.newHttpClient(), h.tracer("it"), h.propagators());
      String[] traceId = new String[1];
      Spans.withSpan(
          "it-fetch-parent",
          in(h),
          () -> {
            HttpResponse<String> r =
                client.send(
                    HttpRequest.newBuilder(URI.create(env.get("GROW_DATADOG_APM_URL") + "/info")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            traceId[0] = Spans.currentTraceId().orElseThrow();
          });
      h.forceFlush();
      List<JsonNode> spans = new TraceReader(env).waitForSpans(Map.of("trace_id", traceId[0]), 2);
      long clients =
          spans.stream().filter(s -> "client".equals(meta(s, "span.kind"))).count();
      assertEquals(1, clients, spans.toString());
    } finally {
      h.shutdown();
    }
  }
}
