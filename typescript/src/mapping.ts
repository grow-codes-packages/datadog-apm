// Service-owned: Datadog span formats → the SPEC §5 trace model (API.md §2.1, §4).
//
// Two Datadog shapes are mapped:
// - a *trace span* as the Agent forwards it and as `GET /api/v2/trace/{id}` returns it (`meta`,
//   `metrics`, nanosecond `start`/`startTime`, decimal ids; camelCase or snake_case keys);
// - a *span event* of the spans search API (`attributes.trace_id`, `start_timestamp`, …).
//
// For spans that came in over OTLP the Datadog `resource` is the OpenTelemetry span name and the
// Datadog `name` is the operation (`Internal`, `server.request`, `http.server.request`, …), so the
// normalized `name` is the resource; the Datadog operation is kept as attribute `operation_name`.

import { fromDatadogTraceId, normalizeDatadogSpanId, normalizeDatadogTraceId } from "./ids.js";
import type {
  Span,
  SpanAttrValue,
  SpanAttributes,
  SpanEvent,
  SpanKindName,
  SpanLink,
} from "./tracing/index.js";

/** A raw span object (any Datadog span shape). */
export type RawDatadogSpan = Record<string, unknown>;

const KINDS: ReadonlySet<string> = new Set([
  "internal",
  "server",
  "client",
  "producer",
  "consumer",
]);

/** Datadog `span.kind` (or OTel kind name) → SPEC kind; unknown → `unspecified`. */
export function datadogKind(value: unknown): SpanKindName {
  if (typeof value !== "string") return "unspecified";
  const lower = value.toLowerCase();
  return KINDS.has(lower) ? (lower as SpanKindName) : "unspecified";
}

function str(value: unknown): string | undefined {
  if (typeof value === "string") return value === "" ? undefined : value;
  if (typeof value === "number" || typeof value === "bigint") return String(value);
  return undefined;
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function toBigInt(value: unknown): bigint | undefined {
  if (typeof value === "bigint") return value;
  if (typeof value === "number" && Number.isFinite(value)) return BigInt(Math.trunc(value));
  if (typeof value === "string" && /^-?\d+$/.test(value.trim())) return BigInt(value.trim());
  return undefined;
}

function nanosToMicros(ns: bigint): number {
  return Number(ns / 1000n);
}

/** Converts JSON data to an attribute value (drops `null`). */
function attrValue(value: unknown): SpanAttrValue | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean")
    return value;
  if (typeof value === "bigint") return value.toString();
  if (Array.isArray(value)) {
    return value.map(attrValue).filter((v): v is SpanAttrValue => v !== undefined);
  }
  if (typeof value === "object") {
    const out: Record<string, SpanAttrValue> = {};
    for (const [k, v] of Object.entries(value)) {
      const a = attrValue(v);
      if (a !== undefined) out[k] = a;
    }
    return out;
  }
  return undefined;
}

function attrs(value: unknown): SpanAttributes {
  return attrValue(record(value)) as SpanAttributes;
}

function parseJsonArray(text: unknown): Record<string, unknown>[] {
  if (typeof text !== "string" || text === "") return [];
  try {
    const parsed = JSON.parse(text) as unknown;
    return Array.isArray(parsed) ? parsed.map(record) : [];
  } catch {
    return [];
  }
}

/** Native Datadog span event attribute (`{type, string_value, int_value, …}`) → plain value. */
function nativeAttr(value: unknown): unknown {
  const v = record(value);
  for (const key of ["string_value", "int_value", "double_value", "bool_value"]) {
    if (key in v) return v[key];
  }
  if ("array_value" in v) {
    const arr = record(v.array_value).values;
    return Array.isArray(arr) ? arr.map(nativeAttr) : [];
  }
  return value;
}

function eventsOf(meta: Record<string, unknown>, raw: RawDatadogSpan): SpanEvent[] {
  const out: SpanEvent[] = [];
  for (const e of parseJsonArray(meta.events)) {
    const ns = toBigInt(e.time_unix_nano) ?? 0n;
    out.push({
      name: str(e.name) ?? "",
      timeMicros: nanosToMicros(ns),
      attributes: attrs(e.attributes),
    });
  }
  const native = raw.span_events ?? raw.spanEvents;
  if (Array.isArray(native)) {
    for (const item of native) {
      const e = record(item);
      const ns = toBigInt(e.time_unix_nano ?? e.timeUnixNano) ?? 0n;
      const a: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(record(e.attributes))) a[k] = nativeAttr(v);
      out.push({ name: str(e.name) ?? "", timeMicros: nanosToMicros(ns), attributes: attrs(a) });
    }
  }
  return out;
}

function linkTraceId(l: Record<string, unknown>): string | undefined {
  try {
    const id = str(l.trace_id ?? l.traceId ?? l.traceID);
    if (id === undefined) return undefined;
    const high = str(l.trace_id_high ?? l.traceIdHigh);
    if (high !== undefined && /^\d+$/.test(id)) {
      const highHex = /^[0-9a-f]{16}$/.test(high)
        ? high
        : BigInt(high).toString(16).padStart(16, "0");
      return fromDatadogTraceId(id, highHex);
    }
    return /^[0-9a-f]{16}$/.test(id) ? id.padStart(32, "0") : normalizeDatadogTraceId(id);
  } catch {
    return undefined;
  }
}

function linkSpanId(l: Record<string, unknown>): string | undefined {
  const id = str(l.span_id ?? l.spanId ?? l.spanID);
  if (id === undefined) return undefined;
  if (/^[0-9a-f]{16}$/.test(id) && /[a-f]/.test(id)) return id;
  try {
    return normalizeDatadogSpanId(id) ?? undefined;
  } catch {
    return undefined;
  }
}

function linksOf(meta: Record<string, unknown>, raw: RawDatadogSpan): SpanLink[] {
  const out: SpanLink[] = [];
  const native = raw.span_links ?? raw.spanLinks;
  const items = [
    ...parseJsonArray(meta["_dd.span_links"]),
    ...(Array.isArray(native) ? native.map(record) : []),
  ];
  for (const l of items) {
    const traceId = linkTraceId(l);
    const spanId = linkSpanId(l);
    if (traceId !== undefined && spanId !== undefined)
      out.push({ traceId, spanId, attributes: attrs(l.attributes) });
  }
  return out;
}

const HIDDEN_META: ReadonlySet<string> = new Set(["events", "_dd.span_links"]);

/**
 * Maps a Datadog trace span — the Agent's format (`trace_id`, `span_id`, `parent_id`, `start`,
 * `duration`, `meta`, `metrics`) or the trace-by-id API format (`traceID`, `traceIDFull`, `spanID`,
 * `parentID`, `startTime`, `endTime`, `duration`) — to a SPEC {@link Span}.
 *
 * The full trace id comes from `traceIDFull` / `trace_id_hex` / meta `otel.trace_id`, else the
 * decimal low 64 bits plus meta `_dd.p.tid`. `name` = Datadog `resource` (the OTel span name for
 * OTLP spans), `kind` = meta `span.kind`, status = `error` flag (message from `error.message`,
 * `error.msg` or `otel.status_description`) or meta `otel.status_code`. Events come from meta
 * `events` and `span_events`, links from meta `_dd.span_links` and `span_links`. Attributes are
 * the remaining meta (strings) and metrics (numbers), plus `operation_name`, `resource_name` and
 * `span.type`.
 */
export function datadogTraceSpanToSpan(raw: RawDatadogSpan): Span {
  const meta = record(raw.meta);
  const metrics = record(raw.metrics);
  const full = str(raw.traceIDFull ?? raw.trace_id_hex ?? meta["otel.trace_id"]);
  const low = raw.traceID ?? raw.trace_id ?? raw.traceId;
  const traceId =
    full !== undefined
      ? normalizeDatadogTraceId(full.padStart(32, "0"))
      : fromDatadogTraceId(str(low) ?? "0", str(meta["_dd.p.tid"] ?? raw.trace_id_high) ?? null);
  const spanId = normalizeDatadogSpanId(str(raw.spanID ?? raw.span_id ?? raw.spanId) ?? "0");
  const parentSpanId = normalizeDatadogSpanId(str(raw.parentID ?? raw.parent_id ?? raw.parentId));
  const startNs = toBigInt(raw.startTime ?? raw.start) ?? 0n;
  const endField = toBigInt(raw.endTime ?? raw.end);
  const durationNs = toBigInt(raw.duration) ?? (endField === undefined ? 0n : endField - startNs);
  const endNs = endField ?? startNs + durationNs;
  const ddName = str(raw.name);
  const resource = str(raw.resource ?? raw.resource_name);
  const type = str(raw.type);

  const attributes: SpanAttributes = {};
  for (const [k, v] of Object.entries(meta)) {
    if (HIDDEN_META.has(k) || typeof v !== "string") continue;
    attributes[k] = v;
  }
  for (const [k, v] of Object.entries(metrics)) {
    if (typeof v === "number") attributes[k] = v;
    else if (typeof v === "string" && v !== "" && Number.isFinite(Number(v)))
      attributes[k] = Number(v);
  }
  if (ddName !== undefined) attributes.operation_name = ddName;
  if (resource !== undefined) attributes.resource_name = resource;
  if (type !== undefined) attributes["span.type"] = type;

  const isError = Number(raw.error ?? 0) !== 0;
  const statusMessage = str(
    meta["error.message"] ?? meta["error.msg"] ?? meta["otel.status_description"],
  );
  const otelStatus = str(meta["otel.status_code"])?.toLowerCase();
  const status: Span["status"] = isError
    ? { code: "error", ...(statusMessage === undefined ? {} : { message: statusMessage }) }
    : { code: otelStatus === "ok" ? "ok" : "unset" };

  const startMicros = nanosToMicros(startNs);
  const endMicros = nanosToMicros(endNs);
  const span: Span = {
    traceId,
    spanId: spanId ?? "0000000000000000",
    name: resource ?? ddName ?? "",
    kind: datadogKind(meta["span.kind"]),
    serviceName: str(raw.service) ?? "",
    startMicros,
    endMicros,
    durationMicros: nanosToMicros(durationNs),
    status,
    attributes,
    events: eventsOf(meta, raw),
    links: linksOf(meta, raw),
  };
  if (parentSpanId !== null) span.parentSpanId = parentSpanId;
  return span;
}

function flatten(prefix: string, value: unknown, out: SpanAttributes): void {
  if (typeof value === "object" && value !== null && !Array.isArray(value)) {
    for (const [k, v] of Object.entries(value))
      flatten(prefix === "" ? k : `${prefix}.${k}`, v, out);
    return;
  }
  const a = attrValue(value);
  if (a !== undefined && prefix !== "") out[prefix] = a;
}

function isoToMicros(value: unknown): number | undefined {
  if (typeof value !== "string" || value === "") return undefined;
  const m = /\.(\d+)(Z|[+-]\d\d:?\d\d)?$/.exec(value);
  const ms = Date.parse(value);
  if (Number.isNaN(ms)) return undefined;
  let micros = Math.floor(ms / 1000) * 1_000_000;
  if (m !== null) micros += Number(((m[1] as string) + "000000").slice(0, 6));
  else micros += (ms % 1000) * 1000;
  return micros;
}

/**
 * Maps one span of the spans search API (`data[i]` of `POST /api/v2/spans/events/search`, or its
 * `attributes`) to a SPEC {@link Span}. Nested span attributes (`attributes`, `custom`) are
 * flattened to dotted keys; `tags` (`key:value`) are added as attributes. `name` = `resource_name`,
 * kind from `span.kind`, status `error` when `status` is `error` or `error` is set; duration from
 * the `duration` attribute (nanoseconds) when present, else end − start.
 */
export function datadogSearchSpanToSpan(item: RawDatadogSpan): Span {
  const a =
    "attributes" in item && "type" in item && item.type === "spans"
      ? record(item.attributes)
      : item;
  const attributes: SpanAttributes = {};
  for (const tag of Array.isArray(a.tags) ? a.tags : []) {
    if (typeof tag !== "string") continue;
    const colon = tag.indexOf(":");
    if (colon > 0) attributes[tag.slice(0, colon)] = tag.slice(colon + 1);
  }
  flatten("", a.custom, attributes);
  flatten("", a.attributes, attributes);
  for (const key of ["env", "host", "type", "resource_hash", "ingestion_reason"]) {
    const v = str(a[key]);
    if (v !== undefined) attributes[key === "type" ? "span.type" : key] = v;
  }
  const opName = str(a.operation_name ?? attributes.operation_name);
  if (opName !== undefined) attributes.operation_name = opName;
  const resource = str(a.resource_name ?? attributes.resource_name);
  if (resource !== undefined) attributes.resource_name = resource;

  const startMicros = isoToMicros(a.start_timestamp) ?? 0;
  const endTs = isoToMicros(a.end_timestamp);
  const durationAttr = toBigInt(attributes.duration);
  const durationMicros =
    durationAttr !== undefined
      ? nanosToMicros(durationAttr)
      : endTs !== undefined
        ? endTs - startMicros
        : 0;
  const endMicros = endTs ?? startMicros + durationMicros;
  const statusWord = str(a.status ?? attributes.status)?.toLowerCase();
  const isError =
    statusWord === "error" ||
    (attributes.error !== undefined &&
      Number(attributes.error) !== 0 &&
      typeof attributes.error !== "object");
  const message = str(attributes["error.message"] ?? attributes["error.msg"]);
  const otelStatus = str(attributes["otel.status_code"])?.toLowerCase();
  const status: Span["status"] = isError
    ? { code: "error", ...(message === undefined ? {} : { message }) }
    : { code: otelStatus === "ok" ? "ok" : "unset" };

  const span: Span = {
    traceId: normalizeDatadogTraceId(str(a.trace_id) ?? "0"),
    spanId: normalizeDatadogSpanId(str(a.span_id) ?? "0") ?? "0000000000000000",
    name: resource ?? opName ?? "",
    kind: datadogKind(attributes["span.kind"]),
    serviceName: str(a.service) ?? "",
    startMicros,
    endMicros,
    durationMicros,
    status,
    attributes,
    events: [],
    links: [],
  };
  const parent = normalizeDatadogSpanId(str(a.parent_id));
  if (parent !== null) span.parentSpanId = parent;
  return span;
}
