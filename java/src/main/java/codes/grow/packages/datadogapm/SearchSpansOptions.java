package codes.grow.packages.datadogapm;

import java.util.Optional;

/**
 * Options of {@link DatadogSpansQuery#searchSpans} and {@link DatadogSpansQuery#searchSpansPage}.
 *
 * <pre>{@code
 * SearchSpansOptions.builder().query("service:checkout @grow.run:abc").limit(100).build();
 * }</pre>
 */
public final class SearchSpansOptions {
  private final String query;
  private final DatadogTime from;
  private final DatadogTime to;
  private final int limit;
  private final String sort;
  private final String cursor;
  private final String timezone;

  private SearchSpansOptions(Builder b) {
    this.query = b.query;
    this.from = b.from;
    this.to = b.to;
    this.limit = b.limit;
    this.sort = b.sort;
    this.cursor = b.cursor;
    this.timezone = b.timezone;
  }

  /**
   * A builder with the defaults.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The defaults: query {@code *}, the last 15 minutes, 10 spans per page, oldest first.
   *
   * @return options
   */
  public static SearchSpansOptions defaults() {
    return builder().build();
  }

  /**
   * A copy builder.
   *
   * @return builder with these values
   */
  public Builder toBuilder() {
    return builder()
        .query(query)
        .from(from)
        .to(to)
        .limit(limit)
        .sort(sort)
        .cursor(cursor)
        .timezone(timezone);
  }

  /**
   * Datadog span query (default {@code *}).
   *
   * @return query
   */
  public String query() {
    return query;
  }

  /**
   * Window start (default: 15 minutes ago, absolute and fixed for all pages).
   *
   * @return start, or empty
   */
  public Optional<DatadogTime> from() {
    return Optional.ofNullable(from);
  }

  /**
   * Window end (default: now, absolute and fixed for all pages).
   *
   * @return end, or empty
   */
  public Optional<DatadogTime> to() {
    return Optional.ofNullable(to);
  }

  /**
   * Page size 1..1000 (default 10).
   *
   * @return limit
   */
  public int limit() {
    return limit;
  }

  /**
   * {@code timestamp} (oldest first, default) or {@code -timestamp} (newest first).
   *
   * @return sort
   */
  public String sort() {
    return sort;
  }

  /**
   * Cursor of a previous page.
   *
   * @return cursor, or empty
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  /**
   * Timezone for relative dates (default {@code UTC}).
   *
   * @return timezone
   */
  public String timezone() {
    return timezone;
  }

  /** Builder of {@link SearchSpansOptions}. */
  public static final class Builder {
    private String query = "*";
    private DatadogTime from;
    private DatadogTime to;
    private int limit = 10;
    private String sort = "timestamp";
    private String cursor;
    private String timezone = "UTC";

    private Builder() {}

    /**
     * Sets the query, e.g. {@code service:web @http.status_code:500}.
     *
     * @param query query ({@code null} or empty means {@code *})
     * @return this builder
     */
    public Builder query(String query) {
      this.query = query == null || query.isEmpty() ? "*" : query;
      return this;
    }

    /**
     * Sets the window start.
     *
     * @param from start or {@code null}
     * @return this builder
     */
    public Builder from(DatadogTime from) {
      this.from = from;
      return this;
    }

    /**
     * Sets the window end.
     *
     * @param to end or {@code null}
     * @return this builder
     */
    public Builder to(DatadogTime to) {
      this.to = to;
      return this;
    }

    /**
     * Sets the page size (1..1000; checked when the request is made).
     *
     * @param limit page size
     * @return this builder
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Sets the sort order.
     *
     * @param sort {@code timestamp} or {@code -timestamp} ({@code null} means {@code timestamp})
     * @return this builder
     */
    public Builder sort(String sort) {
      this.sort = sort == null || sort.isEmpty() ? "timestamp" : sort;
      return this;
    }

    /**
     * Sets the cursor of a previous page.
     *
     * @param cursor cursor or {@code null}
     * @return this builder
     */
    public Builder cursor(String cursor) {
      this.cursor = cursor;
      return this;
    }

    /**
     * Sets the timezone.
     *
     * @param timezone e.g. {@code Europe/Berlin} ({@code null} means {@code UTC})
     * @return this builder
     */
    public Builder timezone(String timezone) {
      this.timezone = timezone == null || timezone.isEmpty() ? "UTC" : timezone;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return options
     */
    public SearchSpansOptions build() {
      return new SearchSpansOptions(this);
    }
  }
}
