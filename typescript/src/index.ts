// Service-owned entry point: sdk-kit generates it once; add service exports below.

export * from "./core/index.js";
export * from "./config.js";
export * from "./client.js";
export * from "./tracing/index.js";
export * from "./sites.js";
export * from "./ids.js";
export * from "./json.js";
export * from "./mapping.js";
export * from "./propagator.js";
export * from "./correlation.js";
export * from "./setup.js";
export * from "./spans.js";
// These explicit exports replace the generic helpers of the same name from ./tracing: they also
// set the Datadog `error.type`, `error.message` and `error.stack` span attributes.
export {
  ATTR_ERROR_MESSAGE,
  ATTR_ERROR_STACK,
  ATTR_ERROR_TYPE,
  DatadogErrorSpanProcessor,
  datadogErrorAttributes,
  recordException,
  setError,
  setErrorAttributes,
} from "./span-errors.js";
