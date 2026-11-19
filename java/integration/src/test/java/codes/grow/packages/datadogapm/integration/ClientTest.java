package codes.grow.packages.datadogapm.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codes.grow.packages.datadogapm.AgentInfo;
import codes.grow.packages.datadogapm.DatadogApmClient;
import codes.grow.packages.datadogapm.DatadogApmConfig;
import codes.grow.packages.datadogapm.DatadogSpansQuery;
import codes.grow.packages.datadogapm.core.CancelledException;
import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.core.HealthStatus;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The client against the real Datadog Agent (docker/compose.yml). */
class ClientTest {
  private static DatadogApmClient client(Map<String, String> env) {
    return DatadogApmClient.builder().fromEnv(EnvReader.of(env)).build();
  }

  @Test
  void healthPingVersionAndInfo() throws Exception {
    Map<String, String> env = TraceReader.requireService();
    String version = env.get("DD_AGENT_VERSION");
    DatadogApmClient c = client(env);
    HealthStatus h = c.health();
    assertTrue(h.healthy(), h.toString());
    assertEquals("ok", h.status());
    assertEquals(version, h.details().get("version"));
    assertTrue(c.healthAsync().get(10, TimeUnit.SECONDS).healthy());
    assertTrue(c.ping().compareTo(Duration.ZERO) > 0);
    assertTrue(c.pingAsync().get(10, TimeUnit.SECONDS).compareTo(Duration.ZERO) > 0);
    assertEquals(version, c.serverVersion());
    assertEquals(version, c.serverVersionAsync().get(10, TimeUnit.SECONDS));
    AgentInfo info = c.agentInfo();
    assertEquals(version, info.version());
    assertTrue(info.endpoints().contains("/v0.4/traces"), info.endpoints().toString());
    assertEquals(8126, info.config().path("receiver_port").asInt());
    assertEquals(version, c.agentInfoAsync().get(10, TimeUnit.SECONDS).version());
    c.close();
    c.close();
    assertTrue(c.isClosed());
    assertThrows(CancelledException.class, c::ping);
  }

  @Test
  void healthUnreachable() {
    TraceReader.requireService();
    try (DatadogApmClient c =
        DatadogApmClient.builder()
            .url("http://127.0.0.1:1")
            .connectTimeout(Duration.ofSeconds(1))
            .retry(RetryPolicy.none())
            .build()) {
      HealthStatus h = c.health();
      assertFalse(h.healthy());
      assertEquals("unreachable", h.status());
    }
  }

  @Test
  void configFromBootstrapEnv() {
    Map<String, String> env = TraceReader.requireService();
    DatadogApmConfig cfg = DatadogApmConfig.fromEnv(env);
    assertEquals(env.get("GROW_DATADOG_APM_URL"), cfg.url());
    assertEquals(
        env.get("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT") + "/v1/traces", cfg.otlpTracesEndpoint());
    assertEquals("datadoghq.com", cfg.site());
    assertTrue(cfg.hasQueryCredentials());
    DatadogApmConfig grpc =
        DatadogApmConfig.builder()
            .protocol(TracingConfig.Protocol.GRPC)
            .fromEnv(EnvReader.of(env))
            .build();
    assertEquals(env.get("GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT"), grpc.otlpTracesEndpoint());
    String key = env.get("DD_API_KEY");
    assertTrue(key != null && !key.isEmpty());
    assertFalse(cfg.toString().contains(key), "API key must be redacted");
    // the process environment only has GROW_DATADOG_APM_URL
    try (DatadogApmClient fromProcess = DatadogApmClient.fromEnv()) {
      assertEquals(env.get("GROW_DATADOG_APM_URL"), fromProcess.config().url());
      assertTrue(fromProcess.health().healthy());
    }
    // the query API exists but is SaaS-only (unit-tested); constructing it needs both keys
    try (DatadogApmClient c = client(env)) {
      DatadogSpansQuery q = c.spans();
      assertEquals("https://api.datadoghq.com", q.config().apiUrl());
      assertEquals(cfg.site(), q.config().site());
    }
    try (DatadogSpansQuery q = DatadogSpansQuery.create(cfg)) {
      assertTrue(q.toString().contains("[REDACTED]"));
    }
    assertThrows(
        ConfigException.class,
        () -> DatadogSpansQuery.create(DatadogApmConfig.builder().apiKey("only-one").build()));
    assertThrows(ConfigException.class, DatadogSpansQuery::fromEnv);
  }
}
