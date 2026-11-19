# Tracing setup

`initTracing(config)` sets up the official OpenTelemetry SDK of each language to export spans over
OTLP to the **Datadog Agent** and returns a `DatadogTracingHandle` with `tracerProvider`,
`tracer(name, version)`, `forceFlush()`, an idempotent `shutdown()` and the exporter `endpoint`.
The Agent converts the OTLP spans to Datadog spans and sends them to Datadog with its own API key,
so the application needs no Datadog credentials.

| | Default |
| - | - |
| Protocol | `http/protobuf` to `http://<agent>:4318/v1/traces`; `grpc` to `http://<agent>:4317` |
| Agent host | the host of `url` (`DD_AGENT_HOST`, `DD_TRACE_AGENT_URL`, `GROW_DATADOG_APM_URL`), else `127.0.0.1` |
| Service, version, env | `serviceName`, `serviceVersion`, `environment` from the config, else `DD_SERVICE`, `DD_VERSION`, `DD_ENV` |
| Resource attributes | `DD_TAGS` plus `resourceAttributes` (explicit keys win) |
| Propagators | `datadog`, `tracecontext`, `baggage` (Datadog headers win on conflict) |
| Sampler | `parentbased_always_on` |
| Compression | `gzip` |
| `setGlobal` | `true` |

The Agent must have its OTLP receiver on:
`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318` and/or
`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317`
([Datadog guide](https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/)).

=== "TypeScript"

    ```ts
    import { initTracing, withSpan } from "@grow-codes/datadog-apm";

    const tracing = initTracing({ serviceName: "checkout", serviceVersion: "1.4.0", environment: "prod" });
    console.log(tracing.endpoint); // http://127.0.0.1:4318/v1/traces
    await withSpan("charge", async () => {
      // ...
    });
    await tracing.shutdown();
    ```

=== "Rust"

    ```rust
    use grow_datadog_apm::{init_tracing, tracing::TracingConfig};

    let tracing = init_tracing(
        TracingConfig::new("checkout").with_service_version("1.4.0").with_environment("prod"),
    )?;
    println!("{}", tracing.endpoint());
    // ... spans via tracing.tracer("checkout", None), with_span, or the `tracing` crate layer
    tracing.shutdown().await?;
    ```

=== "Go"

    ```go
    h, err := datadogapm.InitTracing(ctx, datadogapm.TracingConfig{
        ServiceName: "checkout", ServiceVersion: "1.4.0", Environment: "prod",
    })
    if err != nil {
        return err
    }
    defer h.Shutdown(context.Background())
    fmt.Println(h.Endpoint())
    err = datadogapm.Traced(ctx, "charge", func(ctx context.Context) error { return nil })
    ```

=== "Python"

    ```python
    from grow_datadog_apm import init_tracing, with_span

    tracing = init_tracing(service_name="checkout", service_version="1.4.0", environment="prod")
    print(tracing.endpoint)
    with with_span("charge"):
        ...
    tracing.shutdown()
    ```

=== "Java"

    ```java
    DatadogTracingHandle tracing = DatadogTracing.initTracing(
        TracingConfig.builder("checkout").serviceVersion("1.4.0").environment("prod").build());
    System.out.println(tracing.endpoint());
    Traced.run("charge", () -> { /* ... */ });
    tracing.shutdown();
    ```

`DatadogApmClient.initTracing(config)` does the same with the client's configuration as the
defaults and shuts the handle down in `client.close()`.

## Configuration sources

Unset tracing fields come from `DatadogApmConfig` (explicit config passed to `initTracing`, else
the environment, see [Functions](../functions.md#service-configuration)). The shared OpenTelemetry
variables (`OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_PROPAGATORS`,
`OTEL_RESOURCE_ATTRIBUTES`, `OTEL_EXPORTER_OTLP_*_HEADERS`, `…_COMPRESSION`, `…_TIMEOUT`,
`…_CERTIFICATE`) are read as described in [Tracing](../tracing.md).

| Language | Pass Agent settings instead of the environment |
| - | - |
| TypeScript | `initTracing(config, { config: DatadogApmConfig \| options, env: map \| false })` |
| Rust | `init_tracing_with_options(config, InitTracingOptions { config, env, ignore_env })` |
| Go | `InitTracingWithOptions(ctx, cfg, InitTracingOptions{Config, Env, IgnoreEnv, SpanProcessors})` |
| Python | `init_tracing(config, apm_config=..., env=..., ignore_env=True, span_processors=[...], **fields)` |
| Java | `DatadogTracing.initTracing(config, DatadogApmConfig)` |

**Unset fields.** TypeScript (`Partial<TracingConfig>`) and Go (zero values) can leave a field
unset. Rust, Python and Java always carry the template defaults of `TracingConfig`, so there a
field equal to its template default counts as unset: protocol `http/protobuf` means "the
configured protocol" (`GROW_DATADOG_APM_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_*_PROTOCOL`), the
propagator list `tracecontext, baggage` means "the Datadog default list", and an empty service name
(Rust, Python) means `DD_SERVICE`. To force HTTP while the environment says gRPC, pass an explicit
`endpoint` or change the environment. See [Compatibility](../compatibility.md#language-differences).

The protocol `native` raises `CONFIG`: this package always exports OTLP.

## Unified service tagging

| Config | OpenTelemetry resource attribute | Datadog tag |
| - | - | - |
| `serviceName` | `service.name` | `service` (missing → the Agent uses `otlpresourcenoservicename`) |
| `serviceVersion` | `service.version` | `version` |
| `environment` | `deployment.environment.name` **and** the legacy `deployment.environment` | `env` |
| `tags` / `DD_TAGS` | each `key:value` as an attribute | copied to the span meta |

Both environment keys are set in every language; the Agent maps either to `env`.

## Propagators

The default list is `datadog, tracecontext, baggage`. `orderPropagators(names)` moves
`tracecontext` in front of `datadog` (every other name keeps its place), so the Datadog propagator
sees the W3C result and applies Datadog's own rules (verified against ddtrace):

- Datadog headers win for the trace id.
- A W3C context with a **different** trace id becomes a span link with
  `reason=terminated_context` on the first span started from the extracted context.
- The **same** trace id with a different parent: the W3C parent id wins.

Inject writes `x-datadog-trace-id` (low 64 bits, decimal), `x-datadog-parent-id` (decimal),
`x-datadog-sampling-priority` (`1`/`0`) and, for a 128-bit id, `x-datadog-tags: _dd.p.tid=<high
64 bits hex>`, plus `traceparent`/`tracestate` and `baggage`. Details in
[Propagation and ids](propagation.md).

## Error attributes

The Agent keeps an OpenTelemetry exception event only as JSON in the span meta and sets
`error.msg` from the status message; it does **not** fill `error.type` or `error.stack`. Datadog
Error Tracking needs those, so this package sets the span attributes `error.type`,
`error.message` and `error.stack`:

| Function | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| exception event + attributes, status unchanged | `recordException(err)` | `record_exception(&err)` | `RecordDatadogException(ctx, err)` | `record_exception(err)` | `DatadogSpanErrors.recordException(err)` |
| ERROR status + event + attributes | `setError(err)` | `set_error(&err)` | `SetDatadogError(ctx, err)` | `set_error(err)` | `DatadogSpanErrors.setError(err)` |
| attributes only | `setErrorAttributes(err, span?)` | `set_error_attributes(&err)` | `SetErrorAttributes(ctx, err)` | `set_error_attributes(err, span=None)` | `DatadogSpanErrors.setErrorAttributes([span,] err)` |
| the attributes as a map | `datadogErrorAttributes(err)` | `datadog_error_attributes(&err)` | `DatadogErrorAttributes(err)` | `datadog_error_attributes(err)` | `DatadogSpanErrors.datadogErrorAttributes(err)` |

- TypeScript, Python and Rust export the Datadog versions under the generic names
  `recordException` / `setError` at the package root (they replace the generic helpers of the
  shared tracing module). Rust also has the Go-style aliases `record_datadog_exception` /
  `set_datadog_error`. In Go the generic names `RecordException` / `SetError` belong to the shared
  helpers, so the Datadog versions are `RecordDatadogException` / `SetDatadogError`.
- Spans exported through `initTracing` get the attributes even when the generic helpers,
  `withSpan`, `traced` or the HTTP adapters recorded the error: the attributes are derived from the
  last `exception` event (or, for an ERROR status without an event, from the status message).
  Attributes that are already set are kept.

**`error.type` per language** (the exception type name in the language's own format):

| Language | `error.type` for a plain error | Rule |
| - | - | - |
| TypeScript | `Error`, `TypeError` | `error.name`, else the constructor name; a thrown non-`Error` gives `typeof` (`"Error"` for strings) |
| Rust | `std::io::error::Error` | `std::any::type_name::<E>()`; `error.stack` is a backtrace of the caller |
| Go | `*errors.errorString`, `*fs.PathError` | the type as `reflect` prints it (`pkgpath.Name` for named types); `error.stack` is the calling goroutine's stack |
| Python | `ValueError` | `type(error).__name__` |
| Java | `java.lang.IllegalStateException` | the canonical class name (as OpenTelemetry's `exception.type`) |

Queries in Datadog should therefore not assume one naming scheme across services written in
different languages.

### Where the attributes and links are added

| Language | Error attributes | `terminated_context` link |
| - | - | - |
| TypeScript | `DatadogErrorSpanProcessor` (before the span ends) | `DatadogSpanLinkProcessor` (span start) |
| Go | `DatadogErrorExporter` (export time) | `DatadogSpanLinkProcessor` (span start) |
| Python | `DatadogErrorExporter` (export time) | `DatadogSpanLinkProcessor` (span start) |
| Rust | `DatadogSpanExporter` (export time) | `DatadogSpanExporter` (export time) |
| Java | `DatadogSpanExporter` (export time; the link travels on the winning remote span context) | `DatadogSpanExporter` (export time) |

The template `initTracingWith` of Rust and Java accepts no extra span processors, so both use an
exporter wrapper. `initTracing` installs all of them. **Spans exported through your own tracer
provider** (not created by `initTracing`) need this wiring by hand: wrap your exporter
(`DatadogErrorExporter` / `DatadogSpanExporter`), add the processors (TypeScript, Go, Python), or
add `terminatedContextLinks(ctx)` to the span links yourself when you start a server span from an
extracted context.

## What the Agent makes of the spans

Verified against `datadog/agent:7.85.4`:

| OpenTelemetry | Datadog span |
| - | - |
| 128-bit `traceId` | `trace_id` = low 64 bits (decimal); the full id is kept in meta `otel.trace_id`; no `_dd.p.tid` for OTLP spans |
| `spanId` / `parentSpanId` | `span_id` / `parent_id` (decimal) |
| span name | `resource` (for SERVER spans with `http.route`: `GET /items/{id}`) |
| span kind | `name` (`Internal`, `server.request`, `http.server.request`, …) and meta `span.kind` |
| status ERROR + message | `error=1`, meta `error.msg` |
| events / links | meta `events` / `_dd.span_links` (JSON) |
| string / bool attributes | meta; numbers → metrics |

`datadogTraceSpanToSpan` maps such a span back to the normalized `Span`: `name` is the Datadog
`resource` (= the OpenTelemetry span name), the Datadog operation is kept as attribute
`operation_name`.
