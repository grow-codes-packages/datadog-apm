---
name: datadog-apm-sdk
description: Add and use the Grow Codes SDK for Datadog APM (`datadog-apm`) in TypeScript, Rust, Go, Python or Java projects. Use when code must send OpenTelemetry traces to a Datadog Agent, propagate x-datadog-* headers, correlate logs with dd.trace_id/dd.span_id, set Datadog error attributes, convert 64/128-bit trace ids, or read spans back from the Datadog Spans API - installing @grow-codes/datadog-apm, the grow-datadog-apm crate or PyPI package, the Go module github.com/grow-codes-packages/datadog-apm/go or Maven codes.grow.packages:datadog-apm, configuring it with DD_* / GROW_DATADOG_APM_* variables, handling DatadogApmError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# Datadog APM SDK (Grow Codes)

One API for Datadog APM in five languages (category: tracing). It exports **OpenTelemetry** spans
over OTLP to the **Datadog Agent** (no `dd-trace`), and optionally queries the **Datadog API** for
spans. Pick the package for the project's language, configure it from the environment, and handle
errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/datadog-apm` | `import { DatadogApmClient, initTracing } from "@grow-codes/datadog-apm"` |
| Rust | `cargo add grow-datadog-apm` (add `tokio` with `macros,rt-multi-thread`) | `use grow_datadog_apm::{init_tracing, DatadogApmClient};` |
| Go | `go get github.com/grow-codes-packages/datadog-apm/go` | `datadogapm "github.com/grow-codes-packages/datadog-apm/go"` |
| Python | `pip install grow-datadog-apm` (or `uv add grow-datadog-apm`) | `from grow_datadog_apm import DatadogApmClient, init_tracing` |
| Java | Maven `codes.grow.packages:datadog-apm` (Gradle `implementation("codes.grow.packages:datadog-apm:<version>")`) | `import codes.grow.packages.datadogapm.DatadogTracing;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

The application needs **no Datadog key** to send traces: it talks to the Agent, which holds its own
`DD_API_KEY`. The Agent must have its OTLP receiver on
(`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318` and/or `..._GRPC_ENDPOINT=0.0.0.0:4317`).

```sh
export DD_AGENT_HOST=datadog-agent DD_SERVICE=checkout DD_ENV=prod DD_VERSION=1.4.0
# only for DatadogSpansQuery (from your secret store, never committed):
export DD_API_KEY=... DD_APP_KEY=...   # app key needs the apm_read scope
export DD_SITE=datadoghq.eu            # default datadoghq.com
```

Precedence: explicit config value > `GROW_DATADOG_APM_*` > vendor variables > default.

| Setting | `GROW_DATADOG_APM_*` | Vendor | Default |
| - | - | - | - |
| Agent URL (`/info`) | `URL` | `DD_TRACE_AGENT_URL`, `DD_AGENT_HOST` + `DD_TRACE_AGENT_PORT` | `http://127.0.0.1:8126` |
| OTLP endpoint | `OTLP_HTTP_ENDPOINT` / `OTLP_GRPC_ENDPOINT`, `OTLP_ENDPOINT` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` | Agent host `:4318` / `:4317` |
| Protocol | `OTLP_PROTOCOL` | `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` |
| service / version / env / tags | `SERVICE`, `VERSION`, `ENV`, `TAGS` | `DD_SERVICE` (`OTEL_SERVICE_NAME`), `DD_VERSION`, `DD_ENV`, `DD_TAGS` | — |
| API key / app key | `API_KEY`, `APP_KEY` | `DD_API_KEY`, `DD_APP_KEY` (`DD_APPLICATION_KEY`) | — |
| site / API URL | `SITE`, `API_URL` | `DD_SITE` | `datadoghq.com`, `https://api.<site>` |

Transport: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`, `1500`),
`…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Environment proxies
(`HTTPS_PROXY`) are ignored unless `proxy` is set.

## 3. Use

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### Tracing (most projects need only this)

Call `initTracing` once at startup and `shutdown()` on exit; then use the shared helpers
(`withSpan`, `traced`, `inject`/`extract`, HTTP adapters). Defaults: OTLP HTTP to
`http://<agent>:4318/v1/traces`, propagators `datadog,tracecontext,baggage`, `env` from
`DD_ENV` set as `deployment.environment.name` (+ legacy `deployment.environment`).

| Language | Start | Error with Datadog attributes | Log fields |
| - | - | - | - |
| TypeScript | `const t = initTracing({ serviceName: "checkout" })` | `setError(err)` / `recordException(err)` | `datadogLogFields()` |
| Rust | `let t = init_tracing(TracingConfig::new("checkout"))?;` (inside tokio) | `set_error(&err)` / `record_exception(&err)` | `datadog_log_fields(Default::default())` |
| Go | `t, err := datadogapm.InitTracing(ctx, datadogapm.TracingConfig{ServiceName: "checkout"})` | `datadogapm.SetDatadogError(ctx, err)` / `RecordDatadogException(ctx, err)` | `datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{})` |
| Python | `t = init_tracing(service_name="checkout")` | `set_error(err)` / `record_exception(err)` | `datadog_log_fields()` |
| Java | `var t = DatadogTracing.initTracing(TracingConfig.builder("checkout").build());` | `DatadogSpanErrors.setError(e)` / `recordException(e)` | `DatadogLogCorrelation.datadogLogFields()` |

- In Go use `SetDatadogError` / `RecordDatadogException`; the generic `SetError` /
  `RecordException` do not set the attributes immediately (the `initTracing` exporter still adds
  them from the exception event).
- Merge the log fields into every structured log record (MDC, `extra=`, slog attrs) so Datadog
  links logs to traces.
- Ids: `toDatadogTraceId(hex)` → `{low64Decimal, high64Hex, is128Bit}`,
  `fromDatadogTraceId(low, high)`, `toDatadogSpanId` / `fromDatadogSpanId`.
- In Rust, Python and Java a `TracingConfig` field equal to its template default
  (`http/protobuf`, `tracecontext,baggage`) means "use the configured / Datadog default".
- If you build your own tracer provider instead of `initTracing`, add the Datadog pieces yourself
  (see `docs/functions/tracing-setup.md` "Where the attributes and links are added").

### Client and query

```text
client = DatadogApmClient.fromEnv()        # TS/Java; Rust from_env()?; Go NewClientFromEnv(); Python from_env()
client.health()                            # Agent /info; never throws for an unreachable Agent
client.serverVersion()                     # "7.85.4"
client.initTracing(config)                 # shut down by client.close()
client.spans                               # DatadogSpansQuery; CONFIG without API + app key
client.close()                             # idempotent; later calls -> CANCELLED
```

`DatadogSpansQuery`: `searchSpans({query, from, to, limit})` (lazy cursor iterator),
`aggregateSpans(compute, groupBy)`, `getTrace(id, {preview})`, `searchTraces`, `listServices`,
`listOperations(service)`, `waitForTrace(id, {timeout})` (2 s interval), `ping()` (validates keys),
`getDependencies` → `UNSUPPORTED`. It sees only **indexed** spans after an indexing delay, so poll
with `waitForTrace` instead of sleeping.

### Errors

Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
`RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
`httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
`errors.Is(err, datadogapm.ErrNotFound)`. All I/O is retried with jitter on 429/502/503/504 and
connection errors; a 429 from Datadog waits for `Retry-After` / `X-RateLimit-Reset`.

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real Agent in unit tests (in-memory
span exporter and trace query client): TypeScript `@grow-codes/datadog-apm/testing`, Rust feature
`testing`, Go package `testkit`, Python `grow_datadog_apm.testing`, Java
`codes.grow.packages.datadogapm.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against
the real `datadog/agent:7.85.4` forwarding to `datadog/fakeintake:v79` (spans read back through the
test-only `trace-reader` sidecar). The Datadog API query functions are unit-tested only; see
`docs/compatibility.md`.
