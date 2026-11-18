# Python examples

Install the package, point it at a running Datadog APM and run an example:

```bash
pip install grow-datadog-apm
export GROW_DATADOG_APM_URL=http://127.0.0.1:4318
export GROW_DATADOG_APM_TOKEN=...   # when the service needs one
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_datadog_apm` logger, retries and timeouts |
