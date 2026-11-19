"""Datadog error attributes (DECISIONS.md #2).

The Datadog Agent keeps an OTLP exception only inside the ``events`` JSON and does not fill
``error.type`` / ``error.stack``, so this package sets ``error.type``, ``error.message`` and
``error.stack`` span attributes itself, which Datadog Error Tracking reads.

``error.type`` is the exception class name (``type(error).__name__``), the same value the
OpenTelemetry SDK writes to ``exception.type``; ``error.stack`` is the formatted traceback.
"""

from __future__ import annotations

import json
import traceback
from collections.abc import Sequence

from opentelemetry import trace as otel_trace
from opentelemetry.sdk.trace import ReadableSpan
from opentelemetry.sdk.trace.export import SpanExporter, SpanExportResult
from opentelemetry.trace import StatusCode
from opentelemetry.util.types import Attributes

from ._tracing import mark_span_error
from ._tracing import record_exception as _record_exception_event

__all__ = [
    "ATTR_ERROR_TYPE",
    "ATTR_ERROR_MESSAGE",
    "ATTR_ERROR_STACK",
    "DatadogErrorExporter",
    "datadog_error_attributes",
    "record_exception",
    "set_error",
    "set_error_attributes",
    "with_datadog_error_attributes",
]

#: ``error.type`` span attribute (exception class name).
ATTR_ERROR_TYPE = "error.type"
#: ``error.message`` span attribute.
ATTR_ERROR_MESSAGE = "error.message"
#: ``error.stack`` span attribute.
ATTR_ERROR_STACK = "error.stack"


def datadog_error_attributes(error: BaseException | str | object) -> dict[str, str]:
    """The Datadog error attributes for an exception (or any other value).

    Exceptions give ``error.type`` (class name), ``error.message`` (``str(error)``) and
    ``error.stack`` (formatted traceback); a string gives type ``Error`` and the string as
    message; other values are JSON-encoded (or ``repr``) into the message.
    """
    if isinstance(error, BaseException):
        stack = "".join(traceback.format_exception(type(error), error, error.__traceback__))
        return {
            ATTR_ERROR_TYPE: type(error).__name__,
            ATTR_ERROR_MESSAGE: str(error),
            ATTR_ERROR_STACK: stack,
        }
    if isinstance(error, str):
        return {ATTR_ERROR_TYPE: "Error", ATTR_ERROR_MESSAGE: error}
    try:
        message = json.dumps(error)
    except (TypeError, ValueError):
        message = repr(error)
    return {ATTR_ERROR_TYPE: type(error).__name__, ATTR_ERROR_MESSAGE: message}


def set_error_attributes(
    error: BaseException | str | object, span: otel_trace.Span | None = None
) -> None:
    """Set ``error.type``, ``error.message`` and ``error.stack`` for ``error`` on ``span``
    (default: the active span). No-op without a recording span."""
    target = span if span is not None else otel_trace.get_current_span()
    target.set_attributes(datadog_error_attributes(error))


def record_exception(error: BaseException, attributes: Attributes = None) -> None:
    """Record ``error`` as an exception event on the active span **and** set the Datadog error
    attributes. The span status is unchanged. No-op without an active span."""
    _record_exception_event(error, attributes)
    set_error_attributes(error)


def set_error(error: BaseException | str) -> None:
    """Mark the active span as failed (ERROR status, exception event for exceptions) **and** set
    the Datadog error attributes. No-op without an active span."""
    span = otel_trace.get_current_span()
    mark_span_error(span, error)
    set_error_attributes(error, span)


def with_datadog_error_attributes(span: ReadableSpan) -> ReadableSpan:
    """``span`` with the Datadog error attributes derived from its last ``exception`` event or
    ERROR status (see :class:`DatadogErrorExporter`), or ``span`` itself when it already has them
    or has no error."""
    attributes = dict(span.attributes or {})
    if ATTR_ERROR_TYPE in attributes or ATTR_ERROR_MESSAGE in attributes:
        return span
    add: dict[str, str] = {}
    for event in reversed(tuple(span.events)):
        if event.name != "exception":
            continue
        event_attrs = event.attributes or {}
        for source, target in (
            ("exception.type", ATTR_ERROR_TYPE),
            ("exception.message", ATTR_ERROR_MESSAGE),
            ("exception.stacktrace", ATTR_ERROR_STACK),
        ):
            value = event_attrs.get(source)
            if isinstance(value, str):
                add[target] = value
        break
    status = span.status
    if (
        status.status_code is StatusCode.ERROR
        and ATTR_ERROR_MESSAGE not in add
        and status.description
    ):
        add[ATTR_ERROR_MESSAGE] = status.description
    if not add:
        return span
    attributes.update(add)
    return ReadableSpan(
        name=span.name,
        context=span.context,
        parent=span.parent,
        resource=span.resource,
        attributes=attributes,
        events=span.events,
        links=span.links,
        kind=span.kind,
        status=span.status,
        start_time=span.start_time,
        end_time=span.end_time,
        instrumentation_scope=span.instrumentation_scope,
    )


class DatadogErrorExporter(SpanExporter):
    """Wraps a span exporter and fills the Datadog error attributes of every exported span that
    has none yet: from its last ``exception`` event (``exception.type``, ``exception.message``,
    ``exception.stacktrace``) or, for an ERROR status without an event, ``error.message`` from
    the status description.

    It covers ``with_span``, ``traced``, the HTTP adapters and plain OpenTelemetry
    ``record_exception`` calls. ``init_tracing`` installs it.
    """

    def __init__(self, exporter: SpanExporter) -> None:
        self.exporter = exporter

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        """Add the Datadog error attributes and export with the wrapped exporter."""
        return self.exporter.export([with_datadog_error_attributes(s) for s in spans])

    def shutdown(self) -> None:
        """Shut the wrapped exporter down."""
        self.exporter.shutdown()

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        """Flush the wrapped exporter."""
        return self.exporter.force_flush(timeout_millis)
