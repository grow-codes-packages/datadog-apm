# Datadog APM SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/datadog-apm/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Datadog APM** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`datadog/agent (OTLP receiver) + datadog/fakeintake; optional live Datadog sandbox` in Docker.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/datadog-apm`](https://www.npmjs.com/package/@grow-codes/datadog-apm) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-typescript.json) |
| Rust | [`grow-datadog-apm`](https://crates.io/crates/grow-datadog-apm) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/datadog-apm/go`](https://pkg.go.dev/github.com/grow-codes-packages/datadog-apm/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-go.json) |
| Python | [`grow-datadog-apm`](https://pypi.org/project/grow-datadog-apm/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-python.json) |
| Java | [`codes.grow.packages:datadog-apm`](https://central.sonatype.com/artifact/codes.grow.packages/datadog-apm) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/datadog-apm/badges/coverage-java.json) |

## What you get

- **Client** with health, authenticated ping and server version ([Client](client.md)).
- **Configuration** from code or `GROW_DATADOG_APM_*` / vendor environment variables, immutable
  and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).
- **Tracing**: OpenTelemetry setup, span helpers, propagation, framework adapters and trace
  queries ([Tracing](tracing.md)).
- **Service functions** for the Datadog APM API ([Functions](functions.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Datadog APM is a trademark of
    Datadog, Inc.. This project is not affiliated with or endorsed by Datadog, Inc.. See
    [License & disclaimer](license.md).
