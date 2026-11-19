"""Request hooks, the SDK logger, Datadog log correlation in stdlib logging, retries, timeouts."""

from __future__ import annotations

import logging

import grow_datadog_apm as sdk


class DatadogFields(logging.Filter):
    """Adds dd.trace_id / dd.span_id / dd.service / dd.env / dd.version to every record."""

    def filter(self, record: logging.LogRecord) -> bool:
        fields = sdk.datadog_log_fields()
        record.dd = " ".join(f"{k}={v}" for k, v in fields.items())
        return True


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s [%(dd)s]")
    for handler in logging.getLogger().handlers:
        handler.addFilter(DatadogFields())  # on the handler: also covers the SDK's own records
    log = logging.getLogger("app")
    # The SDK logger is silent by default; enable it to see requests (secrets are redacted).
    logging.getLogger(sdk.LOGGER_NAME).setLevel(logging.DEBUG)

    hooks = sdk.Hooks(
        on_request=lambda info: print(f"-> {info.method} {info.url} (attempt {info.attempt})"),
        on_response=lambda info: print(f"<- {info.status} in {info.duration_ms:.1f} ms"),
        on_error=lambda info: print(f"!! {info.error!r}: retry={info.will_retry}"),
    )
    config = sdk.DatadogApmConfig.from_env(
        hooks=hooks,
        request_timeout=2.0,
        total_timeout=10.0,
        retry=sdk.RetryPolicy(max_retries=2, initial_backoff=0.2),
    )
    with sdk.DatadogApmClient(config) as client:
        client.init_tracing(service_name=config.service_name or "grow-apm-example")
        with sdk.with_span("handle-request"):
            log.info("agent %s", client.server_version())


if __name__ == "__main__":
    main()
