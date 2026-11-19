// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.datadogapm.examples;

import codes.grow.packages.datadogapm.DatadogApmClient;
import codes.grow.packages.datadogapm.DatadogApmConfig;
import codes.grow.packages.datadogapm.DatadogLogCorrelation;
import codes.grow.packages.datadogapm.DatadogSpan;
import codes.grow.packages.datadogapm.DatadogSpanErrors;
import codes.grow.packages.datadogapm.DatadogSpansQuery;
import codes.grow.packages.datadogapm.DatadogTracing;
import codes.grow.packages.datadogapm.DatadogTracingHandle;
import codes.grow.packages.datadogapm.SearchSpansOptions;
import codes.grow.packages.datadogapm.core.DatadogApmException;
import codes.grow.packages.datadogapm.core.HealthStatus;
import codes.grow.packages.datadogapm.tracing.SpanOptions;
import codes.grow.packages.datadogapm.tracing.Spans;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import io.opentelemetry.api.trace.SpanKind;
import java.util.HashMap;
import java.util.Map;

/**
 * Basics: configuration from the environment, Agent health and version, OpenTelemetry tracing to
 * the Datadog Agent (OTLP), Datadog log correlation, header propagation and, when an API key and
 * an application key are set, the spans query API.
 *
 * <pre>
 * DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev mvn -q compile exec:java
 * # optional query API: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
 * </pre>
 */
public final class BasicExample {
  private BasicExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    // Reads DD_AGENT_HOST, DD_TRACE_AGENT_PORT, DD_SERVICE, DD_ENV, DD_VERSION, DD_TAGS, DD_API_KEY,
    // DD_APP_KEY, DD_SITE and GROW_DATADOG_APM_* (which win over DD_*).
    DatadogApmConfig config = DatadogApmConfig.fromEnv();
    if (config.serviceName().isEmpty()) {
      config = config.toBuilder().serviceName("grow-datadog-apm-example").build();
    }
    System.out.println("config: " + config); // keys print as [REDACTED]
    try (DatadogApmClient client = DatadogApmClient.create(config)) {
      HealthStatus health = client.health(); // never throws for an unreachable Agent
      System.out.println("agent health: " + health);
      if (!health.healthy()) {
        return;
      }
      System.out.println("agent version: " + client.serverVersion());

      // OTLP to the Agent (http://<agent>:4318/v1/traces), Datadog + W3C propagation.
      DatadogTracingHandle tracing = client.initTracing();
      System.out.println("exporting to " + tracing.endpoint());
      SpanOptions server = SpanOptions.builder().kind(SpanKind.SERVER).build();
      String traceId =
          Spans.withSpan(
              "checkout",
              server,
              () -> {
                // merge into your logger's MDC so Datadog links logs and traces
                System.out.println("log fields: " + DatadogLogCorrelation.datadogLogFields());
                Map<String, String> headers = new HashMap<>();
                Spans.inject(headers); // x-datadog-* + traceparent for the downstream call
                System.out.println("outgoing headers: " + headers);
                try {
                  throw new IllegalStateException("card declined");
                } catch (IllegalStateException e) {
                  DatadogSpanErrors.setError(e); // error.type / error.message / error.stack
                }
                return Spans.currentTraceId().orElse("");
              });
      tracing.forceFlush();
      System.out.println("trace " + traceId + " sent to the Agent");

      if (config.hasQueryCredentials()) {
        DatadogSpansQuery spans = client.spans();
        try {
          System.out.println("query API: " + spans.ping().toMillis() + " ms");
          SearchSpansOptions search =
              SearchSpansOptions.builder()
                  .query("service:" + config.serviceName().get())
                  .limit(5)
                  .build();
          int n = 0;
          for (DatadogSpan s : spans.searchSpans(search)) {
            System.out.println("indexed span: " + s.span().name());
            if (++n == 5) {
              break;
            }
          }
          System.out.println("services: " + spans.listServices());
        } catch (DatadogApmException e) {
          System.out.println("query API: " + e.code() + " " + e.getMessage()); // keys redacted
        }
      }
    } // close() flushes and shuts down the tracing it started
    // Without a client: DatadogTracing.initTracing(TracingConfig.builder("checkout").build())
    System.out.println(TracingConfig.DEFAULT_PROPAGATORS + " -> " + DatadogTracing.DATADOG_DEFAULT_PROPAGATORS);
  }
}
