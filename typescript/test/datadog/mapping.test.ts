import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  buildTree,
  datadogKind,
  datadogSearchSpanToSpan,
  datadogTraceSpanToSpan,
  parseJsonPreservingBigInts,
  type RawDatadogSpan,
} from "../../src/index.js";

// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
function fixture<T>(name: string): T {
  return JSON.parse(readFileSync(new URL(`../fixtures/${name}`, import.meta.url), "utf8")) as T;
}

describe("datadogTraceSpanToSpan (Agent output recorded from datadog/agent 7.85.4)", () => {
  const spans = fixture<RawDatadogSpan[]>("initTracing.otlp_http_json.agent_spans.json").map(
    datadogTraceSpanToSpan,
  );
  const byName = new Map(spans.map((s) => [s.name, s]));

  it("maps ids, names, kinds and times", () => {
    expect(spans.map((s) => s.name)).toEqual([
      "GET /items/{id}",
      "POST",
      "compute",
      "orders publish",
      "orders process",
    ]);
    const root = byName.get("GET /items/{id}");
    expect(root).toMatchObject({
      traceId: "0123456789abcdefffffffffffffffff",
      spanId: "00000000000000ff",
      kind: "server",
      startMicros: 1795035814558726,
      durationMicros: 9000,
      endMicros: 1795035814567726,
      status: { code: "unset" },
    });
    expect(root?.parentSpanId).toBeUndefined();
    expect(root?.attributes).toMatchObject({
      operation_name: "http.server.request",
      resource_name: "GET /items/{id}",
      "span.type": "web",
    });
    expect(byName.get("POST")).toMatchObject({
      kind: "client",
      parentSpanId: "00000000000000ff",
      status: { code: "error", message: "boom" },
    });
    expect(byName.get("orders process")?.status).toEqual({ code: "ok" });
    expect(byName.get("orders publish")?.kind).toBe("producer");
    const tree = buildTree(spans);
    expect(tree).toHaveLength(1);
    expect(tree[0]?.children.map((c) => c.span.name)).toEqual([
      "POST",
      "compute",
      "orders publish",
    ]);
  });

  it("maps events and links from meta", () => {
    const compute = byName.get("compute");
    expect(compute?.events).toEqual([
      {
        name: "cache.miss",
        timeMicros: expect.any(Number) as number,
        attributes: { key: "k1", n: 3 },
      },
    ]);
    expect(compute?.links).toEqual([
      {
        traceId: "a3ce929d0e0e47364bf92f3577b34da6",
        spanId: "00f067aa0ba902b7",
        attributes: { "link.kind": "follows" },
      },
    ]);
    expect(compute?.attributes.events).toBeUndefined();
  });

  it("maps the trace-reader format and metrics", () => {
    const reader = fixture<{ spans: RawDatadogSpan[] }>("traceReader.spans.ok.json");
    const [probe] = reader.spans.map(datadogTraceSpanToSpan);
    expect(probe).toMatchObject({
      traceId: "69a2af8e68444220f1bf29ab7bd501bf",
      spanId: "4445fd1b19708db1",
      name: "bootstrap.probe",
      serviceName: "grow-bootstrap-probe",
      kind: "internal",
    });
    expect(probe?.attributes._top_level).toBe(1);
    const sdk = fixture<RawDatadogSpan[]>("initTracing.otel_sdk_http.agent_spans.json").map(
      datadogTraceSpanToSpan,
    );
    expect(sdk[0]?.attributes.n).toBe(7);
    expect(sdk[1]?.events[0]?.name).toBe("rows");
  });

  it("maps the trace-by-id API format with exact big integers", () => {
    const doc = fixture<{ body: unknown }>("getTraceById.docs_example.json");
    const text = JSON.stringify(doc.body).replace(
      '"spanID":9876543210987654000',
      '"spanID":9876543210987654321',
    );
    const body = parseJsonPreservingBigInts(text) as {
      data: { attributes: { spans: RawDatadogSpan[] } };
    };
    const [span] = body.data.attributes.spans.map(datadogTraceSpanToSpan);
    expect(span).toMatchObject({
      traceId: "0000000000000000abc1230000000000",
      name: "GET /products",
      serviceName: "web-store",
      startMicros: 1716800000000000,
      endMicros: 1716800000500000,
      durationMicros: 500000,
      kind: "unspecified",
    });
    expect(span?.attributes).toMatchObject({ "http.method": "GET", "http.status_code": 200 });
  });

  it("falls back to low-64 + _dd.p.tid, endTime and native events/links", () => {
    const span = datadogTraceSpanToSpan({
      traceID: "1",
      spanID: 2,
      parentID: "3",
      startTime: "1000000",
      endTime: "4000000",
      name: "op",
      service: "s",
      error: 1,
      meta: {
        "_dd.p.tid": "ffffffffffffffff",
        "otel.status_description": "desc",
        "span.kind": "SERVER",
        num: 5,
        events: "not json",
      },
      metrics: { m: "2.5", bad: "x", empty: "" },
      span_events: [
        {
          time_unix_nano: 5000,
          name: "native",
          attributes: {
            s: { type: 0, string_value: "v" },
            i: { type: 2, int_value: 3 },
            a: { type: 4, array_value: { values: [{ type: 0, string_value: "x" }] } },
            e: { type: 4, array_value: {} },
            raw: "plain",
          },
        },
        { name: "no-time" },
      ],
      span_links: [
        { trace_id: "1", trace_id_high: "ffffffffffffffff", span_id: "2", attributes: {} },
        { trace_id: "1", trace_id_high: "255", span_id: "2" },
        { trace_id: "00f067aa0ba902b7", span_id: "00f067aa0ba902b7" },
        { trace_id: "zz-bad", span_id: "1" },
        { trace_id: "1", span_id: "x-bad" },
        { span_id: "1" },
        { trace_id: "1" },
      ],
    });
    expect(span).toMatchObject({
      traceId: "ffffffffffffffff0000000000000001",
      spanId: "0000000000000002",
      parentSpanId: "0000000000000003",
      startMicros: 1000,
      endMicros: 4000,
      durationMicros: 3000,
      name: "op",
      kind: "server",
      status: { code: "error", message: "desc" },
    });
    expect(span.attributes).toMatchObject({ m: 2.5, operation_name: "op" });
    expect(span.attributes.num).toBeUndefined();
    expect(span.events).toEqual([
      {
        name: "native",
        timeMicros: 5,
        attributes: { s: "v", i: 3, a: ["x"], e: [], raw: "plain" },
      },
      { name: "no-time", timeMicros: 0, attributes: {} },
    ]);
    expect(span.links.map((l) => l.traceId)).toEqual([
      "ffffffffffffffff0000000000000001",
      "00000000000000ff0000000000000001",
      "000000000000000000f067aa0ba902b7",
    ]);
    const minimal = datadogTraceSpanToSpan({
      trace_id: "5",
      span_id: "6",
      start: 2000,
      duration: 1000,
    });
    expect(minimal).toMatchObject({
      name: "",
      serviceName: "",
      endMicros: 3,
      status: { code: "unset" },
    });
    const noDuration = datadogTraceSpanToSpan({ trace_id: "5", span_id: "6", error: 1, meta: [] });
    expect(noDuration).toMatchObject({ durationMicros: 0, status: { code: "error" } });
    expect(noDuration.status.message).toBeUndefined();
  });

  it("datadogKind", () => {
    expect(datadogKind("Client")).toBe("client");
    expect(datadogKind("weird")).toBe("unspecified");
    expect(datadogKind(3)).toBe("unspecified");
  });
});

describe("datadogSearchSpanToSpan", () => {
  it("maps a full search span", () => {
    const span = datadogSearchSpanToSpan({
      id: "AAAA",
      type: "spans",
      attributes: {
        service: "web",
        env: "prod",
        host: "h1",
        resource_name: "GET /x",
        operation_name: "http.server.request",
        type: "web",
        trace_id: "0123456789abcdefffffffffffffffff",
        span_id: "255",
        parent_id: "0",
        start_timestamp: "2026-11-18T10:00:00.123456Z",
        end_timestamp: "2026-11-18T10:00:00.223456Z",
        tags: ["team:sdk", "bare", 5],
        attributes: {
          http: { status_code: 500 },
          span: { kind: "server" },
          duration: 100000000,
          n: null,
        },
        custom: { grow: { run: "abc" }, list: [1, null, { a: 1n }] },
        status: "error",
        ingestion_reason: "rule",
      },
    });
    expect(span).toMatchObject({
      traceId: "0123456789abcdefffffffffffffffff",
      spanId: "00000000000000ff",
      name: "GET /x",
      kind: "server",
      serviceName: "web",
      durationMicros: 100000,
      status: { code: "error" },
    });
    expect(span.startMicros).toBe(Date.parse("2026-11-18T10:00:00Z") * 1000 + 123456);
    expect(span.endMicros - span.startMicros).toBe(100000);
    expect(span.parentSpanId).toBeUndefined();
    expect(span.attributes).toMatchObject({
      team: "sdk",
      "http.status_code": 500,
      "grow.run": "abc",
      list: [1, { a: "1" }],
      env: "prod",
      host: "h1",
      "span.type": "web",
      ingestion_reason: "rule",
      operation_name: "http.server.request",
      resource_name: "GET /x",
    });
  });

  it("handles the decimal 64-bit id, missing fields and the docs example", () => {
    const doc = fixture<{ body: { data: RawDatadogSpan[] } }>("searchSpans.docs_example.json");
    expect(() => datadogSearchSpanToSpan(doc.body.data[0] as RawDatadogSpan)).toThrow();
    const span = datadogSearchSpanToSpan({
      trace_id: "42",
      span_id: "1",
      parent_id: "7",
      start_timestamp: "2026-11-18T10:00:00Z",
      end_timestamp: "2026-11-18T10:00:01+00:00",
      attributes: { error: { message: "nested" }, "otel.status_code": "Ok" },
    });
    expect(span).toMatchObject({
      traceId: "0000000000000000000000000000002a",
      parentSpanId: "0000000000000007",
      durationMicros: 1_000_000,
      status: { code: "ok" },
      name: "",
    });
    const errored = datadogSearchSpanToSpan({
      trace_id: "42",
      span_id: "1",
      start_timestamp: "bad",
      attributes: { error: 1, "error.msg": "m" },
    });
    expect(errored).toMatchObject({
      startMicros: 0,
      endMicros: 0,
      status: { code: "error", message: "m" },
    });
    const noEnd = datadogSearchSpanToSpan({
      trace_id: "42",
      span_id: "1",
      start_timestamp: "2026-11-18T10:00:00.5Z",
      attributes: { duration: "2000" },
    });
    expect(noEnd.endMicros - noEnd.startMicros).toBe(2);
  });
});
