package codes.grow.packages.datadogapm;

import java.util.Optional;

/**
 * Options of {@link DatadogSpansQuery#getTrace(String, GetTraceOptions)}.
 *
 * @param preview use the preview trace-by-id endpoint {@code GET /api/v2/trace/{id}} (full trace,
 *     {@code x-unstable}, 60 requests/minute) instead of searching the indexed spans
 * @param from search window start (search mode; default 24 hours ago)
 * @param to search window end (search mode; default now)
 */
public record GetTraceOptions(
    boolean preview, Optional<DatadogTime> from, Optional<DatadogTime> to) {
  /**
   * Fills the defaults.
   *
   * @param preview preview mode
   * @param from start
   * @param to end
   */
  public GetTraceOptions {
    from = from == null ? Optional.empty() : from;
    to = to == null ? Optional.empty() : to;
  }

  /**
   * Search mode (the default) over the last 24 hours.
   *
   * @return options
   */
  public static GetTraceOptions defaults() {
    return new GetTraceOptions(false, null, null);
  }

  /**
   * The preview trace-by-id endpoint.
   *
   * @return options
   */
  public static GetTraceOptions previewApi() {
    return new GetTraceOptions(true, null, null);
  }

  /**
   * Search mode over a window.
   *
   * @param from start or {@code null}
   * @param to end or {@code null}
   * @return options
   */
  public static GetTraceOptions window(DatadogTime from, DatadogTime to) {
    return new GetTraceOptions(false, Optional.ofNullable(from), Optional.ofNullable(to));
  }
}
