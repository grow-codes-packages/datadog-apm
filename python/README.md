# grow-datadog-apm (Python)

Python SDK for [Datadog APM](https://grow-codes-packages.github.io/datadog-apm/) by Grow Codes.
Unofficial and community maintained; "Datadog" is a trademark of Datadog, Inc.

Tracing uses the official OpenTelemetry SDK and exports OTLP to the Datadog Agent (no dd-trace
code). The package adds Datadog defaults: unified service tagging, the `x-datadog-*` propagator,
64/128-bit id helpers, Datadog log correlation fields, `error.*` span attributes and a client for
the Datadog Spans API.

```bash
pip install grow-datadog-apm            # OTLP/HTTP
pip install "grow-datadog-apm[grpc]"    # + OTLP/gRPC
```

```python
from grow_datadog_apm import DatadogApmClient, datadog_log_fields, with_span

# Reads DD_AGENT_HOST, DD_SERVICE, DD_ENV, DD_VERSION, DD_TAGS, GROW_DATADOG_APM_* …
with DatadogApmClient.from_env() as client:
    print(client.health(), client.server_version())  # the Agent's GET /info
    client.init_tracing(service_name="checkout")       # OTLP → http://<agent>:4318/v1/traces
    with with_span("charge", kind="server"):
        log_fields = datadog_log_fields()  # {"dd.trace_id": …, "dd.span_id": …, "dd.service": …}
# close() flushed and shut the tracing down
```

The Datadog Agent needs its OTLP receiver on
(`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318` and/or `..._GRPC_ENDPOINT=0.0.0.0:4317`).

Query spans through the Datadog SaaS API (needs `DD_API_KEY` and an application key with
`apm_read` in `DD_APP_KEY`):

```python
from grow_datadog_apm import DatadogSpansQuery, SpanCompute, SpanGroupBy

with DatadogSpansQuery.from_env() as spans:
    for item in spans.search_spans(query="service:checkout status:error", from_="now-1h"):
        print(item.span.name, item.span.duration_micros)
    trace = spans.get_trace("a3ce929d0e0e47364bf92f3577b34da6")  # indexed spans; preview=True: full trace
    p90 = spans.aggregate_spans([SpanCompute("pc90", "@duration")], [SpanGroupBy("service")])
```

The async client (`AsyncDatadogApmClient`, `AsyncDatadogSpansQuery`) has the same methods.

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After` / `X-RateLimit-Reset`, connect/request/total
  timeouts, cancellation, hooks and a silent-by-default `logging` logger named `grow_datadog_apm`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_datadog_apm.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/datadog-apm/ ·
Source: https://github.com/grow-codes-packages/datadog-apm · Licence: Apache-2.0
