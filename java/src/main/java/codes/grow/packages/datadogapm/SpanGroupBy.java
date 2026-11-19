package codes.grow.packages.datadogapm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One group-by facet of {@link DatadogSpansQuery#aggregateSpans}.
 *
 * @param facet facet, e.g. {@code service}, {@code resource_name}, {@code @http.status_code}
 * @param limit maximum number of groups ({@code null}: Datadog default 10)
 * @param sort group order or {@code null}
 * @param missing value used for spans without the facet (string or number) or {@code null}
 * @param total adds a total bucket ({@code true}, or the bucket's value) or {@code null}
 * @param histogram buckets for a numeric facet or {@code null}
 */
public record SpanGroupBy(
    String facet, Integer limit, Sort sort, Object missing, Object total, Histogram histogram) {
  /**
   * Order of the groups.
   *
   * @param aggregation aggregation to sort by, or {@code null}
   * @param metric metric to sort by, or {@code null}
   * @param order {@code asc} or {@code desc}, or {@code null}
   * @param type {@code alphabetical} or {@code measure}, or {@code null}
   */
  public record Sort(String aggregation, String metric, String order, String type) {
    Map<String, Object> toJson() {
      Map<String, Object> m = new LinkedHashMap<>();
      SpanCompute.putIf(m, "aggregation", aggregation);
      SpanCompute.putIf(m, "metric", metric);
      SpanCompute.putIf(m, "order", order);
      SpanCompute.putIf(m, "type", type);
      return m;
    }
  }

  /**
   * Buckets of a numeric facet.
   *
   * @param interval bucket width
   * @param min lower bound
   * @param max upper bound
   */
  public record Histogram(double interval, double min, double max) {}

  /**
   * Validates the facet.
   *
   * @param facet facet
   * @param limit limit
   * @param sort sort
   * @param missing missing value
   * @param total total
   * @param histogram histogram
   */
  public SpanGroupBy {
    Objects.requireNonNull(facet, "facet");
  }

  /**
   * Groups by a facet with the defaults.
   *
   * @param facet facet
   * @return group-by
   */
  public static SpanGroupBy of(String facet) {
    return new SpanGroupBy(facet, null, null, null, null, null);
  }

  /**
   * Groups by a facet with a group limit.
   *
   * @param facet facet
   * @param limit maximum groups
   * @return group-by
   */
  public static SpanGroupBy of(String facet, int limit) {
    return new SpanGroupBy(facet, limit, null, null, null, null);
  }

  Map<String, Object> toJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("facet", facet);
    if (limit != null && limit != 0) {
      m.put("limit", limit);
    }
    if (sort != null) {
      m.put("sort", sort.toJson());
    }
    SpanCompute.putIf(m, "missing", missing);
    SpanCompute.putIf(m, "total", total);
    if (histogram != null) {
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("interval", histogram.interval());
      h.put("min", histogram.min());
      h.put("max", histogram.max());
      m.put("histogram", h);
    }
    return m;
  }
}
