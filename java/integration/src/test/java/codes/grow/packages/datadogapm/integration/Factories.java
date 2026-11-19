package codes.grow.packages.datadogapm.integration;

import codes.grow.packages.datadogapm.DatadogApmClient;
import codes.grow.packages.datadogapm.DatadogApmConfig;
import codes.grow.packages.datadogapm.DatadogTracing;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.testing.ConformanceRunner;
import codes.grow.packages.datadogapm.testing.tracing.TracingTarget;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Conformance targets of this service: the Agent client for {@code core} and, for {@code tracing},
 * this package's {@code initTracing} (OTLP to the real Agent) with the trace-reader sidecar as the
 * query side ({@code query} and {@code services} are served from what the Agent forwarded to
 * fakeintake, because the Datadog query API is SaaS-only; docs/compatibility.md).
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"version", "query", "services"};

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    Map<String, String> vars = new HashMap<>(TraceReader.env());
    String service =
        "grow-java-conf-"
            + vars.getOrDefault("GROW_TEST_RUN_ID", "local")
            + "-"
            + Integer.toHexString(new SecureRandom().nextInt(1 << 24));
    runner.serviceName(service);
    runner.target("core", scenario -> DatadogApmClient.builder().fromEnv(env).build());
    runner.target(
        "tracing",
        scenario ->
            TracingTarget.of(
                DatadogTracing.initTracing(
                        TracingConfig.builder(service)
                            .setGlobal(false)
                            .sampler(TracingConfig.SamplerType.ALWAYS_ON)
                            .build(),
                        DatadogApmConfig.builder().fromEnv(env).build())
                    .handle(),
                new TraceReader(vars)));
  }
}
