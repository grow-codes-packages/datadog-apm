package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;

import codes.grow.packages.datadogapm.testing.tracing.InMemorySpanExporter;
import codes.grow.packages.datadogapm.tracing.Spans;
import codes.grow.packages.datadogapm.tracing.Tracing;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import codes.grow.packages.datadogapm.tracing.TracingHandle;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatadogPropagatorTest {
  private static final DatadogPropagator DD = DatadogPropagator.getInstance();
  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private final InMemorySpanExporter exporter = new InMemorySpanExporter();
  private TracingHandle handle;

  @AfterEach
  void stop() {
    if (handle != null) {
      handle.shutdown();
    }
  }

  private TracingHandle pipeline() {
    handle =
        Tracing.initWith(
            TracingConfig.builder("prop-test")
                .setGlobal(false)
                .propagators(
                    DatadogTracing.orderPropagators(DatadogTracing.DATADOG_DEFAULT_PROPAGATORS))
                .batch(
                    new TracingConfig.BatchSettings(
                        2048, 512, Duration.ofMillis(50), Duration.ofSeconds(5)))
                .build(),
            c -> new DatadogSpanExporter(exporter),
            Map.of(DatadogPropagator.NAME, DD));
    return handle;
  }

  private static Context remote(String traceId, String spanId, boolean sampled) {
    return Context.root()
        .with(
            Span.wrap(
                SpanContext.createFromRemoteParent(
                    traceId,
                    spanId,
                    sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                    TraceState.getDefault())));
  }

  @Test
  void injectMatchesDdtraceVectors() {
    for (JsonNode v : Fixtures.load("propagation.datadog_inject.vectors.json")) {
      JsonNode ctx = v.get("context");
      Map<String, String> out = new LinkedHashMap<>();
      DD.inject(
          remote(
              ctx.get("trace_id_hex").asText(),
              ctx.get("span_id_hex").asText(),
              ctx.get("sampling_priority").asInt() == 1),
          out,
          Map::put);
      Map<String, String> want = new LinkedHashMap<>();
      v.get("reference_headers")
          .properties()
          .forEach(e -> want.put(e.getKey(), e.getValue().asText()));
      assertThat(out).isEqualTo(want);
    }
    Map<String, String> none = new HashMap<>();
    DD.inject(Context.root(), none, Map::put);
    DD.inject(remote("0000000000000000000000000000002a", "0000000000000001", true), none, null);
    assertThat(none).isEmpty();
    assertThat(DD.fields()).hasSize(4);
    assertThat(DD.toString()).isEqualTo("DatadogPropagator");
  }

  @Test
  void extractMatchesDdtraceVectors() {
    for (JsonNode v : Fixtures.load("propagation.datadog_extract.vectors.json")) {
      Map<String, String> headers = new HashMap<>();
      v.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
      SpanContext sc =
          Span.fromContext(DD.extract(Context.root(), headers, GETTER)).getSpanContext();
      JsonNode want = v.get("ddtrace_context");
      assertThat(sc.getTraceId()).isEqualTo(want.get("trace_id_hex").asText());
      assertThat(sc.getSpanId()).isEqualTo(want.get("span_id_hex").asText());
      assertThat(sc.isRemote()).isTrue();
      if (want.has("sampling_priority")) {
        assertThat(sc.isSampled()).isEqualTo(want.get("sampling_priority").asInt() > 0);
      }
    }
  }

  @Test
  void extractEdgeCases() {
    assertThat(DatadogPropagator.extractDatadogContext(Map.of())).isEmpty();
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of("X-Datadog-Trace-Id", "42", "x-datadog-parent-id", "1")))
        .get()
        .satisfies(sc -> assertThat(sc.isSampled()).isTrue());
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of("x-datadog-trace-id", "abc", "x-datadog-parent-id", "1")))
        .isEmpty();
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of("x-datadog-trace-id", "42", "x-datadog-parent-id", "1".repeat(21))))
        .isEmpty();
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of("x-datadog-trace-id", "99999999999999999999", "x-datadog-parent-id", "1")))
        .isEmpty();
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of("x-datadog-trace-id", "0", "x-datadog-parent-id", "0")))
        .isEmpty();
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of("x-datadog-trace-id", "42", "x-datadog-parent-id", "0")))
        .isEmpty();
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of(
                    "x-datadog-trace-id", "42",
                    "x-datadog-parent-id", "1",
                    "x-datadog-sampling-priority", "-1",
                    "x-datadog-tags", "_dd.p.dm=-4,_dd.p.tid=zz,x")))
        .get()
        .satisfies(
            sc -> {
              assertThat(sc.isSampled()).isFalse();
              assertThat(sc.getTraceId()).startsWith("0000000000000000");
            });
    assertThat(
            DatadogPropagator.extractDatadogContext(
                Map.of(
                    "x-datadog-trace-id", "42",
                    "x-datadog-parent-id", "1",
                    "x-datadog-sampling-priority", "x",
                    "x-datadog-tags", "=1, _dd.p.tid = ABCDEF0123456789")))
        .get()
        .satisfies(
            sc -> {
              assertThat(sc.isSampled()).isTrue();
              assertThat(sc.getTraceId()).startsWith("abcdef0123456789");
            });
    assertThat(DatadogPropagator.extractDatadogContext(Map.of("x-datadog-trace-id", " ")))
        .isEmpty();
    assertThat(DatadogPropagator.parseTid(null)).isNull();
    assertThat(DatadogPropagator.parseTid("a=b")).isNull();
    assertThat(DatadogPropagator.parseTraceparent(null)).isNull();
    assertThat(
            DatadogPropagator.parseTraceparent(
                "ff-" + "1".repeat(32) + "-" + "1".repeat(16) + "-01"))
        .isNull();
    assertThat(
            DatadogPropagator.parseTraceparent(
                "00-" + "0".repeat(32) + "-" + "1".repeat(16) + "-01"))
        .isNull();
    assertThat(DatadogPropagator.parseTraceparent("garbage")).isNull();
    assertThat(
            DatadogPropagator.parseTraceparent(
                    "00-" + "1".repeat(32) + "-" + "1".repeat(16) + "-00-x")
                .isSampled())
        .isFalse();
    Context ctx = Context.root();
    assertThat(DD.extract(ctx, Map.of(), GETTER)).isSameAs(ctx);
    assertThat(DD.extract(null, Map.of(), GETTER)).isSameAs(Context.root());
    assertThat(DD.extract(ctx, Map.of(), null)).isSameAs(ctx);
    assertThat(DatadogPropagator.terminatedContext((Context) null)).isEmpty();
  }

  private static Map<String, String> ddHeaders(String traceDec, String parentDec) {
    Map<String, String> m = new HashMap<>();
    m.put("x-datadog-trace-id", traceDec);
    m.put("x-datadog-parent-id", parentDec);
    m.put("x-datadog-sampling-priority", "1");
    return m;
  }

  @Test
  void sameTraceTakesTheW3cParent() {
    ContextPropagators props = pipeline().propagators();
    Map<String, String> h = ddHeaders("42", "1");
    h.put("traceparent", "00-0000000000000000000000000000002a-00000000000000ff-01");
    h.put("tracestate", "dd=s:1,other=x");
    SpanContext sc = Span.fromContext(Spans.extract(h, props)).getSpanContext();
    assertThat(sc.getSpanId()).isEqualTo("00000000000000ff");
    assertThat(sc.getTraceState().get("other")).isEqualTo("x");
    // datadog-only propagator: same trace, no W3C result in the context
    SpanContext alone = Span.fromContext(DD.extract(Context.root(), h, GETTER)).getSpanContext();
    assertThat(alone.getSpanId()).isEqualTo("00000000000000ff");
    assertThat(alone.isSampled()).isTrue();
    assertThat(DatadogPropagator.terminatedContext(Spans.extract(h, props))).isEmpty();
  }

  @Test
  void conflictingW3cBecomesATerminatedLink() {
    ContextPropagators props = pipeline().propagators();
    Map<String, String> h = ddHeaders("42", "1");
    h.put("traceparent", "00-11111111111111112222222222222222-0000000000000abc-01");
    Context extracted = Spans.extract(h, props);
    SpanContext winner = Span.fromContext(extracted).getSpanContext();
    assertThat(winner.getTraceId()).isEqualTo("0000000000000000000000000000002a");
    assertThat(winner.getSpanId()).isEqualTo("0000000000000001");
    assertThat(winner.isRemote()).isTrue();
    assertThat(winner.getTraceFlags()).isEqualTo(TraceFlags.getSampled());
    assertThat(winner.getTraceState()).isEqualTo(TraceState.getDefault());
    assertThat(winner.toString()).contains("2a");
    assertThat(winner)
        .isEqualTo(Span.fromContext(DD.extract(Context.root(), h, GETTER)).getSpanContext());
    assertThat(winner.hashCode()).isNotZero();
    assertThat(winner).isNotEqualTo(SpanContext.getInvalid());
    DatadogPropagator.TerminatedContext t =
        DatadogPropagator.terminatedContext(extracted).orElseThrow();
    assertThat(t.winnerSpanId()).isEqualTo("0000000000000001");
    List<LinkData> links = DatadogPropagator.terminatedContextLinks(extracted);
    assertThat(links)
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.getSpanContext().getTraceId())
                  .isEqualTo("11111111111111112222222222222222");
              assertThat(l.getAttributes().get(AttributeKey.stringKey("reason")))
                  .isEqualTo("terminated_context");
            });
    assertThat(DatadogPropagator.terminatedContextLinks(Context.root())).isEmpty();

    Map<String, String> out = new HashMap<>();
    Spans.inject(extracted, out, props);
    assertThat(out).containsEntry("x-datadog-parent-id", "1");
    assertThat(out.get("traceparent")).contains("0000000000000000000000000000002a");

    Scope s = extracted.makeCurrent();
    try {
      Spans.withSpan("child", () -> Spans.withSpan("grandchild", () -> {}));
    } finally {
      s.close();
    }
    handle.forceFlush();
    SpanData child =
        exporter.spans().stream()
            .filter(sd -> sd.getName().equals("child"))
            .findFirst()
            .orElseThrow();
    SpanData grand =
        exporter.spans().stream()
            .filter(sd -> sd.getName().equals("grandchild"))
            .findFirst()
            .orElseThrow();
    assertThat(child.getLinks())
        .singleElement()
        .satisfies(l -> assertThat(l.getSpanContext().getSpanId()).isEqualTo("0000000000000abc"));
    assertThat(child.getTotalRecordedLinks()).isEqualTo(1);
    assertThat(grand.getLinks()).isEmpty();
  }
}
