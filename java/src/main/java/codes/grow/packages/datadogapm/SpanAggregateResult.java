package codes.grow.packages.datadogapm;

import java.util.List;
import java.util.Optional;

/**
 * The result of {@link DatadogSpansQuery#aggregateSpans}.
 *
 * @param buckets buckets
 * @param status {@code done}, or {@code timeout} for a partial result
 * @param warnings warnings of a partial or adjusted result
 * @param requestId {@code meta.request_id}, when present
 */
public record SpanAggregateResult(
    List<SpanAggregateBucket> buckets,
    String status,
    List<DatadogQueryWarning> warnings,
    Optional<String> requestId) {
  /**
   * Copies the lists.
   *
   * @param buckets buckets
   * @param status status
   * @param warnings warnings
   * @param requestId request id
   */
  public SpanAggregateResult {
    buckets = List.copyOf(buckets);
    warnings = List.copyOf(warnings);
  }
}
