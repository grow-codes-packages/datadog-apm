# Datadog APM SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Datadog APM** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against the real
Datadog Agent (`datadog/agent:7.85.4`, forwarding to Datadog's test intake `datadog/fakeintake`) in
Docker. The packages use the official OpenTelemetry SDK of each language; they contain no Datadog
tracer (`dd-trace`) code.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/datadog-apm`](https://www.npmjs.com/package/@grow-codes/datadog-apm) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-typescript.json) |
| Rust | [`grow-datadog-apm`](https://crates.io/crates/grow-datadog-apm) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/datadog-apm/go`](https://pkg.go.dev/github.com/grow-codes-packages/datadog-apm/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-go.json) |
| Python | [`grow-datadog-apm`](https://pypi.org/project/grow-datadog-apm/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-python.json) |
| Java | [`codes.grow.packages:datadog-apm`](https://central.sonatype.com/artifact/codes.grow.packages/datadog-apm) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-java.json) |

## What you get

- **Tracing to the Datadog Agent**: OpenTelemetry setup in one call, exporting OTLP (HTTP/protobuf
  on 4318 or gRPC on 4317) with unified service tagging (`service`, `env`, `version`)
  ([Tracing setup](functions/tracing-setup.md)).
- **Datadog propagation**: `x-datadog-*` headers next to W3C trace context, with the same conflict
  rules as the Datadog tracers, so traces cross services that use `dd-trace`
  ([Propagation and ids](functions/propagation.md)).
- **Error Tracking ready**: `recordException` / `setError` also set `error.type`,
  `error.message` and `error.stack`.
- **Log correlation**: `datadogLogFields()` (`dd.trace_id`, `dd.span_id`, `dd.service`, …) and
  64/128-bit id helpers.
- **Spans query** with an API key and an application key: search and aggregate indexed spans, read
  traces back, list services and operations ([Spans query](functions/query.md)).
- **Client** with Agent health, ping and version ([Client functions](functions/client.md)).
- **Configuration** from code or `GROW_DATADOG_APM_*` / `DD_*` / `OTEL_*` environment variables,
  immutable and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language; **typed errors** with one set of codes
  ([Error handling](errors.md)).
- **Redaction everywhere**: API and application keys never appear in errors, logs, hooks or
  `toString` ([Redaction](redaction.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Datadog and Datadog APM are trademarks
    of Datadog, Inc. This project is not affiliated with or endorsed by Datadog, Inc. See
    [License & disclaimer](license.md).
