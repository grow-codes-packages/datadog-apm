package codes.grow.packages.datadogapm;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * A point in time for a Datadog query: an absolute instant (sent as ISO-8601 in UTC with
 * milliseconds) or a Datadog date expression ({@code now-15m}, ISO-8601, epoch milliseconds) sent
 * as is.
 */
public final class DatadogTime {
  private static final DateTimeFormatter ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final Instant at;
  private final String expr;

  private DatadogTime(Instant at, String expr) {
    this.at = at;
    this.expr = expr;
  }

  /**
   * An absolute time.
   *
   * @param at instant
   * @return time
   */
  public static DatadogTime at(Instant at) {
    return new DatadogTime(Objects.requireNonNull(at, "at"), null);
  }

  /**
   * An absolute time from epoch milliseconds.
   *
   * @param epochMillis milliseconds since the epoch
   * @return time
   */
  public static DatadogTime epochMillis(long epochMillis) {
    return at(Instant.ofEpochMilli(epochMillis));
  }

  /**
   * A Datadog date expression such as {@code now-15m}.
   *
   * @param expr expression
   * @return time
   */
  public static DatadogTime expr(String expr) {
    return new DatadogTime(null, Objects.requireNonNull(expr, "expr"));
  }

  /**
   * The wire form: ISO-8601 for an instant, the expression otherwise.
   *
   * @return wire value
   */
  @Override
  public String toString() {
    return at != null ? ISO.format(at) : expr;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof DatadogTime && toString().equals(o.toString());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }
}
