package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.MockServer;
import codes.grow.packages.datadogapm.core.TlsOptions;
import codes.grow.packages.datadogapm.testing.tracing.InMemorySpanExporter;
import codes.grow.packages.datadogapm.tracing.Spans;
import codes.grow.packages.datadogapm.tracing.Tracing;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import codes.grow.packages.datadogapm.tracing.TracingHandle;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogTracingTest {
  private static final TracingConfig.BatchSettings FAST =
      new TracingConfig.BatchSettings(2048, 512, Duration.ofMillis(50), Duration.ofSeconds(5));
  private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
  private static final AttributeKey<String> ERROR_MESSAGE = AttributeKey.stringKey("error.message");
  private static final AttributeKey<String> ERROR_STACK = AttributeKey.stringKey("error.stack");

  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void orderPropagators() {
    assertThat(DatadogTracing.orderPropagators(List.of("datadog", "tracecontext", "baggage")))
        .containsExactly("tracecontext", "datadog", "baggage");
    assertThat(DatadogTracing.orderPropagators(List.of(" B3 ", "Datadog", "x", "TraceContext")))
        .containsExactly("b3", "tracecontext", "datadog", "x");
    assertThat(DatadogTracing.orderPropagators(List.of("tracecontext", "datadog")))
        .containsExactly("tracecontext", "datadog");
    assertThat(DatadogTracing.orderPropagators(List.of("datadog"))).containsExactly("datadog");
    assertThat(DatadogTracing.orderPropagators(List.of("tracecontext")))
        .containsExactly("tracecontext");
  }

  @Test
  void initTracingExportsOtlpToTheAgentEndpoint() throws Exception {
    DatadogApmConfig apm =
        DatadogApmConfig.builder()
            .otlpEndpoint(server.url())
            .serviceVersion("9.9")
            .environment("ci")
            .tag("team", "sdk")
            .build();
    DatadogTracingHandle h =
        DatadogTracing.initTracing(
            TracingConfig.builder("svc-a")
                .setGlobal(false)
                .batch(FAST)
                .resourceAttributes(Map.of("grow.run", "r1"))
                .build(),
            apm);
    try {
      assertThat(h.endpoint()).isEqualTo(server.url() + "/v1/traces");
      assertThat(h.config().propagators()).containsExactly("tracecontext", "datadog", "baggage");
      assertThat(h.config().serviceVersion()).contains("9.9");
      assertThat(h.config().environment()).contains("ci");
      assertThat(h.config().resourceAttributes())
          .containsEntry("team", "sdk")
          .containsEntry("grow.run", "r1")
          .containsEntry("deployment.environment.name", "ci");
      assertThat(h.propagators().getTextMapPropagator().fields())
          .contains("x-datadog-trace-id", "traceparent", "baggage");
      assertThat(h.handle()).isNotNull();
      assertThat(h.openTelemetry()).isNotNull();
      assertThat(h.toString()).contains(h.endpoint());
      Span s = h.tracer("t", "1.0").spanBuilder("op").startSpan();
      Map<String, String> fields;
      Scope scope = s.makeCurrent();
      try {
        fields = DatadogLogCorrelation.datadogLogFields();
        DatadogSpanErrors.recordException(new IllegalStateException("bad"));
      } finally {
        scope.close();
      }
      s.end();
      h.tracer("t").spanBuilder("op2").startSpan().end();
      h.forceFlush();
      h.forceFlush(Duration.ofSeconds(5));
      h.forceFlushAsync(Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
      assertThat(fields)
          .containsEntry("dd.service", "svc-a")
          .containsEntry("dd.env", "ci")
          .containsEntry("dd.version", "9.9")
          .containsKeys("dd.trace_id", "dd.span_id");
      assertThat(server.requests()).isNotEmpty();
      assertThat(server.requests().get(0).uri()).isEqualTo("/v1/traces");
      assertThat(server.requests().get(0).header("Content-Type"))
          .isEqualTo("application/x-protobuf");
      // without a span: the defaults of the last initTracing
      assertThat(DatadogLogCorrelation.datadogLogFields())
          .doesNotContainKey("dd.trace_id")
          .containsEntry("dd.service", "svc-a");
      assertThat(DatadogLogCorrelation.datadogLogFields("x", "y", "z"))
          .containsEntry("dd.service", "x")
          .containsEntry("dd.env", "y")
          .containsEntry("dd.version", "z");
    } finally {
      h.shutdown();
      h.close();
      h.shutdownAsync().get(5, TimeUnit.SECONDS);
    }
    assertThat(h.isShutdown()).isTrue();
  }

  @Test
  void initTracingProtocolAndEndpointResolution() {
    DatadogApmConfig apm =
        DatadogApmConfig.builder()
            .url("http://agent:8126")
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build();
    DatadogTracingHandle grpc =
        DatadogTracing.initTracing(
            TracingConfig.builder("g")
                .setGlobal(false)
                .protocol(TracingConfig.Protocol.GRPC)
                .propagators(List.of("tracecontext"))
                .build(),
            apm);
    assertThat(grpc.endpoint()).isEqualTo("http://agent:4317");
    assertThat(grpc.config().propagators()).containsExactly("tracecontext");
    assertThat(grpc.config().tls()).isPresent();
    grpc.shutdown();
    DatadogTracingHandle explicit =
        DatadogTracing.initTracing(
            TracingConfig.builder("e").setGlobal(false).endpoint("http://col:4318").build(), apm);
    assertThat(explicit.endpoint()).isEqualTo("http://col:4318/v1/traces");
    explicit.shutdown();
    // the template default protocol (http/protobuf) counts as unset: the configured one applies
    DatadogTracingHandle configured =
        DatadogTracing.initTracing(
            TracingConfig.builder("c").setGlobal(false).build(),
            apm.toBuilder().protocol(TracingConfig.Protocol.GRPC).build());
    assertThat(configured.endpoint()).isEqualTo("http://agent:4317");
    assertThat(configured.config().protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    configured.shutdown();
    DatadogTracingHandle fromEnv =
        DatadogTracing.initTracing(TracingConfig.builder("env").setGlobal(false).build());
    assertThat(fromEnv.endpoint()).endsWith("/v1/traces");
    fromEnv.shutdown();
    DatadogTracingHandle grpcEnv =
        DatadogTracing.initTracing(
            TracingConfig.builder("env")
                .setGlobal(false)
                .protocol(TracingConfig.Protocol.GRPC)
                .build());
    assertThat(grpcEnv.endpoint()).endsWith(":4317");
    grpcEnv.shutdown();
    assertThatThrownBy(
            () ->
                DatadogTracing.initTracing(
                    TracingConfig.builder("n").protocol(TracingConfig.Protocol.NATIVE).build()))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(
            () ->
                DatadogTracing.initTracing(
                    TracingConfig.builder("n").protocol(TracingConfig.Protocol.NATIVE).build(),
                    apm))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(
            () ->
                DatadogTracing.createExporter(
                    TracingConfig.builder("n").protocol(TracingConfig.Protocol.NATIVE).build()))
        .isInstanceOf(ConfigException.class);
    SpanExporter e = DatadogTracing.exporterFactory().create(TracingConfig.builder("d").build());
    assertThat(e).isInstanceOf(DatadogSpanExporter.class);
    assertThat(e.toString()).contains("DatadogSpanExporter");
    e.shutdown().join(5, TimeUnit.SECONDS);
    SpanExporter g =
        DatadogTracing.createExporter(
            TracingConfig.builder("d").protocol(TracingConfig.Protocol.GRPC).build());
    assertThat(((DatadogSpanExporter) g).delegate()).isNotNull();
    g.flush().join(5, TimeUnit.SECONDS);
    g.shutdown().join(5, TimeUnit.SECONDS);
  }

  @Test
  void tracingConfigFromApm() {
    DatadogApmConfig apm =
        DatadogApmConfig.builder()
            .serviceName("svc")
            .protocol(TracingConfig.Protocol.GRPC)
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build();
    TracingConfig c = DatadogTracing.tracingConfig(apm).build();
    assertThat(c.serviceName()).isEqualTo("svc");
    assertThat(c.protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    assertThat(c.endpoint()).contains("http://127.0.0.1:4317");
    assertThat(c.propagators()).isEqualTo(DatadogTracing.DATADOG_DEFAULT_PROPAGATORS);
    assertThat(c.tls()).isPresent();
    assertThat(
            DatadogTracing.tracingConfig(DatadogApmConfig.builder().serviceName("x").build())
                .build()
                .tls())
        .isEmpty();
    assertThatThrownBy(() -> DatadogTracing.tracingConfig(DatadogApmConfig.builder().build()))
        .hasMessageContaining("DD_SERVICE");
    if (System.getenv("DD_SERVICE") == null
        && System.getenv("GROW_DATADOG_APM_SERVICE") == null
        && System.getenv("OTEL_SERVICE_NAME") == null) {
      assertThatThrownBy(DatadogTracing::initTracing).isInstanceOf(ConfigException.class);
    }
  }

  private static TracingHandle pipeline(InMemorySpanExporter exporter) {
    return Tracing.initWith(
        TracingConfig.builder("errors").setGlobal(false).batch(FAST).build(),
        c -> new DatadogSpanExporter(exporter));
  }

  private static SpanData named(InMemorySpanExporter e, String name) {
    return e.spans().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void errorAttributes() {
    InMemorySpanExporter exporter = new InMemorySpanExporter();
    TracingHandle h = pipeline(exporter);
    try {
      Spans.withSpan(
          "recorded",
          SpanOptionsHolder.of(h),
          () -> DatadogSpanErrors.recordException(new IllegalArgumentException("bad arg")));
      Spans.withSpan(
          "set-error",
          SpanOptionsHolder.of(h),
          () -> DatadogSpanErrors.setError(new IllegalStateException("explicit")));
      Spans.withSpan(
          "generic",
          SpanOptionsHolder.of(h),
          () -> {
            Spans.recordException(new RuntimeException("first"));
            Spans.setError(new UnsupportedOperationException("generic status"));
          });
      Spans.withSpan("status-only", SpanOptionsHolder.of(h), () -> Spans.setError("only status"));
      Spans.withSpan(
          "attrs-only",
          SpanOptionsHolder.of(h),
          () -> {
            DatadogSpanErrors.setErrorAttributes(new RuntimeException("attrs"));
            Spans.recordException(new IllegalStateException("ignored later"));
          });
      Spans.withSpan(
          "null-ignored",
          SpanOptionsHolder.of(h),
          () -> {
            DatadogSpanErrors.recordException(null);
            DatadogSpanErrors.setError(null);
            DatadogSpanErrors.setErrorAttributes(Span.current(), null);
          });
      try {
        Spans.withSpan(
            "thrown",
            SpanOptionsHolder.of(h),
            () -> {
              throw new IllegalStateException();
            });
      } catch (IllegalStateException expected) {
        // rethrown by withSpan
      }
      Spans.withSpan(
          "event-no-strings",
          SpanOptionsHolder.of(h),
          () -> Span.current().addEvent("exception", Attributes.empty()));
      h.forceFlush();
      SpanData rec = named(exporter, "recorded");
      assertThat(rec.getAttributes().get(ERROR_TYPE))
          .isEqualTo("java.lang.IllegalArgumentException");
      assertThat(rec.getAttributes().get(ERROR_MESSAGE)).isEqualTo("bad arg");
      assertThat(rec.getAttributes().get(ERROR_STACK)).contains("IllegalArgumentException");
      assertThat(rec.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
      SpanData set = named(exporter, "set-error");
      assertThat(set.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
      assertThat(set.getAttributes().get(ERROR_MESSAGE)).isEqualTo("explicit");
      SpanData generic = named(exporter, "generic");
      assertThat(generic.getAttributes().get(ERROR_TYPE))
          .isEqualTo("java.lang.UnsupportedOperationException");
      assertThat(generic.getAttributes().get(ERROR_MESSAGE)).isEqualTo("generic status");
      assertThat(generic.getAttributes().get(ERROR_STACK)).isNotEmpty();
      assertThat(generic.getTotalAttributeCount()).isEqualTo(generic.getAttributes().size());
      SpanData status = named(exporter, "status-only");
      assertThat(status.getAttributes().get(ERROR_MESSAGE)).isEqualTo("only status");
      assertThat(status.getAttributes().get(ERROR_TYPE)).isNull();
      assertThat(named(exporter, "attrs-only").getAttributes().get(ERROR_MESSAGE))
          .isEqualTo("attrs");
      assertThat(named(exporter, "null-ignored").getAttributes().get(ERROR_TYPE)).isNull();
      SpanData thrown = named(exporter, "thrown");
      assertThat(thrown.getAttributes().get(ERROR_TYPE))
          .isEqualTo("java.lang.IllegalStateException");
      assertThat(thrown.getAttributes().get(ERROR_MESSAGE)).isEqualTo("IllegalStateException");
      assertThat(named(exporter, "event-no-strings").getAttributes().get(ERROR_TYPE)).isNull();
    } finally {
      h.shutdown();
    }
    assertThat(DatadogSpanErrors.datadogErrorAttributes(null)).isEmpty();
    assertThat(DatadogSpanErrors.datadogErrorAttributes(new RuntimeException() {}))
        .containsKey("error.type")
        .doesNotContainKey("error.message");
  }

  @Test
  void exporterDelegates() {
    InMemorySpanExporter inner = new InMemorySpanExporter();
    DatadogSpanExporter e = new DatadogSpanExporter(inner);
    CompletableResultCode flush = e.flush();
    assertThat(flush.isSuccess()).isTrue();
    assertThat(e.shutdown().isSuccess()).isTrue();
    assertThat(inner.isShutdown()).isTrue();
  }

  /** Spans.withSpan options bound to a handle's tracer. */
  private static final class SpanOptionsHolder {
    static codes.grow.packages.datadogapm.tracing.SpanOptions of(TracingHandle h) {
      return codes.grow.packages.datadogapm.tracing.SpanOptions.builder()
          .tracer(h.tracer("t"))
          .build();
    }
  }
}
