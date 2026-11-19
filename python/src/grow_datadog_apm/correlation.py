"""Datadog log correlation (DECISIONS.md #3).

Same field format as ddtrace's log injection: ``dd.trace_id`` is 32 lower-case hex for a 128-bit
id and the decimal low 64 bits for a 64-bit id; ``dd.span_id`` is decimal.
"""

from __future__ import annotations

import threading
from typing import Any

from opentelemetry import trace as otel_trace

from .ids import datadog_trace_id_string, to_datadog_span_id

__all__ = [
    "LOG_FIELD_TRACE_ID",
    "LOG_FIELD_SPAN_ID",
    "LOG_FIELD_SERVICE",
    "LOG_FIELD_ENV",
    "LOG_FIELD_VERSION",
    "datadog_log_fields",
    "set_datadog_log_defaults",
]

LOG_FIELD_TRACE_ID = "dd.trace_id"
LOG_FIELD_SPAN_ID = "dd.span_id"
LOG_FIELD_SERVICE = "dd.service"
LOG_FIELD_ENV = "dd.env"
LOG_FIELD_VERSION = "dd.version"

_lock = threading.Lock()
_defaults: dict[str, str] = {}


def set_datadog_log_defaults(
    *, service: str | None = None, env: str | None = None, version: str | None = None
) -> None:
    """Set the service/env/version :func:`datadog_log_fields` uses when the active span carries
    no resource (called by ``init_tracing``; call without arguments to clear)."""
    values = {"service": service, "env": env, "version": version}
    with _lock:
        _defaults.clear()
        _defaults.update({k: v for k, v in values.items() if v})


def _resource_value(span: Any, key: str) -> str | None:
    resource = getattr(span, "resource", None)
    attributes = getattr(resource, "attributes", None)
    if attributes is None:
        return None
    value = attributes.get(key)
    return value if isinstance(value, str) and value else None


def _first(*values: str | None) -> str | None:
    for value in values:
        if value:
            return value
    return None


def datadog_log_fields(
    *, service: str | None = None, env: str | None = None, version: str | None = None
) -> dict[str, str]:
    """Datadog log correlation fields for the active span, ready to merge into a log record.

    Keys: ``dd.trace_id``, ``dd.span_id``, ``dd.service``, ``dd.env``, ``dd.version``. Service,
    env and version come from the arguments, else the active span's resource (``service.name``,
    ``deployment.environment.name``, ``service.version``), else the last ``init_tracing`` config.
    Without an active span only the service fields are returned; keys are absent when unknown.

    >>> logger.info("checkout done", extra=datadog_log_fields())  # doctest: +SKIP
    """
    out: dict[str, str] = {}
    span = otel_trace.get_current_span()
    ctx = span.get_span_context()
    if ctx.is_valid:
        out[LOG_FIELD_TRACE_ID] = datadog_trace_id_string(format(ctx.trace_id, "032x"))
        out[LOG_FIELD_SPAN_ID] = to_datadog_span_id(format(ctx.span_id, "016x"))
    with _lock:
        defaults = dict(_defaults)
    found_service = _first(service, _resource_value(span, "service.name"), defaults.get("service"))
    found_env = _first(
        env,
        _resource_value(span, "deployment.environment.name"),
        _resource_value(span, "deployment.environment"),
        defaults.get("env"),
    )
    found_version = _first(
        version, _resource_value(span, "service.version"), defaults.get("version")
    )
    if found_service:
        out[LOG_FIELD_SERVICE] = found_service
    if found_env:
        out[LOG_FIELD_ENV] = found_env
    if found_version:
        out[LOG_FIELD_VERSION] = found_version
    return out
