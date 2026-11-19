// Datadog APM with the TypeScript SDK: Agent health, OpenTelemetry tracing exported over OTLP to
// the Datadog Agent, Datadog header propagation, log correlation and (with an API key and an
// application key) reading spans back from the Datadog spans API.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   DD_AGENT_HOST=127.0.0.1 DD_SERVICE=example-checkout DD_ENV=dev npm start
//   # optional query API: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
//
// The Agent needs its OTLP receiver enabled:
//   DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318

import {
  DatadogApmClient,
  DatadogApmError,
  datadogLogFields,
  injectHeaders,
  setError,
  withSpan,
} from "@grow-codes/datadog-apm";

const client = DatadogApmClient.fromEnv({
  serviceName: process.env.DD_SERVICE ?? "example-checkout",
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
});
console.log(`config: ${client.config}`); // API/app keys print as [REDACTED]

try {
  const health = await client.health(); // never throws for an unreachable Agent
  console.log("agent health:", health);
  if (!health.healthy) process.exitCode = 1;
  console.log("agent version:", await client.serverVersion());

  // OTLP to the Agent; service/env/version become Datadog unified service tags.
  const tracing = client.initTracing({ serviceVersion: "1.0.0" });
  console.log(`exporting to ${tracing.endpoint}`);

  const traceId = await withSpan(
    "checkout",
    async (span) => {
      // Correlate logs: dd.trace_id / dd.span_id in ddtrace's format.
      console.log(JSON.stringify({ msg: "checkout started", ...datadogLogFields() }));
      // Outgoing call: x-datadog-* and traceparent headers.
      const headers = injectHeaders(new Headers());
      console.log("propagation headers:", Object.fromEntries(headers));
      await withSpan("charge-card", () => {
        setError(new Error("card declined")); // sets error.type / error.message / error.stack too
      });
      return span.spanContext().traceId;
    },
    { kind: "server", attributes: { "order.id": "o-42" } },
  );
  await tracing.forceFlush();
  console.log(`trace ${traceId} sent`);

  if (client.config.hasQueryCredentials) {
    // Spans become searchable in Datadog after indexing (seconds to a minute).
    const trace = await client.spans.waitForTrace(traceId, { timeoutMs: 120_000 });
    console.log(
      "read back:",
      trace.spans.map((s) => `${s.name} (${s.durationMicros} µs, ${s.status.code})`),
    );
    for await (const s of client.spans.searchSpans({
      query: `service:${client.config.serviceName ?? ""}`,
      limit: 5,
    })) {
      console.log("span:", s.span.name, s.span.traceId);
      break;
    }
  }
} catch (err) {
  if (err instanceof DatadogApmError) {
    // Canonical code + HTTP status + request id; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, {
      httpStatus: err.httpStatus,
      requestId: err.requestId,
    });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close(); // flushes and shuts down the tracing started above
}
