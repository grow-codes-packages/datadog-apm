# @grow-codes/datadog-apm

TypeScript SDK for [Datadog APM](https://grow-codes-packages.github.io/datadog-apm/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/datadog-apm
```

```ts
import { DatadogApmClient } from "@grow-codes/datadog-apm";

// Reads GROW_DATADOG_APM_URL, GROW_DATADOG_APM_TOKEN, timeouts, TLS and proxy settings.
const client = DatadogApmClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "ok", details: {} }
  console.log(`ping: ${(await client.ping()).toFixed(1)} ms`);
} finally {
  await client.close();
}
```

Every error is a `DatadogApmError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/datadog-apm/testing`.

Documentation: https://grow-codes-packages.github.io/datadog-apm/ · Licence: Apache-2.0.
Datadog APM is a trademark of Datadog, Inc.; this package is not affiliated with or endorsed by it.
