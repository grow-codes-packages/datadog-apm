// Service-owned: Datadog <-> OpenTelemetry id conversion (API.md §3, DECISIONS.md #3).
//
// Datadog stores a trace id as the low 64 bits in unsigned decimal plus, for 128-bit ids, the
// high 64 bits as 16 lower-case hex characters (`_dd.p.tid`). OpenTelemetry uses 32 hex
// characters. Span ids are unsigned 64-bit decimals in Datadog and 16 hex characters in
// OpenTelemetry. Every value is handled as a string or `bigint`: ids exceed 2^53.

import { ValidationError } from "./core/index.js";

const MAX_U64 = (1n << 64n) - 1n;
const HEX32 = /^[0-9a-f]{32}$/;
const HEX16 = /^[0-9a-f]{16}$/;
const DIGITS = /^[0-9]+$/;
const ZERO_HIGH = "0000000000000000";

/** A trace id split the way Datadog stores it. */
export interface DatadogTraceId {
  /** Low 64 bits as an unsigned decimal string (`x-datadog-trace-id`, the Agent's `trace_id`). */
  low64Decimal: string;
  /** High 64 bits as 16 lower-case hex characters (`_dd.p.tid`); all zeros for a 64-bit id. */
  high64Hex: string;
  /** True when the high 64 bits are not zero (a 128-bit id that needs `_dd.p.tid`). */
  is128Bit: boolean;
}

function invalid(what: string): never {
  throw new ValidationError(`datadog id: ${what}`);
}

function hexTraceId(traceIdHex: string): string {
  const hex = traceIdHex.trim().toLowerCase();
  if (!/^[0-9a-f]{1,32}$/.test(hex)) invalid("trace id must be 1 to 32 hex characters");
  const padded = hex.padStart(32, "0");
  if (/^0+$/.test(padded)) invalid("trace id must not be all zeros");
  return padded;
}

function parseU64(decimal: string, what: string): bigint {
  const text = decimal.trim();
  if (!DIGITS.test(text)) invalid(`${what} must be an unsigned decimal`);
  const value = BigInt(text);
  if (value > MAX_U64) invalid(`${what} exceeds 2^64-1`);
  return value;
}

/**
 * Splits an OpenTelemetry trace id (up to 32 hex characters, left-padded) into Datadog's form.
 *
 * @example
 * toDatadogTraceId("a3ce929d0e0e47364bf92f3577b34da6")
 * // { low64Decimal: "5474458728733560230", high64Hex: "a3ce929d0e0e4736", is128Bit: true }
 */
export function toDatadogTraceId(traceIdHex: string): DatadogTraceId {
  const hex = hexTraceId(traceIdHex);
  const high64Hex = hex.slice(0, 16);
  return {
    low64Decimal: BigInt(`0x${hex.slice(16)}`).toString(10),
    high64Hex,
    is128Bit: high64Hex !== ZERO_HIGH,
  };
}

/**
 * Joins a Datadog trace id (low 64 bits in decimal, optional `_dd.p.tid` high half in hex) into
 * a 32-character OpenTelemetry trace id. Without `high64Hex` the high half is zero.
 *
 * @example
 * fromDatadogTraceId("1", "ffffffffffffffff") // "ffffffffffffffff0000000000000001"
 * fromDatadogTraceId("42")                    // "0000000000000000000000000000002a"
 */
export function fromDatadogTraceId(low64Decimal: string, high64Hex?: string | null): string {
  const low = parseU64(low64Decimal, "trace id");
  let high = ZERO_HIGH;
  if (high64Hex !== undefined && high64Hex !== null && high64Hex !== "") {
    high = high64Hex.trim().toLowerCase();
    if (!HEX16.test(high)) invalid("high 64 bits must be 16 hex characters");
  }
  const id = high + low.toString(16).padStart(16, "0");
  if (/^0+$/.test(id)) invalid("trace id must not be all zeros");
  return id;
}

/** OpenTelemetry span id (up to 16 hex characters) → Datadog unsigned decimal. */
export function toDatadogSpanId(spanIdHex: string): string {
  const hex = spanIdHex.trim().toLowerCase();
  if (!/^[0-9a-f]{1,16}$/.test(hex)) invalid("span id must be 1 to 16 hex characters");
  return BigInt(`0x${hex}`).toString(10);
}

/** Datadog unsigned decimal span id → 16 lower-case hex characters. */
export function fromDatadogSpanId(decimal: string): string {
  return parseU64(decimal, "span id").toString(16).padStart(16, "0");
}

/**
 * The trace id in the format Datadog's log correlation (`dd.trace_id`) and its search API use:
 * 32 lower-case hex characters for a 128-bit id, the low 64 bits in decimal for a 64-bit id.
 */
export function datadogTraceIdString(traceIdHex: string): string {
  const id = toDatadogTraceId(traceIdHex);
  return id.is128Bit ? hexTraceId(traceIdHex) : id.low64Decimal;
}

/**
 * Normalizes any trace id Datadog returns to 32 lower-case hex characters: a 32-character value
 * is hex, a shorter all-digit value is the 64-bit decimal form, anything else is hex.
 */
export function normalizeDatadogTraceId(value: string | number | bigint): string {
  if (typeof value === "number" || typeof value === "bigint") {
    return fromDatadogTraceId(BigInt(value).toString(10));
  }
  const text = value.trim().toLowerCase();
  if (HEX32.test(text)) return text;
  if (DIGITS.test(text) && text.length <= 20) return fromDatadogTraceId(text);
  return hexTraceId(text);
}

/** Normalizes a Datadog span id (decimal string or number) to 16 hex; `null` for 0 / empty. */
export function normalizeDatadogSpanId(
  value: string | number | bigint | null | undefined,
): string | null {
  if (value === null || value === undefined || value === "") return null;
  const decimal = typeof value === "string" ? value.trim() : BigInt(value).toString(10);
  if (/^0+$/.test(decimal)) return null;
  return fromDatadogSpanId(decimal);
}

/**
 * The id to put in a `GET /api/v2/trace/{id}` path. Datadog reads an all-digit id as decimal, so
 * a hex id without a letter is sent as its full decimal value; otherwise lower-case hex.
 */
export function traceIdForPath(traceIdHex: string): string {
  const hex = hexTraceId(traceIdHex);
  return /[a-f]/.test(hex) ? hex : BigInt(`0x${hex}`).toString(10);
}
