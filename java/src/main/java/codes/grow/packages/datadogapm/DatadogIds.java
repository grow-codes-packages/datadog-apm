package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.ValidationException;
import java.math.BigInteger;
import java.util.Locale;

/**
 * Datadog &lt;-&gt; OpenTelemetry id conversion (API.md section 3, DECISIONS.md #3).
 *
 * <p>Datadog stores a trace id as the low 64 bits in unsigned decimal plus, for 128-bit ids, the
 * high 64 bits as 16 lower-case hex characters ({@code _dd.p.tid}). OpenTelemetry uses 32 hex
 * characters. Span ids are unsigned 64-bit decimals in Datadog and 16 hex characters in
 * OpenTelemetry. Invalid ids are {@link ValidationException}s.
 */
public final class DatadogIds {
  static final String ZERO_HIGH = "0000000000000000";

  private DatadogIds() {}

  /**
   * A trace id split the way Datadog stores it.
   *
   * @param low64Decimal low 64 bits as an unsigned decimal ({@code x-datadog-trace-id}, the Agent's
   *     {@code trace_id})
   * @param high64Hex high 64 bits as 16 lower-case hex characters ({@code _dd.p.tid}); all zeros
   *     for a 64-bit id
   * @param is128Bit true when the high 64 bits are not zero (the id needs {@code _dd.p.tid})
   */
  public record DatadogTraceId(String low64Decimal, String high64Hex, boolean is128Bit) {}

  private static ValidationException invalid(String what) {
    return new ValidationException("datadog id: " + what);
  }

  static boolean isHex(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
        return false;
      }
    }
    return true;
  }

  static boolean isDigits(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  static boolean isHex16(String s) {
    return s.length() == 16 && isHex(s);
  }

  private static boolean allZeros(String s) {
    return s.chars().allMatch(c -> c == '0');
  }

  static String hexTraceId(String traceIdHex) {
    String h = traceIdHex == null ? "" : traceIdHex.trim().toLowerCase(Locale.ROOT);
    if (h.isEmpty() || h.length() > 32 || !isHex(h)) {
      throw invalid("trace id must be 1 to 32 hex characters");
    }
    h = "0".repeat(32 - h.length()) + h;
    if (allZeros(h)) {
      throw invalid("trace id must not be all zeros");
    }
    return h;
  }

  private static long parseU64(String decimal, String what) {
    String t = decimal == null ? "" : decimal.trim();
    if (!isDigits(t)) {
      throw invalid(what + " must be an unsigned decimal");
    }
    try {
      return Long.parseUnsignedLong(t);
    } catch (NumberFormatException e) {
      throw invalid(what + " exceeds 2^64-1");
    }
  }

  static String hex16(long v) {
    String s = Long.toHexString(v);
    return "0".repeat(16 - s.length()) + s;
  }

  /**
   * Splits an OpenTelemetry trace id (up to 32 hex characters, left-padded) into Datadog's form.
   *
   * <pre>{@code
   * DatadogIds.toDatadogTraceId("a3ce929d0e0e47364bf92f3577b34da6");
   * // DatadogTraceId[low64Decimal=5474458728733560230, high64Hex=a3ce929d0e0e4736, is128Bit=true]
   * }</pre>
   *
   * @param traceIdHex OpenTelemetry trace id
   * @return the Datadog form
   * @throws ValidationException for an invalid or all-zero id
   */
  public static DatadogTraceId toDatadogTraceId(String traceIdHex) {
    String h = hexTraceId(traceIdHex);
    long low = Long.parseUnsignedLong(h.substring(16), 16);
    String high = h.substring(0, 16);
    return new DatadogTraceId(Long.toUnsignedString(low), high, !high.equals(ZERO_HIGH));
  }

  /**
   * Joins a Datadog trace id (low 64 bits in decimal, optional {@code _dd.p.tid} high half in hex)
   * into a 32-character OpenTelemetry trace id.
   *
   * <pre>{@code
   * DatadogIds.fromDatadogTraceId("1", "ffffffffffffffff"); // "ffffffffffffffff0000000000000001"
   * DatadogIds.fromDatadogTraceId("42", null);              // "0000000000000000000000000000002a"
   * }</pre>
   *
   * @param low64Decimal low 64 bits, unsigned decimal
   * @param high64Hex high 64 bits as 16 hex characters; {@code null} or empty means zero
   * @return 32 lower-case hex characters
   * @throws ValidationException for invalid parts or an all-zero id
   */
  public static String fromDatadogTraceId(String low64Decimal, String high64Hex) {
    long low = parseU64(low64Decimal, "trace id");
    String high = ZERO_HIGH;
    if (high64Hex != null && !high64Hex.isEmpty()) {
      high = high64Hex.trim().toLowerCase(Locale.ROOT);
      if (!isHex16(high)) {
        throw invalid("high 64 bits must be 16 hex characters");
      }
    }
    String id = high + hex16(low);
    if (allZeros(id)) {
      throw invalid("trace id must not be all zeros");
    }
    return id;
  }

  /**
   * {@link #fromDatadogTraceId(String, String)} for a 64-bit id (zero high half).
   *
   * @param low64Decimal unsigned decimal
   * @return 32 lower-case hex characters
   */
  public static String fromDatadogTraceId(String low64Decimal) {
    return fromDatadogTraceId(low64Decimal, null);
  }

  /**
   * Converts an OpenTelemetry span id (up to 16 hex characters) to Datadog's unsigned decimal.
   *
   * @param spanIdHex span id
   * @return unsigned decimal
   * @throws ValidationException for an invalid id
   */
  public static String toDatadogSpanId(String spanIdHex) {
    String h = spanIdHex == null ? "" : spanIdHex.trim().toLowerCase(Locale.ROOT);
    if (h.isEmpty() || h.length() > 16 || !isHex(h)) {
      throw invalid("span id must be 1 to 16 hex characters");
    }
    return Long.toUnsignedString(Long.parseUnsignedLong(h, 16));
  }

  /**
   * Converts a Datadog unsigned decimal span id to 16 lower-case hex characters.
   *
   * @param decimal unsigned decimal
   * @return 16 lower-case hex characters
   * @throws ValidationException for an invalid id
   */
  public static String fromDatadogSpanId(String decimal) {
    return hex16(parseU64(decimal, "span id"));
  }

  /**
   * The trace id in the format Datadog's log correlation ({@code dd.trace_id}) and its search API
   * use: 32 lower-case hex characters for a 128-bit id, the low 64 bits in decimal for a 64-bit id.
   *
   * @param traceIdHex OpenTelemetry trace id
   * @return the Datadog string form
   * @throws ValidationException for an invalid id
   */
  public static String datadogTraceIdString(String traceIdHex) {
    DatadogTraceId id = toDatadogTraceId(traceIdHex);
    return id.is128Bit() ? hexTraceId(traceIdHex) : id.low64Decimal();
  }

  /**
   * Normalizes any trace id Datadog returns to 32 lower-case hex characters: a 32-character value
   * is hex, a shorter all-digit value (at most 20 digits) is the 64-bit decimal form, anything else
   * is hex.
   *
   * @param value Datadog trace id
   * @return 32 lower-case hex characters
   * @throws ValidationException for an invalid id
   */
  public static String normalizeDatadogTraceId(String value) {
    String t = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (t.length() == 32 && isHex(t)) {
      return t;
    }
    if (isDigits(t) && t.length() <= 20) {
      return fromDatadogTraceId(t, null);
    }
    return hexTraceId(t);
  }

  /**
   * Normalizes a Datadog decimal span id to 16 hex characters.
   *
   * @param value decimal span id
   * @return 16 lower-case hex characters, or {@code ""} for an empty or zero id
   * @throws ValidationException for an invalid id
   */
  public static String normalizeDatadogSpanId(String value) {
    String t = value == null ? "" : value.trim();
    if (t.isEmpty() || (isDigits(t) && allZeros(t))) {
      return "";
    }
    return fromDatadogSpanId(t);
  }

  /**
   * The id to put in a {@code GET /api/v2/trace/{id}} path. Datadog reads an all-digit id as
   * decimal, so a hex id without a letter is sent as its full decimal value; otherwise lower-case
   * hex.
   *
   * @param traceIdHex OpenTelemetry trace id
   * @return path id
   * @throws ValidationException for an invalid id
   */
  public static String traceIdForPath(String traceIdHex) {
    String h = hexTraceId(traceIdHex);
    for (int i = 0; i < h.length(); i++) {
      if (h.charAt(i) >= 'a') {
        return h;
      }
    }
    return new BigInteger(h, 16).toString();
  }
}
