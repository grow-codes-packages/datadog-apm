# Python examples

Install the package, point it at a Datadog Agent with the OTLP receiver on (ports 4317/4318) and
run an example:

```bash
pip install "grow-datadog-apm[grpc]"
export DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev
# optional, for the SaaS Spans API: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | Agent health/version, `init_tracing` (OTLP to the Agent), spans, Datadog log correlation, propagation headers, id helpers and — with keys — the Spans API |
| `async_basic.py` | async client, `AsyncDatadogSpansQuery`, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_datadog_apm` logger, Datadog log fields in stdlib `logging`, retries and timeouts |
