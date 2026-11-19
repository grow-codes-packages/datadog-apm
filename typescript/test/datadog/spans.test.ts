import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it } from "vitest";
import {
  AuthenticationError,
  ConfigError,
  DATADOG_WAIT_INTERVAL_MS,
  DatadogSpansQuery,
  NotFoundError,
  PermissionDeniedError,
  RateLimitError,
  TimeoutError,
  UnavailableError,
  UnsupportedError,
  ValidationError,
  quoteQueryValue,
  traceSearchQueryString,
} from "../../src/index.js";
import {
  type MockHandler,
  type MockResponse,
  type MockServer,
  startServer,
} from "../helpers/server.js";

const API_KEY = "api-key-0123456789";
const APP_KEY = "app-key-0123456789";

const servers: MockServer[] = [];
const clients: DatadogSpansQuery[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
function fixture<T>(name: string): T {
  return JSON.parse(readFileSync(new URL(`../fixtures/${name}`, import.meta.url), "utf8")) as T;
}

const NOW = Date.parse("2026-11-18T12:00:00.000Z");

async function setup(
  handler: MockHandler | MockResponse[],
  extra: Record<string, unknown> = {},
  sleeps: number[] = [],
): Promise<{ q: DatadogSpansQuery; s: MockServer }> {
  const s = await startServer(handler);
  servers.push(s);
  const q = new DatadogSpansQuery(
    {
      apiUrl: s.url,
      apiKey: API_KEY,
      appKey: APP_KEY,
      retry: { maxRetries: 2, initialBackoffMs: 1, random: () => 0 },
      ...extra,
    },
    {
      now: () => NOW,
      sleep: (ms) => {
        sleeps.push(ms);
        return Promise.resolve();
      },
    },
  );
  clients.push(q);
  return { q, s };
}

function body(s: MockServer, i = 0): Record<string, unknown> {
  return JSON.parse(s.requests[i]?.body ?? "{}") as Record<string, unknown>;
}

function searchSpan(traceId: string, spanId: string, parent: string, name: string, startSec = 0) {
  return {
    id: `id-${spanId}`,
    type: "spans",
    attributes: {
      service: "web",
      resource_name: name,
      trace_id: traceId,
      span_id: spanId,
      parent_id: parent,
      start_timestamp: new Date(NOW - 60_000 + startSec * 1000).toISOString(),
      end_timestamp: new Date(NOW - 60_000 + startSec * 1000 + 500).toISOString(),
    },
  };
}

describe("DatadogSpansQuery", () => {
  it("needs both keys", () => {
    expect(() => new DatadogSpansQuery({ apiKey: "x-key" })).toThrow(ConfigError);
    expect(() => new DatadogSpansQuery()).toThrow(/DD_API_KEY/);
    const prev = { a: process.env.DD_API_KEY, b: process.env.DD_APP_KEY };
    process.env.DD_API_KEY = API_KEY;
    process.env.DD_APP_KEY = APP_KEY;
    try {
      const q = DatadogSpansQuery.fromEnv({ site: "datadoghq.eu" });
      clients.push(q);
      expect(q.config.apiUrl).toBe("https://api.datadoghq.eu");
    } finally {
      for (const [k, v] of [
        ["DD_API_KEY", prev.a],
        ["DD_APP_KEY", prev.b],
      ] as const) {
        if (v === undefined) delete process.env[k];
        else process.env[k] = v;
      }
    }
  });

  it("searchSpansPage sends the documented request with both keys", async () => {
    const doc = fixture<{ body: unknown }>("searchSpans.docs_example.json");
    const { q, s } = await setup([
      {
        status: 200,
        body: {
          data: [searchSpan("42", "1", "0", "GET /")],
          meta: {
            status: "done",
            request_id: "req-1",
            page: { after: "cursor-2" },
            warnings: [{ code: "w", title: "partial", detail: "d" }, "odd"],
          },
        },
      },
    ]);
    expect(doc).toBeDefined();
    const page = await q.searchSpansPage({
      query: "service:web",
      from: new Date(NOW - 3_600_000),
      to: NOW,
      limit: 25,
      sort: "-timestamp",
      cursor: "c1",
      timezone: "Europe/Berlin",
    });
    expect(page.nextCursor).toBe("cursor-2");
    expect(page.requestId).toBe("req-1");
    expect(page.warnings).toEqual([{ code: "w", title: "partial", detail: "d" }, {}]);
    expect(page.spans[0]?.span.traceId).toBe("0000000000000000000000000000002a");
    expect(page.spans[0]?.id).toBe("id-1");
    expect(page.spans[0]?.raw.resource_name).toBe("GET /");
    const req = s.requests[0];
    expect(req?.method).toBe("POST");
    expect(req?.url).toBe("/api/v2/spans/events/search");
    expect(req?.headers["dd-api-key"]).toBe(API_KEY);
    expect(req?.headers["dd-application-key"]).toBe(APP_KEY);
    expect(body(s)).toEqual({
      data: {
        type: "search_request",
        attributes: {
          filter: {
            query: "service:web",
            from: new Date(NOW - 3_600_000).toISOString(),
            to: String(NOW),
          },
          options: { timezone: "Europe/Berlin" },
          page: { limit: 25, cursor: "c1" },
          sort: "-timestamp",
        },
      },
    });
  });

  it("searchSpans follows cursors with the same absolute window", async () => {
    const { q, s } = await setup((_req, i): MockResponse => ({
      status: 200,
      body:
        i === 0
          ? { data: [searchSpan("42", "1", "0", "a")], meta: { page: { after: "next" } } }
          : i === 1
            ? { data: [searchSpan("42", "2", "1", "b")], meta: { page: { after: "next" } } }
            : {},
    }));
    const names: string[] = [];
    for await (const sp of q.searchSpans({ query: "x", from: "now-1h" })) names.push(sp.span.name);
    expect(names).toEqual(["a", "b"]);
    expect(s.requests).toHaveLength(2);
    const first = body(s, 0) as { data: { attributes: { filter: unknown; page: unknown } } };
    const second = body(s, 1) as { data: { attributes: { filter: unknown; page: unknown } } };
    expect(first.data.attributes.filter).toEqual(second.data.attributes.filter);
    expect(first.data.attributes.filter).toEqual({
      query: "x",
      from: "now-1h",
      to: new Date(NOW).toISOString(),
    });
    expect(second.data.attributes.page).toEqual({ limit: 10, cursor: "next" });
    const empty = await setup([{ status: 200, body: "" }]);
    const none: unknown[] = [];
    for await (const sp of empty.q.searchSpans()) none.push(sp);
    expect(none).toEqual([]);
    expect(body(empty.s)).toMatchObject({
      data: {
        attributes: {
          filter: {
            query: "*",
            from: new Date(NOW - 15 * 60_000).toISOString(),
            to: new Date(NOW).toISOString(),
          },
          options: { timezone: "UTC" },
          sort: "timestamp",
        },
      },
    });
  });

  it("searchSpans surfaces a timed-out empty result and validates limit", async () => {
    const { q } = await setup([{ status: 200, body: { data: [], meta: { status: "timeout" } } }]);
    const it1 = q.searchSpans();
    await expect(it1.next()).rejects.toBeInstanceOf(TimeoutError);
    await expect(q.searchSpansPage({ limit: 0 })).rejects.toBeInstanceOf(ConfigError);
    await expect(q.searchSpansPage({ limit: 1001 })).rejects.toBeInstanceOf(ConfigError);
    const page = await q.searchSpansPage({ limit: 1000 });
    expect(page.status).toBe("timeout");
  });

  it("maps errors (core.errors.mapping) and never leaks keys", async () => {
    const bad = fixture<{ body: unknown }>("searchSpans.400.docs_example.json");
    const cases: [number, unknown, new (...a: never[]) => Error][] = [
      [400, bad.body, ValidationError],
      [401, { errors: ["Unauthorized"] }, AuthenticationError],
      [403, { errors: ["Forbidden"] }, PermissionDeniedError],
      [404, { errors: ["Not found"] }, NotFoundError],
      [408, { errors: ["Request Timeout"] }, TimeoutError],
      [413, { errors: ["too large"] }, ValidationError],
      [422, { errors: ["bad"] }, ValidationError],
      [500, { errors: [`oops ${API_KEY}`] }, UnavailableError],
    ];
    for (const [status, payload, cls] of cases) {
      const { q } = await setup([{ status, body: payload as object }], {
        retry: { maxRetries: 0 },
      });
      const err = await q.searchSpansPage().catch((e: unknown) => e);
      expect(err, String(status)).toBeInstanceOf(cls);
      expect((err as Error).message).not.toContain(API_KEY);
      expect((err as Error).message).not.toContain(APP_KEY);
      if (status === 400) {
        expect((err as Error).message).toBe(
          "searchSpans: Missing required attribute in body (HTTP 400)",
        );
      }
    }
  });

  it("429: waits Retry-After / X-RateLimit-Reset, then gives up", async () => {
    const sleeps: number[] = [];
    const { q, s } = await setup(
      (_req, i): MockResponse =>
        i === 0
          ? { status: 429, headers: { "retry-after": "2" }, body: { errors: ["Too many"] } }
          : i === 1
            ? { status: 429, headers: { "x-ratelimit-reset": "3" }, body: { errors: ["Too many"] } }
            : { status: 200, body: { data: { attributes: { services: ["b", "a", 1] } } } },
      {},
      sleeps,
    );
    expect(await q.listServices()).toEqual(["a", "b"]);
    expect(sleeps).toEqual([2000, 3000]);
    expect(s.requests[2]?.url).toBe("/api/v2/apm/services?filter%5Benv%5D=*");

    const always = await setup([{ status: 429, body: {} }], {}, []);
    const err = await always.q.listServices({ env: "prod" }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(RateLimitError);
    expect((err as RateLimitError).retryAfterMs).toBeUndefined();
    expect(always.s.requests).toHaveLength(3);

    const date = new Date(NOW + 5000).toUTCString();
    const long = await setup([
      { status: 429, headers: { "retry-after": "3600" } },
      { status: 429, headers: { "retry-after": date } },
    ]);
    const tooLong = await long.q.listServices().catch((e: unknown) => e);
    expect(tooLong).toMatchObject({ code: "RATE_LIMITED", retryAfterMs: 3_600_000 });
    expect(long.s.requests).toHaveLength(1);

    const sleeps2: number[] = [];
    const dated = await setup(
      [
        { status: 429, headers: { "retry-after": date, "x-request-id": "rid" } },
        { status: 429, headers: { "retry-after": "soon", "x-ratelimit-reset": "x" } },
        { status: 200, body: {} },
      ],
      {},
      sleeps2,
    );
    expect(await dated.q.listServices()).toEqual([]);
    expect(sleeps2).toEqual([5000, 0]);

    const budget = await setup([{ status: 429, headers: { "retry-after": "50" } }], {
      totalTimeoutMs: 10_000,
    });
    await expect(budget.q.listServices()).rejects.toMatchObject({ retryAfterMs: 50_000 });
  });

  it("aggregateSpans sends compute/group_by and returns buckets", async () => {
    const doc = fixture<{ request: { body: unknown }; body: object }>(
      "aggregateSpans.docs_example.json",
    );
    const { q, s } = await setup([{ status: 200, body: doc.body }]);
    const result = await q.aggregateSpans(
      [{ aggregation: "pc90", metric: "@duration", type: "timeseries", interval: "5m" }],
      [
        {
          facet: "service",
          limit: 20,
          sort: { aggregation: "pc90", metric: "@duration", order: "desc", type: "measure" },
        },
      ],
      {
        query: "service:web* AND @http.status_code:[200 TO 299]",
        from: "now-15m",
        to: "now",
        timezone: "GMT",
      },
    );
    expect(result.buckets).toEqual([{ by: { host: "my-hostname" }, computes: { c0: 19 } }]);
    expect(result.status).toBe("done");
    expect(s.requests[0]?.url).toBe("/api/v2/spans/analytics/aggregate");
    expect(body(s)).toEqual(doc.request.body);
    const minimal = await setup([{ status: 200, body: { data: [{}], meta: { request_id: "r" } } }]);
    const r2 = await minimal.q.aggregateSpans([{ aggregation: "count" }]);
    expect(r2).toEqual({
      buckets: [{ by: {}, computes: {} }],
      status: "done",
      warnings: [],
      requestId: "r",
    });
    expect(
      (body(minimal.s) as { data: { attributes: Record<string, unknown> } }).data.attributes
        .group_by,
    ).toBeUndefined();
    const empty = await setup([{ status: 200 }]);
    expect((await empty.q.aggregateSpans([{ aggregation: "count" }])).buckets).toEqual([]);
    await expect(q.aggregateSpans([])).rejects.toBeInstanceOf(ConfigError);
  });

  it("listServices uses the configured env and the docs example", async () => {
    const doc = fixture<{ body: object }>("listServices.docs_example.json");
    const { q, s } = await setup([{ status: 200, body: doc.body }], { environment: "prod" });
    expect(await q.listServices()).toEqual(["test-service"]);
    expect(s.requests[0]?.url).toBe("/api/v2/apm/services?filter%5Benv%5D=prod");
    const empty = await setup([{ status: 200 }]);
    expect(await empty.q.listServices()).toEqual([]);
  });

  it("getTrace searches by trace id (128-bit hex / 64-bit decimal) and merges pages", async () => {
    const traceId = "0123456789abcdefffffffffffffffff";
    const { q, s } = await setup((_req, i): MockResponse => ({
      status: 200,
      body:
        i === 0
          ? {
              data: [
                searchSpan(traceId, "2", "1", "child", 1),
                searchSpan(traceId, "1", "0", "root"),
              ],
              meta: { page: { after: "p2" } },
            }
          : { data: [searchSpan(traceId, "2", "1", "child", 1)] },
    }));
    const trace = await q.getTrace(traceId.toUpperCase());
    expect(trace?.traceId).toBe(traceId);
    expect(trace?.spans.map((x) => x.name)).toEqual(["root", "child"]);
    expect(body(s)).toMatchObject({
      data: {
        attributes: {
          filter: {
            query: `trace_id:${traceId}`,
            from: new Date(NOW - 86_400_000).toISOString(),
          },
          page: { limit: 1000 },
        },
      },
    });
    const short = await setup([{ status: 200, body: { data: [] } }]);
    expect(await short.q.getTrace("2a", { from: "now-1h", to: "now" })).toBeNull();
    expect(body(short.s)).toMatchObject({
      data: { attributes: { filter: { query: "trace_id:42", from: "now-1h", to: "now" } } },
    });
    await expect(q.getTrace("not-hex")).rejects.toBeInstanceOf(ValidationError);
  });

  it("getTrace preview reads the trace-by-id API with exact ids", async () => {
    const doc = fixture<{ body: object }>("getTraceById.docs_example.json");
    const raw = JSON.stringify(doc.body).replace(/"spanID":\d+/, '"spanID":9876543210987654321');
    const { q, s } = await setup((req) =>
      !req.url.endsWith("/9007199254740993")
        ? { status: 200, headers: { "content-type": "application/json" }, body: raw }
        : { status: 404, body: { errors: ["not found"] } },
    );
    const trace = await q.getTrace("0000000000000000abc1230000000000", { preview: true });
    expect(s.requests[0]?.url).toBe("/api/v2/trace/0000000000000000abc1230000000000");
    expect(trace?.spans[0]).toMatchObject({
      spanId: "891087b8e3b70cb1",
      name: "GET /products",
      durationMicros: 500000,
    });
    expect(await q.getTrace("00000000000000000020000000000001", { preview: true })).toBeNull();
    expect(s.requests[1]?.url).toBe("/api/v2/trace/9007199254740993");
    const empty = await setup([
      { status: 200, body: "" },
      { status: 200, body: { data: { attributes: { spans: [] } } } },
    ]);
    expect(await empty.q.getTrace("ab", { preview: true })).toBeNull();
    expect(await empty.q.getTrace("ab", { preview: true })).toBeNull();
  });

  it("waitForTrace polls with the Datadog interval", async () => {
    const sleeps: number[] = [];
    const traceId = "0000000000000000000000000000002a";
    const { q } = await setup(
      (_req, i): MockResponse => ({
        status: 200,
        body: { data: i === 0 ? [] : [searchSpan("42", "1", "0", "root")] },
      }),
      {},
      sleeps,
    );
    // pollUntil uses real sleep; keep the interval tiny for the test but check the default.
    const trace = await q.waitForTrace(traceId, { intervalMs: 5, timeoutMs: 5000 });
    expect(trace.spans).toHaveLength(1);
    expect(DATADOG_WAIT_INTERVAL_MS).toBe(2000);
  });

  it("listOperations groups by resource_name", async () => {
    const { q, s } = await setup([
      {
        status: 200,
        body: {
          data: [
            { attributes: { by: { resource_name: "b" } } },
            { attributes: { by: { resource_name: "a" } } },
            { attributes: { by: { resource_name: "" } } },
            { attributes: { by: {} } },
          ],
        },
      },
    ]);
    expect(await q.listOperations("my svc")).toEqual(["a", "b"]);
    expect(body(s)).toMatchObject({
      data: {
        attributes: {
          compute: [{ aggregation: "count" }],
          group_by: [{ facet: "resource_name", limit: 1000 }],
          filter: { query: 'service:"my svc"' },
        },
      },
    });
  });

  it("searchTraces builds the query and summarizes distinct traces", async () => {
    expect(
      DatadogSpansQuery.traceSearchQuery({
        service: "web",
        operation: "GET /x",
        tags: { "grow.run": "abc", odd: 'a"b' },
        minDurationMicros: 5,
        maxDurationMicros: 10,
      }),
    ).toBe(
      'service:web resource_name:"GET /x" @grow.run:abc @odd:"a\\"b" @duration:>=5000 @duration:<=10000',
    );
    expect(DatadogSpansQuery.traceSearchQuery({})).toBe("*");
    expect(
      traceSearchQueryString({
        service: "",
        operation: "",
        tags: { z: "1", a: "2" },
        minDurationMicros: 0,
        maxDurationMicros: 0,
      }),
    ).toBe("@a:2 @z:1");
    const t1 = "0000000000000000000000000000000a";
    const t2 = "0000000000000000000000000000000b";
    const { q, s } = await setup((_req, i): MockResponse => ({
      status: 200,
      body:
        i === 0
          ? {
              data: [searchSpan(t1, "2", "1", "child", 2), searchSpan(t2, "5", "0", "other", 1)],
              meta: { page: { after: "c" } },
            }
          : { data: [searchSpan(t1, "1", "0", "root", 0), searchSpan("c", "9", "0", "third")] },
    }));
    const summaries = await q.searchTraces({
      service: "web",
      limit: 3,
      start: new Date(NOW - 1000),
    });
    expect(summaries).toHaveLength(3);
    expect(summaries[0]).toMatchObject({
      traceId: t1,
      rootName: "root",
      rootServiceName: "web",
      spanCount: 2,
      durationMicros: 2_500_000,
    });
    expect(summaries[1]).toMatchObject({ traceId: t2, spanCount: 1 });
    expect(body(s)).toMatchObject({
      data: {
        attributes: {
          filter: { query: "service:web", from: new Date(NOW - 1000).toISOString() },
          page: { limit: 100 },
          sort: "-timestamp",
        },
      },
    });
    const one = await setup([
      { status: 200, body: { data: [searchSpan(t1, "2", "1", "orphan")] } },
    ]);
    expect((await one.q.searchTraces({}))[0]).toMatchObject({ rootName: "orphan" });
  });

  it("getDependencies is unsupported", async () => {
    const { q } = await setup([{ status: 200 }]);
    await expect(q.getDependencies()).rejects.toBeInstanceOf(UnsupportedError);
  });

  it("ping validates the keys; 403 means invalid keys", async () => {
    const ok = await setup([{ status: 200, body: { status: "ok" } }]);
    expect(await ok.q.ping()).toBeGreaterThanOrEqual(0);
    expect(ok.s.requests[0]?.url).toBe("/api/v2/validate_keys");
    const forbidden = await setup([
      { status: 403, headers: { "x-request-id": "r1" }, body: { errors: ["Forbidden"] } },
    ]);
    const err = await forbidden.q.ping().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AuthenticationError);
    expect(err).toMatchObject({ httpStatus: 403, requestId: "r1" });
    const plain = await setup([{ status: 403 }]);
    await expect(plain.q.ping()).rejects.toBeInstanceOf(AuthenticationError);
    const unauthorized = await setup([{ status: 401, body: { errors: ["Unauthorized"] } }]);
    await expect(unauthorized.q.ping()).rejects.toBeInstanceOf(AuthenticationError);
    const down = await setup([{ status: 500 }], { retry: { maxRetries: 0 } });
    await expect(down.q.ping()).rejects.toBeInstanceOf(UnavailableError);
  });

  it("calls on a closed query client raise CANCELLED", async () => {
    const { q, s } = await setup([{ status: 200, body: {} }]);
    await q.close();
    await expect(q.ping()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(q.listServices()).rejects.toMatchObject({ code: "CANCELLED" });
    expect(s.requests).toHaveLength(0);
  });

  it("cancellation is honoured", async () => {
    const { q } = await setup([{ status: 200, body: {} }]);
    await expect(q.listServices({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(q.ping({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(
      q.getTrace("ab", { preview: true, signal: AbortSignal.abort() }),
    ).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(
      q.aggregateSpans([{ aggregation: "count" }], [], { signal: AbortSignal.abort() }),
    ).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(q.listOperations("x", { signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(q.searchTraces({}, { signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await expect(q.getTrace("ab", { signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
  });

  it("quoteQueryValue", () => {
    expect(quoteQueryValue("web-1.x")).toBe("web-1.x");
    expect(quoteQueryValue("a b")).toBe('"a b"');
    expect(quoteQueryValue('a\\"')).toBe('"a\\\\\\""');
  });

  it("default sleep is abortable", async () => {
    const s = await startServer([{ status: 429, headers: { "retry-after": "1" } }]);
    servers.push(s);
    const q = new DatadogSpansQuery({
      apiUrl: s.url,
      apiKey: API_KEY,
      appKey: APP_KEY,
      retry: { maxRetries: 1, initialBackoffMs: 1 },
    });
    clients.push(q);
    const ctl = new AbortController();
    setTimeout(() => ctl.abort(), 50);
    await expect(q.listServices({ signal: ctl.signal })).rejects.toMatchObject({
      code: "CANCELLED",
    });
  });
});
