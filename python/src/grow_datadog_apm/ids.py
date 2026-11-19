"""Datadog <-> OpenTelemetry id conversion (API.md §3, DECISIONS.md #3).

Datadog stores a trace id as the low 64 bits in unsigned decimal plus, for 128-bit ids, the high
64 bits as 16 lower-case hex characters (``_dd.p.tid``). OpenTelemetry uses 32 hex characters.
Span ids are unsigned 64-bit decimals in Datadog and 16 hex characters in OpenTelemetry.
"""

from __future__ import annotations

import dataclasses
import re

from ._core.errors import ValidationError

__all__ = [
    "DatadogTraceId",
    "to_datadog_trace_id",
    "from_datadog_trace_id",
    "to_datadog_span_id",
    "from_datadog_span_id",
    "datadog_trace_id_string",
    "normalize_datadog_trace_id",
    "normalize_datadog_span_id",
    "trace_id_for_path",
]

_ZERO_HIGH = "0" * 16
_HEX = re.compile(r"^[0-9a-f]+$")
_DIGITS = re.compile(r"^[0-9]+$")
_MAX_U64 = (1 << 64) - 1


@dataclasses.dataclass(frozen=True)
class DatadogTraceId:
    """A trace id split the way Datadog stores it."""

    #: Low 64 bits as an unsigned decimal string (``x-datadog-trace-id``, the Agent's
    #: ``trace_id``).
    low64_decimal: str
    #: High 64 bits as 16 lower-case hex characters (``_dd.p.tid``); zeros for a 64-bit id.
    high64_hex: str
    #: ``True`` when the high 64 bits are not zero (the id needs ``_dd.p.tid``).
    is_128_bit: bool


def _invalid(what: str) -> ValidationError:
    return ValidationError(f"datadog id: {what}")


def _is_hex(text: str) -> bool:
    return bool(_HEX.match(text))


def _is_digits(text: str) -> bool:
    return bool(_DIGITS.match(text))


def _hex_trace_id(trace_id_hex: str) -> str:
    text = trace_id_hex.strip().lower()
    if not text or len(text) > 32 or not _is_hex(text):
        raise _invalid("trace id must be 1 to 32 hex characters")
    text = text.rjust(32, "0")
    if text == "0" * 32:
        raise _invalid("trace id must not be all zeros")
    return text


def _parse_u64(decimal: str, what: str) -> int:
    text = decimal.strip()
    if not _is_digits(text):
        raise _invalid(f"{what} must be an unsigned decimal")
    value = int(text)
    if value > _MAX_U64:
        raise _invalid(f"{what} exceeds 2^64-1")
    return value


def to_datadog_trace_id(trace_id_hex: str) -> DatadogTraceId:
    """Split an OpenTelemetry trace id (up to 32 hex characters, left-padded) into Datadog's form.

    Invalid or all-zero ids raise :class:`ValidationError`.

    >>> to_datadog_trace_id("a3ce929d0e0e47364bf92f3577b34da6").low64_decimal
    '5474458728733560230'
    """
    text = _hex_trace_id(trace_id_hex)
    high = text[:16]
    return DatadogTraceId(str(int(text[16:], 16)), high, high != _ZERO_HIGH)


def from_datadog_trace_id(low64_decimal: str, high64_hex: str | None = None) -> str:
    """Join a Datadog trace id (decimal low 64 bits, optional ``_dd.p.tid`` high half in hex)
    into a 32-character OpenTelemetry trace id. ``None``/empty ``high64_hex`` = zero high half.

    >>> from_datadog_trace_id("1", "ffffffffffffffff")
    'ffffffffffffffff0000000000000001'
    >>> from_datadog_trace_id("42")
    '0000000000000000000000000000002a'
    """
    low = _parse_u64(low64_decimal, "trace id")
    high = _ZERO_HIGH
    if high64_hex:
        high = high64_hex.strip().lower()
        if len(high) != 16 or not _is_hex(high):
            raise _invalid("high 64 bits must be 16 hex characters")
    out = high + format(low, "016x")
    if out == "0" * 32:
        raise _invalid("trace id must not be all zeros")
    return out


def to_datadog_span_id(span_id_hex: str) -> str:
    """OpenTelemetry span id (up to 16 hex characters) → Datadog unsigned decimal."""
    text = span_id_hex.strip().lower()
    if not text or len(text) > 16 or not _is_hex(text):
        raise _invalid("span id must be 1 to 16 hex characters")
    return str(int(text, 16))


def from_datadog_span_id(decimal: str) -> str:
    """Datadog unsigned decimal span id → 16 lower-case hex characters."""
    return format(_parse_u64(decimal, "span id"), "016x")


def datadog_trace_id_string(trace_id_hex: str) -> str:
    """The trace id as Datadog's log correlation (``dd.trace_id``) and search API use it:
    32 lower-case hex characters for a 128-bit id, the low 64 bits in decimal for a 64-bit id."""
    split = to_datadog_trace_id(trace_id_hex)
    return _hex_trace_id(trace_id_hex) if split.is_128_bit else split.low64_decimal


def normalize_datadog_trace_id(value: str | int) -> str:
    """Normalize any trace id Datadog returns to 32 lower-case hex characters.

    A 32-character value is hex, a shorter all-digit value (at most 20 digits) or an ``int`` is
    the 64-bit decimal form, anything else is hex.
    """
    if isinstance(value, bool):
        raise _invalid("trace id must be a string or an integer")
    if isinstance(value, int):
        if value < 0 or value >> 128:
            raise _invalid("trace id is out of range")
        return _hex_trace_id(format(value, "x"))
    text = value.strip().lower()
    if len(text) == 32 and _is_hex(text):
        return text
    if _is_digits(text) and len(text) <= 20:
        return from_datadog_trace_id(text)
    return _hex_trace_id(text)


def normalize_datadog_span_id(value: str | int | None) -> str | None:
    """Datadog decimal span id → 16 hex characters; ``None`` for an empty or zero id."""
    if value is None or isinstance(value, bool):
        return None
    text = str(value).strip()
    if not text or (_is_digits(text) and int(text) == 0):
        return None
    return from_datadog_span_id(text)


def trace_id_for_path(trace_id_hex: str) -> str:
    """The id to put in a ``GET /api/v2/trace/{id}`` path.

    Datadog reads an all-digit id as decimal, so a hex id without a letter is sent as its full
    decimal value; otherwise lower-case hex.
    """
    text = _hex_trace_id(trace_id_hex)
    if any(c in "abcdef" for c in text):
        return text
    return str(int(text, 16))
