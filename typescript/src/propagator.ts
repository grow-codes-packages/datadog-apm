// Service-owned: Datadog header propagation (`x-datadog-*`) with ddtrace's conflict rules
// (API.md §3, DECISIONS.md #5). Implemented from Datadog's documented header format; no ddtrace
// code is used.

import {
  TraceFlags,
  createContextKey,
  isSpanContextValid,
  trace,
  type Context,
  type Link,
  type SpanContext,
  type TextMapGetter,
  type TextMapPropagator,
  type TextMapSetter,
} from "@opentelemetry/api";
import type { Span as SdkSpan, SpanProcessor } from "@opentelemetry/sdk-trace-base";
import { fromDatadogSpanId, fromDatadogTraceId, toDatadogSpanId, toDatadogTraceId } from "./ids.js";

/** `x-datadog-trace-id`: low 64 bits of the trace id, unsigned decimal. */
export const DATADOG_TRACE_ID_HEADER = "x-datadog-trace-id";
/** `x-datadog-parent-id`: span id, unsigned decimal. */
export const DATADOG_PARENT_ID_HEADER = "x-datadog-parent-id";
/** `x-datadog-sampling-priority`: `1` sampled, `0` not sampled. */
export const DATADOG_SAMPLING_PRIORITY_HEADER = "x-datadog-sampling-priority";
/** `x-datadog-tags`: propagated tags, e.g. `_dd.p.tid=<high 64 bits hex>`. */
export const DATADOG_TAGS_HEADER = "x-datadog-tags";
/** `x-datadog-origin`: origin of the trace (synthetics, rum, …); read and passed through. */
export const DATADOG_ORIGIN_HEADER = "x-datadog-origin";

const DIGITS = /^[0-9]{1,20}$/;
const HEX16 = /^[0-9a-f]{16}$/;
const TRACEPARENT = /^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$/;

/** A W3C context that lost against Datadog headers in {@link DatadogPropagator.extract}. */
export interface TerminatedContext {
  /** The W3C span context from `traceparent`. */
  spanContext: SpanContext;
  /** Span id of the Datadog context that won (the parent of the next span). */
  winnerSpanId: string;
}

const TERMINATED_KEY = createContextKey("grow-codes datadog-apm terminated W3C context");

function header(carrier: unknown, getter: TextMapGetter, name: string): string | undefined {
  const value = getter.get(carrier, name);
  const first = Array.isArray(value) ? value[0] : value;
  if (first === undefined) return undefined;
  const trimmed = first.trim();
  return trimmed === "" ? undefined : trimmed;
}

function parseTid(tags: string | undefined): string | undefined {
  if (tags === undefined) return undefined;
  for (const part of tags.split(",")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    if (part.slice(0, eq).trim() !== "_dd.p.tid") continue;
    const value = part
      .slice(eq + 1)
      .trim()
      .toLowerCase();
    return HEX16.test(value) ? value : undefined;
  }
  return undefined;
}

function parseTraceparent(value: string | undefined): SpanContext | undefined {
  if (value === undefined) return undefined;
  const m = TRACEPARENT.exec(value.toLowerCase());
  if (m === null || m[1] === "ff") return undefined;
  const ctx: SpanContext = {
    traceId: m[2] as string,
    spanId: m[3] as string,
    traceFlags: parseInt(m[4] as string, 16) & TraceFlags.SAMPLED,
    isRemote: true,
  };
  return isSpanContextValid(ctx) ? ctx : undefined;
}

/**
 * Reads the Datadog headers of a carrier into a remote span context, or `undefined` when they are
 * missing or invalid. A missing sampling priority counts as sampled.
 */
export function extractDatadogContext(
  carrier: unknown,
  getter: TextMapGetter,
): SpanContext | undefined {
  const traceId = header(carrier, getter, DATADOG_TRACE_ID_HEADER);
  const parentId = header(carrier, getter, DATADOG_PARENT_ID_HEADER);
  if (traceId === undefined || parentId === undefined) return undefined;
  if (!DIGITS.test(traceId) || !DIGITS.test(parentId)) return undefined;
  let ctx: SpanContext;
  try {
    ctx = {
      traceId: fromDatadogTraceId(traceId, parseTid(header(carrier, getter, DATADOG_TAGS_HEADER))),
      spanId: fromDatadogSpanId(parentId),
      traceFlags: TraceFlags.SAMPLED,
      isRemote: true,
    };
  } catch {
    return undefined;
  }
  const priority = header(carrier, getter, DATADOG_SAMPLING_PRIORITY_HEADER);
  if (priority !== undefined && /^-?\d+$/.test(priority) && Number(priority) <= 0) {
    ctx.traceFlags = TraceFlags.NONE;
  }
  return isSpanContextValid(ctx) ? ctx : undefined;
}

/**
 * Datadog propagator: injects and extracts `x-datadog-trace-id` (low 64 bits, decimal),
 * `x-datadog-parent-id` (decimal), `x-datadog-sampling-priority` (`1`/`0`) and, for 128-bit ids,
 * `x-datadog-tags: _dd.p.tid=<high 64 bits hex>`.
 *
 * Conflict rules on extract (ddtrace's behaviour with style order `datadog,tracecontext`): when the
 * carrier also holds a valid `traceparent`,
 * - same trace id → the W3C parent id wins (the context from `traceparent` is kept),
 * - different trace id → the Datadog context wins and the W3C context is remembered in the
 *   returned context; {@link DatadogSpanLinkProcessor} (installed by `initTracing`) adds it to the
 *   next span as a link with `reason: terminated_context` (see {@link terminatedContextLinks}).
 *
 * The rules apply whatever position `datadog` has in the propagator list.
 */
export class DatadogPropagator implements TextMapPropagator {
  /** Writes the Datadog headers of the span in `context`; no-op without a valid span context. */
  inject(context: Context, carrier: unknown, setter: TextMapSetter): void {
    const span = trace.getSpanContext(context);
    if (span === undefined || !isSpanContextValid(span)) return;
    const id = toDatadogTraceId(span.traceId);
    setter.set(carrier, DATADOG_TRACE_ID_HEADER, id.low64Decimal);
    setter.set(carrier, DATADOG_PARENT_ID_HEADER, toDatadogSpanId(span.spanId));
    const sampled = (span.traceFlags & 1) !== 0; // TraceFlags.SAMPLED
    setter.set(carrier, DATADOG_SAMPLING_PRIORITY_HEADER, sampled ? "1" : "0");
    if (id.is128Bit) setter.set(carrier, DATADOG_TAGS_HEADER, `_dd.p.tid=${id.high64Hex}`);
  }

  /** Reads the Datadog headers (with the conflict rules above) into `context`. */
  extract(context: Context, carrier: unknown, getter: TextMapGetter): Context {
    const dd = extractDatadogContext(carrier, getter);
    if (dd === undefined) return context;
    const w3c = parseTraceparent(header(carrier, getter, "traceparent"));
    if (w3c === undefined) return trace.setSpanContext(context, dd);
    if (w3c.traceId === dd.traceId) {
      // Same trace: ddtrace takes the W3C parent id; keep tracestate set by the W3C propagator.
      const current = trace.getSpanContext(context);
      const keep =
        current !== undefined && current.traceId === w3c.traceId && current.spanId === w3c.spanId
          ? current
          : { ...w3c, traceFlags: dd.traceFlags };
      return trace.setSpanContext(context, keep);
    }
    const terminated: TerminatedContext = { spanContext: w3c, winnerSpanId: dd.spanId };
    return trace.setSpanContext(context, dd).setValue(TERMINATED_KEY, terminated);
  }

  /** Header names this propagator reads and writes. */
  fields(): string[] {
    return [
      DATADOG_TRACE_ID_HEADER,
      DATADOG_PARENT_ID_HEADER,
      DATADOG_SAMPLING_PRIORITY_HEADER,
      DATADOG_TAGS_HEADER,
    ];
  }
}

/** The W3C context that lost against Datadog headers during extract, if any. */
export function terminatedContext(context: Context): TerminatedContext | undefined {
  return context.getValue(TERMINATED_KEY) as TerminatedContext | undefined;
}

/**
 * Span links for a span started from an extracted context: one link to the terminated W3C
 * context (`reason: terminated_context`, `context_headers: tracecontext`), or none. Pass them to
 * `withSpan(..., { links })` when you start spans without `initTracing`'s processor.
 */
export function terminatedContextLinks(context: Context): Link[] {
  const t = terminatedContext(context);
  if (t === undefined) return [];
  return [
    {
      context: t.spanContext,
      attributes: { reason: "terminated_context", context_headers: "tracecontext" },
    },
  ];
}

/**
 * Span processor that adds the {@link terminatedContextLinks} link to the first span started from
 * an extracted context whose W3C context lost against Datadog headers. `initTracing` installs it.
 */
export class DatadogSpanLinkProcessor implements SpanProcessor {
  onStart(span: SdkSpan, parentContext: Context): void {
    const t = terminatedContext(parentContext);
    const parent = span.parentSpanContext;
    if (t === undefined || parent?.isRemote !== true || parent.spanId !== t.winnerSpanId) return;
    for (const link of terminatedContextLinks(parentContext)) span.addLink(link);
  }

  onEnd(): void {
    // nothing to do
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
