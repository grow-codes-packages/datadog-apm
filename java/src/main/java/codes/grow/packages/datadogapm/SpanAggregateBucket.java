package codes.grow.packages.datadogapm;

import java.util.Map;

/**
 * One bucket of an aggregation.
 *
 * @param by group-by values, e.g. {@code {service=web}} (empty without group-by); numbers are
 *     {@code Long} or {@code Double}
 * @param computes results keyed {@code c0}, {@code c1}, ... in the order of the compute list: a
 *     {@code Double} for total computes, a {@code List<SpanTimeseriesPoint>} for timeseries
 */
public record SpanAggregateBucket(Map<String, Object> by, Map<String, Object> computes) {
  /**
   * Copies the maps.
   *
   * @param by group-by values
   * @param computes results
   */
  public SpanAggregateBucket {
    by = Map.copyOf(by);
    computes = Map.copyOf(computes);
  }
}
