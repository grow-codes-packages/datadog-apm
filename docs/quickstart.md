# Quickstart

Connect to Datadog APM, check that it is healthy, make an authenticated call and close the client.
The client reads `GROW_DATADOG_APM_URL` and `GROW_DATADOG_APM_TOKEN` (and the vendor variables
listed in [Configuration](configuration.md)), or takes the values in code.

```sh
export GROW_DATADOG_APM_URL=http://127.0.0.1:4318
export GROW_DATADOG_APM_TOKEN=...   # never commit real tokens
```

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

## Next steps

- [Configuration](configuration.md): timeouts, retries, TLS, proxy, environment variables.
- [Functions](functions.md): the Datadog APM API of this SDK.
- [Error handling](errors.md): error codes and retries.
