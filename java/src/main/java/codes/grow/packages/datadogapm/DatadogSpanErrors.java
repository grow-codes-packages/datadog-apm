package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.tracing.Spans;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datadog error attributes (DECISIONS.md #2). The Datadog Agent keeps an OTLP exception only inside
 * the {@code events} JSON and does not fill {@code error.type} / {@code error.stack}, so this
 * package sets {@code error.type}, {@code error.message} and {@code error.stack} span attributes
 * itself, which Datadog Error Tracking reads.
 *
 * <p>{@link #recordException} and {@link #setError} replace the generic helpers of {@link Spans} of
 * the same name. The generic helpers (and {@code withSpan}, {@code @Traced}, the HTTP adapters and
 * plain OpenTelemetry {@code recordException} calls) also get the attributes when spans are
 * exported through {@link DatadogTracing#initTracing}: its exporter fills them from the exception
 * event ({@link #withDatadogErrorAttributes(SpanData)}).
 */
public final class DatadogSpanErrors {
  /** {@code error.type} span attribute (exception class name). */
  public static final String ATTR_ERROR_TYPE = "error.type";

  /** {@code error.message} span attribute. */
  public static final String ATTR_ERROR_MESSAGE = "error.message";

  /** {@code error.stack} span attribute. */
  public static final String ATTR_ERROR_STACK = "error.stack";

  private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey(ATTR_ERROR_TYPE);
  private static final AttributeKey<String> ERROR_MESSAGE =
      AttributeKey.stringKey(ATTR_ERROR_MESSAGE);
  private static final AttributeKey<String> ERROR_STACK = AttributeKey.stringKey(ATTR_ERROR_STACK);
  private static final AttributeKey<String> EXCEPTION_TYPE =
      AttributeKey.stringKey("exception.type");
  private static final AttributeKey<String> EXCEPTION_MESSAGE =
      AttributeKey.stringKey("exception.message");
  private static final AttributeKey<String> EXCEPTION_STACK =
      AttributeKey.stringKey("exception.stacktrace");

  private DatadogSpanErrors() {}

  /**
   * The Datadog error attributes for an exception: {@code error.type} (canonical class name, the
   * same as OpenTelemetry's {@code exception.type}), {@code error.message} (when the exception has
   * a message) and {@code error.stack} (the printed stack trace). {@code null} gives an empty map.
   *
   * @param error exception
   * @return attributes
   */
  public static Map<String, String> datadogErrorAttributes(Throwable error) {
    Map<String, String> out = new LinkedHashMap<>();
    if (error == null) {
      return out;
    }
    String type = error.getClass().getCanonicalName();
    out.put(ATTR_ERROR_TYPE, type != null ? type : error.getClass().getName());
    if (error.getMessage() != null) {
      out.put(ATTR_ERROR_MESSAGE, error.getMessage());
    }
    StringWriter sw = new StringWriter();
    error.printStackTrace(new PrintWriter(sw));
    out.put(ATTR_ERROR_STACK, sw.toString());
    return out;
  }

  /**
   * Sets the Datadog error attributes for {@code error} on {@code span}. No-op for {@code null}.
   *
   * @param span span
   * @param error exception
   */
  public static void setErrorAttributes(Span span, Throwable error) {
    datadogErrorAttributes(error).forEach(span::setAttribute);
  }

  /**
   * Sets the Datadog error attributes for {@code error} on the current span.
   *
   * @param error exception
   */
  public static void setErrorAttributes(Throwable error) {
    setErrorAttributes(Span.current(), error);
  }

  /**
   * Records an exception event on the current span (like {@link Spans#recordException}) and sets
   * the Datadog error attributes. The status is unchanged. {@code null} is ignored.
   *
   * @param error exception
   */
  public static void recordException(Throwable error) {
    if (error == null) {
      return;
    }
    Spans.recordException(error);
    setErrorAttributes(error);
  }

  /**
   * Sets ERROR status (with the message) on the current span, records the exception event (like
   * {@link Spans#setError(Throwable)}) and sets the Datadog error attributes. {@code null} is
   * ignored.
   *
   * @param error exception
   */
  public static void setError(Throwable error) {
    if (error == null) {
      return;
    }
    Spans.setError(error);
    setErrorAttributes(error);
  }

  /**
   * The Datadog error attributes derived from a finished span: from its last {@code exception}
   * event ({@code exception.type}, {@code exception.message}, {@code exception.stacktrace}) or, for
   * an ERROR status without an event message, {@code error.message} from the status description.
   * Empty when the span already has {@code error.type} or {@code error.message}, or has no error.
   *
   * @param span finished span
   * @return attributes to add
   */
  public static Attributes derivedErrorAttributes(SpanData span) {
    Attributes attrs = span.getAttributes();
    if (attrs.get(ERROR_TYPE) != null || attrs.get(ERROR_MESSAGE) != null) {
      return Attributes.empty();
    }
    AttributesBuilder b = Attributes.builder();
    String message = null;
    List<EventData> events = span.getEvents();
    for (int i = events.size() - 1; i >= 0; i--) {
      EventData e = events.get(i);
      if (!"exception".equals(e.getName())) {
        continue;
      }
      put(b, ERROR_TYPE, e.getAttributes().get(EXCEPTION_TYPE));
      message = e.getAttributes().get(EXCEPTION_MESSAGE);
      put(b, ERROR_STACK, e.getAttributes().get(EXCEPTION_STACK));
      break;
    }
    String description = span.getStatus().getDescription();
    if (message == null
        && span.getStatus().getStatusCode() == StatusCode.ERROR
        && !description.isEmpty()) {
      message = description;
    }
    put(b, ERROR_MESSAGE, message);
    return b.build();
  }

  private static void put(AttributesBuilder b, AttributeKey<String> key, String value) {
    if (value != null) {
      b.put(key, value);
    }
  }

  /**
   * {@code span} with the {@link #derivedErrorAttributes(SpanData)} added, or {@code span} itself
   * when there is nothing to add.
   *
   * @param span finished span
   * @return span data
   */
  public static SpanData withDatadogErrorAttributes(SpanData span) {
    Attributes add = derivedErrorAttributes(span);
    if (add.isEmpty()) {
      return span;
    }
    return DatadogSpanExporter.withAttributes(span, add);
  }
}
