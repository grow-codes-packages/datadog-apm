// Service-owned: `DatadogSpansQuery` — the Datadog SaaS spans query API (API.md §4). It needs an
// API key and an application key with `apm_read`. No container can serve these endpoints, so they
// are unit-tested against Datadog's documented examples and verified by the live sandbox job.

import {
  AuthenticationError,
  ConfigError,
  HttpTransport,
  PermissionDeniedError,
  RateLimitError,
  TimeoutError,
  sleep as abortableSleep,
  type RequestOptions,
  type TransportOptions,
  type TransportResponse,
} from "./core/index.js";
import { DatadogApmConfig, type DatadogApmConfigOptions } from "./config.js";
import { datadogTraceIdString, traceIdForPath } from "./ids.js";
import { parseJsonPreservingBigInts } from "./json.js";
import { datadogSearchSpanToSpan, datadogTraceSpanToSpan, type RawDatadogSpan } from "./mapping.js";
import {
  BaseTraceQueryClient,
  normalizeTraceId,
  sortSpans,
  waitForTrace,
  type Span,
  type Trace,
  type TraceSearchQuery,
  type TraceSummary,
  type WaitForTraceOptions,
} from "./tracing/index.js";

/** A point in time for a query: a `Date`, epoch milliseconds, or a Datadog string (`now-15m`, ISO-8601). */
export type DatadogTime = Date | number | string;

/** Per-call options. */
export interface QueryCallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Options of {@link DatadogSpansQuery.searchSpans}. */
export interface SearchSpansOptions extends QueryCallOptions {
  /** Datadog span query, e.g. `service:web @http.status_code:500`. Default `*`. */
  query?: string;
  /** Start of the window. Default: 15 minutes ago (absolute, fixed for all pages). */
  from?: DatadogTime;
  /** End of the window. Default: now (absolute, fixed for all pages). */
  to?: DatadogTime;
  /** Page size, 1..1000. Default 10. */
  limit?: number;
  /** `timestamp` (oldest first) or `-timestamp` (newest first). Default `timestamp`. */
  sort?: "timestamp" | "-timestamp";
  /** Cursor of a previous page (`nextCursor`). */
  cursor?: string;
  /** Time zone for relative dates, e.g. `UTC` (default) or `Europe/Berlin`. */
  timezone?: string;
}

/** A warning attached to a partial query result. */
export interface DatadogQueryWarning {
  code?: string;
  title?: string;
  detail?: string;
}

/** One span returned by the spans search API. */
export interface DatadogSpan {
  /** Datadog event id of the span. */
  id: string;
  /** The span in the SPEC §5 model (ids normalized to hex). */
  span: Span;
  /** `attributes` exactly as Datadog returned them. */
  raw: Record<string, unknown>;
}

/** One page of {@link DatadogSpansQuery.searchSpansPage}. */
export interface DatadogSpansPage {
  spans: DatadogSpan[];
  /** Cursor for the next page, `null` on the last page. */
  nextCursor: string | null;
  /** `done`, or `timeout` for a partial result. */
  status: string;
  /** Non-empty when the result is partial or the query was adjusted. */
  warnings: DatadogQueryWarning[];
  /** `meta.request_id`, when present. */
  requestId?: string;
}

/** Aggregation functions of the spans aggregate API. */
export type SpanAggregation =
  | "count"
  | "cardinality"
  | "pc75"
  | "pc90"
  | "pc95"
  | "pc98"
  | "pc99"
  | "sum"
  | "min"
  | "max"
  | "avg"
  | "median";

/** One computation of {@link DatadogSpansQuery.aggregateSpans}. */
export interface SpanCompute {
  aggregation: SpanAggregation;
  /** Measure, e.g. `@duration` (not needed for `count`). */
  metric?: string;
  /** `total` (default) or `timeseries` (all computes must use the same type and interval). */
  type?: "total" | "timeseries";
  /** Time bucket for `timeseries`, e.g. `5m`. */
  interval?: string;
}

/** One group-by facet of {@link DatadogSpansQuery.aggregateSpans}. */
export interface SpanGroupBy {
  /** Facet, e.g. `service`, `resource_name`, `@http.status_code`. */
  facet: string;
  /** Maximum groups. Default (Datadog) 10. */
  limit?: number;
  sort?: {
    aggregation?: SpanAggregation;
    metric?: string;
    order?: "asc" | "desc";
    type?: "alphabetical" | "measure";
  };
  /** Value used for spans without the facet. */
  missing?: string | number;
  /** Adds a total bucket (`true`, or the bucket's value). */
  total?: boolean | string | number;
  histogram?: { interval: number; min: number; max: number };
}

/** Filter options of {@link DatadogSpansQuery.aggregateSpans}. */
export interface AggregateSpansOptions extends QueryCallOptions {
  /** Default `*`. */
  query?: string;
  /** Default 15 minutes ago. */
  from?: DatadogTime;
  /** Default now. */
  to?: DatadogTime;
  /** Default `UTC`. */
  timezone?: string;
}

/** A time-series point of a `timeseries` compute. */
export interface SpanTimeseriesPoint {
  time: string;
  value: number;
}

/** One bucket of an aggregation. */
export interface SpanAggregateBucket {
  /** Group-by values, e.g. `{ service: "web" }` (empty without `groupBy`). */
  by: Record<string, string | number>;
  /** Results keyed `c0`, `c1`, … in the order of the `compute` list. */
  computes: Record<string, number | SpanTimeseriesPoint[]>;
}

/** Result of {@link DatadogSpansQuery.aggregateSpans}. */
export interface SpanAggregateResult {
  buckets: SpanAggregateBucket[];
  status: string;
  warnings: DatadogQueryWarning[];
  requestId?: string;
}

/** Options of {@link DatadogSpansQuery.getTrace}. */
export interface GetTraceOptions extends QueryCallOptions {
  /**
   * Use the preview trace-by-id endpoint `GET /api/v2/trace/{id}` (full trace, `x-unstable`,
   * 60 requests/minute) instead of searching indexed spans. Default false.
   */
  preview?: boolean;
  /** Search window start (search mode). Default: 24 hours ago. */
  from?: DatadogTime;
  /** Search window end (search mode). Default: now. */
  to?: DatadogTime;
}

/** Options of {@link DatadogSpansQuery.listServices}. */
export interface ListServicesOptions extends QueryCallOptions {
  /** `filter[env]`; `*` = all environments. Default: the configured `environment`, else `*`. */
  env?: string;
}

/** Test seams (undici `MockAgent`, fake sleep and clock). Not part of the stable API. */
export type QueryInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** Default polling interval of {@link DatadogSpansQuery.waitForTrace} (Datadog allows 50 searches/minute). */
export const DATADOG_WAIT_INTERVAL_MS = 2000;
const DEFAULT_WINDOW_MS = 15 * 60_000;
const TRACE_WINDOW_MS = 24 * 60 * 60_000;
const MAX_PAGE = 1000;
const SEARCH_TRACES_MAX_PAGES = 5;

function iso(value: DatadogTime): string {
  if (value instanceof Date) return value.toISOString();
  if (typeof value === "number") return String(Math.trunc(value));
  return value;
}

/** Quotes a value for the Datadog query syntax when it contains spaces or special characters. */
export function quoteQueryValue(value: string): string {
  if (/^[A-Za-z0-9_.\-/*@]+$/.test(value)) return value;
  return `"${value.replace(/["\\]/g, (c) => `\\${c}`)}"`;
}

function rateLimitWaitMs(headers: Headers, now: number): number | undefined {
  const retryAfter = headers.get("retry-after");
  if (retryAfter !== null && retryAfter.trim() !== "") {
    const seconds = Number(retryAfter);
    if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);
    const date = Date.parse(retryAfter);
    if (!Number.isNaN(date)) return Math.max(0, date - now);
  }
  const reset = headers.get("x-ratelimit-reset");
  if (reset !== null && Number.isFinite(Number(reset)) && reset.trim() !== "")
    return Math.max(0, Number(reset) * 1000);
  return undefined;
}

function warningsOf(meta: Record<string, unknown> | undefined): DatadogQueryWarning[] {
  const list = meta?.warnings;
  if (!Array.isArray(list)) return [];
  return list.map((w) => {
    const r = (typeof w === "object" && w !== null ? w : {}) as Record<string, unknown>;
    const out: DatadogQueryWarning = {};
    if (typeof r.code === "string") out.code = r.code;
    if (typeof r.title === "string") out.title = r.title;
    if (typeof r.detail === "string") out.detail = r.detail;
    return out;
  });
}

interface SearchResponse {
  data?: { id?: string; type?: string; attributes?: Record<string, unknown> }[];
  meta?: {
    status?: string;
    request_id?: string;
    page?: { after?: string };
    warnings?: unknown[];
  };
}

interface AggregateResponse {
  data?: {
    attributes?: { by?: Record<string, string | number>; compute?: Record<string, unknown> };
  }[];
  meta?: { status?: string; request_id?: string; warnings?: unknown[] };
}

interface ServicesResponse {
  data?: { attributes?: { services?: unknown[] } };
}

/**
 * Datadog spans query client (SaaS API at `https://api.<site>`, headers `DD-API-KEY` and
 * `DD-APPLICATION-KEY`). Implements the SPEC {@link TraceQueryClient} on top of the spans search
 * and aggregate APIs, plus Datadog-specific `searchSpans`, `aggregateSpans` and `ping`.
 *
 * Errors: 400/413/422 → `ValidationError`, 401 → `AuthenticationError`, 403 →
 * `PermissionDeniedError` (`AuthenticationError` from `ping()`), 404 → `NotFoundError`, 408 →
 * `TimeoutError`, 429 → `RateLimitError` (waits `Retry-After` / `X-RateLimit-Reset` within the
 * retry budget), 5xx → `UnavailableError`.
 */
export class DatadogSpansQuery extends BaseTraceQueryClient {
  /** The configuration (API key, application key, site / API URL). */
  readonly config: DatadogApmConfig;
  readonly #transport: HttpTransport;
  readonly #sleep: (ms: number, signal?: AbortSignal) => Promise<void>;
  readonly #now: () => number;

  /** Throws `ConfigError` unless both `apiKey` and `appKey` are configured. */
  constructor(
    config: DatadogApmConfig | DatadogApmConfigOptions = {},
    internals: QueryInternals = {},
  ) {
    super();
    const cfg = DatadogApmConfig.from(config);
    if (!cfg.hasQueryCredentials) {
      throw new ConfigError(
        "invalid configuration: the Datadog query API needs apiKey (DD_API_KEY) and appKey (DD_APP_KEY)",
      );
    }
    this.config = cfg;
    this.#sleep = internals.sleep ?? ((ms, signal) => abortableSleep(ms, signal, "datadog query"));
    this.#now = internals.now ?? Date.now;
    this.#transport = new HttpTransport({
      ...internals,
      config: {
        url: cfg.apiUrl,
        connectTimeoutMs: cfg.connectTimeoutMs,
        requestTimeoutMs: cfg.requestTimeoutMs,
        totalTimeoutMs: cfg.totalTimeoutMs,
        retry: cfg.retry,
        tls: cfg.tls,
        headers: cfg.headers,
        ...(cfg.proxy === undefined ? {} : { proxy: cfg.proxy }),
        ...(cfg.userAgentSuffix === undefined ? {} : { userAgentSuffix: cfg.userAgentSuffix }),
        ...(cfg.hooks === undefined ? {} : { hooks: cfg.hooks }),
        ...(cfg.logger === undefined ? {} : { logger: cfg.logger }),
      },
      defaultHeaders: () => ({
        "dd-api-key": cfg.apiKey?.reveal() ?? "",
        "dd-application-key": cfg.appKey?.reveal() ?? "",
      }),
      knownSecrets: () => cfg.knownSecrets(),
    });
  }

  /** Builds a query client from the environment (`DD_API_KEY`, `DD_APP_KEY`, `DD_SITE`, …). */
  static fromEnv(overrides: DatadogApmConfigOptions = {}): DatadogSpansQuery {
    return new DatadogSpansQuery(DatadogApmConfig.fromEnv(overrides));
  }

  /** Sends a request; handles 429 with `Retry-After` / `X-RateLimit-Reset` pacing. */
  async #call<T>(options: RequestOptions): Promise<TransportResponse<T>> {
    const policy = this.config.retry;
    const deadline = this.#now() + this.config.totalTimeoutMs;
    const operation = options.operation ?? options.path;
    for (let retry = 0; ; retry++) {
      const res = await this.#transport.request<T>({
        ...options,
        allowStatus: [...(options.allowStatus ?? []), 429],
      });
      if (res.status !== 429) return res;
      const waitMs = rateLimitWaitMs(res.headers, this.#now());
      const error = new RateLimitError(`${operation}: rate limited (HTTP 429)`, {
        httpStatus: 429,
        ...(waitMs === undefined ? {} : { retryAfterMs: waitMs }),
        ...(res.requestId === undefined ? {} : { requestId: res.requestId }),
      });
      const delay = Math.max(waitMs ?? 0, policy.backoffMs(retry));
      if (
        retry >= policy.maxRetries ||
        (waitMs !== undefined && waitMs > policy.maxRetryAfterMs) ||
        delay >= deadline - this.#now()
      ) {
        throw error;
      }
      await this.#sleep(delay, options.signal);
    }
  }

  #searchBody(opts: SearchSpansOptions, from: string, to: string, cursor?: string): unknown {
    const limit = opts.limit ?? 10;
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_PAGE)
      throw new ConfigError("searchSpans: limit must be an integer between 1 and 1000");
    const page: Record<string, unknown> = { limit };
    if (cursor !== undefined) page.cursor = cursor;
    return {
      data: {
        type: "search_request",
        attributes: {
          filter: { query: opts.query ?? "*", from, to },
          options: { timezone: opts.timezone ?? "UTC" },
          page,
          sort: opts.sort ?? "timestamp",
        },
      },
    };
  }

  #window(from: DatadogTime | undefined, to: DatadogTime | undefined, spanMs: number) {
    const now = this.#now();
    return {
      from: iso(from ?? new Date(now - spanMs)),
      to: iso(to ?? new Date(now)),
    };
  }

  async #page(
    opts: SearchSpansOptions,
    from: string,
    to: string,
    cursor: string | undefined,
  ): Promise<DatadogSpansPage> {
    const res = await this.#call<SearchResponse | undefined>({
      method: "POST",
      path: "/api/v2/spans/events/search",
      json: this.#searchBody(opts, from, to, cursor),
      idempotent: true,
      operation: "searchSpans",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const body = res.data ?? {};
    const spans: DatadogSpan[] = [];
    for (const item of body.data ?? []) {
      const attrs = item.attributes ?? {};
      spans.push({ id: item.id ?? "", span: datadogSearchSpanToSpan(attrs), raw: attrs });
    }
    const after = body.meta?.page?.after;
    const page: DatadogSpansPage = {
      spans,
      nextCursor: typeof after === "string" && after !== "" ? after : null,
      status: body.meta?.status ?? "done",
      warnings: warningsOf(body.meta),
    };
    const requestId = body.meta?.request_id ?? res.requestId;
    if (requestId !== undefined) page.requestId = requestId;
    return page;
  }

  /**
   * One page of `POST /api/v2/spans/events/search`. Pass `nextCursor` back as `cursor` with the
   * **same** absolute `from`/`to` for the next page. `status: "timeout"` or `warnings` mark a
   * partial result.
   */
  searchSpansPage(opts: SearchSpansOptions = {}): Promise<DatadogSpansPage> {
    const { from, to } = this.#window(opts.from, opts.to, DEFAULT_WINDOW_MS);
    return this.#page(opts, from, to, opts.cursor);
  }

  /**
   * Iterates over every span matching the search, following the cursor page by page (same
   * absolute window for every page). Throws `TimeoutError` when Datadog reports a timed-out query
   * that returned no spans (never a silently empty result).
   *
   * @example
   * for await (const s of query.searchSpans({ query: "service:checkout @grow.run:abc" })) {
   *   console.log(s.span.name, s.span.durationMicros);
   * }
   */
  async *searchSpans(opts: SearchSpansOptions = {}): AsyncGenerator<DatadogSpan, void, undefined> {
    const { from, to } = this.#window(opts.from, opts.to, DEFAULT_WINDOW_MS);
    let cursor = opts.cursor;
    for (;;) {
      const page = await this.#page(opts, from, to, cursor);
      if (page.status === "timeout" && page.spans.length === 0) {
        throw new TimeoutError("searchSpans: the Datadog query timed out without results");
      }
      yield* page.spans;
      if (page.nextCursor === null || page.nextCursor === cursor) return;
      cursor = page.nextCursor;
    }
  }

  /**
   * `POST /api/v2/spans/analytics/aggregate`: computes `compute` (results keyed `c0`, `c1`, …)
   * per group of `groupBy`.
   *
   * @example
   * await query.aggregateSpans([{ aggregation: "pc90", metric: "@duration" }], [{ facet: "service" }]);
   */
  async aggregateSpans(
    compute: SpanCompute[],
    groupBy: SpanGroupBy[] = [],
    opts: AggregateSpansOptions = {},
  ): Promise<SpanAggregateResult> {
    if (compute.length === 0) throw new ConfigError("aggregateSpans: compute must not be empty");
    const { from, to } = this.#window(opts.from, opts.to, DEFAULT_WINDOW_MS);
    const attributes: Record<string, unknown> = {
      compute,
      filter: { query: opts.query ?? "*", from, to },
      options: { timezone: opts.timezone ?? "UTC" },
    };
    if (groupBy.length > 0) attributes.group_by = groupBy;
    const res = await this.#call<AggregateResponse | undefined>({
      method: "POST",
      path: "/api/v2/spans/analytics/aggregate",
      json: { data: { type: "aggregate_request", attributes } },
      idempotent: true,
      operation: "aggregateSpans",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const body = res.data ?? {};
    const buckets = (body.data ?? []).map((b) => ({
      by: { ...b.attributes?.by },
      computes: { ...b.attributes?.compute } as Record<string, number | SpanTimeseriesPoint[]>,
    }));
    const result: SpanAggregateResult = {
      buckets,
      status: body.meta?.status ?? "done",
      warnings: warningsOf(body.meta),
    };
    const requestId = body.meta?.request_id ?? res.requestId;
    if (requestId !== undefined) result.requestId = requestId;
    return result;
  }

  /**
   * Reads a trace. Default: searches the indexed spans with `trace_id:<id>` over the last 24 hours
   * (all pages, merged by span id) — only spans kept by retention filters, so the trace may be
   * partial. With `preview: true`: `GET /api/v2/trace/{id}` (full trace, preview API). Returns
   * `null` when nothing is found.
   */
  async getTrace(traceId: string, opts: GetTraceOptions = {}): Promise<Trace | null> {
    const id = normalizeTraceId(traceId);
    if (opts.preview === true) return this.#getTracePreview(id, opts);
    const { from, to } = this.#window(opts.from, opts.to, TRACE_WINDOW_MS);
    const bySpanId = new Map<string, Span>();
    const search: SearchSpansOptions = {
      query: `trace_id:${datadogTraceIdString(id)}`,
      from,
      to,
      limit: MAX_PAGE,
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    };
    for await (const s of this.searchSpans(search)) bySpanId.set(s.span.spanId, s.span);
    if (bySpanId.size === 0) return null;
    return { traceId: id, spans: sortSpans([...bySpanId.values()]) };
  }

  async #getTracePreview(id: string, opts: QueryCallOptions): Promise<Trace | null> {
    const res = await this.#call<string>({
      method: "GET",
      path: `/api/v2/trace/${traceIdForPath(id)}`,
      responseType: "text",
      allowStatus: [404],
      operation: "getTrace",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    if (res.status === 404 || res.data.trim() === "") return null;
    const body = parseJsonPreservingBigInts(res.data) as {
      data?: { attributes?: { spans?: RawDatadogSpan[] } };
    };
    const spans = (body.data?.attributes?.spans ?? []).map(datadogTraceSpanToSpan);
    if (spans.length === 0) return null;
    return { traceId: id, spans: sortSpans(spans) };
  }

  /**
   * `GET /api/v2/apm/services?filter[env]=…`: names of the services that reported traces. The env
   * filter defaults to the configured `environment`, else `*` (all).
   */
  async listServices(opts: ListServicesOptions = {}): Promise<string[]> {
    const res = await this.#call<ServicesResponse | undefined>({
      method: "GET",
      path: "/api/v2/apm/services",
      query: { "filter[env]": opts.env ?? this.config.environment ?? "*" },
      operation: "listServices",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const services = res.data?.data?.attributes?.services ?? [];
    return services.filter((s): s is string => typeof s === "string").sort();
  }

  /**
   * Distinct span names (Datadog `resource_name`) of `service` in the last 24 hours, via
   * `aggregateSpans` grouped by `resource_name` (up to 1000).
   */
  async listOperations(service: string, opts: QueryCallOptions = {}): Promise<string[]> {
    const now = this.#now();
    const result = await this.aggregateSpans(
      [{ aggregation: "count" }],
      [{ facet: "resource_name", limit: 1000 }],
      {
        query: `service:${quoteQueryValue(service)}`,
        from: new Date(now - TRACE_WINDOW_MS),
        to: new Date(now),
        ...(opts.signal === undefined ? {} : { signal: opts.signal }),
      },
    );
    const names = new Set<string>();
    for (const b of result.buckets) {
      const v = b.by.resource_name;
      if (v !== undefined && v !== "") names.add(String(v));
    }
    return [...names].sort();
  }

  /** Builds the Datadog query string for a SPEC {@link TraceSearchQuery}. */
  static traceSearchQuery(q: TraceSearchQuery): string {
    const parts: string[] = [];
    if (q.service !== undefined) parts.push(`service:${quoteQueryValue(q.service)}`);
    if (q.operation !== undefined) parts.push(`resource_name:${quoteQueryValue(q.operation)}`);
    for (const [k, v] of Object.entries(q.tags ?? {})) parts.push(`@${k}:${quoteQueryValue(v)}`);
    if (q.minDurationMicros !== undefined) parts.push(`@duration:>=${q.minDurationMicros * 1000}`);
    if (q.maxDurationMicros !== undefined) parts.push(`@duration:<=${q.maxDurationMicros * 1000}`);
    return parts.length === 0 ? "*" : parts.join(" ");
  }

  /**
   * Finds traces with spans matching `query` (service, span name, `@tag` values, duration in
   * nanoseconds) in `start..end` (default: the last 15 minutes), newest first, up to `limit`
   * (default 20) distinct trace ids. Summaries are built from the matching spans only.
   */
  async searchTraces(
    query: TraceSearchQuery,
    opts: QueryCallOptions = {},
  ): Promise<TraceSummary[]> {
    const limit = query.limit ?? 20;
    const { from, to } = this.#window(query.start, query.end, DEFAULT_WINDOW_MS);
    const traces = new Map<string, Span[]>();
    let cursor: string | undefined;
    for (let page = 0; page < SEARCH_TRACES_MAX_PAGES && traces.size < limit; page++) {
      const res = await this.#page(
        {
          query: DatadogSpansQuery.traceSearchQuery(query),
          limit: Math.min(MAX_PAGE, Math.max(limit * 10, 100)),
          sort: "-timestamp",
          ...(opts.signal === undefined ? {} : { signal: opts.signal }),
        },
        from,
        to,
        cursor,
      );
      for (const { span } of res.spans) {
        const list = traces.get(span.traceId);
        if (list !== undefined) list.push(span);
        else if (traces.size < limit) traces.set(span.traceId, [span]);
      }
      if (res.nextCursor === null) break;
      cursor = res.nextCursor;
    }
    return [...traces.entries()].map(([traceId, spans]) => {
      const sorted = sortSpans(spans);
      const ids = new Set(sorted.map((s) => s.spanId));
      const root =
        sorted.find((s) => s.parentSpanId === undefined || !ids.has(s.parentSpanId)) ?? sorted[0];
      const start = Math.min(...sorted.map((s) => s.startMicros));
      const end = Math.max(...sorted.map((s) => s.endMicros));
      const summary: TraceSummary = {
        traceId,
        startMicros: start,
        durationMicros: end - start,
        spanCount: sorted.length,
      };
      if (root !== undefined) {
        summary.rootServiceName = root.serviceName;
        summary.rootName = root.name;
      }
      return summary;
    });
  }

  /**
   * Polls {@link getTrace} until the trace has `minSpans` spans (default timeout 30 s, interval
   * 2 s because Datadog allows 50 searches per minute). Throws `TimeoutError` when it does not
   * appear in time.
   */
  override waitForTrace(traceId: string, opts: WaitForTraceOptions = {}): Promise<Trace> {
    return waitForTrace(this, traceId, { intervalMs: DATADOG_WAIT_INTERVAL_MS, ...opts });
  }

  /**
   * Authenticated round trip `GET /api/v2/validate_keys` (API key + application key); returns the
   * latency in ms. Invalid keys → `AuthenticationError` (Datadog answers 401 or 403).
   */
  async ping(opts: QueryCallOptions = {}): Promise<number> {
    const start = performance.now();
    try {
      await this.#call({
        method: "GET",
        path: "/api/v2/validate_keys",
        responseType: "none",
        operation: "ping",
        ...(opts.signal === undefined ? {} : { signal: opts.signal }),
      });
    } catch (err) {
      if (err instanceof PermissionDeniedError) {
        throw new AuthenticationError(err.message.replace("permission denied", "invalid keys"), {
          httpStatus: 403,
          cause: err,
          ...(err.requestId === undefined ? {} : { requestId: err.requestId }),
        });
      }
      throw err;
    }
    return performance.now() - start;
  }

  /** Releases connections. Idempotent. */
  async close(): Promise<void> {
    await this.#transport.close();
  }
}
