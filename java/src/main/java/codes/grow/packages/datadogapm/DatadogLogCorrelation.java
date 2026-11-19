package codes.grow.packages.datadogapm;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Datadog log correlation (DECISIONS.md #3). Same field format as ddtrace's log injection: {@code
 * dd.trace_id} is 32 lower-case hex for a 128-bit id and the decimal low 64 bits for a 64-bit id;
 * {@code dd.span_id} is decimal.
 */
public final class DatadogLogCorrelation {
  /** {@code dd.trace_id}. */
  public static final String TRACE_ID = "dd.trace_id";

  /** {@code dd.span_id}. */
  public static final String SPAN_ID = "dd.span_id";

  /** {@code dd.service}. */
  public static final String SERVICE = "dd.service";

  /** {@code dd.env}. */
  public static final String ENV = "dd.env";

  /** {@code dd.version}. */
  public static final String VERSION = "dd.version";

  private static volatile String[] defaults = new String[3];

  private DatadogLogCorrelation() {}

  /** Sets the service/env/version used when the current span carries no resource. */
  static void setDefaults(String service, String env, String version) {
    defaults = new String[] {service, env, version};
  }

  /**
   * {@link #datadogLogFields(String, String, String)} without explicit values.
   *
   * <pre>{@code
   * logger.info("checkout done {}", DatadogLogCorrelation.datadogLogFields());
   * }</pre>
   *
   * @return fields; keys are absent when unknown
   */
  public static Map<String, String> datadogLogFields() {
    return datadogLogFields(null, null, null);
  }

  /**
   * The Datadog log correlation fields for the current span, ready to merge into any structured log
   * record (for example SLF4J MDC): {@code dd.trace_id}, {@code dd.span_id}, {@code dd.service},
   * {@code dd.env}, {@code dd.version}. Service, env and version come from the arguments, else the
   * current span's resource ({@code service.name}, {@code deployment.environment.name}, {@code
   * service.version}), else the last {@link DatadogTracing#initTracing} config. Without a valid
   * span only the service fields are returned.
   *
   * @param service explicit service or {@code null}
   * @param env explicit env or {@code null}
   * @param version explicit version or {@code null}
   * @return fields; keys are absent when unknown
   */
  public static Map<String, String> datadogLogFields(String service, String env, String version) {
    Map<String, String> out = new LinkedHashMap<>();
    Span span = Span.current();
    SpanContext sc = span.getSpanContext();
    if (sc.isValid()) {
      out.put(TRACE_ID, DatadogIds.datadogTraceIdString(sc.getTraceId()));
      out.put(SPAN_ID, DatadogIds.toDatadogSpanId(sc.getSpanId()));
    }
    Resource res =
        span instanceof ReadableSpan ? ((ReadableSpan) span).toSpanData().getResource() : null;
    String[] d = defaults;
    put(out, SERVICE, first(service, value(res, "service.name"), d[0]));
    put(
        out,
        ENV,
        first(
            env,
            value(res, "deployment.environment.name"),
            value(res, "deployment.environment"),
            d[1]));
    put(out, VERSION, first(version, value(res, "service.version"), d[2]));
    return out;
  }

  private static String value(Resource res, String key) {
    return res == null ? null : res.getAttribute(AttributeKey.stringKey(key));
  }

  private static String first(String... values) {
    for (String v : values) {
      if (v != null && !v.isEmpty()) {
        return v;
      }
    }
    return null;
  }

  private static void put(Map<String, String> out, String key, String value) {
    if (value != null) {
      out.put(key, value);
    }
  }
}
