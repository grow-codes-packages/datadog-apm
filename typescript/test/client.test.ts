import { readFileSync } from "node:fs";
import { inspect } from "node:util";
import { afterEach, describe, expect, it } from "vitest";
import {
  ConfigError,
  DatadogApmClient,
  DatadogApmConfig,
  DatadogSpansQuery,
  PACKAGE_KEY,
  REDACTED,
  RetryPolicy,
  Secret,
  otlpTracesUrl,
  parseDatadogTags,
} from "../src/index.js";
import { type MockServer, closedPort, startServer } from "./helpers/server.js";

const servers: MockServer[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const info = JSON.parse(
  readFileSync(new URL("./fixtures/health.info.ok.json", import.meta.url), "utf8"),
) as { body: Record<string, unknown>; headers: Record<string, string> };

async function agent(): Promise<MockServer> {
  const s = await startServer((req) =>
    req.url === "/info"
      ? {
          status: 200,
          headers: { "content-type": info.headers["Content-Type"] ?? "text/plain" },
          body: JSON.stringify(info.body),
        }
      : { status: 404, headers: { "content-type": "text/plain" }, body: "404 page not found\n" },
  );
  servers.push(s);
  return s;
}

const fast = { retry: { maxRetries: 1, initialBackoffMs: 1 } };

describe("DatadogApmConfig", () => {
  it("uses defaults and freezes", () => {
    const c = new DatadogApmConfig();
    expect(c.url).toBe("http://127.0.0.1:8126");
    expect(c.url).toBe(DatadogApmConfig.DEFAULT_URL);
    expect(c.protocol).toBe("http/protobuf");
    expect(c.otlpTracesEndpoint).toBe("http://127.0.0.1:4318/v1/traces");
    expect(c.site).toBe("datadoghq.com");
    expect(c.apiUrl).toBe("https://api.datadoghq.com");
    expect(c.requestTimeoutMs).toBe(30_000);
    expect(c.retry).toBeInstanceOf(RetryPolicy);
    expect(c.apiKey).toBeUndefined();
    expect(c.hasQueryCredentials).toBe(false);
    expect(Object.isFrozen(c)).toBe(true);
    expect(DatadogApmConfig.from(c)).toBe(c);
    expect(DatadogApmConfig.from().url).toBe(c.url);
    const prefix = `GROW_${PACKAGE_KEY.toUpperCase().replaceAll("-", "_")}_`;
    expect(DatadogApmConfig.ENV_PREFIX).toBe(prefix);
  });

  it("derives the OTLP endpoint from the agent host and protocol", () => {
    expect(new DatadogApmConfig({ url: "http://agent:8126" }).otlpTracesEndpoint).toBe(
      "http://agent:4318/v1/traces",
    );
    expect(
      new DatadogApmConfig({ url: "http://agent:8126", protocol: "grpc" }).otlpTracesEndpoint,
    ).toBe("http://agent:4317");
    expect(new DatadogApmConfig({ url: "http://[::1]:8126" }).otlpTracesEndpoint).toBe(
      "http://[::1]:4318/v1/traces",
    );
    expect(new DatadogApmConfig({ otlpEndpoint: "http://o:9/" }).otlpTracesEndpoint).toBe(
      "http://o:9/v1/traces",
    );
    expect(new DatadogApmConfig({ otlpEndpoint: "http://o:9/custom" }).otlpTracesEndpoint).toBe(
      "http://o:9/custom",
    );
    expect(otlpTracesUrl("grpc", "http://g:4317")).toBe("http://g:4317");
  });

  it("keeps optional settings", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const c = new DatadogApmConfig({
      proxy: "http://p:1",
      userAgentSuffix: "s",
      hooks,
      logger,
      apiKey: new Secret("api-key-value"),
      appKey: "app-key-value",
      serviceName: "svc",
      serviceVersion: "1.0",
      environment: "prod",
      tags: { team: "sdk" },
      site: "DatadogHQ.EU",
    });
    expect(c.proxy).toBe("http://p:1");
    expect(c.userAgentSuffix).toBe("s");
    expect(c.hooks).toBe(hooks);
    expect(c.logger).toBe(logger);
    expect(c.site).toBe("datadoghq.eu");
    expect(c.apiUrl).toBe("https://api.datadoghq.eu");
    expect(c.hasQueryCredentials).toBe(true);
    expect(c.knownSecrets()).toEqual(["api-key-value", "app-key-value"]);
    expect(c.toJSON()).toMatchObject({
      serviceName: "svc",
      serviceVersion: "1.0",
      environment: "prod",
      tags: { team: "sdk" },
    });
  });

  it("validate() raises ConfigError", () => {
    const bad = [
      { url: "ftp://x" },
      { apiKey: "   " },
      { appKey: "  " },
      { site: "https://app.datadoghq.com" },
      { site: "datadoghq.com/path" },
      { apiUrl: "nope" },
      { otlpEndpoint: "tcp://x" },
      { protocol: "native" as never },
      { serviceName: " " },
    ];
    for (const opts of bad)
      expect(() => new DatadogApmConfig(opts), JSON.stringify(opts)).toThrow(ConfigError);
    expect(new DatadogApmConfig({ site: "us3.datadoghq.com" }).site).toBe("us3.datadoghq.com");
    expect(new DatadogApmConfig({ site: "custom.example.org" }).apiUrl).toBe(
      "https://api.custom.example.org",
    );
  });

  it("core.redaction.config: toString/JSON/inspect hide credentials", () => {
    const c = new DatadogApmConfig({
      apiKey: "super-secret-api",
      appKey: "super-secret-app",
      headers: { "x-api-key": "hdr-secret" },
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c), `${c.apiKey}`]) {
      expect(form).not.toContain("super-secret-api");
      expect(form).not.toContain("super-secret-app");
      expect(form).not.toContain("hdr-secret");
    }
    expect(String(c)).toContain(REDACTED);
    expect(String(c)).toMatch(/Config\(/);
    expect(new DatadogApmConfig().toJSON().apiKey).toBeNull();
    expect(new DatadogApmConfig().toJSON().appKey).toBeNull();
  });

  it("parses DD_TAGS", () => {
    expect(parseDatadogTags("team:sdk, tier:web  a:b:c,bare,:x")).toEqual({
      team: "sdk",
      tier: "web",
      a: "b:c",
    });
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const p = DatadogApmConfig.ENV_PREFIX;
    const env = {
      [`${p}URL`]: "http://grow:1",
      DD_TRACE_AGENT_URL: "http://vendor:2",
      DD_AGENT_HOST: "host3",
      DD_API_KEY: "vendor-api-key",
      [`${p}APP_KEY`]: "grow-app-key",
      DD_APP_KEY: "vendor-app-key",
      DD_SERVICE: "dd-svc",
      OTEL_SERVICE_NAME: "otel-svc",
      DD_ENV: "dd-env",
      [`${p}VERSION`]: "9.9.9",
      DD_VERSION: "1.0.0",
      DD_TAGS: "team:sdk,tier:web",
      DD_SITE: "datadoghq.eu",
      [`${p}REQUEST_TIMEOUT`]: "5s",
      [`${p}MAX_RETRIES`]: "7",
    };
    const c = DatadogApmConfig.fromEnv({}, env);
    expect(c.url).toBe("http://grow:1");
    expect(c.apiKey?.reveal()).toBe("vendor-api-key");
    expect(c.appKey?.reveal()).toBe("grow-app-key");
    expect(c.serviceName).toBe("dd-svc");
    expect(c.environment).toBe("dd-env");
    expect(c.serviceVersion).toBe("9.9.9");
    expect(c.tags).toEqual({ team: "sdk", tier: "web" });
    expect(c.site).toBe("datadoghq.eu");
    expect(c.apiUrl).toBe("https://api.datadoghq.eu");
    expect(c.requestTimeoutMs).toBe(5000);
    expect(c.retry.maxRetries).toBe(7);
    expect(c.otlpTracesEndpoint).toBe("http://grow:4318/v1/traces");

    expect(DatadogApmConfig.fromEnv({}, { DD_TRACE_AGENT_URL: "http://vendor:2" }).url).toBe(
      "http://vendor:2",
    );
    expect(DatadogApmConfig.fromEnv({}, { DD_AGENT_HOST: "h" }).url).toBe("http://h:8126");
    expect(DatadogApmConfig.fromEnv({}, { DD_TRACE_AGENT_PORT: "9126" }).url).toBe(
      "http://127.0.0.1:9126",
    );
    expect(DatadogApmConfig.fromEnv({}, { DD_AGENT_HOST: "::1" }).url).toBe("http://[::1]:8126");
    expect(
      DatadogApmConfig.fromEnv({}, { DD_APPLICATION_KEY: "k-app", OTEL_SERVICE_NAME: "o" }),
    ).toMatchObject({ serviceName: "o" });
    expect(DatadogApmConfig.fromEnv({}, { DD_APPLICATION_KEY: "k-app" }).appKey?.reveal()).toBe(
      "k-app",
    );

    const explicit = DatadogApmConfig.fromEnv(
      {
        url: "http://explicit:3",
        apiKey: "explicit-key",
        serviceName: "x",
        tags: { tier: "api" },
        retry: { initialBackoffMs: 1 },
      },
      env,
    );
    expect(explicit.url).toBe("http://explicit:3");
    expect(explicit.apiKey?.reveal()).toBe("explicit-key");
    expect(explicit.serviceName).toBe("x");
    expect(explicit.tags).toEqual({ team: "sdk", tier: "api" });
    expect(explicit.retry.maxRetries).toBe(7);
    expect(explicit.retry.initialBackoffMs).toBe(1);

    expect(DatadogApmConfig.fromEnv({}, {}).url).toBe(DatadogApmConfig.DEFAULT_URL);
    expect(DatadogApmConfig.fromEnv().url).toBeTypeOf("string");

    for (const [name, value] of [
      [`${p}TOTAL_TIMEOUT`, "forever-and-secret"],
      ["DD_TRACE_AGENT_PORT", "port-secret"],
      ["DD_TRACE_AGENT_PORT", "99999"],
      [`${p}OTLP_PROTOCOL`, "thrift-secret"],
    ] as const) {
      let err: unknown;
      try {
        DatadogApmConfig.fromEnv({}, { [name]: value });
      } catch (e) {
        err = e;
      }
      expect(err).toBeInstanceOf(ConfigError);
      expect((err as Error).message).toContain(name);
      expect((err as Error).message).not.toContain(value);
    }
  });

  it("reads OTLP endpoint and protocol variables by precedence", () => {
    const p = DatadogApmConfig.ENV_PREFIX;
    const all = {
      [`${p}OTLP_HTTP_ENDPOINT`]: "http://h:4318",
      [`${p}OTLP_GRPC_ENDPOINT`]: "http://g:4317",
      [`${p}OTLP_ENDPOINT`]: "http://generic:4318",
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://traces:1/v1/traces",
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:2",
    };
    expect(DatadogApmConfig.fromEnv({}, all).otlpTracesEndpoint).toBe("http://h:4318/v1/traces");
    expect(DatadogApmConfig.fromEnv({ protocol: "grpc" }, all).otlpTracesEndpoint).toBe(
      "http://g:4317",
    );
    expect(DatadogApmConfig.fromEnv({}, { ...all, [`${p}OTLP_PROTOCOL`]: "GRPC" }).protocol).toBe(
      "grpc",
    );
    expect(
      DatadogApmConfig.fromEnv({}, { OTEL_EXPORTER_OTLP_PROTOCOL: "grpc" }).otlpTracesEndpoint,
    ).toBe("http://127.0.0.1:4317");
    const { [`${p}OTLP_HTTP_ENDPOINT`]: _h, ...noHttp } = all;
    expect(DatadogApmConfig.fromEnv({}, noHttp).otlpEndpoint).toBe("http://generic:4318");
    expect(
      DatadogApmConfig.fromEnv(
        {},
        {
          OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://traces:1/v1/traces",
          OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:2",
        },
      ).otlpTracesEndpoint,
    ).toBe("http://traces:1/v1/traces");
    expect(
      DatadogApmConfig.fromEnv({}, { OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:2/" })
        .otlpTracesEndpoint,
    ).toBe("http://base:2/v1/traces");
    expect(
      DatadogApmConfig.fromEnv(
        { protocol: "grpc" },
        { OTEL_EXPORTER_OTLP_ENDPOINT: "http://base:4317" },
      ).otlpTracesEndpoint,
    ).toBe("http://base:4317");
    expect(DatadogApmConfig.fromEnv({}, { [`${p}API_URL`]: "http://mock:1" }).apiUrl).toBe(
      "http://mock:1",
    );
  });
});

describe("DatadogApmClient", () => {
  it("health, ping, serverVersion and agentInfo against the Agent /info", async () => {
    const s = await agent();
    const client = new DatadogApmClient({ url: s.url, ...fast });
    expect(await client.health()).toEqual({
      healthy: true,
      status: "ok",
      details: { version: "7.85.4", git_commit: "52568a4d" },
    });
    expect(await client.ping()).toBeGreaterThanOrEqual(0);
    expect(await client.serverVersion()).toBe("7.85.4");
    expect((await client.agentInfo()).endpoints).toContain("/v0.4/traces");
    expect(client.transport.userAgent).toContain(`grow-codes-${PACKAGE_KEY}/`);
    expect(s.requests.every((r) => r.url === "/info")).toBe(true);
    await client.close();
  });

  it("serverVersion raises UnsupportedError when absent", async () => {
    const s = await startServer([{ status: 200, body: { other: 1 } }]);
    servers.push(s);
    const client = new DatadogApmClient({ url: s.url, ...fast });
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    const empty = await startServer([{ status: 200 }]);
    servers.push(empty);
    const client2 = new DatadogApmClient({ url: empty.url, ...fast });
    await expect(client2.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    expect(await client2.health()).toEqual({ healthy: true, status: "ok", details: {} });
    await client.close();
    await client2.close();
  });

  it("health reports unhealthy on HTTP errors", async () => {
    const s = await startServer([{ status: 404, body: "404 page not found\n" }]);
    servers.push(s);
    const sick = new DatadogApmClient({ url: s.url, ...fast });
    expect(await sick.health()).toMatchObject({ healthy: false, status: "unhealthy" });
    await expect(sick.ping()).rejects.toMatchObject({ code: "NOT_FOUND", httpStatus: 404 });
    await sick.close();
  });

  it("core.health.unreachable: closed port -> healthy=false, status=unreachable", async () => {
    const port = await closedPort();
    const client = new DatadogApmClient({ url: `http://127.0.0.1:${port}`, ...fast });
    expect(await client.health()).toEqual({
      healthy: false,
      status: "unreachable",
      details: { error: "UNAVAILABLE" },
    });
    await client.close();
  });

  it("health rethrows cancellation", async () => {
    const s = await agent();
    const client = new DatadogApmClient({ url: s.url });
    await expect(client.health({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await client.close();
  });

  it("core.close.idempotent: close twice is safe and shuts tracing down", async () => {
    const s = await agent();
    const client = new DatadogApmClient({ url: s.url, serviceName: "close-test" });
    const handle = client.initTracing({ setGlobal: false, protocol: "grpc" });
    expect(handle.endpoint).toBe(`http://127.0.0.1:4317`);
    expect(handle.config.serviceName).toBe("close-test");
    await client.health();
    await Promise.all([client.close(), client.close()]);
    await client.close();
    await expect(client.ping()).rejects.toMatchObject({ code: "CONFIG" });
  });

  it("spans needs query credentials and is reused", async () => {
    const client = new DatadogApmClient();
    expect(() => client.spans).toThrow(ConfigError);
    const withKeys = new DatadogApmClient({ apiKey: "api-key-1", appKey: "app-key-1" });
    expect(withKeys.spans).toBeInstanceOf(DatadogSpansQuery);
    expect(withKeys.spans).toBe(withKeys.spans);
    await withKeys.close();
    await client.close();
  });

  it("fromEnv builds from GROW_* variables", async () => {
    const s = await agent();
    const key = `${DatadogApmConfig.ENV_PREFIX}URL`;
    process.env[key] = s.url;
    try {
      const client = DatadogApmClient.fromEnv({ serviceName: "env-svc" });
      expect(client.config.url).toBe(s.url);
      expect(client.config.serviceName).toBe("env-svc");
      expect(await client.ping()).toBeGreaterThanOrEqual(0);
      await client.close();
    } finally {
      delete process.env[key];
    }
  });
});
