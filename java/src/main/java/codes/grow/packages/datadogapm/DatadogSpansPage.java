package codes.grow.packages.datadogapm;

import java.util.List;
import java.util.Optional;

/**
 * One page of {@link DatadogSpansQuery#searchSpansPage}.
 *
 * @param spans spans of the page
 * @param nextCursor cursor of the next page, empty on the last page
 * @param status {@code done}, or {@code timeout} for a partial result
 * @param warnings non-empty when the result is partial or the query was adjusted
 * @param requestId {@code meta.request_id}, when present
 */
public record DatadogSpansPage(
    List<DatadogSpan> spans,
    Optional<String> nextCursor,
    String status,
    List<DatadogQueryWarning> warnings,
    Optional<String> requestId) {
  /**
   * Copies the lists.
   *
   * @param spans spans
   * @param nextCursor next cursor
   * @param status status
   * @param warnings warnings
   * @param requestId request id
   */
  public DatadogSpansPage {
    spans = List.copyOf(spans);
    warnings = List.copyOf(warnings);
  }
}
