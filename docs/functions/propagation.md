# Propagation, ids and log correlation

Datadog tracers (`dd-trace`) and OpenTelemetry describe the same trace differently: Datadog uses
the low 64 bits of the trace id as an unsigned decimal plus the high 64 bits as hex in the
`_dd.p.tid` tag, OpenTelemetry uses 32 hex characters. The functions on this page translate between
the two, so traces cross services that use either kind of tracer and logs link to traces.

All behaviour was checked byte for byte against Datadog's own Python tracer (ddtrace 4.16.5) used
as a reference, and end to end through the real Agent. No ddtrace code is used.

## `DatadogPropagator`

An OpenTelemetry `TextMapPropagator` for the Datadog headers. `initTracing` installs it by default
(name `datadog`, before W3C `tracecontext` and `baggage`).

| Header | Inject | Extract |
| - | - | - |
| `x-datadog-trace-id` | low 64 bits of the trace id, decimal | required, digits only |
| `x-datadog-parent-id` | span id, decimal | required, digits only |
| `x-datadog-sampling-priority` | `1` sampled, `0` not sampled | `> 0` = sampled |
| `x-datadog-tags` | `_dd.p.tid=<16 hex>` when the high 64 bits are not zero | `_dd.p.tid` restores the 128-bit id |
| `x-datadog-origin` | — | kept in the trace state |

Conflict rules with W3C `tracecontext` (same as ddtrace): Datadog wins for the trace id; a W3C
context with another trace id becomes a `terminated_context` span link; with the same trace id the
W3C parent id wins.

| Function | Description |
| - | - |
| `DatadogPropagator` | the propagator (TypeScript `new DatadogPropagator()`, Rust `DatadogPropagator::new()`, Go `datadogapm.DatadogPropagator{}`, Python `DatadogPropagator()`, Java `DatadogPropagator.getInstance()`) |
| `extractDatadogContext(carrier)` | the remote `SpanContext` in the Datadog headers, or none |
| `terminatedContext(ctx)` | the W3C context that lost during extract, if any |
| `terminatedContextLinks(ctx)` | that context as a span link (`reason=terminated_context`) for the first server span |
| `DATADOG_*_HEADER` constants | the header names (Go `DatadogTraceIDHeader`, …; Java `DatadogPropagator.TRACE_ID_HEADER`, …) |

=== "TypeScript"

    ```ts
    import { inject, extract, extractDatadogContext, terminatedContextLinks } from "@grow-codes/datadog-apm";
    import { defaultTextMapGetter } from "@opentelemetry/api";

    const headers: Record<string, string> = {};
    inject(headers); // x-datadog-*, traceparent, baggage
    const parent = extract(incoming);
    const sc = extractDatadogContext(incoming, defaultTextMapGetter);
    const links = terminatedContextLinks(parent);
    ```

=== "Rust"

    ```rust
    use std::collections::HashMap;
    use grow_datadog_apm::{extract_datadog_context, terminated_context_links, tracing};

    let mut headers = HashMap::new();
    tracing::inject(&mut headers);
    let parent = tracing::extract(&incoming);
    let sc = extract_datadog_context(&incoming);
    let links = terminated_context_links(&parent);
    ```

=== "Go"

    ```go
    headers := map[string]string{}
    datadogapm.Inject(ctx, headers)
    parent := datadogapm.Extract(ctx, incoming)
    sc, ok := datadogapm.ExtractDatadogContext(propagation.MapCarrier(incoming))
    links := datadogapm.TerminatedContextLinks(parent)
    ```

=== "Python"

    ```python
    from grow_datadog_apm import extract, extract_datadog_context, inject, terminated_context_links

    headers: dict[str, str] = {}
    inject(headers)
    parent = extract(incoming)
    sc = extract_datadog_context(incoming)
    links = terminated_context_links(parent)
    ```

=== "Java"

    ```java
    Map<String, String> headers = new HashMap<>();
    Spans.inject(headers);
    Context parent = Spans.extract(incoming);
    Optional<SpanContext> sc = DatadogPropagator.extractDatadogContext(incoming);
    List<LinkData> links = DatadogPropagator.terminatedContextLinks(parent);
    ```

## Trace and span ids

| Function | Description |
| - | - |
| `toDatadogTraceId(hex)` | `{low64Decimal, high64Hex, is128Bit}` from an OpenTelemetry trace id (1–32 hex, left-padded) |
| `fromDatadogTraceId(low64Decimal, high64Hex?)` | the 32-hex OpenTelemetry trace id |
| `toDatadogSpanId(hex)` / `fromDatadogSpanId(decimal)` | 16 hex ↔ unsigned decimal |
| `datadogTraceIdString(hex)` | the `dd.trace_id` form: 32 hex for a 128-bit id, decimal low 64 bits for a 64-bit id |
| `normalizeDatadogTraceId(value)` / `normalizeDatadogSpanId(value)` | any id Datadog returns → 32 / 16 hex (span id: none for `0` or empty) |
| `traceIdForPath(hex)` | the id for `GET /api/v2/trace/{id}`: Datadog reads an all-digit id as decimal, so such ids are sent as their decimal value |

Invalid ids, all-zero trace ids and values above 2^64 − 1 raise `VALIDATION`.
Mapping a Datadog span whose own `span_id` is missing or `0` also raises `VALIDATION`.

=== "TypeScript"

    ```ts
    const dd = toDatadogTraceId("a3ce929d0e0e47364bf92f3577b34da6");
    // { low64Decimal: "5474458728733560230", high64Hex: "a3ce929d0e0e4736", is128Bit: true }
    fromDatadogTraceId(dd.low64Decimal, dd.high64Hex); // back to the hex id
    toDatadogSpanId("00f067aa0ba902b7");              // "67667974448284343"
    ```

=== "Rust"

    ```rust
    let dd = to_datadog_trace_id("a3ce929d0e0e47364bf92f3577b34da6")?;
    let hex = from_datadog_trace_id(&dd.low64_decimal, Some(&dd.high64_hex))?;
    let span = to_datadog_span_id("00f067aa0ba902b7")?; // "67667974448284343"
    ```

=== "Go"

    ```go
    dd, err := datadogapm.ToDatadogTraceID("a3ce929d0e0e47364bf92f3577b34da6")
    hex, err := datadogapm.FromDatadogTraceID(dd.Low64Decimal, dd.High64Hex)
    span, err := datadogapm.ToDatadogSpanID("00f067aa0ba902b7") // "67667974448284343"
    ```

=== "Python"

    ```python
    dd = to_datadog_trace_id("a3ce929d0e0e47364bf92f3577b34da6")
    hex_id = from_datadog_trace_id(dd.low64_decimal, dd.high64_hex)
    span = to_datadog_span_id("00f067aa0ba902b7")  # "67667974448284343"
    ```

=== "Java"

    ```java
    DatadogIds.DatadogTraceId dd = DatadogIds.toDatadogTraceId("a3ce929d0e0e47364bf92f3577b34da6");
    String hex = DatadogIds.fromDatadogTraceId(dd.low64Decimal(), dd.high64Hex());
    String span = DatadogIds.toDatadogSpanId("00f067aa0ba902b7"); // "67667974448284343"
    ```

## Log correlation

`datadogLogFields()` returns the fields Datadog uses to link logs to traces, in the same format as
the Datadog tracers' log injection: `dd.trace_id` (32 hex for a 128-bit id, decimal for a 64-bit
id), `dd.span_id` (decimal), `dd.service`, `dd.env`, `dd.version`. Service, env and version come
from the explicit options, else the active span's resource, else the defaults that the last
`initTracing` set (`setDatadogLogDefaults` sets them by hand, for spans from another tracer
provider). Without an active span only the service fields are returned. The generic
`traceFields()` (`{trace_id, span_id}` in hex) stays available.

=== "TypeScript"

    ```ts
    logger.info({ msg: "order placed", ...datadogLogFields() });
    setDatadogLogDefaults({ service: "checkout", env: "prod", version: "1.4.0" });
    ```

=== "Rust"

    ```rust
    let fields = datadog_log_fields(DatadogLogFieldsOptions::default());
    ::tracing::info!(dd.trace_id = fields.trace_id, dd.span_id = fields.span_id, "order placed");
    ```

=== "Go"

    ```go
    slog.InfoContext(ctx, "order placed", "dd", datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{}))
    datadogapm.SetDatadogLogDefaults(datadogapm.DatadogLogFieldsOptions{Service: "checkout", Env: "prod"})
    ```

=== "Python"

    ```python
    logger.info("order placed", extra=datadog_log_fields())
    set_datadog_log_defaults(service="checkout", env="prod", version="1.4.0")
    ```

=== "Java"

    ```java
    DatadogLogCorrelation.datadogLogFields().forEach(MDC::put);
    DatadogLogCorrelation.setDatadogLogDefaults("checkout", "prod", "1.4.0");
    ```
