# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.88, edition 2021, tokio | stable, 1.88 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## Datadog versions

Datadog APM is a SaaS product; there is no self-hostable Datadog backend. The packages talk to:

| Part | Tested against | How |
| - | - | - |
| Datadog Agent (OTLP receiver 4317/4318, trace intake `/info` on 8126) | `datadog/agent:7.85.4` (pinned by digest) | Docker suite, all five languages |
| Datadog backend (what the Agent sends) | `datadog/fakeintake:v79`, Datadog's own test intake | Docker suite; the test-only `trace-reader` sidecar decodes the Agent's zstd + protobuf trace payloads so tests can assert the converted spans |
| Datadog API (`https://api.<site>`): Spans API v2, APM services v2, key validation, preview trace-by-id | — | **unit tests only**, on examples from Datadog's published OpenAPI spec |

The Grow Codes stack catalog entry `datadog-apm` is cloud-managed and pins no image, so the Agent
and fakeintake tags were chosen for this repository. Other Agent 7.x releases with the OTLP
receiver are expected to work. The Agent needs
`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT` and/or `..._GRPC_ENDPOINT`; OTLP logs and the
agentless OTLP intake are not used.

!!! warning "Live Datadog checks waiting for credentials"
    The query functions below have not yet run against a real Datadog organisation. The
    [live checks](#live-checks) send spans through a real Agent to a Datadog sandbox org and read
    them back through the Spans API; they run as soon as the owner adds the sandbox credentials
    (an API key and an application key with `apm_read`). Until a green live run, treat the query
    module as unverified against the live service.

## Functions covered by unit tests only

Every Agent-side function is called against the real Agent in the Docker suite (client, OTLP HTTP
and gRPC export, unified service tags, Datadog propagation in both directions, error attributes,
ids, log fields, framework adapters, and the conformance scenarios `core.health`, `tracing.tree`
and `tracing.services`, whose query side is a test-only `TraceQueryClient` over `trace-reader`).

| Function | Languages | Reason |
| - | - | - |
| `DatadogSpansQuery.searchSpans`, `searchSpansPage` | all | Datadog API (SaaS); no emulator, fakeintake does not implement it |
| `aggregateSpans` | all | same |
| `getTrace` (search and `preview`) | all | same |
| `searchTraces`, `listServices`, `listOperations`, `waitForTrace` | all | same |
| `DatadogSpansQuery.ping` (`/api/v2/validate_keys`) | all | same |
| `getDependencies` | all | always `UNSUPPORTED`: Datadog has no public dependency API |
| `orderPropagators`, `extractDatadogContext`, `normalizeDatadog*Id`, `traceIdForPath`, `quoteQueryValue`, `traceSearchQueryString` | all | pure functions; exercised indirectly by the Docker suite |

The unit tests use Datadog's OpenAPI examples (marked `*.docs_example.json` in the fixtures) for the
request and response shapes, the error envelopes (`{"errors": ["..."]}` and JSON:API), cursor
paging, 429 with `Retry-After` / `X-RateLimit-Reset`, and 401/403 key errors.

## Live checks

`live/` (service-owned) holds a small Python suite that runs the SaaS-only query functions against
a real Datadog organisation, using the package built from `./python`. It starts tracing with
`init_tracing` (OTLP to a real `datadog/agent:7.85.4`, the same build as the Docker suite, with
the real API key and site), sends a two-span trace with a unique marker attribute
(`grow.live.marker`) and reads it back: `wait_for_trace` (up to 5 minutes for indexing),
`get_trace` (span names, ids, parent link, service, error status), `search_spans` with a cursor
(1 per page), `search_traces`, `aggregate_spans` (count by `resource_name`), `list_operations`,
`list_services` (polled up to 3 minutes), `spans.ping` with the real keys and with a wrong
application key (`AUTHENTICATION`), Agent `health` / `ping` / `server_version`, and
`get_dependencies` (`UNSUPPORTED`). Two checks are warnings only (reported, never failing the
run): `get_trace(preview=True)` (the unstable trace-by-id API) and whether the spans returned by
the search carry the full 128-bit trace id.

Both spans are marked as errors, so Datadog's default `Error Default` retention filter indexes
them. If the run reports "ingested but not indexed", add a retention filter that keeps 100 % of
`service:grow-sdk-live-python` (env `live`).

| Variable | Secret / variable in GitHub | Required | What |
| - | - | - | - |
| `DATADOG_API_KEY` | organisation secret | yes | API key of the sandbox organisation (also given to the Agent) |
| `DATADOG_APP_KEY` | organisation secret | yes | application key with `apm_read` |
| `DATADOG_SITE` | organisation variable | no | Datadog site, default `datadoghq.eu` |
| `LIVE_TIMEOUT_S` | — | no | how long to wait for the trace to become searchable (default 300) |
| `GROW_DATADOG_APM_URL`, `GROW_DATADOG_APM_OTLP_ENDPOINT` | — | no | use an Agent that is already running |

- **CI**: `.github/workflows/live.yml` runs on `workflow_dispatch` and every Monday, with the Agent
  as a job service container. Without the two secrets it ends green with a notice ("Live checks
  skipped") and starts no Agent.
- **Locally**: `DATADOG_API_KEY=… DATADOG_APP_KEY=… make -C live live` (creates `live/.venv`,
  installs `./python`). Unless `GROW_DATADOG_APM_URL` or `DD_AGENT_HOST` points at a running
  Agent, `live/run.sh` starts a `datadog/agent` container (ports on 127.0.0.1 only) and always
  removes it. Without the keys it prints `skipped: no credentials` and exits 0. `make -C live
  lint` runs ruff on the suite.

## Skipped conformance scenarios

| Scenario | Reason |
| - | - |
| — | none: `core.health`, `tracing.tree` and `tracing.services` pass in all five languages |

## Datadog behaviour worth knowing

| Behaviour | Effect on the SDK |
| - | - |
| The Agent turns a 128-bit OTLP trace id into `trace_id` = low 64 bits (decimal) and keeps the full id in meta `otel.trace_id`; it adds no `_dd.p.tid` to OTLP spans | `datadogTraceSpanToSpan` prefers `otel.trace_id`; `toDatadogTraceId` gives both halves |
| A missing `service.name` becomes the service `otlpresourcenoservicename` | `initTracing` requires a service name (`DD_SERVICE` or explicit) |
| The Agent sets only `error.msg`; `error.type` / `error.stack` stay empty for OTLP exceptions | the package sets `error.type`, `error.message`, `error.stack` itself |
| `deployment.environment.name` and the legacy `deployment.environment` both map to `env` | both are set |
| The span name becomes the Datadog `resource`; the Datadog `name` is the operation (`Internal`, `http.server.request`, …) | the normalized `Span.name` is the resource; the operation is attribute `operation_name` |
| Ids and nanosecond times exceed 2^53 | parsed losslessly (BigInt / `json.Number` / 64-bit integers) |
| Datadog's trace-by-id endpoint reads an all-digit id as decimal | `traceIdForPath` sends such hex ids as their decimal value |
| The Spans API sees only **indexed** spans, after an indexing delay | `getTrace` may be partial; `waitForTrace` polls every 2 s (50 searches per minute) |
| An all-zero trace or span id is accepted by the Agent and dropped silently | OpenTelemetry SDKs never create them; the id helpers reject them, and mapping a Datadog span without a span id (or `0`) raises `VALIDATION` in every language |

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Points where the languages necessarily differ:

| Topic | Difference |
| - | - |
| Datadog error helpers | TypeScript, Python and Rust export them as `recordException` / `setError` at the package root (replacing the generic helpers); Rust also has `record_datadog_exception` / `set_datadog_error`. Go uses `RecordDatadogException` / `SetDatadogError` because `RecordException` / `SetError` are the shared generic helpers. Java: `DatadogSpanErrors.recordException` / `setError` |
| `error.type` value | the language's own type name: TypeScript `Error`, Rust `std::io::error::Error`, Go `*errors.errorString`, Python `ValueError`, Java `java.lang.IllegalStateException` ([details](functions/tracing-setup.md#error-attributes)) |
| Where error attributes and `terminated_context` links are added | TypeScript: span processors. Go, Python: error attributes in an exporter wrapper (`DatadogErrorExporter`), links in `DatadogSpanLinkProcessor`. Rust, Java: both in `DatadogSpanExporter`, because the template `initTracingWith` accepts no extra span processors. Spans exported outside `initTracing` need this wiring (or `terminatedContextLinks`) by hand |
| `TracingConfig` "unset" | TypeScript and Go can leave fields unset. In Rust, Python and Java a field equal to its template default counts as unset: protocol `http/protobuf` → the configured protocol (`GROW_DATADOG_APM_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_*_PROTOCOL`), propagators `tracecontext,baggage` → `datadog,tracecontext,baggage`, an empty service name (Rust, Python) → `DD_SERVICE`. Forcing `http/protobuf` against a gRPC environment needs an explicit `endpoint` |
| Extra span processors | Go (`InitTracingWithOptions.SpanProcessors`) and Python (`span_processors=`) and TypeScript (`spanProcessors` option) accept them; Rust and Java do not |
| Calls on a closed client | `CANCELLED` ("client is closed") in every language, for `DatadogApmClient` and `DatadogSpansQuery` |
| `ping()` result | TypeScript and Python: milliseconds (number); Rust, Go, Java: a duration |
| JSON with big integers | TypeScript `parseJsonPreservingBigInts`, Go `DecodeJSONPreservingNumbers`; Rust, Python and Java parse 64-bit integers natively |
| `loadEnv` | Java cannot change the process environment, so `loadEnv` sets **system properties** instead and returns the names it set |
