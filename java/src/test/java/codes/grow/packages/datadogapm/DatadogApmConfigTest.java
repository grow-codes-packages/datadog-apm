package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.core.Hooks;
import codes.grow.packages.datadogapm.core.Logger;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.core.Secret;
import codes.grow.packages.datadogapm.core.TlsOptions;
import codes.grow.packages.datadogapm.core.TransportConfig;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatadogApmConfigTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private static Map<String, String> env(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void defaults() {
    DatadogApmConfig c = DatadogApmConfig.builder().build();
    assertThat(c.url()).isEqualTo("http://127.0.0.1:8126");
    assertThat(c.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThat(c.otlpTracesEndpoint()).isEqualTo("http://127.0.0.1:4318/v1/traces");
    assertThat(c.site()).isEqualTo("datadoghq.com");
    assertThat(c.apiUrl()).isEqualTo("https://api.datadoghq.com");
    assertThat(c.hasQueryCredentials()).isFalse();
    assertThat(c.knownSecrets()).isEmpty();
    assertThat(c.otlpEndpoint()).isEmpty();
    assertThat(c.serviceName()).isEmpty();
    assertThat(c.apiKey()).isEmpty();
    assertThat(c.tags()).isEmpty();
    assertThat(DatadogApmConfig.fromEnv(Map.of()).url()).isEqualTo(DatadogApmConfig.DEFAULT_URL);
  }

  @Test
  void envPrecedence() {
    DatadogApmConfig c =
        DatadogApmConfig.fromEnv(
            env(
                P + "URL",
                "http://grow:8126",
                "DD_TRACE_AGENT_URL",
                "http://dd:8126",
                "DD_AGENT_HOST",
                "ignored",
                P + "SERVICE",
                " grow-svc ",
                "DD_SERVICE",
                "dd-svc",
                "DD_VERSION",
                "1.2.3",
                "DD_ENV",
                "prod",
                "DD_TAGS",
                "team:sdk, tier:web bad :x",
                "DD_API_KEY",
                "api-secret-1",
                "DD_APPLICATION_KEY",
                "app-secret-2",
                "DD_SITE",
                " DatadogHQ.EU ",
                "OTEL_EXPORTER_OTLP_PROTOCOL",
                "grpc",
                P + "OTLP_GRPC_ENDPOINT",
                "http://otlp:4317",
                P + "CONNECT_TIMEOUT",
                "2s",
                P + "REQUEST_TIMEOUT",
                "3s",
                P + "TOTAL_TIMEOUT",
                "9s",
                P + "MAX_RETRIES",
                "1",
                P + "PROXY",
                "http://proxy:3128",
                P + "INSECURE_SKIP_VERIFY",
                "true"));
    assertThat(c.url()).isEqualTo("http://grow:8126");
    assertThat(c.serviceName()).contains("grow-svc");
    assertThat(c.serviceVersion()).contains("1.2.3");
    assertThat(c.environment()).contains("prod");
    assertThat(c.tags()).containsExactlyInAnyOrderEntriesOf(Map.of("team", "sdk", "tier", "web"));
    assertThat(c.hasQueryCredentials()).isTrue();
    assertThat(c.knownSecrets()).containsExactly("api-secret-1", "app-secret-2");
    assertThat(c.site()).isEqualTo("datadoghq.eu");
    assertThat(c.apiUrl()).isEqualTo("https://api.datadoghq.eu");
    assertThat(c.protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    assertThat(c.otlpTracesEndpoint()).isEqualTo("http://otlp:4317");
    assertThat(c.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(c.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(c.totalTimeout()).isEqualTo(Duration.ofSeconds(9));
    assertThat(c.retry().maxRetries()).isEqualTo(1);
    assertThat(c.proxy()).contains("http://proxy:3128");
    assertThat(c.tls().insecureSkipVerify()).isTrue();
    String text = c.toString();
    assertThat(text).doesNotContain("api-secret-1").doesNotContain("app-secret-2");
    assertThat(text).contains("[REDACTED]").contains("team=sdk");
    TransportConfig q = c.queryTransportConfig();
    assertThat(q.baseUrl()).isEqualTo("https://api.datadoghq.eu");
    assertThat(q.knownSecrets()).contains("api-secret-1");
    assertThat(c.toTransportConfig().baseUrl()).isEqualTo("http://grow:8126");
  }

  @Test
  void vendorFallbacksAndEndpoints() {
    DatadogApmConfig c =
        DatadogApmConfig.fromEnv(
            env(
                "DD_TRACE_AGENT_URL",
                "http://dd:8126",
                "OTEL_SERVICE_NAME",
                "otel-svc",
                "DD_APP_KEY",
                "k",
                P + "OTLP_ENDPOINT",
                "http://generic:4318",
                "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
                "HTTP/PROTOBUF"));
    assertThat(c.url()).isEqualTo("http://dd:8126");
    assertThat(c.serviceName()).contains("otel-svc");
    assertThat(c.appKey()).isPresent();
    assertThat(c.hasQueryCredentials()).isFalse();
    assertThat(c.otlpTracesEndpoint()).isEqualTo("http://generic:4318/v1/traces");

    DatadogApmConfig host =
        DatadogApmConfig.fromEnv(
            env("DD_AGENT_HOST", "agent", "OTEL_EXPORTER_OTLP_ENDPOINT", "http://col:4318/"));
    assertThat(host.url()).isEqualTo("http://agent:8126");
    assertThat(host.otlpTracesEndpoint()).isEqualTo("http://col:4318/v1/traces");
    DatadogApmConfig grpc =
        DatadogApmConfig.builder()
            .protocol(TracingConfig.Protocol.GRPC)
            .fromEnv(EnvReader.of(env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://col:4317")))
            .build();
    assertThat(grpc.otlpTracesEndpoint()).isEqualTo("http://col:4317");
    DatadogApmConfig port = DatadogApmConfig.fromEnv(env("DD_TRACE_AGENT_PORT", "9126"));
    assertThat(port.url()).isEqualTo("http://127.0.0.1:9126");
    assertThat(port.otlpTracesEndpoint()).isEqualTo("http://127.0.0.1:4318/v1/traces");
    DatadogApmConfig v6 = DatadogApmConfig.fromEnv(env("DD_AGENT_HOST", "::1"));
    assertThat(v6.url()).isEqualTo("http://[::1]:8126");
    DatadogApmConfig traces =
        DatadogApmConfig.fromEnv(
            env("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://x:4318/custom/traces"));
    assertThat(traces.otlpTracesEndpoint()).isEqualTo("http://x:4318/custom/traces");
    DatadogApmConfig grpcFromUrl =
        DatadogApmConfig.builder()
            .url("https://agent.example:8126")
            .protocol(TracingConfig.Protocol.GRPC)
            .build();
    assertThat(grpcFromUrl.otlpTracesEndpoint()).isEqualTo("https://agent.example:4317");
  }

  @Test
  void explicitValuesWinAndTagsMerge() {
    DatadogApmConfig c =
        DatadogApmConfig.builder()
            .url("http://explicit:8126")
            .serviceName("explicit")
            .tag("team", "explicit")
            .tags(Map.of("extra", "1"))
            .apiKey(Secret.of("a"))
            .appKey(Secret.of("b"))
            .site("us5.datadoghq.com")
            .apiUrl("http://mock:1")
            .otlpEndpoint("http://o:4318/v1/traces")
            .fromEnv(
                EnvReader.of(
                    env("DD_SERVICE", "env", "DD_TAGS", "team:env,other:2", "DD_API_KEY", "c")))
            .build();
    assertThat(c.url()).isEqualTo("http://explicit:8126");
    assertThat(c.serviceName()).contains("explicit");
    assertThat(c.tags()).containsEntry("team", "explicit").containsEntry("other", "2");
    assertThat(c.apiKey().get().reveal()).isEqualTo("a");
    assertThat(c.apiUrl()).isEqualTo("http://mock:1");
    assertThat(c.otlpTracesEndpoint()).isEqualTo("http://o:4318/v1/traces");
    DatadogApmConfig copy = c.toBuilder().build();
    assertThat(copy.toString()).isEqualTo(c.toString());
    assertThat(DatadogApmConfig.parseDatadogTags(null)).isEmpty();
  }

  @Test
  void builderSetsCommonFields() {
    Hooks hooks = new Hooks() {};
    Logger logger = Logger.NOOP;
    DatadogApmConfig c =
        DatadogApmConfig.builder()
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .proxy("http://p:1")
            .header("X-A", "1")
            .userAgentSuffix("suffix")
            .hooks(hooks)
            .logger(logger)
            .serviceVersion("1")
            .environment("e")
            .apiKey("x")
            .appKey("y")
            .build();
    assertThat(c.hooks()).isSameAs(hooks);
    assertThat(c.logger()).isSameAs(logger);
    assertThat(c.headers()).containsEntry("X-A", "1");
    assertThat(c.userAgentSuffix()).contains("suffix");
    assertThat(c.toTransportConfig().userAgent()).contains("suffix");
    assertThat(c.toString()).contains("proxy=http://p:1");
    assertThat(
            DatadogApmConfig.builder().apiKey((String) null).appKey((String) null).build().apiKey())
        .isEmpty();
  }

  @Test
  void invalidValues() {
    assertThatThrownBy(() -> DatadogApmConfig.fromEnv(env(P + "OTLP_PROTOCOL", "native")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "OTLP_PROTOCOL");
    assertThatThrownBy(() -> DatadogApmConfig.fromEnv(env("DD_TRACE_AGENT_PORT", "99999")))
        .hasMessageContaining("DD_TRACE_AGENT_PORT");
    assertThatThrownBy(() -> DatadogApmConfig.fromEnv(env("DD_TRACE_AGENT_PORT", "abc")))
        .hasMessageContaining("DD_TRACE_AGENT_PORT");
    assertThatThrownBy(() -> DatadogApmConfig.fromEnv(env(P + "MAX_RETRIES", "-1")))
        .hasMessageContaining("MAX_RETRIES");
    assertThatThrownBy(() -> DatadogApmConfig.builder().url("ftp://x").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> DatadogApmConfig.builder().url("http://bad host").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> DatadogApmConfig.builder().connectTimeout(Duration.ZERO).build())
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(() -> DatadogApmConfig.builder().proxy("noscheme").build())
        .hasMessageContaining("proxy");
    assertThatThrownBy(() -> DatadogApmConfig.builder().proxy("http://bad host").build())
        .hasMessageContaining("proxy");
    assertThatThrownBy(
            () -> DatadogApmConfig.builder().protocol(TracingConfig.Protocol.NATIVE).build())
        .hasMessageContaining("protocol");
    assertThatThrownBy(() -> DatadogApmConfig.builder().otlpEndpoint("x:1").build())
        .hasMessageContaining("otlpEndpoint");
    assertThatThrownBy(() -> DatadogApmConfig.builder().apiUrl("nope").build())
        .hasMessageContaining("apiUrl");
    assertThatThrownBy(() -> DatadogApmConfig.builder().apiUrl("http://bad host").build())
        .hasMessageContaining("apiUrl");
    assertThatThrownBy(
            () ->
                DatadogApmConfig.builder()
                    .serviceName(" ")
                    .serviceVersion(" ")
                    .environment(" ")
                    .apiKey(" ")
                    .appKey(" ")
                    .build())
        .hasMessageContaining("serviceName must not be blank")
        .hasMessageContaining("appKey must not be blank");
    assertThatThrownBy(() -> DatadogApmConfig.builder().site("https://x.y").build())
        .hasMessageContaining("site");
    assertThatThrownBy(
            () ->
                DatadogApmConfig.builder()
                    .tls(TlsOptions.builder().clientCertPath(Paths.get("/nope")).build())
                    .build())
        .isInstanceOf(ConfigException.class);
    assertThat(DatadogApmConfig.otlpTracesUrl(TracingConfig.Protocol.HTTP_PROTOBUF, "::bad"))
        .isEqualTo("::bad/v1/traces");
  }
}
