# Grow Codes SDK for Datadog APM

[![CI](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/datadog-apm?sort=semver)](https://github.com/grow-codes-packages/datadog-apm/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/datadog-apm/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/datadog-apm)](https://www.npmjs.com/package/@grow-codes/datadog-apm) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-datadog-apm)](https://crates.io/crates/grow-datadog-apm) [![docs.rs](https://img.shields.io/docsrs/grow-datadog-apm)](https://docs.rs/grow-datadog-apm) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-rust.json)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/datadog-apm/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/datadog-apm/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-go.json)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-datadog-apm)](https://pypi.org/project/grow-datadog-apm/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-python.json)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/datadog-apm)](https://central.sonatype.com/artifact/codes.grow.packages/datadog-apm) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-java.json)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml) |

Open-source client libraries for [Datadog APM](#trademarks-and-disclaimer) in **TypeScript, Rust,
Go, Python and Java** — one API, five idiomatic packages, tested against the real Datadog Agent in
Docker.

- **Tracing to the Datadog Agent**: OpenTelemetry setup in one call that exports OTLP
  (HTTP/protobuf on 4318 or gRPC on 4317) to the Agent, with unified service tagging (`service`,
  `env`, `version`), the Datadog propagator (`x-datadog-*` headers) next to W3C trace context, and
  the Datadog error attributes (`error.type`, `error.message`, `error.stack`) for Error Tracking.
- **Log correlation**: `datadogLogFields()` gives `dd.trace_id` / `dd.span_id` in the format the
  Datadog tracers use, and 64/128-bit trace id helpers (`toDatadogTraceId`, `fromDatadogTraceId`).
- **Query** (needs an API key and an application key): `DatadogSpansQuery` searches and
  aggregates indexed spans, reads traces back, lists services and operations and waits for a
  trace in tests — in one normalized trace model shared with the other Grow Codes tracing SDKs.
- **Typed client** with timeouts, retries with jitter, `Retry-After`, cancellation and one error
  model (`DatadogApmError` with canonical codes) in every language.
- **Secrets stay secret**: the API and application keys are redacted in `toString`, logs, hooks
  and error messages.

The package uses the official OpenTelemetry SDK of each language. It contains no Datadog tracer
(`dd-trace`) code.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Datadog APM is a SaaS product. The packages talk to two endpoints:

| Endpoint | Used by | Tested against |
| - | - | - |
| The **Datadog Agent** (OTLP receiver on 4317/4318, trace intake `/info` on 8126) | tracing, `health()`, `ping()`, `serverVersion()` | **`datadog/agent:7.85.4`** in Docker, forwarding to Datadog's test intake `datadog/fakeintake:v79` |
| The **Datadog API** `https://api.<site>` (spans search/aggregate, APM services, key validation) | `DatadogSpansQuery` | unit tests on Datadog's published OpenAPI examples; a live job against a Datadog sandbox org is pending (see [docs/compatibility.md](docs/compatibility.md)) |

The Agent needs its OTLP receiver turned on (`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318`
and/or `..._GRPC_ENDPOINT=0.0.0.0:4317`). Agent 7.x releases with the OTLP receiver usually work;
see [Compatibility](#compatibility).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/datadog-apm` | `npm install @grow-codes/datadog-apm` |
| Rust (MSRV 1.88) | `grow-datadog-apm` | `cargo add grow-datadog-apm` |
| Go (1.26+) | `github.com/grow-codes-packages/datadog-apm/go` | `go get github.com/grow-codes-packages/datadog-apm/go` |
| Python (≥ 3.10) | `grow-datadog-apm` | `pip install grow-datadog-apm` |
| Java (17+) | `codes.grow.packages:datadog-apm` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>datadog-apm</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:datadog-apm:0.1.0")
```

## Quickstart

Every package reads its configuration from the environment (`DD_AGENT_HOST`, `DD_SERVICE`,
`DD_ENV`, `GROW_DATADOG_APM_*`, …, see [Configuration](#configuration)) or from an explicit config
object. Each example checks the Agent, starts tracing, sends one span with log-correlation fields
and — when `DD_API_KEY` and `DD_APP_KEY` are set — reads the trace back from Datadog.

```sh
export DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev
# optional, for the query API: DD_API_KEY=… DD_APP_KEY=… DD_SITE=datadoghq.eu
```

### TypeScript

```ts
import { DatadogApmClient, datadogLogFields, withSpan } from "@grow-codes/datadog-apm";

const client = DatadogApmClient.fromEnv();
try {
  console.log((await client.health()).healthy, await client.serverVersion());
  const tracing = client.initTracing({ serviceVersion: "1.4.0" });
  const traceId = await withSpan("place-order", async (span) => {
    console.log(JSON.stringify({ msg: "order placed", ...datadogLogFields() }));
    return span.spanContext().traceId;
  });
  await tracing.forceFlush();
  if (client.config.hasQueryCredentials) {
    const trace = await client.spans.waitForTrace(traceId, { timeoutMs: 120_000 });
    console.log(trace.spans.map((s) => s.name));
  }
} finally {
  await client.close(); // flushes and shuts down the tracing it started
}
```

### Rust

```rust
use grow_datadog_apm::tracing::{current_trace_id, with_span, SpanOptions, TraceQueryClient as _, TracingConfig};
use grow_datadog_apm::{datadog_log_fields, DatadogApmClient};

#[tokio::main]
async fn main() -> grow_datadog_apm::Result<()> {
    let client = DatadogApmClient::from_env()?;
    println!("{} {}", client.health().await?.healthy, client.server_version().await?);
    let tracing = client.init_tracing(TracingConfig::new("checkout").with_service_version("1.4.0"))?;
    let trace_id = with_span("place-order", SpanOptions::default(), async {
        println!("{:?}", datadog_log_fields(Default::default()));
        Ok::<_, grow_datadog_apm::Error>(current_trace_id().unwrap_or_default())
    })
    .await?;
    tracing.force_flush(None).await?;
    if let Ok(spans) = client.spans() {
        let trace = spans.wait_for_trace(&trace_id, Default::default()).await?;
        println!("{} spans", trace.spans.len());
    }
    client.close().await; // flushes and shuts down the tracing it started
    Ok(())
}
```

### Go

```go
package main

import (
	"context"
	"fmt"
	"log"
	"log/slog"
	"time"

	datadogapm "github.com/grow-codes-packages/datadog-apm/go"
)

func main() {
	ctx := context.Background()
	client, err := datadogapm.NewClientFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close() // flushes and shuts down the tracing it started

	health, _ := client.Health(ctx)
	fmt.Println(health.Healthy)
	tracing, err := client.InitTracing(ctx, datadogapm.TracingConfig{ServiceVersion: "1.4.0"})
	if err != nil {
		log.Fatal(err)
	}
	traceID, _ := datadogapm.WithSpan(ctx, "place-order", func(ctx context.Context) (string, error) {
		slog.InfoContext(ctx, "order placed", "dd", datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{}))
		id, _ := datadogapm.CurrentTraceID(ctx)
		return id, nil
	})
	_ = tracing.ForceFlush(ctx)
	if spans, err := client.Spans(); err == nil {
		trace, err := spans.WaitForTrace(ctx, traceID, datadogapm.WaitForTraceOptions{Timeout: 2 * time.Minute})
		if err == nil {
			fmt.Println(len(trace.Spans), "spans")
		}
	}
}
```

### Python

```python
from grow_datadog_apm import DatadogApmClient, current_trace_id, datadog_log_fields, with_span

with DatadogApmClient.from_env() as client:  # close() flushes the tracing it started
    print(client.health().healthy, client.server_version())
    tracing = client.init_tracing(service_version="1.4.0")
    with with_span("place-order"):
        print({"msg": "order placed", **datadog_log_fields()})
        trace_id = current_trace_id()
    tracing.force_flush()
    if client.config.has_query_credentials:
        trace = client.spans.wait_for_trace(trace_id, timeout_s=120)
        print([s.name for s in trace.spans])
```

Async code uses `AsyncDatadogApmClient` and `AsyncDatadogSpansQuery` with the same methods
(`async with`, `await client.health()`).

### Java

```java
import codes.grow.packages.datadogapm.DatadogApmClient;
import codes.grow.packages.datadogapm.DatadogLogCorrelation;
import codes.grow.packages.datadogapm.DatadogTracingHandle;
import codes.grow.packages.datadogapm.tracing.Spans;
import codes.grow.packages.datadogapm.tracing.query.Trace;

try (DatadogApmClient client = DatadogApmClient.fromEnv()) { // close() flushes the tracing it started
    System.out.println(client.health().healthy() + " " + client.serverVersion());
    DatadogTracingHandle tracing = client.initTracing();
    String traceId = Spans.withSpan("place-order", () -> {
        System.out.println(DatadogLogCorrelation.datadogLogFields()); // merge into your MDC
        return Spans.currentTraceId().orElseThrow();
    });
    tracing.forceFlush();
    if (client.config().hasQueryCredentials()) {
        Trace trace = client.spans().waitForTrace(traceId);
        System.out.println(trace.spans().size() + " spans");
    }
}
```

More complete programs live in [examples/](examples/) — one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_DATADOG_APM_*` variable > vendor variable (in the order listed) > default.** A present but
invalid value raises a `ConfigError` that names the variable (never its value).

**Agent, service and query API** (`DatadogApmConfig`):

| Setting | `GROW_DATADOG_APM_*` | Vendor variables (in order) | Default |
| - | - | - | - |
| `url` (Agent trace intake; `/info` for `health`, `ping`, `serverVersion`) | `URL` | `DD_TRACE_AGENT_URL`, else `http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}` | `http://127.0.0.1:8126` |
| `otlpEndpoint` (OTLP receiver of the Agent) | `OTLP_HTTP_ENDPOINT` / `OTLP_GRPC_ENDPOINT` (by protocol), `OTLP_ENDPOINT` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` (HTTP appends `/v1/traces`) | host of `url` + `:4318` (HTTP) / `:4317` (gRPC) |
| `protocol` | `OTLP_PROTOCOL` | `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` (or `grpc`) |
| `serviceName` (Datadog `service`) | `SERVICE` | `DD_SERVICE`, `OTEL_SERVICE_NAME` | none (required by `initTracing`) |
| `serviceVersion` (Datadog `version`) | `VERSION` | `DD_VERSION` | none |
| `environment` (Datadog `env`) | `ENV` | `DD_ENV` | none |
| `tags` (extra resource attributes) | `TAGS` | `DD_TAGS` (`key:value`, comma or space separated) | none |
| `apiKey` (query API only, secret) | `API_KEY` | `DD_API_KEY` | none |
| `appKey` (query API only, secret, needs `apm_read`) | `APP_KEY` | `DD_APP_KEY`, `DD_APPLICATION_KEY` | none |
| `site` | `SITE` | `DD_SITE` | `datadoghq.com` |
| `apiUrl` (query API base URL) | `API_URL` | — | `https://api.<site>` |

**Transport** (shared by every Grow Codes SDK):

| Setting | Environment variable | Default |
| - | - | - |
| `connectTimeout` | `GROW_DATADOG_APM_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_DATADOG_APM_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_DATADOG_APM_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_DATADOG_APM_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_DATADOG_APM_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_DATADOG_APM_CLIENT_CERT` / `GROW_DATADOG_APM_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_DATADOG_APM_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_DATADOG_APM_PROXY` | none (environment proxies are ignored) |
| `headers`, `userAgentSuffix`, `hooks`, `logger` | — | none |

The Agent path needs no key: the SDK sends OTLP to the Agent and the Agent holds its own
`DD_API_KEY`. `apiKey` and `appKey` are needed only by `DatadogSpansQuery`; the query API is
reached at `https://api.<site>` (`datadoghq.com`, `us3.datadoghq.com`, `us5.datadoghq.com`,
`ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com`, `datadoghq.eu`, `ddog-gov.com`,
`us2.ddog-gov.com`; any other host name is accepted, a scheme or path is not).

`initTracing` also reads the OpenTelemetry variables of the shared tracing setup
(`OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_PROPAGATORS`, `OTEL_RESOURCE_ATTRIBUTES`,
`OTEL_EXPORTER_OTLP_*_HEADERS`, `…_COMPRESSION`, `…_TIMEOUT`, `…_CERTIFICATE`); see
[Tracing setup](https://grow-codes-packages.github.io/datadog-apm/functions/tracing-setup/).

Durations accept milliseconds (`1500`) or units (`500ms`, `10s`, `1m30s`); booleans accept
`true/false/1/0/yes/no/on/off`. Field names follow each language's convention (`service_name` in
Python and Rust, `ServiceName` in Go). The full reference is in the
[documentation](https://grow-codes-packages.github.io/datadog-apm/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`searchSpans` in
TypeScript and Java, `search_spans` in Python and Rust, `SearchSpans` in Go). Every function has an
example in all five languages in the
[documentation](https://grow-codes-packages.github.io/datadog-apm/functions/).

### Client — `DatadogApmClient`

| Function | Description |
| - | - |
| `health()` | Agent liveness (`GET /info` on 8126); `unreachable` / `unhealthy` instead of throwing |
| `ping()` | round trip to the Agent, returns the latency (the Agent has no authentication) |
| `serverVersion()` / `agentInfo()` | Agent version (`7.85.4`) / the full `/info` document |
| `initTracing(config)` | `initTracing` with the client's settings as defaults; shut down by `close()` |
| `spans` | the `DatadogSpansQuery` (needs `apiKey` + `appKey`, else `CONFIG`) |
| `close()` | idempotent; flushes and stops the tracing started through the client; later calls raise `CANCELLED` |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `await client.serverVersion()` | `client.server_version().await?` | `client.ServerVersion(ctx)` | `client.server_version()` | `client.serverVersion()` |

### Tracing setup

| Function | Description |
| - | - |
| `initTracing(config)` | OpenTelemetry to the Agent over OTLP: unified service tagging, propagators `datadog,tracecontext,baggage`, Datadog error attributes, `terminated_context` links; returns a handle with `tracer`, `forceFlush`, `shutdown`, `endpoint` |
| `orderPropagators(names)` | puts `tracecontext` before `datadog` so Datadog headers win on conflict |
| `datadogExporterFactory(config)` | the OTLP span exporter `initTracing` uses |
| `withSpan`, `traced`, `currentSpan`, `currentTraceId`, `currentSpanId`, `addEvent`, `inject`, `extract`, `traceFields` | shared span helpers and propagation (see [Tracing](docs/tracing.md)) |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `initTracing({ serviceName: "checkout", environment: "prod" })` | `init_tracing(TracingConfig::new("checkout").with_environment("prod"))?` | `datadogapm.InitTracing(ctx, datadogapm.TracingConfig{ServiceName: "checkout", Environment: "prod"})` | `init_tracing(service_name="checkout", environment="prod")` | `DatadogTracing.initTracing(TracingConfig.builder("checkout").environment("prod").build())` |

### Error attributes

`recordException` and `setError` of this package also set `error.type`, `error.message` and
`error.stack`, which Datadog Error Tracking reads (the Agent alone fills only `error.msg`).

| Function | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| record + attributes | `recordException(err)` | `record_exception(&err)` (alias `record_datadog_exception`) | `datadogapm.RecordDatadogException(ctx, err)` | `record_exception(err)` | `DatadogSpanErrors.recordException(err)` |
| ERROR status + attributes | `setError(err)` | `set_error(&err)` (alias `set_datadog_error`) | `datadogapm.SetDatadogError(ctx, err)` | `set_error(err)` | `DatadogSpanErrors.setError(err)` |
| attributes only | `setErrorAttributes(err)` | `set_error_attributes(&err)` | `datadogapm.SetErrorAttributes(ctx, err)` | `set_error_attributes(err)` | `DatadogSpanErrors.setErrorAttributes(err)` |
| the attribute map | `datadogErrorAttributes(err)` | `datadog_error_attributes(&err)` | `datadogapm.DatadogErrorAttributes(err)` | `datadog_error_attributes(err)` | `DatadogSpanErrors.datadogErrorAttributes(err)` |

In Go the generic `RecordException` / `SetError` names belong to the shared tracing helpers, so the
Datadog versions are `RecordDatadogException` / `SetDatadogError`. Spans exported through
`initTracing` get the attributes in every language even when the generic helpers were used.

### Propagation, ids and log correlation

| Function | Description |
| - | - |
| `DatadogPropagator` | OpenTelemetry propagator for `x-datadog-trace-id`, `x-datadog-parent-id`, `x-datadog-sampling-priority`, `x-datadog-origin`, `x-datadog-tags` (`_dd.p.tid` for 128-bit ids) |
| `extractDatadogContext(carrier)` | the remote span context in the Datadog headers |
| `terminatedContext(ctx)` / `terminatedContextLinks(ctx)` | the W3C context that lost against different Datadog headers, as a span link (`reason=terminated_context`) |
| `toDatadogTraceId(hex)` / `fromDatadogTraceId(low, high)` | 128-bit OpenTelemetry id ↔ Datadog low 64 bits (decimal) + `_dd.p.tid` (hex) |
| `toDatadogSpanId` / `fromDatadogSpanId` / `datadogTraceIdString` | span id hex ↔ decimal; the `dd.trace_id` form of a trace id |
| `datadogLogFields()` / `setDatadogLogDefaults()` | `dd.trace_id`, `dd.span_id`, `dd.service`, `dd.env`, `dd.version` for any logger |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `toDatadogTraceId(traceId).low64Decimal` | `to_datadog_trace_id(&trace_id)?.low64_decimal` | `datadogapm.ToDatadogTraceID(traceID)` | `to_datadog_trace_id(trace_id).low64_decimal` | `DatadogIds.toDatadogTraceId(traceId).low64Decimal()` |
| `datadogLogFields()` | `datadog_log_fields(Default::default())` | `datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{})` | `datadog_log_fields()` | `DatadogLogCorrelation.datadogLogFields()` |

### Query — `DatadogSpansQuery` (`TraceQueryClient`)

| Function | Description |
| - | - |
| `searchSpans({query, from, to, limit, sort})` / `searchSpansPage(…)` | indexed spans (`POST /api/v2/spans/events/search`), cursor iterator / one page |
| `aggregateSpans(compute, groupBy)` | counts, percentiles and timeseries (`POST /api/v2/spans/analytics/aggregate`) |
| `getTrace(traceId, {preview})` | a trace from a spans search (indexed spans only); `preview` = trace-by-id API |
| `searchTraces(query)` | trace summaries by service, operation, tags and duration |
| `listServices()` / `listOperations(service)` | APM services / resource names of a service |
| `waitForTrace(traceId, opts)` | poll until a trace is searchable (tests; 2 s interval) |
| `getDependencies(range)` | `UNSUPPORTED`: Datadog has no public dependency API |
| `ping()` | validates the API + application key (`GET /api/v2/validate_keys`) |

| TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - |
| `query.searchSpans({ query: "service:checkout", limit: 50 })` | `query.search_spans(SearchSpansOptions::query("service:checkout").limit(50))` | `query.SearchSpans(ctx, datadogapm.SearchSpansOptions{Query: "service:checkout", Limit: 50})` | `query.search_spans(query="service:checkout", limit=50)` | `query.searchSpans(SearchSpansOptions.builder().query("service:checkout").limit(50).build())` |

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs every Agent-side function against the real Datadog Agent
(`datadog/agent:7.85.4`) in Docker. The Agent forwards to `datadog/fakeintake:v79`, and a small
test-only `trace-reader` sidecar decodes what the Agent would send to Datadog, so the tests assert
the spans after the Agent converted them (service, env, version, ids, errors, links). Nothing
reaches Datadog: all keys are placeholders and every Agent intake URL points at fakeintake.
Each language package is packed and installed into a separate consumer project first, so the tests
use the package exactly as you would.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). Unit tests need no Docker:
`make test` or `make coverage`. The SaaS query API has no emulator; it is covered by unit tests
(see [docs/compatibility.md](docs/compatibility.md)).

Live checks against a real Datadog organisation (a trace sent through a real Agent and read back
through the Spans API) live in `live/` and run weekly in `.github/workflows/live.yml` once the
`DATADOG_API_KEY` / `DATADOG_APP_KEY` secrets exist. Run them locally with the same variables
(Docker starts the Agent unless `GROW_DATADOG_APM_URL` names one):

```sh
DATADOG_API_KEY=… DATADOG_APP_KEY=… DATADOG_SITE=datadoghq.eu make -C live live
```

Without the keys the suite prints `skipped: no credentials` and exits 0. Details:
[docs/compatibility.md](docs/compatibility.md#live-checks).

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.88 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| Datadog Agent | `7.85.4` | `7.85.4` | `7.85.4` | `7.85.4` | `7.85.4` |
| Datadog API | Spans API v2, APM services v2 (unit-tested) | same | same | same | same |

Functions that cannot be tested against a container, and the small differences between languages,
are listed in [docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by Datadog, Inc.** Datadog and Datadog APM are trademarks of Datadog, Inc.; they are used
here only to name the service this SDK connects to. Datadog APM is a proprietary SaaS product of
Datadog, Inc.; the Datadog Agent and the Datadog tracing libraries are licensed separately by their
owner (Apache-2.0, BSD-3-Clause). This SDK contains no code from them.

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost that results from using it.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
