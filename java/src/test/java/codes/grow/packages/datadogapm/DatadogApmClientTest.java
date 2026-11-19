package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadogapm.core.CancelledException;
import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.core.HealthStatus;
import codes.grow.packages.datadogapm.core.Hooks;
import codes.grow.packages.datadogapm.core.Logger;
import codes.grow.packages.datadogapm.core.MockServer;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.core.Secret;
import codes.grow.packages.datadogapm.core.TlsOptions;
import codes.grow.packages.datadogapm.core.UnavailableException;
import codes.grow.packages.datadogapm.core.UnsupportedException;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogApmClientTest {
  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private DatadogApmClient client() {
    return DatadogApmClient.builder()
        .url(server.url())
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
        .build();
  }

  @Test
  void healthVariants() throws Exception {
    try (DatadogApmClient c = client()) {
      server.enqueue(200, Fixtures.body("health.info.ok.json"));
      HealthStatus ok = c.health();
      assertThat(ok.healthy()).isTrue();
      assertThat(ok.status()).isEqualTo("ok");
      assertThat(ok.details()).isEqualTo(Map.of("version", "7.85.4", "git_commit", "52568a4d"));
      server.enqueue(200, "{}");
      assertThat(c.healthAsync().get(5, TimeUnit.SECONDS).details()).isEmpty();
      server.enqueue(404, "404 page not found");
      HealthStatus nf = c.health();
      assertThat(nf.healthy()).isFalse();
      assertThat(nf.status()).isEqualTo("unhealthy");
      assertThat(nf.details())
          .containsEntry("httpStatus", "404")
          .containsEntry("error", "NOT_FOUND");
      server.enqueue(200, "{oops");
      assertThat(c.health().status()).isEqualTo("unhealthy");
      assertThat(server.requests()).allSatisfy(r -> assertThat(r.uri()).isEqualTo("/info"));
    }
  }

  @Test
  void core_health_unreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (DatadogApmClient c =
        DatadogApmClient.builder()
            .url("http://127.0.0.1:" + port)
            .retry(RetryPolicy.none())
            .connectTimeout(Duration.ofSeconds(1))
            .build()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isFalse();
      assertThat(h.status()).isEqualTo("unreachable");
      assertThat(h.details()).containsEntry("error", "UNAVAILABLE");
      assertThatThrownBy(c::ping).isInstanceOf(UnavailableException.class);
    }
  }

  @Test
  void pingVersionAndInfo() throws Exception {
    server.fallback(ex -> MockServer.send(ex, 200, Fixtures.body("health.info.ok.json")));
    try (DatadogApmClient c = client()) {
      assertThat(c.ping()).isPositive();
      assertThat(c.serverVersion()).isEqualTo("7.85.4");
      assertThat(c.serverVersionAsync().get(5, TimeUnit.SECONDS)).isEqualTo("7.85.4");
      AgentInfo info = c.agentInfo();
      assertThat(info.version()).isEqualTo("7.85.4");
      assertThat(info.gitCommit()).isEqualTo("52568a4d");
      assertThat(info.endpoints()).contains("/v0.4/traces");
      assertThat(info.config().path("receiver_port").asInt()).isEqualTo(8126);
      assertThat(info.raw().path("version").asText()).isEqualTo("7.85.4");
    }
    server.fallback(ex -> MockServer.send(ex, 200, "{\"endpoints\":[1,\"/x\"]}"));
    try (DatadogApmClient c = client()) {
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThat(c.agentInfo().endpoints()).containsExactly("/x");
    }
    server.fallback(ex -> MockServer.send(ex, 200, ""));
    try (DatadogApmClient c = client()) {
      assertThat(c.agentInfo().version()).isEmpty();
    }
    server.fallback(ex -> MockServer.send(ex, 200, "not json"));
    try (DatadogApmClient c = client()) {
      assertThatThrownBy(c::agentInfo).isInstanceOf(UnavailableException.class);
    }
  }

  @Test
  void core_close_idempotent() throws Exception {
    server.fallback(ex -> MockServer.send(ex, 200, "{\"version\":\"1\"}"));
    AtomicInteger closedResources = new AtomicInteger();
    DatadogApmClient c =
        DatadogApmClient.builder()
            .url(server.url())
            .apiKey("k1")
            .appKey("k2")
            .otlpEndpoint(server.url())
            .serviceName("svc")
            .build();
    c.register(closedResources::incrementAndGet);
    c.register(
        () -> {
          throw new IllegalStateException("ignored");
        });
    DatadogSpansQuery spans = c.spans();
    assertThat(c.spans()).isSameAs(spans);
    DatadogTracingHandle h = c.initTracing(TracingConfig.builder("svc").setGlobal(false).build());
    assertThat(h.endpoint()).isEqualTo(server.url() + "/v1/traces");
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(closedResources).hasValue(1);
    assertThat(h.isShutdown()).isTrue();
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
    assertThatThrownBy(c::health).isInstanceOf(CancelledException.class);
  }

  @Test
  void initTracingFromClientConfig() {
    try (DatadogApmClient c =
        DatadogApmClient.builder()
            .url(server.url())
            .serviceName("from-client")
            .serviceVersion("2.0")
            .environment("qa")
            .tag("team", "sdk")
            .protocol(TracingConfig.Protocol.GRPC)
            .build()) {
      // tracingConfig() keeps setGlobal true; use a non-global copy
      DatadogTracingHandle h =
          c.initTracing(DatadogTracing.tracingConfig(c.config()).setGlobal(false).build());
      assertThat(h.config().serviceName()).isEqualTo("from-client");
      assertThat(h.endpoint()).endsWith(":4317");
      assertThat(h.config().resourceAttributes()).containsEntry("team", "sdk");
    }
    try (DatadogApmClient c = DatadogApmClient.builder().url(server.url()).build()) {
      assertThatThrownBy(c::initTracing).isInstanceOf(ConfigException.class);
      assertThatThrownBy(c::spans).isInstanceOf(ConfigException.class);
    }
  }

  @Test
  void builderSetsEveryField() {
    Hooks hooks = new Hooks() {};
    try (DatadogApmClient c =
        DatadogApmClient.builder()
            .fromEnv(EnvReader.of(Map.of("DD_ENV", "env-from-reader")))
            .url(server.url())
            .apiKey(Secret.of("a"))
            .appKey(Secret.of("b"))
            .site("datadoghq.eu")
            .apiUrl("http://api.local")
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .tls(TlsOptions.defaults())
            .proxy("http://proxy:1")
            .header("X-A", "1")
            .userAgentSuffix("ua")
            .hooks(hooks)
            .logger(Logger.NOOP)
            .build()) {
      DatadogApmConfig cfg = c.config();
      assertThat(cfg.environment()).contains("env-from-reader");
      assertThat(cfg.site()).isEqualTo("datadoghq.eu");
      assertThat(cfg.apiUrl()).isEqualTo("http://api.local");
      assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(cfg.hooks()).isSameAs(hooks);
      assertThat(c.transport()).isNotNull();
      assertThat(c.toString()).doesNotContain("=a,").contains("[REDACTED]");
    }
  }

  @Test
  void createAndFromEnv() {
    DatadogApmClient c =
        DatadogApmClient.create(DatadogApmConfig.builder().url(server.url()).build());
    assertThat(c.config().url()).isEqualTo(server.url());
    c.close();
    if (System.getenv("GROW_DATADOG_APM_URL") == null
        && System.getenv("DD_TRACE_AGENT_URL") == null) {
      try (DatadogApmClient e = DatadogApmClient.fromEnv()) {
        assertThat(e.config().url()).isNotEmpty();
      }
    }
    assertThatThrownBy(() -> DatadogApmClient.builder().url("ftp://x").build())
        .isInstanceOf(ConfigException.class);
  }
}
