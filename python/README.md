# grow-datadog-apm (Python)

Python SDK for [Datadog APM](https://grow-codes-packages.github.io/datadog-apm/) by Grow Codes.
Unofficial and community maintained; "Datadog APM" is a trademark of Datadog, Inc..

```bash
pip install grow-datadog-apm
```

```python
from grow_datadog_apm import DatadogApmClient, DatadogApmConfig

# Reads GROW_DATADOG_APM_URL, GROW_DATADOG_APM_TOKEN, ... from the environment.
with DatadogApmClient.from_env() as client:
    print(client.health())
    print(client.server_version())
```

The async client has the same methods:

```python
import asyncio
from grow_datadog_apm import AsyncDatadogApmClient

async def main() -> None:
    async with AsyncDatadogApmClient.from_env() as client:
        print(await client.health())

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_datadog_apm`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_datadog_apm.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/datadog-apm/ ·
Source: https://github.com/grow-codes-packages/datadog-apm · Licence: Apache-2.0
