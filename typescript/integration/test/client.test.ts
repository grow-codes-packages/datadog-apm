// Integration tests of DatadogApmClient against the real Datadog Agent (docker/compose.yml).

import assert from "node:assert/strict";
import { before, test } from "node:test";
import {
  ConfigError,
  DatadogApmClient,
  DatadogApmConfig,
  DatadogSpansQuery,
} from "@grow-codes/datadog-apm";
import { loadBootstrapEnv } from "./env.ts";

before(() => {
  loadBootstrapEnv();
  assert.ok(process.env.GROW_DATADOG_APM_URL, "GROW_DATADOG_APM_URL must be set");
});

test("health reports a healthy Agent with its version", async () => {
  const client = DatadogApmClient.fromEnv();
  try {
    const health = await client.health();
    assert.equal(health.healthy, true, JSON.stringify(health));
    assert.equal(health.status, "ok");
    assert.equal(health.details.version, process.env.DD_AGENT_VERSION);
  } finally {
    await client.close();
  }
});

test("ping measures a round trip to the Agent", async () => {
  const client = DatadogApmClient.fromEnv();
  try {
    const latency = await client.ping();
    assert.equal(typeof latency, "number");
    assert.ok(latency >= 0);
  } finally {
    await client.close();
  }
});

test("serverVersion and agentInfo return the Agent's /info", async () => {
  const client = DatadogApmClient.fromEnv();
  try {
    const version = await client.serverVersion();
    assert.equal(version, process.env.DD_AGENT_VERSION);
    const info = await client.agentInfo();
    assert.equal(info.version, version);
    assert.ok(info.endpoints?.includes("/v0.4/traces"));
  } finally {
    await client.close();
    await client.close();
  }
});

test("health of an unreachable Agent is 'unreachable', not an exception", async () => {
  const client = new DatadogApmClient({
    url: "http://127.0.0.1:1",
    retry: { maxRetries: 0 },
  });
  try {
    const health = await client.health();
    assert.equal(health.healthy, false);
    assert.equal(health.status, "unreachable");
  } finally {
    await client.close();
  }
});

test("fromEnv reads the bootstrap variables (DD_* and GROW_DATADOG_APM_*)", () => {
  const cfg = DatadogApmConfig.fromEnv();
  assert.equal(cfg.url, process.env.GROW_DATADOG_APM_URL);
  assert.equal(
    cfg.otlpTracesEndpoint,
    `${process.env.GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT}/v1/traces`,
  );
  assert.equal(
    DatadogApmConfig.fromEnv({ protocol: "grpc" }).otlpTracesEndpoint,
    process.env.GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT,
  );
  assert.equal(cfg.site, "datadoghq.com");
  assert.ok(cfg.hasQueryCredentials);
  assert.ok(!String(cfg).includes(process.env.DD_API_KEY ?? "missing"));
  // The query API exists but is SaaS-only (unit-tested); constructing it needs both keys.
  const client = DatadogApmClient.fromEnv();
  assert.ok(client.spans instanceof DatadogSpansQuery);
  assert.throws(() => new DatadogSpansQuery({ apiKey: "only-one-key" }), ConfigError);
  void client.close();
});
