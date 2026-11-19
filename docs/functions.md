# Functions

The Datadog APM API of this SDK. Every function exists in all five languages with the same
behaviour, named per language convention (`searchSpans` in TypeScript and Java, `search_spans` in
Python and Rust, `SearchSpans` in Go). Each module page shows every function with an example per
language.

| Module | Page | What it does |
| - | - | - |
| Client | [Client functions](functions/client.md) | Agent health, ping, version, `/info`, tracing and query access, close |
| Tracing setup | [Tracing setup](functions/tracing-setup.md) | `initTracing` to the Datadog Agent over OTLP, unified service tagging, propagator order, error attributes |
| Propagation, ids and logs | [Propagation and ids](functions/propagation.md) | `DatadogPropagator`, 64/128-bit id helpers, `datadogLogFields` |
| Query | [Spans query](functions/query.md) | `DatadogSpansQuery`: spans search and aggregation, traces, services, operations (Datadog API) |

The shared tracing helpers (`withSpan`, `traced`, `inject`/`extract`, `traceFields`, framework
adapters) are described in [Tracing](tracing.md); the shared client surface in [Client](client.md).

## Overview

"Docker" means the function is called against the real Datadog Agent in the integration suite;
"unit" means it is covered by unit tests only, because no container can serve the Datadog API (see
[Compatibility](compatibility.md)).

| Function | Description | Tested |
| - | - | - |
| `health()` | Agent liveness (`GET /info`) | Docker (`core.health`) |
| `ping()` | round trip to the Agent, latency | Docker (`core.health`) |
| `serverVersion()` | Agent version from `/info` | Docker |
| `agentInfo()` | the Agent's `/info` document | Docker |
| `initTracing(config)` (client) | tracing with the client's settings as defaults; shut down by `close()` | Docker |
| `spans` (client) | the `DatadogSpansQuery`; `CONFIG` without `apiKey` + `appKey` | unit |
| `close()` | idempotent; flushes and stops the tracing started through the client | Docker |
| `initTracing(config)` | OpenTelemetry to the Agent over OTLP HTTP or gRPC | Docker (`tracing.tree`, `tracing.services`) |
| `orderPropagators(names)` | `tracecontext` before `datadog` (Datadog wins on conflict) | unit |
| `datadogExporterFactory(config)` | the OTLP exporter used by `initTracing` | Docker |
| `recordException(err)` / `setError(err)` / `setErrorAttributes(err)` / `datadogErrorAttributes(err)` | `error.type`, `error.message`, `error.stack` for Error Tracking | Docker (error attributes read back from the Agent payload) |
| `DatadogPropagator` | `x-datadog-*` inject/extract with the ddtrace conflict rules | Docker (propagation through the Agent) |
| `extractDatadogContext(carrier)` | remote span context from Datadog headers | unit |
| `terminatedContext(ctx)` / `terminatedContextLinks(ctx)` | the losing W3C context as a span link | Docker |
| `toDatadogTraceId` / `fromDatadogTraceId` / `toDatadogSpanId` / `fromDatadogSpanId` | id conversion | Docker (ids checked in the Agent payload) |
| `datadogTraceIdString` / `normalizeDatadogTraceId` / `normalizeDatadogSpanId` / `traceIdForPath` | id formats Datadog uses | unit |
| `datadogLogFields()` / `setDatadogLogDefaults()` | log correlation fields in the ddtrace format | Docker |
| `datadogTraceSpanToSpan` / `datadogSearchSpanToSpan` | Datadog span → normalized `Span` | Docker (trace-reader spans) + unit |
| `searchSpans(opts)` / `searchSpansPage(opts)` | indexed spans, cursor paging | unit |
| `aggregateSpans(compute, groupBy, opts)` | span analytics | unit |
| `getTrace(traceId, {preview})` | a trace (spans search or preview trace-by-id API) | unit |
| `searchTraces(query)` | trace summaries | unit |
| `listServices()` / `listOperations(service)` | services / resource names | unit |
| `waitForTrace(traceId, opts)` | poll until searchable (2 s interval) | unit |
| `getDependencies(range)` | always `UNSUPPORTED` | unit |
| `ping()` (query) | validates API + application key | unit |
| `quoteQueryValue(v)` / `traceSearchQueryString(q)` | Datadog query syntax helpers | unit |

## Service configuration

Precedence for every setting: **explicit value > `GROW_DATADOG_APM_*` > vendor variables (in the
order listed) > default**.

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url` (Agent trace intake, port 8126) | `http://127.0.0.1:8126` | `GROW_DATADOG_APM_URL`, `DD_TRACE_AGENT_URL`, `http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}` |
| `otlpEndpoint` (Agent OTLP receiver) | host of `url` + `:4318` (HTTP, `/v1/traces` appended) / `:4317` (gRPC) | `GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT` / `GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT` (by protocol), `GROW_DATADOG_APM_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `protocol` | `http/protobuf` | `GROW_DATADOG_APM_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` |
| `serviceName` | none | `GROW_DATADOG_APM_SERVICE`, `DD_SERVICE`, `OTEL_SERVICE_NAME` |
| `serviceVersion` | none | `GROW_DATADOG_APM_VERSION`, `DD_VERSION` |
| `environment` | none | `GROW_DATADOG_APM_ENV`, `DD_ENV` |
| `tags` | none | `GROW_DATADOG_APM_TAGS`, `DD_TAGS` |
| `apiKey` (secret) | none | `GROW_DATADOG_APM_API_KEY`, `DD_API_KEY` |
| `appKey` (secret) | none | `GROW_DATADOG_APM_APP_KEY`, `DD_APP_KEY`, `DD_APPLICATION_KEY` |
| `site` | `datadoghq.com` | `GROW_DATADOG_APM_SITE`, `DD_SITE` |
| `apiUrl` | `https://api.<site>` | `GROW_DATADOG_APM_API_URL` |

The common transport settings (`GROW_DATADOG_APM_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`,
`…_TOTAL_TIMEOUT`, `…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`,
`…_INSECURE_SKIP_VERIFY`, `…_PROXY`) are described in [Configuration](configuration.md). The
Agent needs no credentials; `apiKey` and `appKey` (with the `apm_read` scope) are needed only by
`DatadogSpansQuery` and are always redacted.
