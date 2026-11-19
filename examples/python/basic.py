"""Basics of the Datadog APM Python SDK.

Configuration from the environment, Agent health and version, OpenTelemetry tracing to the
Datadog Agent (OTLP), Datadog log correlation, ``x-datadog-*`` propagation and, when an API key
and an application key are set, the SaaS Spans API::

    DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev python examples/python/basic.py
    # optional query API: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
"""

from __future__ import annotations

import grow_datadog_apm as sdk


def main() -> None:
    config = sdk.DatadogApmConfig.from_env(service_name=None)
    print("config:", config)  # keys print as [REDACTED]
    with sdk.DatadogApmClient(config) as client:
        status = client.health()
        print(f"agent healthy={status.healthy} status={status.status} {dict(status.details)}")
        if not status.healthy:
            return
        print(f"agent version {client.server_version()}, ping {client.ping():.1f} ms")

        # OTLP to the Agent; DD_SERVICE / DD_ENV / DD_VERSION become unified service tags.
        handle = client.init_tracing(service_name=config.service_name or "grow-apm-example")
        print("exporting to", handle.endpoint)

        @sdk.traced("load-cart")
        def load_cart() -> int:
            sdk.add_event("cache.miss", {"key": "cart:42"})
            return 3

        with sdk.with_span("checkout", kind="server", attributes={"cart.items": 0}) as span:
            span.set_attribute("cart.items", load_cart())
            print("log fields:", sdk.datadog_log_fields())  # dd.trace_id, dd.span_id, dd.service…
            headers = sdk.inject_headers({})  # x-datadog-* + traceparent for outgoing calls
            print("outgoing headers:", headers)
            trace_id = sdk.current_trace_id() or ""
            try:
                raise ValueError("card declined")
            except ValueError as err:
                sdk.set_error(err)  # ERROR status + error.type / error.message / error.stack
        split = sdk.to_datadog_trace_id(trace_id)
        print(f"trace {trace_id} = Datadog trace_id {split.low64_decimal} (tid {split.high64_hex})")
        handle.force_flush()

        if config.has_query_credentials:  # SaaS Spans API (indexed spans; may take a minute)
            spans = client.spans
            print(f"api keys valid, {spans.ping():.0f} ms")
            for item in spans.search_spans(query="service:" + handle.config.service_name, limit=5):
                print("span:", item.span.name, item.span.duration_micros, "µs")
                break
            print("services:", spans.list_services()[:10])
    # close() flushed and shut the tracing down


if __name__ == "__main__":
    try:
        main()
    except sdk.DatadogApmError as err:
        print(f"failed with {err.code}: {err} (retryable={err.retryable})")
