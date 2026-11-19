package codes.grow.packages.datadogapm;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Span exporter wrapper installed by {@link DatadogTracing#initTracing}. Before the wrapped OTLP
 * exporter sends a batch it
 *
 * <ul>
 *   <li>fills the Datadog error attributes ({@link
 *       DatadogSpanErrors#withDatadogErrorAttributes(SpanData)}) and
 *   <li>adds the {@code reason=terminated_context} link to spans whose remote parent is a Datadog
 *       context that won against a different W3C context in {@link DatadogPropagator#extract}
 *       ({@link #withTerminatedContextLinks(SpanData)}).
 * </ul>
 *
 * <p>The Java template's {@code Tracing.initWith} accepts no extra span processor, so both are done
 * here, on the finished span data, instead of in a processor.
 */
public final class DatadogSpanExporter implements SpanExporter {
  private final SpanExporter next;

  /**
   * Wraps an exporter.
   *
   * @param next exporter that sends the spans
   */
  public DatadogSpanExporter(SpanExporter next) {
    this.next = Objects.requireNonNull(next, "next");
  }

  /**
   * The wrapped exporter.
   *
   * @return exporter
   */
  public SpanExporter delegate() {
    return next;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    List<SpanData> out = new ArrayList<>(spans.size());
    for (SpanData s : spans) {
      out.add(DatadogSpanErrors.withDatadogErrorAttributes(withTerminatedContextLinks(s)));
    }
    return next.export(out);
  }

  @Override
  public CompletableResultCode flush() {
    return next.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return next.shutdown();
  }

  @Override
  public String toString() {
    return "DatadogSpanExporter{" + next + "}";
  }

  /**
   * {@code span} with the terminated-context link of its remote parent added (see {@link
   * DatadogPropagator}), or {@code span} itself.
   *
   * @param span finished span
   * @return span data
   */
  public static SpanData withTerminatedContextLinks(SpanData span) {
    Optional<DatadogPropagator.TerminatedContext> t =
        DatadogPropagator.terminatedContext(span.getParentSpanContext());
    if (t.isEmpty()) {
      return span;
    }
    List<LinkData> links = new ArrayList<>(span.getLinks());
    links.add(DatadogPropagator.link(t.get()));
    return new Patched(span, span.getAttributes(), span.getTotalAttributeCount(), links);
  }

  static SpanData withAttributes(SpanData span, Attributes add) {
    Attributes merged = span.getAttributes().toBuilder().putAll(add).build();
    int total = span.getTotalAttributeCount() + (merged.size() - span.getAttributes().size());
    return new Patched(span, merged, total, span.getLinks());
  }

  /** Span data with replaced attributes and links. */
  private static final class Patched extends DelegatingSpanData {
    private final Attributes attributes;
    private final int totalAttributes;
    private final List<LinkData> links;
    private final int totalLinks;

    Patched(SpanData delegate, Attributes attributes, int totalAttributes, List<LinkData> links) {
      super(delegate);
      this.attributes = attributes;
      this.totalAttributes = totalAttributes;
      this.links = List.copyOf(links);
      this.totalLinks =
          delegate.getTotalRecordedLinks() + (links.size() - delegate.getLinks().size());
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public int getTotalAttributeCount() {
      return totalAttributes;
    }

    @Override
    public List<LinkData> getLinks() {
      return links;
    }

    @Override
    public int getTotalRecordedLinks() {
      return totalLinks;
    }
  }
}
