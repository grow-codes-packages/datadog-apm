package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.core.ValidationException;
import codes.grow.packages.datadogapm.tracing.query.Span;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Datadog span formats to the SPEC section 5 trace model (API.md sections 2.1 and 4).
 *
 * <p>Two Datadog shapes are mapped:
 *
 * <ul>
 *   <li>a trace span as the Agent forwards it and as {@code GET /api/v2/trace/{id}} returns it
 *       ({@code meta}, {@code metrics}, nanosecond {@code start}/{@code startTime}, decimal ids;
 *       camelCase or snake_case keys): {@link #traceSpanToSpan(JsonNode)};
 *   <li>a span event of the spans search API ({@code attributes.trace_id}, {@code start_timestamp},
 *       ...): {@link #searchSpanToSpan(JsonNode)}.
 * </ul>
 *
 * <p>For spans that came in over OTLP the Datadog resource is the OpenTelemetry span name and the
 * Datadog name is the operation ({@code Internal}, {@code server.request}, ...), so the normalized
 * name is the resource; the Datadog operation is kept as attribute {@code operation_name}. Parse
 * JSON with Jackson (as {@link Json#tree(byte[])} does) so ids above 2^53 stay exact.
 */
public final class DatadogMapping {
  private DatadogMapping() {}

  /**
   * Converts a Datadog {@code span.kind} (or OpenTelemetry kind name) to a span kind.
   *
   * @param value kind text
   * @return kind ({@code UNSPECIFIED} when unknown)
   */
  public static Span.Kind datadogKind(String value) {
    if (value == null) {
      return Span.Kind.UNSPECIFIED;
    }
    Span.Kind k = Span.Kind.parse(value);
    return value.trim().toUpperCase(Locale.ROOT).startsWith("SPAN_KIND_")
        ? Span.Kind.UNSPECIFIED
        : k;
  }

  private static JsonNode get(JsonNode m, String key) {
    JsonNode v = m == null ? null : m.get(key);
    return v == null || v.isNull() ? null : v;
  }

  private static JsonNode first(JsonNode m, String... keys) {
    for (String k : keys) {
      JsonNode v = get(m, k);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /** Strings and numbers as text; "" for anything else. */
  static String str(JsonNode v) {
    if (v == null) {
      return "";
    }
    if (v.isTextual()) {
      return v.asText();
    }
    if (v.isIntegralNumber()) {
      return v.bigIntegerValue().toString();
    }
    if (v.isNumber()) {
      double d = v.doubleValue();
      if (d == Math.rint(d) && Math.abs(d) < 1e21) {
        return new BigDecimal(d).toBigInteger().toString();
      }
      return Double.toString(d);
    }
    return "";
  }

  private static JsonNode record(JsonNode v) {
    return v != null && v.isObject() ? v : MissingNode.getInstance();
  }

  /** An integer from a number or a decimal string; null otherwise. */
  static Long int64(JsonNode v) {
    if (v == null) {
      return null;
    }
    if (v.isIntegralNumber()) {
      return v.longValue();
    }
    if (v.isNumber()) {
      double d = v.doubleValue();
      return Double.isFinite(d) ? (long) d : null;
    }
    if (v.isTextual()) {
      try {
        return Long.parseLong(v.asText().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /** A JSON number as Long (integral, in range) or Double; null for other nodes. */
  static Object number(JsonNode v) {
    if (v == null || !v.isNumber()) {
      return null;
    }
    if (v.isIntegralNumber() && v.canConvertToLong()) {
      return v.longValue();
    }
    return v.doubleValue();
  }

  /** Decoded JSON to an attribute value; null is dropped. */
  static Object attrValue(JsonNode v) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isTextual()) {
      return v.asText();
    }
    if (v.isBoolean()) {
      return v.booleanValue();
    }
    if (v.isNumber()) {
      return number(v);
    }
    if (v.isArray()) {
      List<Object> out = new ArrayList<>();
      for (JsonNode it : v) {
        Object a = attrValue(it);
        if (a != null) {
          out.add(a);
        }
      }
      return out;
    }
    if (v.isObject()) {
      return attrs(v);
    }
    return null;
  }

  private static Map<String, Object> attrs(JsonNode v) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (v == null || !v.isObject()) {
      return out;
    }
    for (Map.Entry<String, JsonNode> e : v.properties()) {
      Object a = attrValue(e.getValue());
      if (a != null) {
        out.put(e.getKey(), a);
      }
    }
    return out;
  }

  private static List<JsonNode> parseJsonArray(JsonNode v) {
    List<JsonNode> out = new ArrayList<>();
    if (v == null || !v.isTextual() || v.asText().isEmpty()) {
      return out;
    }
    JsonNode items;
    try {
      items = Json.tree(v.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (ValidationException e) {
      return out;
    }
    if (!items.isArray()) {
      return out;
    }
    for (JsonNode it : items) {
      out.add(record(it));
    }
    return out;
  }

  /** A native Datadog span event attribute ({type, string_value, ...}) as a plain value. */
  private static JsonNode nativeAttr(JsonNode v) {
    for (String k : new String[] {"string_value", "int_value", "double_value", "bool_value"}) {
      JsonNode x = v.get(k);
      if (x != null) {
        return x;
      }
    }
    JsonNode arr = v.get("array_value");
    if (arr != null) {
      com.fasterxml.jackson.databind.node.ArrayNode out = Json.mapper().createArrayNode();
      JsonNode values = arr.get("values");
      if (values != null && values.isArray()) {
        for (JsonNode it : values) {
          out.add(nativeAttr(it));
        }
      }
      return out;
    }
    return v;
  }

  private static List<Span.Event> events(JsonNode meta, JsonNode raw) {
    List<Span.Event> out = new ArrayList<>();
    for (JsonNode e : parseJsonArray(get(meta, "events"))) {
      Long ns = int64(get(e, "time_unix_nano"));
      out.add(new Span.Event(str(get(e, "name")), micros(ns), attrs(get(e, "attributes"))));
    }
    JsonNode nat = first(raw, "span_events", "spanEvents");
    if (nat != null && nat.isArray()) {
      for (JsonNode item : nat) {
        JsonNode e = record(item);
        Long ns = int64(first(e, "time_unix_nano", "timeUnixNano"));
        com.fasterxml.jackson.databind.node.ObjectNode a = Json.mapper().createObjectNode();
        JsonNode attributes = record(get(e, "attributes"));
        for (Map.Entry<String, JsonNode> en : attributes.properties()) {
          a.set(en.getKey(), nativeAttr(en.getValue()));
        }
        out.add(new Span.Event(str(get(e, "name")), micros(ns), attrs(a)));
      }
    }
    return out;
  }

  private static long micros(Long ns) {
    return ns == null ? 0 : ns / 1000;
  }

  private static String linkTraceId(JsonNode l) {
    String id = str(first(l, "trace_id", "traceId", "traceID"));
    if (id.isEmpty()) {
      return null;
    }
    String high = str(first(l, "trace_id_high", "traceIdHigh"));
    try {
      if (!high.isEmpty() && DatadogIds.isDigits(id)) {
        String highHex = high;
        if (!DatadogIds.isHex16(high)) {
          if (!DatadogIds.isDigits(high)) {
            return null;
          }
          highHex = new BigInteger(high).toString(16);
          highHex = "0".repeat(Math.max(0, 16 - highHex.length())) + highHex;
        }
        return DatadogIds.fromDatadogTraceId(id, highHex);
      }
      if (DatadogIds.isHex16(id)) {
        return DatadogIds.ZERO_HIGH + id;
      }
      return DatadogIds.normalizeDatadogTraceId(id);
    } catch (ValidationException e) {
      return null;
    }
  }

  private static String linkSpanId(JsonNode l) {
    String id = str(first(l, "span_id", "spanId", "spanID"));
    if (id.isEmpty()) {
      return null;
    }
    if (DatadogIds.isHex16(id) && !DatadogIds.isDigits(id)) {
      return id;
    }
    try {
      String out = DatadogIds.normalizeDatadogSpanId(id);
      return out.isEmpty() ? null : out;
    } catch (ValidationException e) {
      return null;
    }
  }

  private static List<Span.Link> links(JsonNode meta, JsonNode raw) {
    List<JsonNode> items = parseJsonArray(get(meta, "_dd.span_links"));
    JsonNode nat = first(raw, "span_links", "spanLinks");
    if (nat != null && nat.isArray()) {
      for (JsonNode it : nat) {
        items.add(record(it));
      }
    }
    List<Span.Link> out = new ArrayList<>();
    for (JsonNode l : items) {
      String traceId = linkTraceId(l);
      String spanId = linkSpanId(l);
      if (traceId != null && spanId != null) {
        out.add(new Span.Link(traceId, spanId, attrs(get(l, "attributes"))));
      }
    }
    return out;
  }

  /** JavaScript's {@code Number(v) !== 0} for flags such as {@code error}. */
  static boolean numericTruthy(JsonNode v) {
    if (v == null) {
      return false;
    }
    if (v.isBoolean()) {
      return v.booleanValue();
    }
    if (v.isNumber()) {
      return v.doubleValue() != 0;
    }
    if (v.isTextual()) {
      String t = v.asText().trim();
      if (t.isEmpty()) {
        return false;
      }
      try {
        return Double.parseDouble(t) != 0;
      } catch (NumberFormatException e) {
        return true;
      }
    }
    return false;
  }

  /**
   * Maps a Datadog trace span to a span: the Agent's format ({@code trace_id}, {@code span_id},
   * {@code parent_id}, {@code start}, {@code duration}, {@code meta}, {@code metrics}) or the
   * trace-by-id API format ({@code traceID}, {@code traceIDFull}, {@code spanID}, {@code parentID},
   * {@code startTime}, {@code endTime}, {@code duration}).
   *
   * <p>The full trace id comes from {@code traceIDFull} / {@code trace_id_hex} / meta {@code
   * otel.trace_id}, else the decimal low 64 bits plus meta {@code _dd.p.tid}. Name = Datadog
   * resource, kind = meta {@code span.kind}, status = {@code error} flag (message from {@code
   * error.message}, {@code error.msg} or {@code otel.status_description}) or meta {@code
   * otel.status_code}. Events come from meta {@code events} and {@code span_events}, links from
   * meta {@code _dd.span_links} and {@code span_links}. Attributes are the remaining meta (strings)
   * and metrics (numbers), plus {@code operation_name}, {@code resource_name} and {@code
   * span.type}.
   *
   * @param raw span object
   * @return span
   * @throws ValidationException for invalid ids
   */
  public static Span traceSpanToSpan(JsonNode raw) {
    JsonNode meta = record(get(raw, "meta"));
    JsonNode metrics = record(get(raw, "metrics"));
    String traceId;
    String full = str(first(raw, "traceIDFull", "trace_id_hex"));
    String otel = str(get(meta, "otel.trace_id"));
    if (!full.isEmpty() || !otel.isEmpty()) {
      if (full.isEmpty()) {
        full = otel;
      }
      if (full.length() < 32) {
        full = "0".repeat(32 - full.length()) + full;
      }
      traceId = DatadogIds.normalizeDatadogTraceId(full);
    } else {
      String low = str(first(raw, "traceID", "trace_id", "traceId"));
      JsonNode high = get(meta, "_dd.p.tid");
      if (high == null) {
        high = get(raw, "trace_id_high");
      }
      traceId = DatadogIds.fromDatadogTraceId(low.isEmpty() ? "0" : low, str(high));
    }
    String spanRaw = str(first(raw, "spanID", "span_id", "spanId"));
    String spanId = DatadogIds.normalizeDatadogSpanId(spanRaw.isEmpty() ? "0" : spanRaw);
    String parentId =
        DatadogIds.normalizeDatadogSpanId(str(first(raw, "parentID", "parent_id", "parentId")));
    Long startNs = int64(first(raw, "startTime", "start"));
    Long endNs = int64(first(raw, "endTime", "end"));
    Long durationNs = int64(get(raw, "duration"));
    long start = startNs == null ? 0 : startNs;
    long duration;
    if (durationNs != null) {
      duration = durationNs;
    } else {
      duration = endNs != null ? endNs - start : 0;
    }
    long end = endNs != null ? endNs : start + duration;
    String ddName = str(get(raw, "name"));
    String resource = str(first(raw, "resource", "resource_name"));
    String spanType = str(get(raw, "type"));

    Map<String, Object> attributes = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : meta.properties()) {
      String k = e.getKey();
      if (e.getValue().isTextual() && !k.equals("events") && !k.equals("_dd.span_links")) {
        attributes.put(k, e.getValue().asText());
      }
    }
    for (Map.Entry<String, JsonNode> e : metrics.properties()) {
      Object n = number(e.getValue());
      if (n != null) {
        attributes.put(e.getKey(), n);
      } else if (e.getValue().isTextual()) {
        try {
          double d = Double.parseDouble(e.getValue().asText());
          if (Double.isFinite(d)) {
            attributes.put(e.getKey(), d);
          }
        } catch (NumberFormatException ignored) {
          // not a number: skip
        }
      }
    }
    if (!ddName.isEmpty()) {
      attributes.put("operation_name", ddName);
    }
    if (!resource.isEmpty()) {
      attributes.put("resource_name", resource);
    }
    if (!spanType.isEmpty()) {
      attributes.put("span.type", spanType);
    }
    Span.Status status = Span.Status.UNSET;
    if (numericTruthy(get(raw, "error"))) {
      String msg = str(first(meta, "error.message", "error.msg", "otel.status_description"));
      status = status(Span.StatusCode.ERROR, msg);
    } else if (str(get(meta, "otel.status_code")).equalsIgnoreCase("ok")) {
      status = status(Span.StatusCode.OK, "");
    }
    String name = resource.isEmpty() ? ddName : resource;
    return new Span(
        traceId,
        spanId.isEmpty() ? DatadogIds.ZERO_HIGH : spanId,
        parentId.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(parentId),
        name,
        datadogKind(str(get(meta, "span.kind"))),
        str(get(raw, "service")),
        start / 1000,
        end / 1000,
        duration / 1000,
        status,
        attributes,
        events(meta, raw),
        links(meta, raw));
  }

  /**
   * {@link #traceSpanToSpan(JsonNode)} for a decoded map.
   *
   * @param raw span map
   * @return span
   */
  public static Span traceSpanToSpan(Map<String, ?> raw) {
    JsonNode node = Json.mapper().valueToTree(raw);
    return traceSpanToSpan(node);
  }

  private static Span.Status status(Span.StatusCode code, String message) {
    return new Span.Status(
        code, message.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(message));
  }

  private static void flatten(String prefix, JsonNode v, Map<String, Object> out) {
    if (v == null) {
      return;
    }
    if (v.isObject()) {
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
      }
      return;
    }
    Object a = attrValue(v);
    if (a != null && !prefix.isEmpty()) {
      out.put(prefix, a);
    }
  }

  /** An ISO-8601 timestamp as Unix microseconds; null when absent or invalid. */
  static Long isoToMicros(JsonNode v) {
    if (v == null || !v.isTextual() || v.asText().isEmpty()) {
      return null;
    }
    String s = v.asText();
    Instant t;
    try {
      t = OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      try {
        t = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
    return t.getEpochSecond() * 1_000_000L + t.getNano() / 1000;
  }

  /**
   * Maps one span of the spans search API ({@code data[i]} of {@code POST
   * /api/v2/spans/events/search}, or its {@code attributes}) to a span. Nested span attributes
   * ({@code attributes}, {@code custom}) are flattened to dotted keys; tags ({@code key:value}) are
   * added as attributes. Name = {@code resource_name}, kind from {@code span.kind}, status error
   * when {@code status} is {@code error} or {@code error} is set; duration from the {@code
   * duration} attribute (nanoseconds) when present, else end - start.
   *
   * @param item span event
   * @return span
   * @throws ValidationException for invalid ids
   */
  public static Span searchSpanToSpan(JsonNode item) {
    JsonNode a = item;
    if (get(item, "attributes") != null && "spans".equals(str(get(item, "type")))) {
      a = record(get(item, "attributes"));
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    JsonNode tags = get(a, "tags");
    if (tags != null && tags.isArray()) {
      for (JsonNode t : tags) {
        if (t.isTextual()) {
          String s = t.asText();
          int colon = s.indexOf(':');
          if (colon > 0) {
            attributes.put(s.substring(0, colon), s.substring(colon + 1));
          }
        }
      }
    }
    flatten("", get(a, "custom"), attributes);
    flatten("", get(a, "attributes"), attributes);
    for (String key : new String[] {"env", "host", "type", "resource_hash", "ingestion_reason"}) {
      String v = str(get(a, key));
      if (!v.isEmpty()) {
        attributes.put(key.equals("type") ? "span.type" : key, v);
      }
    }
    String opName = str(get(a, "operation_name"));
    if (opName.isEmpty()) {
      opName = asString(attributes.get("operation_name"));
    }
    if (!opName.isEmpty()) {
      attributes.put("operation_name", opName);
    }
    String resource = str(get(a, "resource_name"));
    if (resource.isEmpty()) {
      resource = asString(attributes.get("resource_name"));
    }
    if (!resource.isEmpty()) {
      attributes.put("resource_name", resource);
    }
    Long startMicros = isoToMicros(get(a, "start_timestamp"));
    Long endMicros = isoToMicros(get(a, "end_timestamp"));
    long start = startMicros == null ? 0 : startMicros;
    long duration = 0;
    Object d = attributes.get("duration");
    Long durationNs = d == null ? null : int64(Json.mapper().valueToTree(d));
    if (durationNs != null) {
      duration = durationNs / 1000;
    } else if (endMicros != null) {
      duration = endMicros - start;
    }
    long end = endMicros != null ? endMicros : start + duration;
    String statusWord = str(get(a, "status")).toLowerCase(Locale.ROOT);
    if (statusWord.isEmpty()) {
      statusWord = asString(attributes.get("status")).toLowerCase(Locale.ROOT);
    }
    Span.Status status = Span.Status.UNSET;
    if (statusWord.equals("error") || truthy(attributes.get("error"))) {
      String msg = asString(attributes.get("error.message"));
      if (msg.isEmpty()) {
        msg = asString(attributes.get("error.msg"));
      }
      status = status(Span.StatusCode.ERROR, msg);
    } else if (asString(attributes.get("otel.status_code")).equalsIgnoreCase("ok")) {
      status = status(Span.StatusCode.OK, "");
    }
    String traceRaw = str(get(a, "trace_id"));
    String traceId = DatadogIds.normalizeDatadogTraceId(traceRaw.isEmpty() ? "0" : traceRaw);
    String spanRaw = str(get(a, "span_id"));
    String spanId = DatadogIds.normalizeDatadogSpanId(spanRaw.isEmpty() ? "0" : spanRaw);
    String parentId = DatadogIds.normalizeDatadogSpanId(str(get(a, "parent_id")));
    return new Span(
        traceId,
        spanId.isEmpty() ? DatadogIds.ZERO_HIGH : spanId,
        parentId.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(parentId),
        resource.isEmpty() ? opName : resource,
        datadogKind(asString(attributes.get("span.kind"))),
        str(get(a, "service")),
        start,
        end,
        duration,
        status,
        attributes,
        List.of(),
        List.of());
  }

  private static String asString(Object v) {
    if (v instanceof String) {
      return (String) v;
    }
    if (v instanceof Number) {
      return str(Json.mapper().valueToTree(v));
    }
    return "";
  }

  private static boolean truthy(Object v) {
    return v != null && numericTruthy(Json.mapper().valueToTree(v));
  }
}
