package codes.grow.packages.datadogapm;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.trace.data.LinkData;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Datadog header propagation ({@code x-datadog-*}) with ddtrace's conflict rules (API.md section 3,
 * DECISIONS.md #5). Implemented from Datadog's documented header format; no ddtrace code is used.
 *
 * <p>Injects and extracts {@code x-datadog-trace-id} (low 64 bits, decimal), {@code
 * x-datadog-parent-id} (decimal), {@code x-datadog-sampling-priority} ({@code 1}/{@code 0}) and,
 * for 128-bit ids, {@code x-datadog-tags: _dd.p.tid=<high 64 bits hex>}.
 *
 * <p>Conflict rules on extract (ddtrace's behaviour with style order {@code datadog,tracecontext}):
 * when the carrier also holds a valid {@code traceparent},
 *
 * <ul>
 *   <li>same trace id: the W3C parent id wins (the context from {@code traceparent} is kept);
 *   <li>different trace id: the Datadog context wins and the W3C context is remembered in the
 *       returned context ({@link #terminatedContext(Context)}). Spans exported through {@link
 *       DatadogTracing#initTracing} that are direct children of the winning context get a link to
 *       it with {@code reason=terminated_context} (see {@link #terminatedContextLinks(Context)}).
 * </ul>
 *
 * <p>The rules apply whatever position {@code datadog} has in the propagator list; {@link
 * DatadogTracing#orderPropagators} puts {@code tracecontext} first so an existing tracestate is
 * kept.
 */
public final class DatadogPropagator implements TextMapPropagator {
  /** {@code x-datadog-trace-id}: low 64 bits of the trace id, unsigned decimal. */
  public static final String TRACE_ID_HEADER = "x-datadog-trace-id";

  /** {@code x-datadog-parent-id}: span id, unsigned decimal. */
  public static final String PARENT_ID_HEADER = "x-datadog-parent-id";

  /** {@code x-datadog-sampling-priority}: {@code 1} sampled, {@code 0} not sampled. */
  public static final String SAMPLING_PRIORITY_HEADER = "x-datadog-sampling-priority";

  /** {@code x-datadog-tags}: propagated tags, e.g. {@code _dd.p.tid=<high 64 bits hex>}. */
  public static final String TAGS_HEADER = "x-datadog-tags";

  /** {@code x-datadog-origin}: origin of the trace (synthetics, rum, ...). */
  public static final String ORIGIN_HEADER = "x-datadog-origin";

  /** Name of this propagator in {@code TracingConfig} propagator lists. */
  public static final String NAME = "datadog";

  private static final DatadogPropagator INSTANCE = new DatadogPropagator();
  private static final List<String> FIELDS =
      List.of(TRACE_ID_HEADER, PARENT_ID_HEADER, SAMPLING_PRIORITY_HEADER, TAGS_HEADER);
  private static final Pattern TRACEPARENT =
      Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$");
  private static final ContextKey<TerminatedContext> TERMINATED_KEY =
      ContextKey.named("grow-codes datadog-apm terminated W3C context");
  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<>() {

        @Override
        public String get(Map<String, String> carrier, String key) {
          if (carrier == null) {
            return null;
          }
          for (Map.Entry<String, String> e : carrier.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
              return e.getValue();
            }
          }
          return null;
        }
      };

  /**
   * A W3C context that lost against Datadog headers in {@link #extract}.
   *
   * @param spanContext the W3C span context from {@code traceparent}
   * @param winnerSpanId span id of the Datadog context that won (the parent of the next span)
   */
  public record TerminatedContext(SpanContext spanContext, String winnerSpanId) {}

  private DatadogPropagator() {}

  /**
   * The shared instance.
   *
   * @return propagator
   */
  public static DatadogPropagator getInstance() {
    return INSTANCE;
  }

  private static <C> String header(C carrier, TextMapGetter<C> getter, String name) {
    String v = getter.get(carrier, name);
    if (v == null) {
      return null;
    }
    String t = v.trim();
    return t.isEmpty() ? null : t;
  }

  static String parseTid(String tags) {
    if (tags == null) {
      return null;
    }
    for (String part : tags.split(",")) {
      int eq = part.indexOf('=');
      if (eq <= 0 || !part.substring(0, eq).trim().equals("_dd.p.tid")) {
        continue;
      }
      String v = part.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
      return DatadogIds.isHex16(v) ? v : null;
    }
    return null;
  }

  static SpanContext parseTraceparent(String value) {
    if (value == null) {
      return null;
    }
    Matcher m = TRACEPARENT.matcher(value.toLowerCase(Locale.ROOT));
    if (!m.matches() || m.group(1).equals("ff")) {
      return null;
    }
    int flags = Integer.parseInt(m.group(4), 16) & 1;
    SpanContext sc =
        SpanContext.createFromRemoteParent(
            m.group(2),
            m.group(3),
            flags == 1 ? TraceFlags.getSampled() : TraceFlags.getDefault(),
            TraceState.getDefault());
    return sc.isValid() ? sc : null;
  }

  private static boolean shortDigits(String s) {
    return s != null && s.length() <= 20 && DatadogIds.isDigits(s);
  }

  /**
   * Reads the Datadog headers of a carrier into a remote span context. A missing sampling priority
   * counts as sampled.
   *
   * @param carrier carrier
   * @param getter getter
   * @param <C> carrier type
   * @return the context, or empty when the headers are missing or invalid
   */
  public static <C> Optional<SpanContext> extractDatadogContext(
      C carrier, TextMapGetter<C> getter) {
    String traceRaw = header(carrier, getter, TRACE_ID_HEADER);
    String parentRaw = header(carrier, getter, PARENT_ID_HEADER);
    if (!shortDigits(traceRaw) || !shortDigits(parentRaw)) {
      return Optional.empty();
    }
    String traceHex;
    String spanHex;
    try {
      traceHex =
          DatadogIds.fromDatadogTraceId(traceRaw, parseTid(header(carrier, getter, TAGS_HEADER)));
      spanHex = DatadogIds.fromDatadogSpanId(parentRaw);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    boolean sampled = true;
    String p = header(carrier, getter, SAMPLING_PRIORITY_HEADER);
    if (p != null) {
      try {
        sampled = Integer.parseInt(p) > 0;
      } catch (NumberFormatException ignored) {
        // keep sampled, like ddtrace for unparseable priorities
      }
    }
    SpanContext sc =
        SpanContext.createFromRemoteParent(
            traceHex,
            spanHex,
            sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
            TraceState.getDefault());
    return sc.isValid() ? Optional.of(sc) : Optional.empty();
  }

  /**
   * {@link #extractDatadogContext(Object, TextMapGetter)} for a string map (keys match
   * case-insensitively).
   *
   * @param headers headers
   * @return the context, or empty
   */
  public static Optional<SpanContext> extractDatadogContext(Map<String, String> headers) {
    return extractDatadogContext(headers, MAP_GETTER);
  }

  @Override
  public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
    SpanContext sc = Span.fromContext(context).getSpanContext();
    if (!sc.isValid() || setter == null) {
      return;
    }
    DatadogIds.DatadogTraceId id = DatadogIds.toDatadogTraceId(sc.getTraceId());
    setter.set(carrier, TRACE_ID_HEADER, id.low64Decimal());
    setter.set(carrier, PARENT_ID_HEADER, DatadogIds.toDatadogSpanId(sc.getSpanId()));
    setter.set(carrier, SAMPLING_PRIORITY_HEADER, sc.isSampled() ? "1" : "0");
    if (id.is128Bit()) {
      setter.set(carrier, TAGS_HEADER, "_dd.p.tid=" + id.high64Hex());
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
    if (context == null) {
      context = Context.root();
    }
    if (getter == null) {
      return context;
    }
    Optional<SpanContext> found = extractDatadogContext(carrier, getter);
    if (found.isEmpty()) {
      return context;
    }
    SpanContext dd = found.get();
    SpanContext w3c = parseTraceparent(header(carrier, getter, "traceparent"));
    if (w3c == null) {
      return context.with(Span.wrap(dd));
    }
    if (w3c.getTraceId().equals(dd.getTraceId())) {
      // Same trace: ddtrace takes the W3C parent id; keep tracestate set by the W3C propagator.
      SpanContext current = Span.fromContext(context).getSpanContext();
      if (current.isValid()
          && current.getTraceId().equals(w3c.getTraceId())
          && current.getSpanId().equals(w3c.getSpanId())) {
        return context;
      }
      return context.with(
          Span.wrap(
              SpanContext.createFromRemoteParent(
                  w3c.getTraceId(), w3c.getSpanId(), dd.getTraceFlags(), w3c.getTraceState())));
    }
    TerminatedContext terminated = new TerminatedContext(w3c, dd.getSpanId());
    return context
        .with(Span.wrap(new WinnerSpanContext(dd, terminated)))
        .with(TERMINATED_KEY, terminated);
  }

  @Override
  public List<String> fields() {
    return FIELDS;
  }

  @Override
  public String toString() {
    return "DatadogPropagator";
  }

  /**
   * The W3C context that lost against Datadog headers during extract.
   *
   * @param context extracted context
   * @return the terminated context, or empty
   */
  public static Optional<TerminatedContext> terminatedContext(Context context) {
    return Optional.ofNullable(context == null ? null : context.get(TERMINATED_KEY));
  }

  /**
   * The span links for a span started from an extracted context: one link to the terminated W3C
   * context ({@code reason=terminated_context}, {@code context_headers=tracecontext}), or none. Add
   * them (for example with {@code SpanBuilder.addLink}) when you export spans without {@link
   * DatadogTracing#initTracing}.
   *
   * @param context extracted context
   * @return zero or one link
   */
  public static List<LinkData> terminatedContextLinks(Context context) {
    return terminatedContext(context).map(DatadogPropagator::link).map(List::of).orElse(List.of());
  }

  static LinkData link(TerminatedContext t) {
    return LinkData.create(
        t.spanContext(),
        Attributes.builder()
            .put("reason", "terminated_context")
            .put("context_headers", "tracecontext")
            .build());
  }

  /**
   * The terminated W3C context carried by a parent span context created by {@link #extract}.
   *
   * @param parent parent span context (for example {@code SpanData.getParentSpanContext()})
   * @return the terminated context, or empty
   */
  static Optional<TerminatedContext> terminatedContext(SpanContext parent) {
    return parent instanceof WinnerSpanContext
        ? Optional.of(((WinnerSpanContext) parent).terminated)
        : Optional.empty();
  }

  /**
   * The winning Datadog span context; it remembers the terminated W3C context so the exporter of
   * {@link DatadogTracing} can add the link to the spans started from it.
   */
  static final class WinnerSpanContext implements SpanContext {
    private final SpanContext delegate;
    final TerminatedContext terminated;

    WinnerSpanContext(SpanContext delegate, TerminatedContext terminated) {
      this.delegate = delegate;
      this.terminated = terminated;
    }

    @Override
    public String getTraceId() {
      return delegate.getTraceId();
    }

    @Override
    public String getSpanId() {
      return delegate.getSpanId();
    }

    @Override
    public TraceFlags getTraceFlags() {
      return delegate.getTraceFlags();
    }

    @Override
    public TraceState getTraceState() {
      return delegate.getTraceState();
    }

    @Override
    public boolean isRemote() {
      return true;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof WinnerSpanContext && delegate.equals(((WinnerSpanContext) o).delegate);
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
