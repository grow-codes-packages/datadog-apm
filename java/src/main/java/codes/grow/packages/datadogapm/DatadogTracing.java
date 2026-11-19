package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.tracing.OtlpExporters;
import codes.grow.packages.datadogapm.tracing.SpanExporterFactory;
import codes.grow.packages.datadogapm.tracing.Tracing;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import codes.grow.packages.datadogapm.tracing.TracingHandle;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code initTracing} for Datadog: the OpenTelemetry SDK exporting OTLP to the Datadog Agent
 * (HTTP/protobuf on 4318 by default, or gRPC on 4317), unified service tagging, the Datadog + W3C
 * propagators, the Datadog error attributes and the terminated-context links (API.md section 2,
 * DECISIONS.md #2, #5).
 *
 * <pre>{@code
 * try (DatadogTracingHandle tracing = DatadogTracing.initTracing(
 *     TracingConfig.builder("checkout").environment("prod").build())) {
 *   Spans.withSpan("charge", () -> charge(order));
 * }
 * }</pre>
 */
public final class DatadogTracing {
  /** Default propagators of this package: Datadog headers, W3C trace context and baggage. */
  public static final List<String> DATADOG_DEFAULT_PROPAGATORS =
      List.of(DatadogPropagator.NAME, "tracecontext", "baggage");

  private DatadogTracing() {}

  /**
   * Puts {@code tracecontext} before {@code datadog} so the Datadog propagator sees the W3C result
   * and applies the conflict rules (Datadog wins); every other name keeps its position. Names are
   * trimmed and lower-cased.
   *
   * @param names propagator names
   * @return the ordered names
   */
  public static List<String> orderPropagators(List<String> names) {
    List<String> list = new ArrayList<>();
    for (String n : names) {
      list.add(n.trim().toLowerCase(Locale.ROOT));
    }
    int dd = list.indexOf(DatadogPropagator.NAME);
    int tc = list.indexOf("tracecontext");
    if (dd == -1 || tc == -1 || tc < dd) {
      return list;
    }
    list.remove(tc);
    list.add(dd, "tracecontext");
    return list;
  }

  /**
   * The exporter factory of this package (see {@link #createExporter(TracingConfig)}).
   *
   * @return factory
   */
  public static SpanExporterFactory exporterFactory() {
    return DatadogTracing::createExporter;
  }

  /**
   * Creates the span exporter: OTLP {@code http/protobuf} or {@code grpc} to the config's endpoint
   * (default: the local Agent, {@code http://127.0.0.1:4318/v1/traces} or {@code
   * http://127.0.0.1:4317}), wrapped in a {@link DatadogSpanExporter}.
   *
   * @param config tracing config
   * @return exporter
   * @throws ConfigException for the {@code native} protocol (the package exports OTLP only)
   */
  public static SpanExporter createExporter(TracingConfig config) {
    TracingConfig.Protocol protocol = config.protocol();
    if (protocol == TracingConfig.Protocol.NATIVE) {
      throw nativeError();
    }
    String endpoint =
        config
            .endpoint()
            .orElseGet(
                () -> DatadogApmConfig.builder().protocol(protocol).build().otlpTracesEndpoint());
    TracingConfig resolved =
        config.toBuilder().endpoint(DatadogApmConfig.otlpTracesUrl(protocol, endpoint)).build();
    return new DatadogSpanExporter(OtlpExporters.create(resolved));
  }

  private static ConfigException nativeError() {
    return new ConfigException(
        "tracing: the Datadog package exports OTLP; use http/protobuf or grpc");
  }

  /**
   * A tracing config builder prefilled from a {@link DatadogApmConfig}: service name, version,
   * environment, OTLP endpoint and protocol, {@code DD_TAGS} as resource attributes, TLS and the
   * Datadog default propagators.
   *
   * @param apm Agent/service settings
   * @return builder
   * @throws ConfigException when {@code apm} has no service name ({@code DD_SERVICE})
   */
  public static TracingConfig.Builder tracingConfig(DatadogApmConfig apm) {
    String service =
        apm.serviceName()
            .orElseThrow(
                () ->
                    new ConfigException(
                        "tracing.serviceName is required (set DD_SERVICE or"
                            + " GROW_DATADOG_APM_SERVICE)"));
    TracingConfig.Builder b =
        TracingConfig.builder(service)
            .serviceVersion(apm.serviceVersion().orElse(null))
            .environment(apm.environment().orElse(null))
            .protocol(apm.protocol())
            .endpoint(apm.otlpTracesEndpoint())
            .resourceAttributes(apm.tags())
            .propagators(DATADOG_DEFAULT_PROPAGATORS);
    if (apm.tls().isCustom()) {
      b.tls(apm.tls());
    }
    return b;
  }

  /**
   * Sets up tracing entirely from the environment ({@link DatadogApmConfig#fromEnv()}: {@code
   * DD_SERVICE}, {@code DD_ENV}, {@code DD_VERSION}, {@code DD_TAGS}, {@code DD_AGENT_HOST}, {@code
   * GROW_DATADOG_APM_*}, {@code OTEL_EXPORTER_OTLP_*}).
   *
   * @return handle
   * @throws ConfigException when the environment is invalid or names no service
   */
  public static DatadogTracingHandle initTracing() {
    DatadogApmConfig apm = DatadogApmConfig.fromEnv();
    return initTracing(tracingConfig(apm).build(), apm);
  }

  /**
   * Sets up OpenTelemetry tracing that exports to the Datadog Agent (SPEC section 5), with the
   * environment ({@link DatadogApmConfig}, protocol taken from {@code config}) as defaults for the
   * fields {@code config} leaves unset.
   *
   * <ul>
   *   <li>OTLP {@code http/protobuf} to {@code http://<agent>:4318/v1/traces} by default, or {@code
   *       grpc} to {@code http://<agent>:4317} (the Agent needs its OTLP receiver enabled);
   *   <li>unified service tagging: service name / version / environment become resource {@code
   *       service.name} / {@code service.version} / {@code deployment.environment.name} (Datadog
   *       {@code service}, {@code version}, {@code env}), plus {@code DD_TAGS} as resource
   *       attributes;
   *   <li>propagators {@code datadog}, {@code tracecontext}, {@code baggage} when {@code config}
   *       keeps the template default list (Datadog headers win on conflict);
   *   <li>the Datadog error attributes and the terminated-context link ({@link
   *       DatadogSpanExporter}).
   * </ul>
   *
   * @param config tracing config
   * @return handle
   * @throws ConfigException for the {@code native} protocol or an invalid environment
   */
  public static DatadogTracingHandle initTracing(TracingConfig config) {
    if (config.protocol() == TracingConfig.Protocol.NATIVE) {
      throw nativeError();
    }
    DatadogApmConfig apm =
        DatadogApmConfig.builder().protocol(config.protocol()).fromEnv(EnvReader.system()).build();
    return initTracing(config, apm);
  }

  /**
   * {@link #initTracing(TracingConfig)} with explicit Agent/service settings instead of the
   * environment.
   *
   * @param config tracing config
   * @param apm Agent/service settings (Agent host, OTLP endpoint, version, env, tags, TLS)
   * @return handle
   * @throws ConfigException for the {@code native} protocol
   */
  public static DatadogTracingHandle initTracing(TracingConfig config, DatadogApmConfig apm) {
    TracingConfig.Protocol protocol = config.protocol();
    if (protocol == TracingConfig.Protocol.NATIVE) {
      throw nativeError();
    }
    String endpoint;
    if (config.endpoint().isPresent()) {
      endpoint = DatadogApmConfig.otlpTracesUrl(protocol, config.endpoint().get());
    } else if (protocol == apm.protocol()) {
      endpoint = apm.otlpTracesEndpoint();
    } else {
      endpoint = apm.toBuilder().otlpEndpoint(null).protocol(protocol).build().otlpTracesEndpoint();
    }
    String version = config.serviceVersion().or(apm::serviceVersion).orElse(null);
    String environment = config.environment().or(apm::environment).orElse(null);
    List<String> propagators =
        config.propagators().equals(TracingConfig.DEFAULT_PROPAGATORS)
            ? DATADOG_DEFAULT_PROPAGATORS
            : config.propagators();
    Map<String, Object> attrs = new LinkedHashMap<>(apm.tags());
    attrs.putAll(config.resourceAttributes());
    if (environment != null) {
      attrs.putIfAbsent("deployment.environment.name", environment);
    }
    TracingConfig.Builder b =
        config.toBuilder()
            .endpoint(endpoint)
            .serviceVersion(version)
            .environment(environment)
            .propagators(orderPropagators(propagators))
            .resourceAttributes(attrs);
    if (config.tls().isEmpty() && apm.tls().isCustom()) {
      b.tls(apm.tls());
    }
    TracingConfig effective = b.build();
    TracingHandle handle =
        Tracing.initWith(
            effective,
            exporterFactory(),
            Map.of(DatadogPropagator.NAME, DatadogPropagator.getInstance()));
    DatadogLogCorrelation.setDefaults(effective.serviceName(), environment, version);
    return new DatadogTracingHandle(handle, endpoint);
  }
}
