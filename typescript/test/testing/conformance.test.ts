import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { DatadogApmClient } from "../../src/client.js";
import {
  type ConformanceOptions,
  conformanceCases,
  formatReport,
  runConformance,
} from "../../src/testing/index.js";
import { type MockServer, startServer } from "../helpers/server.js";

const scenariosDir = fileURLToPath(new URL("../../../conformance/scenarios/", import.meta.url));
let server: MockServer;

beforeAll(async () => {
  server = await startServer((req) => {
    if (req.url === "/info") return { status: 200, body: { version: "7.85.4" } };
    return { status: 404 };
  });
});

afterAll(async () => {
  await server.close();
});

function options(): ConformanceOptions {
  return {
    scenariosDir,
    categories: ["core"],
    factories: {
      core: () => new DatadogApmClient({ url: server.url }),
    },
    capabilities: { core: ["version"] },
  };
}

describe("conformance (core) against a mock Datadog Agent", () => {
  it("every core scenario passes", async () => {
    const report = await runConformance(options());
    expect(report.failed, formatReport(report)).toBe(0);
    expect(report.skipped).toBe(0);
    expect(report.results.map((r) => r.id)).toContain("core.health");
  });

  for (const c of conformanceCases(options())) {
    it.skipIf(c.skip !== undefined)(`case ${c.id}`, async () => {
      expect((await c.run()).status).toBe("passed");
    });
  }
});
