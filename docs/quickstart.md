# Quickstart

Point the SDK at a Datadog Agent with the OTLP receiver turned on, check that it is healthy, send a
trace with log-correlation fields and — with an API key and an application key — read it back from
Datadog. Configuration comes from the environment (`DD_*`, `GROW_DATADOG_APM_*`, see
[Configuration](configuration.md)) or from code.

```sh
export DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev
# the Agent: DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318
# optional, for the query API (never commit real keys):
# export DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
```

## 1. Check the Agent

=== "TypeScript"

    ```ts
    import { DatadogApmClient } from "@grow-codes/datadog-apm";

    const client = DatadogApmClient.fromEnv();
    try {
      const health = await client.health();
      console.log("healthy:", health.healthy, health.status);
      console.log("ping:", await client.ping(), "ms");
      console.log("server version:", await client.serverVersion());
    } finally {
      await client.close();
    }
    ```

=== "Rust"

    ```rust
    use grow_datadog_apm::DatadogApmClient;

    #[tokio::main]
    async fn main() -> grow_datadog_apm::Result<()> {
        let client = DatadogApmClient::from_env()?;
        let health = client.health().await?;
        println!("healthy: {} {}", health.healthy, health.status);
        println!("ping: {:?}", client.ping().await?);
        println!("server version: {}", client.server_version().await?);
        client.close().await;
        Ok(())
    }
    ```

=== "Go"

    ```go
    package main

    import (
        "context"
        "fmt"
        "log"

        datadogapm "github.com/grow-codes-packages/datadog-apm/go"
    )

    func main() {
        ctx := context.Background()
        client, err := datadogapm.NewClientFromEnv()
        if err != nil {
            log.Fatal(err)
        }
        defer client.Close()

        health, err := client.Health(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("healthy:", health.Healthy, health.Status)

        latency, err := client.Ping(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("ping:", latency)

        version, err := client.ServerVersion(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("server version:", version)
    }
    ```

=== "Python"

    ```python
    from grow_datadog_apm import DatadogApmClient

    with DatadogApmClient.from_env() as client:
        health = client.health()
        print("healthy:", health.healthy, health.status)
        print("ping:", client.ping(), "ms")
        print("server version:", client.server_version())
    ```

    Async code uses `AsyncDatadogApmClient` with the same methods:

    ```python
    import asyncio
    from grow_datadog_apm import AsyncDatadogApmClient

    async def main() -> None:
        async with AsyncDatadogApmClient.from_env() as client:
            print(await client.health())

    asyncio.run(main())
    ```

=== "Java"

    ```java
    import codes.grow.packages.datadogapm.DatadogApmClient;
    import codes.grow.packages.datadogapm.core.HealthStatus;

    public final class Quickstart {
      public static void main(String[] args) {
        try (DatadogApmClient client = DatadogApmClient.fromEnv()) {
          HealthStatus health = client.health();
          System.out.println("healthy: " + health.healthy() + " " + health.status());
          System.out.println("ping: " + client.ping().toMillis() + " ms");
          System.out.println("server version: " + client.serverVersion());
        }
      }
    }
    ```

## 2. Send a trace and read it back

=== "TypeScript"

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

=== "Rust"

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

=== "Go"

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

=== "Python"

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

=== "Java"

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

## Next steps

- [Tracing setup](functions/tracing-setup.md): protocol, propagators, error attributes, unified service tagging.
- [Propagation and ids](functions/propagation.md): `x-datadog-*` headers, id conversion, log correlation.
- [Spans query](functions/query.md): search, aggregate and read traces from Datadog.
- [Configuration](configuration.md): timeouts, retries, TLS, proxy, environment variables.
- [Error handling](errors.md): error codes and retries.
