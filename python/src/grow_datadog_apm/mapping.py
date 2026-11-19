"""Datadog span formats → the SPEC §5 trace model (API.md §2.1, §4).

Two Datadog shapes are mapped:

* a trace span as the Agent forwards it and as ``GET /api/v2/trace/{id}`` returns it (``meta``,
  ``metrics``, nanosecond ``start``/``startTime``, decimal ids; camelCase or snake_case keys);
* a span event of the spans search API (``attributes.trace_id``, ``start_timestamp``, …).

For spans that came in over OTLP the Datadog resource is the OpenTelemetry span name and the
Datadog name is the operation (``Internal``, ``server.request``, ``http.server.request``, …), so
the normalized ``name`` is the resource; the Datadog operation is kept as attribute
``operation_name``. Python's ``json`` keeps big integers exact, so ids and nanosecond timestamps
above 2^53 need no special parser.
"""

from __future__ import annotations

import json
import math
import re
from collections.abc import Mapping
from datetime import datetime, timezone
from typing import Any

from ._core.errors import ValidationError
from ._tracing import Span, SpanEvent, SpanKindName, SpanLink, SpanStatus, datetime_to_micros
from .ids import from_datadog_trace_id, normalize_datadog_span_id, normalize_datadog_trace_id

__all__ = [
    "RawDatadogSpan",
    "datadog_kind",
    "datadog_trace_span_to_span",
    "datadog_search_span_to_span",
]

#: A raw span object (any Datadog span shape) decoded from JSON.
RawDatadogSpan = Mapping[str, Any]

_KINDS: dict[str, SpanKindName] = {
    "internal": "internal",
    "server": "server",
    "client": "client",
    "producer": "producer",
    "consumer": "consumer",
}
_HEX16 = re.compile(r"^[0-9a-f]{16}$")
_DIGITS = re.compile(r"^[0-9]+$")
_ZERO_HIGH = "0" * 16


def datadog_kind(value: Any) -> SpanKindName:
    """Datadog ``span.kind`` (or OpenTelemetry kind name) → SPEC kind; unknown → ``unspecified``."""
    if not isinstance(value, str):
        return "unspecified"
    return _KINDS.get(value.lower(), "unspecified")


def _first(record: Mapping[str, Any], *keys: str) -> Any:
    for key in keys:
        value = record.get(key)
        if value is not None:
            return value
    return None


def _str(value: Any) -> str | None:
    """Strings and numbers as text; ``None`` for anything else or an empty string."""
    if isinstance(value, bool):
        return None
    if isinstance(value, str):
        return value or None
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float) and math.isfinite(value):
        return str(int(value)) if value.is_integer() and abs(value) < 1e21 else repr(value)
    return None


def _record(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value) if math.isfinite(value) else None
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def _attr_value(value: Any) -> Any:
    """Decoded JSON → attribute value (``None`` = dropped)."""
    if value is None:
        return None
    if isinstance(value, (str, bool, int, float)):
        return value
    if isinstance(value, list):
        return [v for v in (_attr_value(x) for x in value) if v is not None]
    if isinstance(value, Mapping):
        return {
            str(k): v for k, v in ((k, _attr_value(x)) for k, x in value.items()) if v is not None
        }
    return None


def _attrs(value: Any) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, item in _record(value).items():
        converted = _attr_value(item)
        if converted is not None:
            out[str(key)] = converted
    return out


def _json_array(value: Any) -> list[Mapping[str, Any]]:
    if not isinstance(value, str) or not value:
        return []
    try:
        items = json.loads(value)
    except ValueError:
        return []
    return [_record(i) for i in items] if isinstance(items, list) else []


def _native_attr(value: Any) -> Any:
    record = _record(value)
    for key in ("string_value", "int_value", "double_value", "bool_value"):
        if key in record:
            return record[key]
    if "array_value" in record:
        values = _record(record["array_value"]).get("values")
        return [_native_attr(v) for v in values] if isinstance(values, list) else []
    return value


def _events(meta: Mapping[str, Any], raw: RawDatadogSpan) -> list[SpanEvent]:
    out: list[SpanEvent] = []
    for event in _json_array(meta.get("events")):
        ns = _int(event.get("time_unix_nano")) or 0
        out.append(
            SpanEvent(_str(event.get("name")) or "", ns // 1000, _attrs(event.get("attributes")))
        )
    native = _first(raw, "span_events", "spanEvents")
    for item in native if isinstance(native, list) else []:
        event = _record(item)
        ns = _int(_first(event, "time_unix_nano", "timeUnixNano")) or 0
        attrs = {str(k): _native_attr(v) for k, v in _record(event.get("attributes")).items()}
        out.append(SpanEvent(_str(event.get("name")) or "", ns // 1000, _attrs(attrs)))
    return out


def _link_trace_id(link: Mapping[str, Any]) -> str | None:
    raw = _str(_first(link, "trace_id", "traceId", "traceID"))
    if raw is None:
        return None
    raw = raw.lower()
    high = _str(_first(link, "trace_id_high", "traceIdHigh"))
    try:
        if high is not None and _DIGITS.match(raw):
            high_hex = high.lower() if _HEX16.match(high.lower()) else format(int(high), "016x")
            return from_datadog_trace_id(raw, high_hex)
        if _HEX16.match(raw):
            return _ZERO_HIGH + raw
        return normalize_datadog_trace_id(raw)
    except (ValidationError, ValueError):
        return None


def _link_span_id(link: Mapping[str, Any]) -> str | None:
    raw = _str(_first(link, "span_id", "spanId", "spanID"))
    if raw is None:
        return None
    raw = raw.lower()
    if _HEX16.match(raw) and any(c in "abcdef" for c in raw):
        return raw
    try:
        return normalize_datadog_span_id(raw)
    except ValidationError:
        return None


def _links(meta: Mapping[str, Any], raw: RawDatadogSpan) -> list[SpanLink]:
    items = _json_array(meta.get("_dd.span_links"))
    native = _first(raw, "span_links", "spanLinks")
    items += [_record(i) for i in (native if isinstance(native, list) else [])]
    out: list[SpanLink] = []
    for link in items:
        trace_id, span_id = _link_trace_id(link), _link_span_id(link)
        if trace_id is not None and span_id is not None:
            out.append(SpanLink(trace_id, span_id, _attrs(link.get("attributes"))))
    return out


def _truthy(value: Any) -> bool:
    """JavaScript's ``Number(v) !== 0`` for flags such as ``error``."""
    if value is None or isinstance(value, (Mapping, list)):
        return False
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return False
        try:
            return float(text) != 0
        except ValueError:
            return True
    return False


def _require_span_id(value: str | None) -> str:
    if value is None:
        raise ValidationError("datadog span: span id is missing or zero")
    return value


def datadog_trace_span_to_span(raw: RawDatadogSpan) -> Span:
    """Map a Datadog trace span to a SPEC :class:`~grow_datadog_apm.Span`.

    Accepts the Agent's format (``trace_id``, ``span_id``, ``parent_id``, ``start``,
    ``duration``, ``meta``, ``metrics``) and the trace-by-id API format (``traceID``,
    ``traceIDFull``, ``spanID``, ``parentID``, ``startTime``, ``endTime``, ``duration``).

    The full trace id comes from ``traceIDFull`` / ``trace_id_hex`` / meta ``otel.trace_id``, else
    the decimal low 64 bits plus meta ``_dd.p.tid``. ``name`` = Datadog resource (the OTel span
    name for OTLP spans), kind = meta ``span.kind``, status = ``error`` flag (message from
    ``error.message``, ``error.msg`` or ``otel.status_description``) or meta
    ``otel.status_code``. Events come from meta ``events`` and ``span_events``, links from meta
    ``_dd.span_links`` and ``span_links``. Attributes are the remaining meta (strings) and
    metrics (numbers), plus ``operation_name``, ``resource_name`` and ``span.type``. Invalid ids
    raise :class:`ValidationError`.
    """
    meta = _record(raw.get("meta"))
    metrics = _record(raw.get("metrics"))
    full = _str(_first(raw, "traceIDFull", "trace_id_hex")) or _str(meta.get("otel.trace_id"))
    if full is not None:
        trace_id = normalize_datadog_trace_id(full.rjust(32, "0"))
    else:
        low = _str(_first(raw, "traceID", "trace_id", "traceId")) or "0"
        high = _str(meta.get("_dd.p.tid")) or _str(raw.get("trace_id_high"))
        trace_id = from_datadog_trace_id(low, high)
    span_id = _require_span_id(
        normalize_datadog_span_id(_str(_first(raw, "spanID", "span_id", "spanId")) or "0")
    )
    parent_id = normalize_datadog_span_id(_str(_first(raw, "parentID", "parent_id", "parentId")))
    start_ns = _int(_first(raw, "startTime", "start")) or 0
    end_ns = _int(_first(raw, "endTime", "end"))
    duration_ns = _int(raw.get("duration"))
    if duration_ns is None:
        duration_ns = end_ns - start_ns if end_ns is not None else 0
    if end_ns is None:
        end_ns = start_ns + duration_ns
    dd_name = _str(raw.get("name"))
    resource = _str(_first(raw, "resource", "resource_name"))
    span_type = _str(raw.get("type"))

    attributes: dict[str, Any] = {
        str(k): v
        for k, v in meta.items()
        if isinstance(v, str) and k not in ("events", "_dd.span_links")
    }
    for key, value in metrics.items():
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            attributes[str(key)] = value
        elif isinstance(value, str) and value:
            try:
                number = float(value)
            except ValueError:
                continue
            if math.isfinite(number):
                attributes[str(key)] = number
    if dd_name:
        attributes["operation_name"] = dd_name
    if resource:
        attributes["resource_name"] = resource
    if span_type:
        attributes["span.type"] = span_type

    if _truthy(raw.get("error")):
        message = _str(_first(meta, "error.message", "error.msg", "otel.status_description"))
        status = SpanStatus("error", message)
    elif (_str(meta.get("otel.status_code")) or "").lower() == "ok":
        status = SpanStatus("ok")
    else:
        status = SpanStatus()
    return Span(
        trace_id=trace_id,
        span_id=span_id,
        parent_span_id=parent_id,
        name=resource or dd_name or "",
        kind=datadog_kind(meta.get("span.kind")),
        service_name=_str(raw.get("service")) or "",
        start_micros=start_ns // 1000,
        end_micros=end_ns // 1000,
        duration_micros=duration_ns // 1000,
        status=status,
        attributes=attributes,
        events=_events(meta, raw),
        links=_links(meta, raw),
    )


def _flatten(prefix: str, value: Any, out: dict[str, Any]) -> None:
    if isinstance(value, Mapping):
        for key, item in value.items():
            _flatten(f"{prefix}.{key}" if prefix else str(key), item, out)
        return
    converted = _attr_value(value)
    if converted is not None and prefix:
        out[prefix] = converted


_FRACTION = re.compile(r"\.(\d+)")


def _iso_to_micros(value: Any) -> int | None:
    if not isinstance(value, str) or not value:
        return None
    text = value.strip()
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    # Python < 3.11 accepts only 3 or 6 fraction digits: normalize to 6.
    text = _FRACTION.sub(lambda m: "." + (m.group(1) + "000000")[:6], text, count=1)
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return datetime_to_micros(parsed)


def datadog_search_span_to_span(item: RawDatadogSpan) -> Span:
    """Map one span of the spans search API (``data[i]`` of ``POST /api/v2/spans/events/search``,
    or its ``attributes``) to a SPEC :class:`~grow_datadog_apm.Span`.

    Nested span attributes (``attributes``, ``custom``) are flattened to dotted keys; ``tags``
    (``key:value``) are added as attributes. ``name`` = ``resource_name``, kind from
    ``span.kind``, status ``error`` when ``status`` is ``error`` or ``error`` is set; duration
    from the ``duration`` attribute (nanoseconds) when present, else end - start.
    """
    a = item
    if "attributes" in item and item.get("type") == "spans":
        a = _record(item.get("attributes"))
    attributes: dict[str, Any] = {}
    tags = a.get("tags")
    for tag in tags if isinstance(tags, list) else []:
        if isinstance(tag, str):
            key, sep, value = tag.partition(":")
            if sep and key:
                attributes[key] = value
    _flatten("", a.get("custom"), attributes)
    _flatten("", a.get("attributes"), attributes)
    for key in ("env", "host", "type", "resource_hash", "ingestion_reason"):
        text = _str(a.get(key))
        if text is not None:
            attributes["span.type" if key == "type" else key] = text
    op_name = _str(a.get("operation_name")) or _str(attributes.get("operation_name"))
    if op_name:
        attributes["operation_name"] = op_name
    resource = _str(a.get("resource_name")) or _str(attributes.get("resource_name"))
    if resource:
        attributes["resource_name"] = resource
    start = _iso_to_micros(a.get("start_timestamp")) or 0
    end = _iso_to_micros(a.get("end_timestamp"))
    duration_ns = _int(attributes.get("duration"))
    if duration_ns is not None:
        duration = duration_ns // 1000
    else:
        duration = end - start if end is not None else 0
    if end is None:
        end = start + duration
    status_word = (_str(a.get("status")) or _str(attributes.get("status")) or "").lower()
    if status_word == "error" or _truthy(attributes.get("error")):
        status = SpanStatus(
            "error", _str(attributes.get("error.message")) or _str(attributes.get("error.msg"))
        )
    elif (_str(attributes.get("otel.status_code")) or "").lower() == "ok":
        status = SpanStatus("ok")
    else:
        status = SpanStatus()
    return Span(
        trace_id=normalize_datadog_trace_id(_str(a.get("trace_id")) or "0"),
        span_id=_require_span_id(normalize_datadog_span_id(_str(a.get("span_id")) or "0")),
        parent_span_id=normalize_datadog_span_id(_str(a.get("parent_id"))),
        name=resource or op_name or "",
        kind=datadog_kind(attributes.get("span.kind")),
        service_name=_str(a.get("service")) or "",
        start_micros=start,
        end_micros=end,
        duration_micros=duration,
        status=status,
        attributes=attributes,
    )
