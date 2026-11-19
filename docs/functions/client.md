# Client functions

`DatadogApmClient` talks to the local **Datadog Agent**: its trace intake (`GET /info` on port
8126) for health, ping and version. It also starts tracing with the client's settings and gives
access to the Datadog spans query API. The Agent has no authentication, so the client needs no
credentials; `apiKey` and `appKey` are used only by [`spans`](query.md).

| Function | Description |
| - | - |
| `DatadogApmClient(config)` / `fromEnv()` | build a client from a `DatadogApmConfig` or from the environment ([Configuration](../configuration.md)) |
| `health()` | `GET /info`: `healthy=true`, status `ok`, details `version`, `git_commit`; `unreachable` on network errors, `unhealthy` on HTTP errors. Only `CANCELLED` and `CONFIG` are raised |
| `ping()` | round trip to the Agent (`GET /info`), returns the latency (TypeScript/Python: ms, other languages: a duration) |
| `serverVersion()` | the Agent version (`7.85.4`); `UNSUPPORTED` when `/info` has none |
| `agentInfo()` | the `/info` document: `version`, `git_commit`, `endpoints`, `config` |
| `initTracing(config)` | [`initTracing`](tracing-setup.md) with the client's config as defaults (Agent host, OTLP endpoint and protocol, service, env, version, tags, TLS); `close()` shuts the handle down |
| `spans` | the [`DatadogSpansQuery`](query.md), created on first use and closed by `close()`; `CONFIG` without `apiKey` and `appKey` |
| `close()` | idempotent: flushes and shuts down the tracing started through the client, then releases connections. Every later call raises `CANCELLED` |

=== "TypeScript"

    ```ts
    import { DatadogApmClient } from "@grow-codes/datadog-apm";

    const client = DatadogApmClient.fromEnv({ serviceName: "checkout" });
    const health = await client.health();          // { healthy, status, details }
    console.log(await client.ping(), "ms", await client.serverVersion());
    console.log((await client.agentInfo()).endpoints);
    const tracing = client.initTracing({ environment: "prod" });
    if (client.config.hasQueryCredentials) await client.spans.ping();
    await client.close();                           // flushes `tracing`
    ```

=== "Rust"

    ```rust
    use grow_datadog_apm::{DatadogApmClient, tracing::TracingConfig};

    let client = DatadogApmClient::from_env()?;
    let health = client.health().await?;
    println!("{:?} {}", client.ping().await?, client.server_version().await?);
    println!("{:?}", client.agent_info().await?.endpoints);
    let tracing = client.init_tracing(TracingConfig::new("checkout").with_environment("prod"))?;
    if let Ok(spans) = client.spans() {
        spans.ping().await?;
    }
    client.close().await; // flushes `tracing`
    ```

    The `blocking` feature adds `grow_datadog_apm::blocking::DatadogApmClient` with `health`,
    `ping`, `server_version` and `agent_info`.

=== "Go"

    ```go
    client, err := datadogapm.NewClientFromEnv()
    if err != nil {
        return err
    }
    defer client.Close() // flushes the tracing below
    health, _ := client.Health(ctx)
    latency, err := client.Ping(ctx)
    version, err := client.ServerVersion(ctx)
    info, err := client.AgentInfo(ctx) // info.Endpoints, info.Config
    tracing, err := client.InitTracing(ctx, datadogapm.TracingConfig{ServiceName: "checkout", Environment: "prod"})
    if spans, err := client.Spans(); err == nil {
        _, _ = spans.Ping(ctx)
    }
    ```

=== "Python"

    ```python
    from grow_datadog_apm import DatadogApmClient

    with DatadogApmClient.from_env(service_name="checkout") as client:
        health = client.health()
        print(client.ping(), "ms", client.server_version(), client.agent_info().endpoints)
        tracing = client.init_tracing(environment="prod")
        if client.config.has_query_credentials:
            client.spans.ping()
    # close() flushed `tracing`
    ```

    `AsyncDatadogApmClient` has the same methods (`await client.health()`, `await client.aclose()`,
    `client.spans` is an `AsyncDatadogSpansQuery`).

=== "Java"

    ```java
    try (DatadogApmClient client = DatadogApmClient.fromEnv()) {
        HealthStatus health = client.health();
        System.out.println(client.ping().toMillis() + " ms " + client.serverVersion());
        System.out.println(client.agentInfo().endpoints());
        DatadogTracingHandle tracing =
            client.initTracing(TracingConfig.builder("checkout").environment("prod").build());
        if (client.config().hasQueryCredentials()) {
            client.spans().ping();
        }
    } // close() flushes `tracing`
    ```

    Every call has an `…Async` variant returning a `CompletableFuture` (`healthAsync()`,
    `pingAsync()`, `serverVersionAsync()`, `agentInfoAsync()`).

## Errors

| Situation | Code |
| - | - |
| Agent not reachable | `health()` → `healthy=false`, `unreachable`; other calls `UNAVAILABLE` (retried) |
| `/info` answers 404 (not a Datadog Agent trace intake) | `health()` → `unhealthy`; other calls `NOT_FOUND` |
| no `version` in `/info` | `serverVersion()` → `UNSUPPORTED` |
| `spans` without `apiKey` / `appKey` | `CONFIG` naming `DD_API_KEY` / `DD_APP_KEY` |
| any call after `close()` | `CANCELLED` ("client is closed") |
