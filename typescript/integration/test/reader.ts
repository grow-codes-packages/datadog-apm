// Test-only access to the spans the real Datadog Agent forwarded to fakeintake, decoded by the
// `trace-reader` sidecar (GET /spans?trace_id=<hex>&service=…). Not part of the package: the
// Datadog query API is SaaS-only, so the integration suite reads the Agent's output here and maps
// it with the package's own `datadogTraceSpanToSpan`.

import {
  BaseTraceQueryClient,
  datadogTraceSpanToSpan,
  normalizeTraceId,
  sortSpans,
} from "@grow-codes/datadog-apm";
import type {
  RawDatadogSpan,
  Trace,
  TraceSearchQuery,
  TraceSummary,
} from "@grow-codes/datadog-apm";

export interface ReaderSpan extends RawDatadogSpan {
  service: string;
  name: string;
  resource: string;
  type: string;
  trace_id: string;
  trace_id_hex: string;
  trace_id_high: string | null;
  span_id: string;
  span_id_hex: string;
  parent_id: string;
  parent_id_hex: string;
  error: number;
  meta: Record<string, string>;
  metrics: Record<string, number>;
}

function readerUrl(): string {
  const url = process.env.TRACE_READER_URL;
  if (url === undefined || url === "")
    throw new Error("TRACE_READER_URL must be set (/bootstrap/env)");
  return url.replace(/\/+$/, "");
}

/** `GET /spans` with exact-match filters. */
export async function readerSpans(filters: Record<string, string> = {}): Promise<ReaderSpan[]> {
  const res = await fetch(`${readerUrl()}/spans?${new URLSearchParams(filters).toString()}`);
  if (!res.ok) throw new Error(`trace-reader: HTTP ${res.status} ${await res.text()}`);
  const doc = (await res.json()) as { spans: ReaderSpan[]; undecodable_idx_payloads: number };
  if (doc.undecodable_idx_payloads !== 0) throw new Error("trace-reader: undecodable payloads");
  return doc.spans;
}

/** Polls {@link readerSpans} until at least `min` spans match (Agent flush takes ~4 s). */
export async function waitForSpans(
  filters: Record<string, string>,
  min: number,
  timeoutMs = 45_000,
): Promise<ReaderSpan[]> {
  const deadline = Date.now() + timeoutMs;
  let last: ReaderSpan[] = [];
  while (Date.now() < deadline) {
    last = await readerSpans(filters);
    if (last.length >= min) return last;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`trace-reader: ${last.length}/${min} spans for ${JSON.stringify(filters)}`);
}

/**
 * A {@link TraceQueryClient} over the trace-reader, so the shared conformance scenarios run
 * against what the real Agent made of the exported spans.
 */
export class TraceReaderQuery extends BaseTraceQueryClient {
  async getTrace(traceId: string): Promise<Trace | null> {
    const id = normalizeTraceId(traceId);
    const spans = await readerSpans({ trace_id: id });
    if (spans.length === 0) return null;
    return { traceId: id, spans: sortSpans(spans.map(datadogTraceSpanToSpan)) };
  }

  async searchTraces(query: TraceSearchQuery): Promise<TraceSummary[]> {
    const filters: Record<string, string> = {};
    if (query.service !== undefined) filters.service = query.service;
    if (query.operation !== undefined) filters.resource = query.operation;
    const ids = [...new Set((await readerSpans(filters)).map((s) => s.trace_id_hex))];
    const out: TraceSummary[] = [];
    for (const id of ids.slice(0, query.limit ?? 20)) {
      const trace = await this.getTrace(id);
      if (trace !== null)
        out.push({
          traceId: id,
          startMicros: trace.spans[0]?.startMicros ?? 0,
          durationMicros: 0,
          spanCount: trace.spans.length,
        });
    }
    return out;
  }

  async listServices(): Promise<string[]> {
    return [...new Set((await readerSpans()).map((s) => s.service))].sort();
  }

  async listOperations(service: string): Promise<string[]> {
    return [...new Set((await readerSpans({ service })).map((s) => s.resource))].sort();
  }
}
