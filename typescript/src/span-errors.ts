// Service-owned: Datadog error attributes (DECISIONS.md #2). The Datadog Agent keeps an OTLP
// exception only inside the `events` JSON and does not fill `error.type` / `error.stack`, so this
// package sets `error.type`, `error.message` and `error.stack` span attributes itself, which
// Datadog Error Tracking reads.

import { SpanStatusCode, trace, type Span as OtelSpan } from "@opentelemetry/api";
import type { Span as SdkSpan, SpanProcessor } from "@opentelemetry/sdk-trace-base";
import {
  recordException as recordExceptionEvent,
  setError as setErrorStatus,
} from "./tracing/index.js";

/** `error.type` span attribute (exception class name). */
export const ATTR_ERROR_TYPE = "error.type";
/** `error.message` span attribute. */
export const ATTR_ERROR_MESSAGE = "error.message";
/** `error.stack` span attribute. */
export const ATTR_ERROR_STACK = "error.stack";

/** The Datadog error attributes for any thrown value. */
export function datadogErrorAttributes(error: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (error instanceof Error) {
    out[ATTR_ERROR_TYPE] = error.name !== "" ? error.name : error.constructor.name;
    out[ATTR_ERROR_MESSAGE] = error.message;
    if (error.stack !== undefined) out[ATTR_ERROR_STACK] = error.stack;
    return out;
  }
  out[ATTR_ERROR_TYPE] = typeof error === "string" ? "Error" : typeof error;
  out[ATTR_ERROR_MESSAGE] = typeof error === "string" ? error : safeJson(error);
  return out;
}

function safeJson(value: unknown): string {
  try {
    const json = JSON.stringify(value) as string | undefined;
    return json ?? String(value);
  } catch {
    return Object.prototype.toString.call(value);
  }
}

/**
 * Sets the Datadog error attributes (`error.type`, `error.message`, `error.stack`) for `error` on
 * `span` (default: the active span). No-op without a span.
 */
export function setErrorAttributes(error: unknown, span?: OtelSpan): void {
  const target = span ?? trace.getActiveSpan();
  target?.setAttributes(datadogErrorAttributes(error));
}

/**
 * Records an exception event on the active span **and** sets the Datadog error attributes
 * (`error.type`, `error.message`, `error.stack`). No-op without an active span.
 */
export function recordException(error: unknown): void {
  recordExceptionEvent(error);
  setErrorAttributes(error);
}

/**
 * Sets ERROR status (with the error message) on the active span **and** the Datadog error
 * attributes. No-op without an active span.
 */
export function setError(error: unknown): void {
  setErrorStatus(error);
  setErrorAttributes(error);
}

/**
 * Span processor that fills the Datadog error attributes just before a span ends, from its last
 * `exception` event (`exception.type`, `exception.message`, `exception.stacktrace`) or, for an
 * ERROR status without an event, from the status message. Attributes already set are kept. It
 * covers `withSpan`, `traced`, the HTTP adapters and plain OpenTelemetry `recordException` calls.
 * `initTracing` installs it.
 */
export class DatadogErrorSpanProcessor implements SpanProcessor {
  onStart(): void {
    // nothing to do
  }

  onEnding(span: SdkSpan): void {
    const attrs = span.attributes;
    if (attrs[ATTR_ERROR_TYPE] !== undefined || attrs[ATTR_ERROR_MESSAGE] !== undefined) return;
    const event = [...span.events].reverse().find((e) => e.name === "exception");
    const out: Record<string, string> = {};
    if (event !== undefined) {
      const a = event.attributes ?? {};
      const type = a["exception.type"];
      const message = a["exception.message"];
      const stack = a["exception.stacktrace"];
      if (typeof type === "string") out[ATTR_ERROR_TYPE] = type;
      if (typeof message === "string") out[ATTR_ERROR_MESSAGE] = message;
      if (typeof stack === "string") out[ATTR_ERROR_STACK] = stack;
    }
    if (
      span.status.code === SpanStatusCode.ERROR &&
      out[ATTR_ERROR_MESSAGE] === undefined &&
      span.status.message !== undefined &&
      span.status.message !== ""
    ) {
      out[ATTR_ERROR_MESSAGE] = span.status.message;
    }
    if (Object.keys(out).length > 0) span.setAttributes(out);
  }

  onEnd(): void {
    // nothing to do
  }

  forceFlush(): Promise<void> {
    return Promise.resolve();
  }

  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
