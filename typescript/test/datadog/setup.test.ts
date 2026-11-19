import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { OTLPTraceExporter as OtlpGrpcExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPTraceExporter as OtlpHttpExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { SpanStatusCode, context, propagation, trace } from "@opentelemetry/api";
import { InMemorySpanExporter, SimpleSpanProcessor } from "@opentelemetry/sdk-trace-base";
import { afterEach, describe, expect, it } from "vitest";
import {
  ATTR_ERROR_MESSAGE,
  ATTR_ERROR_STACK,
  ATTR_ERROR_TYPE,
  ConfigError,
  DATADOG_DEFAULT_PROPAGATORS,
  DatadogApmConfig,
  DatadogErrorSpanProcessor,
  DatadogSpanLinkProcessor,
  datadogErrorAttributes,
  datadogExporterFactory,
  datadogLogFields,
  extractHeaders,
  initTracing,
  injectHeaders,
  orderPropagators,
  recordException,
  resolveTracingConfig,
  setDatadogLogDefaults,
  setError,
  setErrorAttributes,
  traceFields,
  withSpan,
  type DatadogTracingHandle,
} from "../../src/index.js";
import { type MockServer, startServer } from "../helpers/server.js";

const handles: DatadogTracingHandle[] = [];
const servers: MockServer[] = [];
afterEach(async () => {
  await Promise.all(handles.splice(0).map((h) => h.shutdown()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
  setDatadogLogDefaults({});
});

function track(h: DatadogTracingHandle): DatadogTracingHandle {
  handles.push(h);
  return h;
}

describe("initTracing", () => {
  it("exports OTLP/HTTP protobuf to the Agent with unified service tagging", async () => {
    const otlp = await startServer([{ status: 200, body: "" }]);
    servers.push(otlp);
    const memory = new InMemorySpanExporter();
    const h = track(
      initTracing(
        { serviceName: "checkout", setGlobal: false, compression: "none" },
        {
          env: {
            GROW_DATADOG_APM_OTLP_ENDPOINT: otlp.url,
            DD_ENV: "prod",
            DD_VERSION: "2.0.0",
            DD_TAGS: "team:sdk",
          },
          spanProcessors: [new SimpleSpanProcessor(memory)],
        },
      ),
    );
    expect(h.endpoint).toBe(`${otlp.url}/v1/traces`);
    expect(h.config.propagators).toEqual(["tracecontext", "datadog", "baggage"]);
    expect(h.config.environment).toBe("prod");
    const tracer = h.tracer("t");
    tracer.startSpan("work").end();
    await h.forceFlush();
    expect(otlp.requests).toHaveLength(1);
    expect(otlp.requests[0]?.url).toBe("/v1/traces");
    expect(otlp.requests[0]?.headers["content-type"]).toBe("application/x-protobuf");
    const [span] = memory.getFinishedSpans();
    expect(span?.resource.attributes).toMatchObject({
      "service.name": "checkout",
      "service.version": "2.0.0",
      "deployment.environment.name": "prod",
      team: "sdk",
    });
  });

  it("uses the environment for every unset field and an explicit config object", () => {
    const h = track(
      initTracing(
        {},
        {
          env: {
            DD_SERVICE: "env-svc",
            DD_AGENT_HOST: "agent",
            OTEL_EXPORTER_OTLP_PROTOCOL: "grpc",
          },
        },
      ),
    );
    expect(h.config.serviceName).toBe("env-svc");
    expect(h.endpoint).toBe("http://agent:4317");
    expect(h.config.setGlobal).toBe(true);

    const cfg = new DatadogApmConfig({ url: "http://a:8126", serviceName: "cfg-svc" });
    const h2 = track(initTracing({ setGlobal: false, protocol: "grpc" }, { config: cfg }));
    expect(h2.endpoint).toBe("http://a:4317");
    expect(h2.config.serviceName).toBe("cfg-svc");
    const h3 = track(
      initTracing(
        { setGlobal: false, endpoint: "http://x:4318", propagators: ["datadog"] },
        { config: { serviceName: "o", tls: { insecureSkipVerify: true } }, env: false },
      ),
    );
    expect(h3.endpoint).toBe("http://x:4318/v1/traces");
    expect(h3.config.tls.insecureSkipVerify).toBe(true);
    expect(h3.config.propagators).toEqual(["datadog"]);
  });

  it("requires a service name and rejects the native protocol", () => {
    expect(() => initTracing({}, { env: false })).toThrow(ConfigError);
    expect(() => initTracing({ serviceName: "x", protocol: "native" }, { env: false })).toThrow(
      /OTLP/,
    );
    const resolved = resolveTracingConfig({ serviceName: "x", protocol: "native" });
    expect(() => datadogExporterFactory(resolved)).toThrow(ConfigError);
  });

  it("propagates Datadog + W3C headers through the global propagator", () => {
    const h = track(initTracing({ serviceName: "prop" }, { env: false }));
    const span = h.tracer("t").startSpan("out");
    const headers: Record<string, string> = {};
    context.with(trace.setSpan(context.active(), span), () => injectHeaders(headers));
    const id = span.spanContext();
    expect(headers.traceparent).toContain(id.traceId);
    expect(headers["x-datadog-trace-id"]).toBe(BigInt(`0x${id.traceId.slice(16)}`).toString());
    expect(headers["x-datadog-parent-id"]).toBe(BigInt(`0x${id.spanId}`).toString());
    expect(headers["x-datadog-sampling-priority"]).toBe("1");
    span.end();
    const ctx = extractHeaders({
      "x-datadog-trace-id": "42",
      "x-datadog-parent-id": "1",
      traceparent: "00-11111111111111112222222222222222-0000000000000abc-01",
    });
    expect(trace.getSpanContext(ctx)?.traceId).toBe("0000000000000000000000000000002a");
    expect(propagation.fields()).toContain("x-datadog-parent-id");
  });

  it("adds the terminated W3C context as a span link and Datadog error attributes", async () => {
    const memory = new InMemorySpanExporter();
    const h = track(
      initTracing(
        { serviceName: "links", setGlobal: false },
        { env: false, spanProcessors: [new SimpleSpanProcessor(memory)] },
      ),
    );
    const ctx = extractHeaders(
      {
        "x-datadog-trace-id": "42",
        "x-datadog-parent-id": "1",
        traceparent: "00-11111111111111112222222222222222-0000000000000abc-01",
      },
      { propagator: h.propagator },
    );
    const tracer = h.tracer("t");
    expect(() =>
      withSpan(
        "server",
        () =>
          withSpan(
            "child",
            () => {
              throw new TypeError("bad input");
            },
            { tracer },
          ),
        { tracer, context: ctx },
      ),
    ).toThrow(TypeError);
    withSpan(
      "status-only",
      (span) => {
        span.setStatus({ code: SpanStatusCode.ERROR, message: "denied" });
      },
      { tracer },
    );
    withSpan(
      "preset",
      (span) => {
        span.setAttribute(ATTR_ERROR_TYPE, "Custom");
        span.recordException(new Error("ignored"));
      },
      { tracer },
    );
    withSpan("ok", () => undefined, { tracer });
    withSpan(
      "status-no-message",
      (span) => {
        span.setStatus({ code: SpanStatusCode.ERROR });
        span.addEvent("exception", { "exception.type": 1 });
      },
      { tracer },
    );
    const spans = new Map(memory.getFinishedSpans().map((s) => [s.name, s]));
    const server = spans.get("server");
    expect(server?.links).toHaveLength(1);
    expect(server?.links[0]?.context.traceId).toBe("11111111111111112222222222222222");
    expect(server?.links[0]?.attributes).toEqual({
      reason: "terminated_context",
      context_headers: "tracecontext",
    });
    expect(server?.parentSpanContext?.spanId).toBe("0000000000000001");
    expect(spans.get("child")?.links).toHaveLength(0);
    expect(spans.get("child")?.attributes).toMatchObject({
      [ATTR_ERROR_TYPE]: "TypeError",
      [ATTR_ERROR_MESSAGE]: "bad input",
    });
    expect(String(spans.get("child")?.attributes[ATTR_ERROR_STACK])).toContain("TypeError");
    expect(spans.get("status-only")?.attributes).toEqual({ [ATTR_ERROR_MESSAGE]: "denied" });
    expect(spans.get("preset")?.attributes).toEqual({ [ATTR_ERROR_TYPE]: "Custom" });
    expect(spans.get("ok")?.attributes).toEqual({});
    expect(spans.get("status-no-message")?.attributes).toEqual({});
  });

  it("builds HTTP and gRPC exporters with TLS options", () => {
    const dir = mkdtempSync(join(tmpdir(), "dd-tls-"));
    const pem = join(dir, "ca.pem");
    writeFileSync(pem, "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
    const base = { serviceName: "x", headers: { "x-k": "v" } };
    const http = datadogExporterFactory(
      resolveTracingConfig({
        ...base,
        endpoint: "https://a:4318",
        tls: { caCertPath: pem, clientCertPath: pem, clientKeyPath: pem, insecureSkipVerify: true },
      }),
    );
    expect(http).toBeInstanceOf(OtlpHttpExporter);
    const plainHttp = datadogExporterFactory(
      resolveTracingConfig({ ...base, compression: "none" }),
    );
    expect(plainHttp).toBeInstanceOf(OtlpHttpExporter);
    const grpc = datadogExporterFactory(
      resolveTracingConfig({ ...base, protocol: "grpc", endpoint: "http://a:4317" }),
    );
    expect(grpc).toBeInstanceOf(OtlpGrpcExporter);
    const grpcTls = datadogExporterFactory(
      resolveTracingConfig({
        ...base,
        protocol: "grpc",
        endpoint: "https://a:4317",
        tls: { insecureSkipVerify: true },
      }),
    );
    expect(grpcTls).toBeInstanceOf(OtlpGrpcExporter);
    const grpcCa = datadogExporterFactory(
      resolveTracingConfig({ ...base, protocol: "grpc", tls: { caCertPem: "pem" } }),
    );
    expect(grpcCa).toBeInstanceOf(OtlpGrpcExporter);
    expect(() =>
      datadogExporterFactory(
        resolveTracingConfig({ ...base, tls: { caCertPath: join(dir, "missing.pem") } }),
      ),
    ).toThrow(/TLS file/);
    void Promise.all([http, plainHttp, grpc, grpcTls, grpcCa].map((e) => e.shutdown()));
  });

  it("orders propagators so Datadog sees the W3C result", () => {
    expect(orderPropagators(["datadog", "tracecontext", "baggage"])).toEqual([
      "tracecontext",
      "datadog",
      "baggage",
    ]);
    expect(orderPropagators(["baggage", " Datadog", "b3", "tracecontext"])).toEqual([
      "baggage",
      "tracecontext",
      "datadog",
      "b3",
    ]);
    expect(orderPropagators(["tracecontext", "datadog"])).toEqual(["tracecontext", "datadog"]);
    expect(orderPropagators(["datadog"])).toEqual(["datadog"]);
    expect(DATADOG_DEFAULT_PROPAGATORS).toEqual(["datadog", "tracecontext", "baggage"]);
  });
});

describe("span helpers and log correlation", () => {
  it("recordException and setError also set the Datadog error attributes", () => {
    const memory = new InMemorySpanExporter();
    track(
      initTracing(
        { serviceName: "helpers", serviceVersion: "1.2.3", environment: "ci" },
        { env: false, spanProcessors: [new SimpleSpanProcessor(memory)] },
      ),
    );
    expect(datadogLogFields()).toEqual({
      "dd.service": "helpers",
      "dd.env": "ci",
      "dd.version": "1.2.3",
    });
    withSpan("a", () => {
      recordException(new RangeError("out of range"));
      const fields = datadogLogFields();
      const tf = traceFields();
      expect(fields["dd.trace_id"]).toMatch(/^[0-9a-f]{32}$|^\d+$/);
      expect(fields["dd.span_id"]).toBe(BigInt(`0x${tf.span_id}`).toString());
      expect(fields["dd.service"]).toBe("helpers");
      expect(datadogLogFields({ service: "o", env: "e", version: "v" })).toMatchObject({
        "dd.service": "o",
        "dd.env": "e",
        "dd.version": "v",
      });
    });
    withSpan("b", () => {
      setError("plain failure");
    });
    withSpan("c", () => {
      setErrorAttributes({ code: 7 });
    });
    const spans = new Map(memory.getFinishedSpans().map((s) => [s.name, s]));
    expect(spans.get("a")?.attributes[ATTR_ERROR_TYPE]).toBe("RangeError");
    expect(spans.get("a")?.events[0]?.name).toBe("exception");
    expect(spans.get("b")?.status).toEqual({
      code: SpanStatusCode.ERROR,
      message: "plain failure",
    });
    expect(spans.get("b")?.attributes).toEqual({
      [ATTR_ERROR_TYPE]: "Error",
      [ATTR_ERROR_MESSAGE]: "plain failure",
    });
    expect(spans.get("c")?.attributes).toEqual({
      [ATTR_ERROR_TYPE]: "object",
      [ATTR_ERROR_MESSAGE]: '{"code":7}',
    });
    // no active span: no-ops
    recordException(new Error("x"));
    setError(new Error("x"));
    setErrorAttributes(new Error("x"));
  });

  it("datadogErrorAttributes handles odd values", () => {
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    expect(datadogErrorAttributes(circular)[ATTR_ERROR_MESSAGE]).toBe("[object Object]");
    expect(datadogErrorAttributes(undefined)).toEqual({
      [ATTR_ERROR_TYPE]: "undefined",
      [ATTR_ERROR_MESSAGE]: "undefined",
    });
    const anonymous = new (class extends Error {})("m");
    anonymous.name = "";
    delete anonymous.stack;
    expect(datadogErrorAttributes(anonymous)).toEqual({
      [ATTR_ERROR_TYPE]: anonymous.constructor.name,
      [ATTR_ERROR_MESSAGE]: "m",
    });
  });

  it("datadogLogFields uses the 64-bit decimal form and resource values", () => {
    track(initTracing({ serviceName: "ctx-manager" }, { env: false }));
    setDatadogLogDefaults({});
    const tracer = {
      span: {
        spanContext: () => ({
          traceId: "0000000000000000000000000000002a",
          spanId: "b75d0f3b167ddb14",
          traceFlags: 1,
        }),
        resource: { attributes: { "deployment.environment": "legacy" } },
      },
    };
    const ctx = trace.setSpan(context.active(), tracer.span as never);
    context.with(ctx, () => {
      expect(datadogLogFields()).toEqual({
        "dd.trace_id": "42",
        "dd.span_id": "13212733628229737236",
        "dd.env": "legacy",
      });
    });
    expect(datadogLogFields()).toEqual({});
  });

  it("processors are no-op safe", async () => {
    for (const p of [new DatadogErrorSpanProcessor(), new DatadogSpanLinkProcessor()]) {
      p.onEnd();
      await p.forceFlush();
      await p.shutdown();
    }
    new DatadogErrorSpanProcessor().onStart();
  });
});
