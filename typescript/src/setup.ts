// Service-owned: `initTracing` for Datadog — OpenTelemetry SDK exporting OTLP to the Datadog
// Agent (HTTP/protobuf on 4318 by default, or gRPC on 4317), unified service tagging, the
// Datadog + W3C propagators and the Datadog span processors (API.md §2, DECISIONS.md #2, #5).

import { readFileSync } from "node:fs";
import { credentials as grpcCredentials, Metadata } from "@grpc/grpc-js";
import { OTLPTraceExporter as OtlpGrpcExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPTraceExporter as OtlpHttpExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { CompressionAlgorithm } from "@opentelemetry/otlp-exporter-base";
import type { SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ConfigError, type EnvMap } from "./core/index.js";
import {
  DatadogApmConfig,
  type DatadogApmConfigOptions,
  type DatadogOtlpProtocol,
  otlpTracesUrl,
} from "./config.js";
import { setDatadogLogDefaults } from "./correlation.js";
import { DatadogPropagator, DatadogSpanLinkProcessor } from "./propagator.js";
import { DatadogErrorSpanProcessor } from "./span-errors.js";
import {
  initTracingWith,
  type InitTracingOptions,
  type ResolvedTracingConfig,
  type TracingConfig,
  type TracingHandle,
} from "./tracing/index.js";

/** Default propagators of this package: Datadog headers, W3C trace context and baggage. */
export const DATADOG_DEFAULT_PROPAGATORS: readonly string[] = Object.freeze([
  "datadog",
  "tracecontext",
  "baggage",
]);

/**
 * {@link TracingConfig} for Datadog. Every field is optional: `serviceName`, `serviceVersion`,
 * `environment`, `endpoint`, `protocol` and extra resource attributes default to
 * {@link DatadogApmConfig} (environment: `DD_SERVICE`, `DD_VERSION`, `DD_ENV`, `DD_TAGS`,
 * `DD_AGENT_HOST`, `GROW_DATADOG_APM_*`, `OTEL_EXPORTER_OTLP_*`).
 */
export type DatadogTracingConfig = Partial<TracingConfig>;

/** Options of {@link initTracing}. */
export interface DatadogInitTracingOptions extends InitTracingOptions {
  /**
   * Agent/service settings beneath the tracing config: a {@link DatadogApmConfig} (used as is)
   * or options merged over the environment.
   */
  config?: DatadogApmConfig | DatadogApmConfigOptions;
  /** Environment for {@link DatadogApmConfig.fromEnv}; default `process.env`, `false` ignores it. */
  env?: EnvMap | false;
}

/** A running Datadog tracing setup. */
export interface DatadogTracingHandle extends TracingHandle {
  /** The OTLP traces URL spans are exported to. */
  readonly endpoint: string;
}

/**
 * Puts `tracecontext` before `datadog` so the Datadog propagator sees the W3C result and applies
 * the conflict rules (Datadog wins); every other name keeps its position.
 */
export function orderPropagators(names: readonly string[]): string[] {
  const list = names.map((n) => n.trim().toLowerCase());
  const dd = list.indexOf("datadog");
  const tc = list.indexOf("tracecontext");
  if (dd === -1 || tc === -1 || tc < dd) return list;
  list.splice(tc, 1);
  list.splice(dd, 0, "tracecontext");
  return list;
}

function readPem(path: string | undefined): Buffer | undefined {
  if (path === undefined) return undefined;
  try {
    return readFileSync(path);
  } catch {
    throw new ConfigError("tracing: cannot read a TLS file (check the configured path)");
  }
}

/**
 * Builds the OTLP span exporter for a resolved config (the `exporterFactory` of
 * `initTracingWith`): `http/protobuf` → `@opentelemetry/exporter-trace-otlp-proto`, `grpc` →
 * `@opentelemetry/exporter-trace-otlp-grpc`. Headers, compression, timeout and TLS come from the
 * config. `native` throws `ConfigError` (the package exports OTLP only).
 */
export function datadogExporterFactory(config: ResolvedTracingConfig): SpanExporter {
  if (config.protocol === "native")
    throw new ConfigError("tracing: the Datadog package exports OTLP; use http/protobuf or grpc");
  const endpoint = config.endpoint ?? new DatadogApmConfig().otlpTracesEndpoint;
  const url = otlpTracesUrl(config.protocol, endpoint);
  const compression =
    config.compression === "gzip" ? CompressionAlgorithm.GZIP : CompressionAlgorithm.NONE;
  const ca = readPem(config.tls.caCertPath) ?? config.tls.caCertPem;
  const cert = readPem(config.tls.clientCertPath);
  const key = readPem(config.tls.clientKeyPath);
  const timeoutMillis = config.batch.exportTimeoutMs;
  const insecure = config.tls.insecureSkipVerify === true;
  if (config.protocol === "grpc") {
    const metadata = new Metadata();
    for (const [k, v] of Object.entries(config.headers)) metadata.set(k.toLowerCase(), v);
    const secure = url.startsWith("https:") || ca !== undefined || cert !== undefined || insecure;
    const credentials = secure
      ? grpcCredentials.createSsl(
          ca === undefined ? null : Buffer.from(ca),
          key ?? null,
          cert ?? null,
          insecure ? { checkServerIdentity: () => undefined } : {},
        )
      : grpcCredentials.createInsecure();
    return new OtlpGrpcExporter({ url, metadata, compression, timeoutMillis, credentials });
  }
  const agent: Record<string, unknown> = {};
  if (ca !== undefined) agent.ca = ca;
  if (cert !== undefined) agent.cert = cert;
  if (key !== undefined) agent.key = key;
  if (insecure) agent.rejectUnauthorized = false;
  return new OtlpHttpExporter({
    url,
    headers: { ...config.headers },
    compression,
    timeoutMillis,
    ...(Object.keys(agent).length > 0 ? { httpAgentOptions: agent } : {}),
  });
}

function apmConfig(
  protocol: DatadogOtlpProtocol | undefined,
  options: DatadogInitTracingOptions,
): DatadogApmConfig {
  const given = options.config;
  if (given instanceof DatadogApmConfig) return given;
  const overrides: DatadogApmConfigOptions = { ...given };
  if (protocol !== undefined) overrides.protocol = protocol;
  return options.env === false
    ? new DatadogApmConfig(overrides)
    : DatadogApmConfig.fromEnv(overrides, options.env ?? process.env);
}

/**
 * Sets up OpenTelemetry tracing that exports to the Datadog Agent (SPEC §5):
 *
 * - OTLP `http/protobuf` to `http://<agent>:4318/v1/traces` by default, or `grpc` to
 *   `http://<agent>:4317` (the Agent needs its OTLP receiver enabled);
 * - unified service tagging: `serviceName` / `serviceVersion` / `environment` → resource
 *   `service.name` / `service.version` / `deployment.environment.name` (Datadog `service`,
 *   `version`, `env`), plus `DD_TAGS` as resource attributes;
 * - propagators `datadog`, `tracecontext`, `baggage` by default (Datadog headers win on conflict);
 * - span processors that set the Datadog `error.*` attributes and the terminated-context link.
 *
 * Unset fields come from {@link DatadogApmConfig} (environment by default).
 *
 * @example
 * const tracing = initTracing({ serviceName: "checkout", environment: "prod" });
 * await withSpan("charge", async () => { ... });
 * await tracing.shutdown();
 */
export function initTracing(
  config: DatadogTracingConfig = {},
  options: DatadogInitTracingOptions = {},
): DatadogTracingHandle {
  const { config: _cfg, env: _env, ...initOptions } = options;
  if (config.protocol === "native")
    throw new ConfigError("tracing: the Datadog package exports OTLP; use http/protobuf or grpc");
  const apm = apmConfig(config.protocol, options);
  const protocol = config.protocol ?? apm.protocol;
  const endpoint =
    config.endpoint !== undefined
      ? otlpTracesUrl(protocol, config.endpoint)
      : protocol === apm.protocol
        ? apm.otlpTracesEndpoint
        : new DatadogApmConfig({ url: apm.url, protocol }).otlpTracesEndpoint;
  const effective: TracingConfig = {
    ...config,
    serviceName: config.serviceName ?? apm.serviceName ?? "",
    protocol,
    endpoint,
    propagators: orderPropagators(config.propagators ?? DATADOG_DEFAULT_PROPAGATORS),
    resourceAttributes: { ...apm.tags, ...config.resourceAttributes },
  };
  const version = config.serviceVersion ?? apm.serviceVersion;
  if (version !== undefined) effective.serviceVersion = version;
  const environment = config.environment ?? apm.environment;
  if (environment !== undefined) effective.environment = environment;
  if (config.tls === undefined && Object.keys(apm.tls).length > 0) effective.tls = { ...apm.tls };

  const handle = initTracingWith(effective, datadogExporterFactory, {
    ...initOptions,
    propagators: { datadog: () => new DatadogPropagator(), ...initOptions.propagators },
    spanProcessors: [
      new DatadogErrorSpanProcessor(),
      new DatadogSpanLinkProcessor(),
      ...(initOptions.spanProcessors ?? []),
    ],
  });
  setDatadogLogDefaults({
    service: handle.config.serviceName,
    ...(environment === undefined ? {} : { env: environment }),
    ...(version === undefined ? {} : { version }),
  });
  return { ...handle, endpoint };
}
