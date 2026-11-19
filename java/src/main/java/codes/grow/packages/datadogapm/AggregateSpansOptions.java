package codes.grow.packages.datadogapm;

import java.util.Optional;

/**
 * Filter of {@link DatadogSpansQuery#aggregateSpans}.
 *
 * @param query span query ({@code null} means {@code *})
 * @param from window start ({@code null}: 15 minutes ago)
 * @param to window end ({@code null}: now)
 * @param timezone timezone ({@code null} means {@code UTC})
 */
public record AggregateSpansOptions(
    String query, Optional<DatadogTime> from, Optional<DatadogTime> to, String timezone) {
  /**
   * Fills the defaults.
   *
   * @param query query
   * @param from start
   * @param to end
   * @param timezone timezone
   */
  public AggregateSpansOptions {
    query = query == null || query.isEmpty() ? "*" : query;
    from = from == null ? Optional.empty() : from;
    to = to == null ? Optional.empty() : to;
    timezone = timezone == null || timezone.isEmpty() ? "UTC" : timezone;
  }

  /**
   * Options from nullable values.
   *
   * @param query query or {@code null}
   * @param from start or {@code null}
   * @param to end or {@code null}
   * @return options
   */
  public static AggregateSpansOptions of(String query, DatadogTime from, DatadogTime to) {
    return new AggregateSpansOptions(
        query, Optional.ofNullable(from), Optional.ofNullable(to), null);
  }

  /**
   * The defaults: all spans of the last 15 minutes, UTC.
   *
   * @return options
   */
  public static AggregateSpansOptions defaults() {
    return of(null, null, null);
  }
}
