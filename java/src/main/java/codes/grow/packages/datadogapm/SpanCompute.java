package codes.grow.packages.datadogapm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One computation of {@link DatadogSpansQuery#aggregateSpans}.
 *
 * @param aggregation one of the {@code AGGREGATION_*} constants
 * @param metric measure, e.g. {@code @duration} ({@code null} for count)
 * @param type {@code total} (default when {@code null}) or {@code timeseries} (all computes must
 *     use the same type and interval)
 * @param interval time bucket for timeseries, e.g. {@code 5m}, or {@code null}
 */
public record SpanCompute(String aggregation, String metric, String type, String interval) {
  /** {@code count}. */
  public static final String AGGREGATION_COUNT = "count";

  /** {@code cardinality}. */
  public static final String AGGREGATION_CARDINALITY = "cardinality";

  /** {@code pc75}. */
  public static final String AGGREGATION_PC75 = "pc75";

  /** {@code pc90}. */
  public static final String AGGREGATION_PC90 = "pc90";

  /** {@code pc95}. */
  public static final String AGGREGATION_PC95 = "pc95";

  /** {@code pc98}. */
  public static final String AGGREGATION_PC98 = "pc98";

  /** {@code pc99}. */
  public static final String AGGREGATION_PC99 = "pc99";

  /** {@code sum}. */
  public static final String AGGREGATION_SUM = "sum";

  /** {@code min}. */
  public static final String AGGREGATION_MIN = "min";

  /** {@code max}. */
  public static final String AGGREGATION_MAX = "max";

  /** {@code avg}. */
  public static final String AGGREGATION_AVG = "avg";

  /** {@code median}. */
  public static final String AGGREGATION_MEDIAN = "median";

  /**
   * Validates the aggregation.
   *
   * @param aggregation aggregation
   * @param metric metric
   * @param type type
   * @param interval interval
   */
  public SpanCompute {
    Objects.requireNonNull(aggregation, "aggregation");
  }

  /**
   * A total compute without a metric (for example {@code count}).
   *
   * @param aggregation aggregation
   * @return compute
   */
  public static SpanCompute of(String aggregation) {
    return new SpanCompute(aggregation, null, null, null);
  }

  /**
   * A total compute over a metric.
   *
   * @param aggregation aggregation
   * @param metric metric, e.g. {@code @duration}
   * @return compute
   */
  public static SpanCompute of(String aggregation, String metric) {
    return new SpanCompute(aggregation, metric, null, null);
  }

  /**
   * A timeseries compute.
   *
   * @param aggregation aggregation
   * @param metric metric or {@code null}
   * @param interval bucket, e.g. {@code 5m}
   * @return compute
   */
  public static SpanCompute timeseries(String aggregation, String metric, String interval) {
    return new SpanCompute(aggregation, metric, "timeseries", interval);
  }

  Map<String, Object> toJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("aggregation", aggregation);
    putIf(m, "metric", metric);
    putIf(m, "type", type);
    putIf(m, "interval", interval);
    return m;
  }

  static void putIf(Map<String, Object> m, String key, Object value) {
    if (value != null && !"".equals(value)) {
      m.put(key, value);
    }
  }
}
