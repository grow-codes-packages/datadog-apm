// Integration tests of the tracing half against the real Datadog Agent: spans are exported over
// OTLP (HTTP/protobuf and gRPC), converted by the Agent, forwarded to fakeintake and read back
// through the trace-reader sidecar.

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { context, trace } from "@opentelemetry/api";
import {
  ATTR_ERROR_MESSAGE,
  ATTR_ERROR_TYPE,
  DatadogApmClient,
  DatadogPropagator,
  addEvent,
  currentSpan,
  currentSpanId,
  currentTraceId,
  datadogLogFields,
  datadogTraceSpanToSpan,
  extract,
  extractHeaders,
  fromDatadogSpanId,
  fromDatadogTraceId,
  initTracing,
  inject,
  injectHeaders,
  recordException,
  setError,
  terminatedContextLinks,
  toDatadogSpanId,
  toDatadogTraceId,
  traceFields,
  traced,
  tracedFetch,
  withSpan,
} from "@grow-codes/datadog-apm";
import type { DatadogLogFields, DatadogTracingHandle } from "@grow-codes/datadog-apm";
import { loadBootstrapEnv, runId } from "./env.ts";
import { readerSpans, waitForSpans } from "./reader.ts";

const run = `${runId}-${Math.random().toString(36).slice(2, 8)}`;
let handle: DatadogTracingHandle;
const service = `grow-ts-it-${run}`;

before(() => {
  loadBootstrapEnv();
  handle = initTracing({
    serviceName: service,
    serviceVersion: "1.2.3",
    environment: "it",
    resourceAttributes: { "grow.run": run },
    sampler: "always_on",
  });
});

after(async () => {
  await handle.shutdown();
  await handle.shutdown();
});

test("initTracing (OTLP/HTTP protobuf) → Agent: names, unified tagging, events, errors, links", async () => {
  assert.equal(handle.endpoint, `${process.env.GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT}/v1/traces`);
  let fields: DatadogLogFields = {};
  let ids: { trace_id?: string; span_id?: string } = {};
  let current: { traceId: string | null; spanId: string | null } = { traceId: null, spanId: null };
  const linkTarget = {
    traceId: "a3ce929d0e0e47364bf92f3577b34da6",
    spanId: "00f067aa0ba902b7",
    traceFlags: 1,
  };
  const traceId = await withSpan(
    "it-root",
    async (root) => {
      fields = datadogLogFields();
      ids = traceFields();
      current = { traceId: currentTraceId(), spanId: currentSpanId() };
      assert.equal(currentSpan(), root);
      addEvent("checkpoint", { step: 1 });
      withSpan("it-child-ok", () => undefined, { attributes: { n: 7, flag: true } });
      try {
        withSpan("it-child-fail", () => {
          throw new RangeError("value out of range");
        });
      } catch {
        // expected
      }
      withSpan("it-recorded", () => {
        recordException(new TypeError("bad type"));
      });
      withSpan("it-set-error", () => {
        setError(new Error("explicit failure"));
      });
      await traced(async function tracedWork() {
        await Promise.resolve();
      })();
      withSpan("it-linked", () => undefined, { links: [{ context: linkTarget }] });
      return root.spanContext().traceId;
    },
    { kind: "server" },
  );
  await handle.forceFlush();
  const spans = await waitForSpans({ trace_id: traceId }, 7);
  const byResource = new Map(spans.map((s) => [s.resource, s]));
  const root = byResource.get("it-root");
  assert.ok(root, JSON.stringify(spans.map((s) => s.resource)));
  assert.equal(root.service, service);
  assert.equal(root.meta.env, "it");
  assert.equal(root.meta.version, "1.2.3");
  assert.equal(root.meta["grow.run"], run);
  assert.equal(root.meta["span.kind"], "server");
  assert.equal(root.name, "server.request");
  assert.equal(root.trace_id_hex, traceId);

  // id helpers and log correlation against the Agent's own ids
  const dd = toDatadogTraceId(traceId);
  assert.equal(root.trace_id, dd.low64Decimal);
  assert.equal(root.meta["otel.trace_id"], traceId);
  assert.equal(fromDatadogTraceId(root.trace_id, dd.high64Hex), traceId);
  assert.equal(fromDatadogSpanId(root.span_id), root.span_id_hex);
  assert.equal(toDatadogSpanId(root.span_id_hex), root.span_id);
  assert.equal(fields["dd.trace_id"], dd.is128Bit ? traceId : root.trace_id);
  assert.equal(fields["dd.span_id"], root.span_id);
  assert.equal(fields["dd.service"], service);
  assert.equal(fields["dd.env"], "it");
  assert.equal(fields["dd.version"], "1.2.3");
  assert.deepEqual(ids, { trace_id: traceId, span_id: root.span_id_hex });
  assert.deepEqual(current, { traceId, spanId: root.span_id_hex });

  const fail = byResource.get("it-child-fail");
  assert.equal(fail?.error, 1);
  assert.equal(fail?.meta[ATTR_ERROR_TYPE], "RangeError");
  assert.equal(fail?.meta[ATTR_ERROR_MESSAGE], "value out of range");
  assert.ok(fail?.meta["error.stack"]?.includes("RangeError"));
  assert.equal(byResource.get("it-recorded")?.meta[ATTR_ERROR_TYPE], "TypeError");
  assert.equal(byResource.get("it-set-error")?.error, 1);
  assert.equal(byResource.get("it-set-error")?.meta["error.msg"], "explicit failure");
  assert.equal(byResource.get("it-child-ok")?.metrics.n, 7);
  assert.equal(byResource.get("it-child-ok")?.meta.flag, "true");
  assert.ok(byResource.has("tracedWork"));

  // normalized model from the Agent's span format
  const model = spans.map(datadogTraceSpanToSpan);
  const mRoot = model.find((s) => s.name === "it-root");
  assert.equal(mRoot?.kind, "server");
  assert.equal(mRoot?.events[0]?.name, "checkpoint");
  assert.equal(mRoot?.events[0]?.attributes.step, 1);
  for (const s of model.filter((m) => m.name !== "it-root"))
    assert.equal(s.parentSpanId, mRoot?.spanId, s.name);
  const linked = model.find((s) => s.name === "it-linked");
  assert.deepEqual(
    linked?.links.map((l) => [l.traceId, l.spanId]),
    [[linkTarget.traceId, linkTarget.spanId]],
  );
  assert.equal(model.find((s) => s.name === "it-child-fail")?.status.code, "error");
});

test("initTracing (OTLP/gRPC) via DatadogApmClient → Agent", async () => {
  const client = DatadogApmClient.fromEnv();
  const grpc = client.initTracing({
    serviceName: `${service}-grpc`,
    protocol: "grpc",
    setGlobal: false,
  });
  assert.equal(grpc.endpoint, process.env.GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT);
  const span = grpc.tracer("it").startSpan("it-grpc-span", { attributes: { via: "grpc" } });
  const traceId = span.spanContext().traceId;
  span.end();
  await grpc.forceFlush();
  const spans = await waitForSpans({ trace_id: traceId }, 1);
  assert.equal(spans[0]?.service, `${service}-grpc`);
  assert.equal(spans[0]?.meta.via, "grpc");
  await client.close(); // shuts the handle down
  await client.close();
});

test("Datadog propagation: inject → extract → child span shares the trace in the Agent", async () => {
  const parent = handle.tracer("it").startSpan("it-upstream", { root: true });
  const parentCtx = trace.setSpan(context.active(), parent);
  const headers = inject<Record<string, string>>({}, { context: parentCtx });
  const ddHeaders = injectHeaders(new Headers(), {
    context: parentCtx,
    propagator: new DatadogPropagator(),
  });
  const ids = parent.spanContext();
  const dd = toDatadogTraceId(ids.traceId);
  assert.equal(headers["x-datadog-trace-id"], dd.low64Decimal);
  assert.equal(headers["x-datadog-parent-id"], toDatadogSpanId(ids.spanId));
  assert.equal(headers["x-datadog-sampling-priority"], "1");
  assert.equal(headers["x-datadog-tags"], dd.is128Bit ? `_dd.p.tid=${dd.high64Hex}` : undefined);
  assert.ok(headers.traceparent?.includes(ids.traceId));
  assert.equal(ddHeaders.get("x-datadog-trace-id"), dd.low64Decimal);
  assert.equal(ddHeaders.get("traceparent"), null);

  // downstream: only Datadog headers (what a ddtrace service sends)
  const ddOnly = {
    "x-datadog-trace-id": headers["x-datadog-trace-id"] ?? "",
    "x-datadog-parent-id": headers["x-datadog-parent-id"] ?? "",
    "x-datadog-sampling-priority": "1",
    ...(headers["x-datadog-tags"] === undefined
      ? {}
      : { "x-datadog-tags": headers["x-datadog-tags"] }),
  };
  const downstream = extract(ddOnly);
  assert.equal(trace.getSpanContext(downstream)?.traceId, ids.traceId);
  withSpan("it-downstream", () => undefined, { context: downstream, kind: "server" });

  // conflicting W3C context: Datadog wins, W3C becomes a span link
  const conflicting = extractHeaders({
    ...ddOnly,
    traceparent: "00-11111111111111112222222222222222-0000000000000abc-01",
  });
  assert.equal(terminatedContextLinks(conflicting).length, 1);
  withSpan("it-conflict", () => undefined, { context: conflicting });
  parent.end();
  await handle.forceFlush();

  const spans = await waitForSpans({ trace_id: ids.traceId }, 3);
  const up = spans.find((s) => s.resource === "it-upstream");
  const down = spans.find((s) => s.resource === "it-downstream");
  const conflict = spans.find((s) => s.resource === "it-conflict");
  assert.equal(down?.parent_id, up?.span_id);
  assert.equal(down?.trace_id, up?.trace_id);
  assert.equal(conflict?.parent_id, up?.span_id);
  const links = JSON.parse(conflict?.meta["_dd.span_links"] ?? "[]") as {
    trace_id: string;
    span_id: string;
    attributes: Record<string, string>;
  }[];
  assert.deepEqual(
    links.map((l) => [l.trace_id, l.span_id, l.attributes.reason]),
    [["11111111111111112222222222222222", "0000000000000abc", "terminated_context"]],
  );
});

test("tracedFetch creates a client span with propagated headers", async () => {
  const fetchTraced = tracedFetch(fetch);
  const traceId = await withSpan("it-fetch-parent", async (span) => {
    const res = await fetchTraced(`${process.env.GROW_DATADOG_APM_URL}/info`);
    assert.equal(res.status, 200);
    return span.spanContext().traceId;
  });
  await handle.forceFlush();
  const spans = await waitForSpans({ trace_id: traceId }, 2);
  const client = spans.find((s) => s.meta["span.kind"] === "client");
  assert.ok(client, JSON.stringify(spans.map((s) => s.resource)));
  assert.equal(client.meta["http.method"] ?? client.meta["http.request.method"], "GET");
  assert.equal((await readerSpans({ trace_id: traceId, service })).length, spans.length);
});
