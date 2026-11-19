// Service-owned: Datadog log correlation (DECISIONS.md #3). Same field format as ddtrace's log
// injection: `dd.trace_id` is 32 lower-case hex for a 128-bit id and the decimal low 64 bits for
// a 64-bit id; `dd.span_id` is decimal.

import { isSpanContextValid, trace } from "@opentelemetry/api";
import { datadogTraceIdString, toDatadogSpanId } from "./ids.js";

/** Fields returned by {@link datadogLogFields}; keys are absent when unknown. */
export interface DatadogLogFields {
  "dd.trace_id"?: string;
  "dd.span_id"?: string;
  "dd.service"?: string;
  "dd.env"?: string;
  "dd.version"?: string;
}

/** Explicit unified-service-tagging values for {@link datadogLogFields}. */
export interface DatadogLogFieldsOptions {
  service?: string;
  env?: string;
  version?: string;
}

let defaults: DatadogLogFieldsOptions = {};

/**
 * Sets the service/env/version that {@link datadogLogFields} uses when the active span carries no
 * resource (called by `initTracing`; pass `{}` to clear). @internal
 */
export function setDatadogLogDefaults(values: DatadogLogFieldsOptions): void {
  defaults = { ...values };
}

function resourceValue(span: unknown, key: string): string | undefined {
  const resource = (span as { resource?: { attributes?: Record<string, unknown> } } | undefined)
    ?.resource;
  const value = resource?.attributes?.[key];
  return typeof value === "string" && value !== "" ? value : undefined;
}

/**
 * Datadog log correlation fields for the active span, ready to merge into any structured log
 * record: `dd.trace_id`, `dd.span_id`, `dd.service`, `dd.env`, `dd.version`. Service, env and
 * version come from `options`, else the active span's resource (`service.name`,
 * `deployment.environment.name`, `service.version`), else the last `initTracing` config. Without
 * an active span only the service fields are returned.
 *
 * @example
 * logger.info({ ...datadogLogFields(), msg: "checkout done" });
 */
export function datadogLogFields(options: DatadogLogFieldsOptions = {}): DatadogLogFields {
  const out: DatadogLogFields = {};
  const span = trace.getActiveSpan();
  const ctx = span?.spanContext();
  if (ctx !== undefined && isSpanContextValid(ctx)) {
    out["dd.trace_id"] = datadogTraceIdString(ctx.traceId);
    out["dd.span_id"] = toDatadogSpanId(ctx.spanId);
  }
  const service = options.service ?? resourceValue(span, "service.name") ?? defaults.service;
  const env =
    options.env ??
    resourceValue(span, "deployment.environment.name") ??
    resourceValue(span, "deployment.environment") ??
    defaults.env;
  const version = options.version ?? resourceValue(span, "service.version") ?? defaults.version;
  if (service !== undefined) out["dd.service"] = service;
  if (env !== undefined) out["dd.env"] = env;
  if (version !== undefined) out["dd.version"] = version;
  return out;
}
