// Runs the shared conformance scenarios (conformance/scenarios) against the real Datadog Agent.
// Tracing: spans are exported with this package's initTracing and read back through the
// trace-reader sidecar (what the Agent forwarded to fakeintake), because the Datadog query API is
// SaaS-only; capabilities `query` and `services` are therefore declared for the reader.

import assert from "node:assert/strict";
import { before, test } from "node:test";
import { DatadogApmClient, initTracing } from "@grow-codes/datadog-apm";
import { formatReport, runConformance } from "@grow-codes/datadog-apm/testing";
import { loadBootstrapEnv, runId, scenariosDir } from "./env.ts";
import { TraceReaderQuery } from "./reader.ts";

before(() => {
  loadBootstrapEnv();
});

test("conformance: core", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core"],
    factories: { core: () => DatadogApmClient.fromEnv() },
    capabilities: { core: ["version"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.ok(report.passed > 0);
});

test("conformance: tracing (Agent → fakeintake → trace-reader)", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["tracing"],
    factories: {
      tracing: (ctx: { run: string }) => ({
        handle: initTracing({
          serviceName: `grow-ts-conf-${runId}-${ctx.run}`,
          setGlobal: false,
          sampler: "always_on",
        }),
        query: new TraceReaderQuery(),
      }),
    },
    capabilities: { tracing: ["query", "services"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
  assert.ok(report.passed >= 2);
});
