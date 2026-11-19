"""Datadog header propagation (``x-datadog-*``) with ddtrace's conflict rules.

API.md §3, DECISIONS.md #5. Implemented from Datadog's documented header format; no ddtrace code
is used.
"""

from __future__ import annotations

import dataclasses
import re
from typing import Any

from opentelemetry import context as otel_context
from opentelemetry import trace as otel_trace
from opentelemetry.propagators.textmap import (
    CarrierT,
    Getter,
    Setter,
    TextMapPropagator,
    default_getter,
    default_setter,
)
from opentelemetry.sdk.trace import Span as SdkSpan
from opentelemetry.sdk.trace import SpanProcessor
from opentelemetry.trace import Link, NonRecordingSpan, SpanContext, TraceFlags

from ._core.errors import ValidationError
from .ids import (
    from_datadog_span_id,
    from_datadog_trace_id,
    to_datadog_span_id,
    to_datadog_trace_id,
)

__all__ = [
    "DATADOG_TRACE_ID_HEADER",
    "DATADOG_PARENT_ID_HEADER",
    "DATADOG_SAMPLING_PRIORITY_HEADER",
    "DATADOG_TAGS_HEADER",
    "DATADOG_ORIGIN_HEADER",
    "TerminatedContext",
    "DatadogPropagator",
    "DatadogSpanLinkProcessor",
    "extract_datadog_context",
    "terminated_context",
    "terminated_context_links",
]

#: Low 64 bits of the trace id, unsigned decimal.
DATADOG_TRACE_ID_HEADER = "x-datadog-trace-id"
#: Span id, unsigned decimal.
DATADOG_PARENT_ID_HEADER = "x-datadog-parent-id"
#: ``1`` (sampled) or ``0`` (not sampled).
DATADOG_SAMPLING_PRIORITY_HEADER = "x-datadog-sampling-priority"
#: Propagated tags, e.g. ``_dd.p.tid=<high 64 bits hex>``.
DATADOG_TAGS_HEADER = "x-datadog-tags"
#: Origin of the trace (synthetics, rum, …).
DATADOG_ORIGIN_HEADER = "x-datadog-origin"

_DIGITS = re.compile(r"^[0-9]{1,20}$")
_HEX16 = re.compile(r"^[0-9a-f]{16}$")
_PRIORITY = re.compile(r"^-?[0-9]+$")
_TRACEPARENT = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(?:-.*)?$")
_TERMINATED_KEY = otel_context.create_key("grow-codes datadog-apm terminated W3C context")


@dataclasses.dataclass(frozen=True)
class TerminatedContext:
    """A W3C context that lost against Datadog headers in :meth:`DatadogPropagator.extract`."""

    #: The W3C span context from ``traceparent``.
    span_context: SpanContext
    #: Span id (16 hex) of the Datadog context that won (the parent of the next span).
    winner_span_id: str


def _header(carrier: Any, getter: Getter[Any], name: str) -> str | None:
    values = getter.get(carrier, name)
    if not values:
        return None
    first = str(values[0]).strip()
    return first or None


def _parse_tid(tags: str | None) -> str | None:
    if tags is None:
        return None
    for part in tags.split(","):
        key, sep, value = part.partition("=")
        if not sep or key.strip() != "_dd.p.tid":
            continue
        value = value.strip().lower()
        return value if _HEX16.match(value) else None
    return None


def _parse_traceparent(value: str | None) -> SpanContext | None:
    if value is None:
        return None
    match = _TRACEPARENT.match(value.lower())
    if match is None or match.group(1) == "ff":
        return None
    ctx = SpanContext(
        trace_id=int(match.group(2), 16),
        span_id=int(match.group(3), 16),
        is_remote=True,
        trace_flags=TraceFlags(int(match.group(4), 16) & TraceFlags.SAMPLED),
    )
    return ctx if ctx.is_valid else None


def extract_datadog_context(
    carrier: Any, getter: Getter[Any] = default_getter
) -> SpanContext | None:
    """Read the Datadog headers of a carrier into a remote span context.

    Returns ``None`` when they are missing or invalid. A missing sampling priority counts as
    sampled.
    """
    trace_raw = _header(carrier, getter, DATADOG_TRACE_ID_HEADER)
    parent_raw = _header(carrier, getter, DATADOG_PARENT_ID_HEADER)
    if trace_raw is None or parent_raw is None:
        return None
    if not _DIGITS.match(trace_raw) or not _DIGITS.match(parent_raw):
        return None
    try:
        trace_hex = from_datadog_trace_id(
            trace_raw, _parse_tid(_header(carrier, getter, DATADOG_TAGS_HEADER))
        )
        span_hex = from_datadog_span_id(parent_raw)
    except ValidationError:
        return None
    flags = TraceFlags.SAMPLED
    priority = _header(carrier, getter, DATADOG_SAMPLING_PRIORITY_HEADER)
    if priority is not None and _PRIORITY.match(priority) and int(priority) <= 0:
        flags = TraceFlags.DEFAULT
    ctx = SpanContext(
        trace_id=int(trace_hex, 16),
        span_id=int(span_hex, 16),
        is_remote=True,
        trace_flags=TraceFlags(flags),
    )
    return ctx if ctx.is_valid else None


class DatadogPropagator(TextMapPropagator):
    """Injects and extracts ``x-datadog-trace-id`` (low 64 bits, decimal), ``x-datadog-parent-id``
    (decimal), ``x-datadog-sampling-priority`` (``1``/``0``) and, for 128-bit ids,
    ``x-datadog-tags: _dd.p.tid=<high 64 bits hex>``.

    Conflict rules on extract (ddtrace's behaviour with style order ``datadog,tracecontext``):
    when the carrier also holds a valid ``traceparent``,

    * same trace id → the W3C parent id wins (the context from ``traceparent`` is kept),
    * different trace id → the Datadog context wins and the W3C context is remembered in the
      returned context; :class:`DatadogSpanLinkProcessor` (installed by ``init_tracing``) adds it
      to the next span as a link with ``reason=terminated_context`` (see
      :func:`terminated_context_links`).

    The rules apply whatever position ``datadog`` has in the propagator list; put
    ``tracecontext`` before it (:func:`~grow_datadog_apm.order_propagators`) so an existing
    ``tracestate`` is kept.
    """

    def inject(
        self,
        carrier: CarrierT,
        context: otel_context.Context | None = None,
        setter: Setter[CarrierT] = default_setter,
    ) -> None:
        """Write the Datadog headers of the span in ``context``; no-op without a valid span."""
        span_context = otel_trace.get_current_span(context).get_span_context()
        if not span_context.is_valid:
            return
        split = to_datadog_trace_id(format(span_context.trace_id, "032x"))
        setter.set(carrier, DATADOG_TRACE_ID_HEADER, split.low64_decimal)
        setter.set(
            carrier,
            DATADOG_PARENT_ID_HEADER,
            to_datadog_span_id(format(span_context.span_id, "016x")),
        )
        setter.set(
            carrier,
            DATADOG_SAMPLING_PRIORITY_HEADER,
            "1" if span_context.trace_flags.sampled else "0",
        )
        if split.is_128_bit:
            setter.set(carrier, DATADOG_TAGS_HEADER, "_dd.p.tid=" + split.high64_hex)

    def extract(
        self,
        carrier: CarrierT,
        context: otel_context.Context | None = None,
        getter: Getter[CarrierT] = default_getter,
    ) -> otel_context.Context:
        """Read the Datadog headers (with the conflict rules above) into ``context``."""
        base = otel_context.get_current() if context is None else context
        dd = extract_datadog_context(carrier, getter)
        if dd is None:
            return base
        w3c = _parse_traceparent(_header(carrier, getter, "traceparent"))
        if w3c is None:
            return otel_trace.set_span_in_context(NonRecordingSpan(dd), base)
        if w3c.trace_id == dd.trace_id:
            # Same trace: ddtrace takes the W3C parent id; keep the W3C propagator's tracestate.
            current = otel_trace.get_current_span(base).get_span_context()
            if (
                current.is_valid
                and current.trace_id == w3c.trace_id
                and current.span_id == w3c.span_id
            ):
                return base
            merged = SpanContext(
                trace_id=w3c.trace_id,
                span_id=w3c.span_id,
                is_remote=True,
                trace_flags=dd.trace_flags,
            )
            return otel_trace.set_span_in_context(NonRecordingSpan(merged), base)
        out = otel_trace.set_span_in_context(NonRecordingSpan(dd), base)
        return otel_context.set_value(
            _TERMINATED_KEY, TerminatedContext(w3c, format(dd.span_id, "016x")), out
        )

    @property
    def fields(self) -> set[str]:
        """Header names this propagator reads and writes."""
        return {
            DATADOG_TRACE_ID_HEADER,
            DATADOG_PARENT_ID_HEADER,
            DATADOG_SAMPLING_PRIORITY_HEADER,
            DATADOG_TAGS_HEADER,
        }


def terminated_context(context: otel_context.Context | None = None) -> TerminatedContext | None:
    """The W3C context that lost against Datadog headers during extract, if any."""
    value = otel_context.get_value(_TERMINATED_KEY, context)
    return value if isinstance(value, TerminatedContext) else None


def terminated_context_links(context: otel_context.Context | None = None) -> list[Link]:
    """Span links for a span started from an extracted context.

    One link to the terminated W3C context (``reason=terminated_context``,
    ``context_headers=tracecontext``), or none. Pass them as ``links=`` when you start spans
    without ``init_tracing``'s processor.
    """
    found = terminated_context(context)
    if found is None:
        return []
    return [
        Link(
            found.span_context,
            {"reason": "terminated_context", "context_headers": "tracecontext"},
        )
    ]


class DatadogSpanLinkProcessor(SpanProcessor):
    """Adds the :func:`terminated_context_links` link to the first span started from an extracted
    context whose W3C context lost against Datadog headers. ``init_tracing`` installs it."""

    def on_start(self, span: SdkSpan, parent_context: otel_context.Context | None = None) -> None:
        """Add the terminated-context link when the span's remote parent won the conflict."""
        found = terminated_context(parent_context)
        parent = span.parent
        if found is None or parent is None or not parent.is_remote:
            return
        if format(parent.span_id, "016x") != found.winner_span_id:
            return
        for link in terminated_context_links(parent_context):
            span.add_link(link.context, link.attributes)

    def on_end(self, span: Any) -> None:
        """Nothing to do."""

    def shutdown(self) -> None:
        """Nothing to do."""

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        """Nothing to do."""
        return True
