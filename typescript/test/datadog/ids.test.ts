import { readFileSync } from "node:fs";
import {
  ROOT_CONTEXT,
  TraceFlags,
  context,
  defaultTextMapGetter,
  defaultTextMapSetter,
  trace,
  type SpanContext,
} from "@opentelemetry/api";
import { describe, expect, it } from "vitest";
import {
  DATADOG_SITES,
  DatadogPropagator,
  ValidationError,
  apiUrlForSite,
  datadogTraceIdString,
  extractDatadogContext,
  fromDatadogSpanId,
  fromDatadogTraceId,
  isDatadogSite,
  normalizeDatadogSpanId,
  normalizeDatadogTraceId,
  parseJsonPreservingBigInts,
  terminatedContext,
  terminatedContextLinks,
  toDatadogSpanId,
  toDatadogTraceId,
  traceIdForPath,
  validateSite,
} from "../../src/index.js";

// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
function fixture<T>(name: string): T {
  return JSON.parse(readFileSync(new URL(`../fixtures/${name}`, import.meta.url), "utf8")) as T;
}

interface IdVector {
  otel_trace_id_hex: string;
  datadog_trace_id_low64_decimal: string;
  datadog_trace_id_high64_hex: string;
  "x-datadog-tags": string | null;
}

describe("trace id conversion (Agent-verified vectors)", () => {
  for (const v of fixture<IdVector[]>("toDatadogTraceId.vectors.json")) {
    it(`toDatadogTraceId / fromDatadogTraceId ${v.otel_trace_id_hex}`, () => {
      const dd = toDatadogTraceId(v.otel_trace_id_hex);
      expect(dd.low64Decimal).toBe(v.datadog_trace_id_low64_decimal);
      expect(dd.high64Hex).toBe(v.datadog_trace_id_high64_hex);
      expect(dd.is128Bit).toBe(v["x-datadog-tags"] !== null);
      expect(fromDatadogTraceId(dd.low64Decimal, dd.high64Hex)).toBe(v.otel_trace_id_hex);
      expect(normalizeDatadogTraceId(v.otel_trace_id_hex.toUpperCase())).toBe(v.otel_trace_id_hex);
    });
  }

  it("handles short and 64-bit forms", () => {
    expect(toDatadogTraceId("2a")).toEqual({
      low64Decimal: "42",
      high64Hex: "0000000000000000",
      is128Bit: false,
    });
    expect(fromDatadogTraceId("42")).toBe("0000000000000000000000000000002a");
    expect(fromDatadogTraceId("42", null)).toBe("0000000000000000000000000000002a");
    expect(fromDatadogTraceId("42", "")).toBe("0000000000000000000000000000002a");
    expect(datadogTraceIdString("0000000000000000000000000000002a")).toBe("42");
    expect(datadogTraceIdString("0123456789abcdefffffffffffffffff")).toBe(
      "0123456789abcdefffffffffffffffff",
    );
    expect(normalizeDatadogTraceId("9007199254740993")).toBe("00000000000000000020000000000001");
    expect(normalizeDatadogTraceId(42)).toBe("0000000000000000000000000000002a");
    expect(normalizeDatadogTraceId(12376211733897805824n)).toBe("0000000000000000abc1230000000000");
    expect(normalizeDatadogTraceId("abc")).toBe("00000000000000000000000000000abc");
  });

  it("rejects invalid ids", () => {
    for (const fn of [
      () => toDatadogTraceId("xyz"),
      () => toDatadogTraceId("0".repeat(32)),
      () => toDatadogTraceId("1".repeat(33)),
      () => fromDatadogTraceId("18446744073709551616"),
      () => fromDatadogTraceId("-1"),
      () => fromDatadogTraceId("12a"),
      () => fromDatadogTraceId("1", "abc"),
      () => fromDatadogTraceId("0"),
      () => toDatadogSpanId("1234567890abcdef0"),
      () => fromDatadogSpanId("x"),
    ]) {
      expect(fn).toThrow(ValidationError);
    }
  });

  it("converts span ids", () => {
    expect(toDatadogSpanId("00000000000000ff")).toBe("255");
    expect(toDatadogSpanId("ffffffffffffffff")).toBe("18446744073709551615");
    expect(fromDatadogSpanId("255")).toBe("00000000000000ff");
    expect(normalizeDatadogSpanId("0")).toBeNull();
    expect(normalizeDatadogSpanId(null)).toBeNull();
    expect(normalizeDatadogSpanId(undefined)).toBeNull();
    expect(normalizeDatadogSpanId("")).toBeNull();
    expect(normalizeDatadogSpanId(255)).toBe("00000000000000ff");
    expect(normalizeDatadogSpanId(9876543210987654321n)).toBe("891087b8e3b70cb1");
  });

  it("formats ids for the trace-by-id path", () => {
    expect(traceIdForPath("0000000000000000abc1230000000000")).toBe(
      "0000000000000000abc1230000000000",
    );
    expect(traceIdForPath("00000000000000000020000000000001")).toBe("9007199254740993");
    expect(traceIdForPath("11111111111111112222222222222222")).toBe(
      BigInt("0x11111111111111112222222222222222").toString(),
    );
  });
});

describe("parseJsonPreservingBigInts", () => {
  it("keeps large integers exact and leaves strings alone", () => {
    const text =
      '{"a": 9876543210987654321, "b": -12376211733897805824, "c": 123, "d": 1.5e3, "e": "x 12345678901234567890 \\" y", "f": [1716800000500000000], "g": 1234567890123456.5, "h": true, "i": null}';
    expect(parseJsonPreservingBigInts(text)).toEqual({
      a: "9876543210987654321",
      b: "-12376211733897805824",
      c: 123,
      d: 1500,
      e: 'x 12345678901234567890 " y',
      f: ["1716800000500000000"],
      g: 1234567890123456.5,
      h: true,
      i: null,
    });
  });
});

describe("sites", () => {
  it("validates and resolves", () => {
    expect(DATADOG_SITES).toContain("datadoghq.eu");
    expect(isDatadogSite(" DatadogHQ.com ")).toBe(true);
    expect(isDatadogSite("example.org")).toBe(false);
    expect(apiUrlForSite()).toBe("https://api.datadoghq.com");
    expect(apiUrlForSite("us5.datadoghq.com")).toBe("https://api.us5.datadoghq.com");
    expect(validateSite("ddog-gov.com")).toBe("ddog-gov.com");
    expect(() => validateSite("http://x.com")).toThrow(/site/);
    expect(() => validateSite("localhost")).toThrow();
  });
});

interface InjectVector {
  context: { trace_id_hex: string; span_id_hex: string; sampling_priority: number };
  reference_headers: Record<string, string>;
}

interface ExtractVector {
  headers: Record<string, string>;
  ddtrace_context: { trace_id_hex: string; span_id_hex: string; sampling_priority?: number };
  note?: string;
}

function ctxWith(span: SpanContext) {
  return trace.setSpanContext(ROOT_CONTEXT, span);
}

describe("DatadogPropagator (ddtrace-verified vectors)", () => {
  const p = new DatadogPropagator();

  for (const v of fixture<InjectVector[]>("propagation.datadog_inject.vectors.json")) {
    it(`inject ${v.context.trace_id_hex}`, () => {
      const carrier: Record<string, string> = {};
      p.inject(
        ctxWith({
          traceId: v.context.trace_id_hex,
          spanId: v.context.span_id_hex,
          traceFlags: v.context.sampling_priority > 0 ? TraceFlags.SAMPLED : TraceFlags.NONE,
        }),
        carrier,
        defaultTextMapSetter,
      );
      expect(carrier).toEqual(v.reference_headers);
    });
  }

  for (const v of fixture<ExtractVector[]>("propagation.datadog_extract.vectors.json")) {
    it(`extract ${v.note ?? v.headers["x-datadog-trace-id"]}`, () => {
      const ctx = p.extract(ROOT_CONTEXT, v.headers, defaultTextMapGetter);
      const sc = trace.getSpanContext(ctx);
      expect(sc?.traceId).toBe(v.ddtrace_context.trace_id_hex);
      expect(sc?.spanId).toBe(v.ddtrace_context.span_id_hex);
      expect(sc?.isRemote).toBe(true);
      if (v.ddtrace_context.sampling_priority !== undefined) {
        expect(sc?.traceFlags).toBe(
          v.ddtrace_context.sampling_priority > 0 ? TraceFlags.SAMPLED : TraceFlags.NONE,
        );
      }
      const links = terminatedContextLinks(ctx);
      if (v.note?.includes("datadog wins") === true) {
        expect(links).toHaveLength(1);
        expect(links[0]?.context.traceId).toBe("11111111111111112222222222222222");
        expect(links[0]?.context.spanId).toBe("0000000000000abc");
        expect(links[0]?.attributes).toEqual({
          reason: "terminated_context",
          context_headers: "tracecontext",
        });
        expect(terminatedContext(ctx)?.winnerSpanId).toBe("00000000000000ff");
      } else {
        expect(links).toEqual([]);
      }
    });
  }

  it("ignores missing and invalid headers", () => {
    const cases: Record<string, string>[] = [
      {},
      { "x-datadog-trace-id": "1" },
      { "x-datadog-trace-id": "abc", "x-datadog-parent-id": "1" },
      { "x-datadog-trace-id": "0", "x-datadog-parent-id": "1" },
      { "x-datadog-trace-id": "1", "x-datadog-parent-id": "0" },
      { "x-datadog-trace-id": "18446744073709551616", "x-datadog-parent-id": "1" },
      { "x-datadog-trace-id": "  ", "x-datadog-parent-id": "1" },
    ];
    for (const headers of cases) {
      expect(p.extract(ROOT_CONTEXT, headers, defaultTextMapGetter)).toBe(ROOT_CONTEXT);
      expect(extractDatadogContext(headers, defaultTextMapGetter)).toBeUndefined();
    }
  });

  it("parses tags, priority and array headers leniently", () => {
    const sc = extractDatadogContext(
      {
        "x-datadog-trace-id": ["1"],
        "x-datadog-parent-id": "2",
        "x-datadog-sampling-priority": "-1",
        "x-datadog-tags": "_dd.p.dm=-4,bad,_dd.p.tid=ZZ",
      },
      defaultTextMapGetter,
    );
    expect(sc).toMatchObject({
      traceId: "00000000000000000000000000000001",
      spanId: "0000000000000002",
      traceFlags: TraceFlags.NONE,
    });
    const keep = extractDatadogContext(
      {
        "x-datadog-trace-id": "1",
        "x-datadog-parent-id": "2",
        "x-datadog-sampling-priority": "2",
        "x-datadog-tags": "_dd.p.tid=00000000000000ab",
      },
      defaultTextMapGetter,
    );
    expect(keep?.traceId).toBe("00000000000000ab0000000000000001");
    expect(keep?.traceFlags).toBe(TraceFlags.SAMPLED);
    const noTid = extractDatadogContext(
      { "x-datadog-trace-id": "1", "x-datadog-parent-id": "2", "x-datadog-tags": "a=b" },
      defaultTextMapGetter,
    );
    expect(noTid?.traceId).toBe("00000000000000000000000000000001");
  });

  it("same trace id: W3C parent wins and an existing W3C context is kept", () => {
    const headers = {
      "x-datadog-trace-id": "18446744073709551615",
      "x-datadog-parent-id": "255",
      "x-datadog-sampling-priority": "0",
      "x-datadog-tags": "_dd.p.tid=0123456789abcdef",
      traceparent: "00-0123456789abcdefffffffffffffffff-0000000000000abc-01",
    };
    const fresh = trace.getSpanContext(p.extract(ROOT_CONTEXT, headers, defaultTextMapGetter));
    expect(fresh).toMatchObject({ spanId: "0000000000000abc", traceFlags: TraceFlags.NONE });
    const w3c: SpanContext = {
      traceId: "0123456789abcdefffffffffffffffff",
      spanId: "0000000000000abc",
      traceFlags: TraceFlags.SAMPLED,
      isRemote: true,
    };
    const kept = trace.getSpanContext(p.extract(ctxWith(w3c), headers, defaultTextMapGetter));
    expect(kept).toBe(w3c);
    const bad = {
      ...headers,
      traceparent: "ff-0123456789abcdefffffffffffffffff-0000000000000abc-01",
    };
    expect(trace.getSpanContext(p.extract(ROOT_CONTEXT, bad, defaultTextMapGetter))?.spanId).toBe(
      "00000000000000ff",
    );
    const zero = { ...headers, traceparent: `00-${"0".repeat(32)}-0000000000000abc-01` };
    expect(trace.getSpanContext(p.extract(ROOT_CONTEXT, zero, defaultTextMapGetter))?.spanId).toBe(
      "00000000000000ff",
    );
  });

  it("inject without a valid span is a no-op and lists its fields", () => {
    const carrier: Record<string, string> = {};
    p.inject(context.active(), carrier, defaultTextMapSetter);
    p.inject(
      ctxWith({ traceId: "0".repeat(32), spanId: "0".repeat(16), traceFlags: 0 }),
      carrier,
      defaultTextMapSetter,
    );
    expect(carrier).toEqual({});
    expect(p.fields()).toContain("x-datadog-trace-id");
    expect(terminatedContext(ROOT_CONTEXT)).toBeUndefined();
  });
});
