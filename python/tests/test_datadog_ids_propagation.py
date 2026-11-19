"""Id helpers, the Datadog propagator (ddtrace vectors) and log correlation (API.md §3)."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest
from opentelemetry import context as otel_context
from opentelemetry import trace as otel_trace
from opentelemetry.propagators.textmap import DefaultGetter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

import grow_datadog_apm as sdk
from _support import load_fixture


def ctx_of(trace_hex: str, span_hex: str, sampled: bool = True) -> otel_context.Context:
    sc = SpanContext(
        int(trace_hex, 16),
        int(span_hex, 16),
        is_remote=True,
        trace_flags=TraceFlags(TraceFlags.SAMPLED if sampled else TraceFlags.DEFAULT),
    )
    return otel_trace.set_span_in_context(NonRecordingSpan(sc))


def span_hex(ctx: otel_context.Context) -> tuple[str, str, bool]:
    sc = otel_trace.get_current_span(ctx).get_span_context()
    return format(sc.trace_id, "032x"), format(sc.span_id, "016x"), sc.trace_flags.sampled


# --------------------------------------------------------------------------- ids


@pytest.mark.parametrize("vector", load_fixture("toDatadogTraceId.vectors.json"))
def test_trace_id_vectors(vector: dict[str, Any]) -> None:
    hex_id = vector["otel_trace_id_hex"]
    split = sdk.to_datadog_trace_id(hex_id)
    assert split.low64_decimal == vector["datadog_trace_id_low64_decimal"]
    assert split.high64_hex == vector["datadog_trace_id_high64_hex"]
    assert split.is_128_bit == (vector["x-datadog-tags"] is not None)
    observed = vector["agent_observed"]
    if isinstance(observed, dict):
        assert split.low64_decimal == observed["agent_trace_id"]
    high = split.high64_hex if split.is_128_bit else None
    assert sdk.from_datadog_trace_id(split.low64_decimal, high) == hex_id
    expected = hex_id if split.is_128_bit else split.low64_decimal
    assert sdk.datadog_trace_id_string(hex_id) == expected


def test_id_helpers() -> None:
    assert sdk.to_datadog_trace_id("2a") == sdk.DatadogTraceId("42", "0" * 16, False)
    assert sdk.from_datadog_trace_id("42", "") == "0" * 30 + "2a"
    assert (
        sdk.from_datadog_trace_id(" 1 ", "FFFFFFFFFFFFFFFF") == "ffffffffffffffff" + "0" * 15 + "1"
    )
    assert sdk.to_datadog_span_id("00000000000000ff") == "255"
    assert sdk.to_datadog_span_id("FF") == "255"
    assert sdk.from_datadog_span_id("255") == "00000000000000ff"
    for bad in ("", "xyz", "1" * 33, "0" * 32):
        with pytest.raises(sdk.ValidationError):
            sdk.to_datadog_trace_id(bad)
    for args in (("abc",), ("-1",), (str(1 << 64),), ("0",), ("1", "abc"), ("1", "zz" * 8)):
        with pytest.raises(sdk.ValidationError):
            sdk.from_datadog_trace_id(*args)
    for bad in ("", "g", "1" * 17):
        with pytest.raises(sdk.ValidationError):
            sdk.to_datadog_span_id(bad)
    with pytest.raises(sdk.ValidationError):
        sdk.from_datadog_span_id("1.5")


def test_normalize_datadog_ids() -> None:
    assert sdk.normalize_datadog_trace_id("A3CE929D0E0E47364BF92F3577B34DA6") == (
        "a3ce929d0e0e47364bf92f3577b34da6"
    )
    assert sdk.normalize_datadog_trace_id("42") == "0" * 30 + "2a"
    assert sdk.normalize_datadog_trace_id(42) == "0" * 30 + "2a"
    assert sdk.normalize_datadog_trace_id("abc") == "0" * 29 + "abc"
    assert sdk.normalize_datadog_trace_id("1" * 21) == "0" * 11 + "1" * 21
    for bad in (True, -1, 1 << 128, "0"):
        with pytest.raises(sdk.ValidationError):
            sdk.normalize_datadog_trace_id(bad)  # type: ignore[arg-type]
    assert sdk.normalize_datadog_span_id("255") == "00000000000000ff"
    assert sdk.normalize_datadog_span_id(255) == "00000000000000ff"
    for empty in (None, "", "0", "000", True):
        assert sdk.normalize_datadog_span_id(empty) is None  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError):
        sdk.normalize_datadog_span_id("ff")


def test_trace_id_for_path() -> None:
    assert sdk.trace_id_for_path("0000000000000000abc1230000000000") == (
        "0000000000000000abc1230000000000"
    )
    assert sdk.trace_id_for_path("00000000000000000020000000000001") == "9007199254740993"
    assert sdk.trace_id_for_path("10000000000000000000000000000000") == str(1 << 124)


# --------------------------------------------------------------------------- propagator


@pytest.mark.parametrize("vector", load_fixture("propagation.datadog_inject.vectors.json"))
def test_inject_matches_ddtrace(vector: dict[str, Any]) -> None:
    c = vector["context"]
    carrier: dict[str, str] = {}
    sdk.DatadogPropagator().inject(
        carrier, ctx_of(c["trace_id_hex"], c["span_id_hex"], c["sampling_priority"] > 0)
    )
    assert carrier == vector["reference_headers"]
    for key, value in carrier.items():
        assert vector["ddtrace_headers"][key] == value


@pytest.mark.parametrize("vector", load_fixture("propagation.datadog_extract.vectors.json"))
def test_extract_matches_ddtrace(vector: dict[str, Any]) -> None:
    expected = vector.get("ddtrace_context")
    ctx = sdk.DatadogPropagator().extract(vector["headers"])
    if expected is None:
        assert not otel_trace.get_current_span(ctx).get_span_context().is_valid
        return
    trace_hex, sid, sampled = span_hex(ctx)
    assert trace_hex == expected["trace_id_hex"]
    assert sid == expected["span_id_hex"]
    assert sampled == (expected.get("sampling_priority", 1) > 0)
    links = sdk.terminated_context_links(ctx)
    assert len(links) == len(vector.get("span_links", []))
    if links:
        assert links[0].context.trace_id == 22685491128062564232121423433698517538
        assert links[0].context.span_id == 2748


def test_extract_edge_cases() -> None:
    prop = sdk.DatadogPropagator()
    base = otel_context.Context()
    assert prop.extract({}, base) is base
    assert prop.extract({"x-datadog-trace-id": "1"}, base) is base
    assert prop.extract({"x-datadog-trace-id": "abc", "x-datadog-parent-id": "1"}, base) is base
    assert prop.extract({"x-datadog-trace-id": "0", "x-datadog-parent-id": "1"}, base) is base
    big = str(1 << 64)
    assert prop.extract({"x-datadog-trace-id": big, "x-datadog-parent-id": "1"}, base) is base
    ctx = prop.extract(
        {
            "x-datadog-trace-id": "42",
            "x-datadog-parent-id": "0",
            "x-datadog-sampling-priority": "2",
        },
        base,
    )
    assert ctx is base  # span id 0 is invalid
    kept = prop.extract(
        {
            "x-datadog-trace-id": "42",
            "x-datadog-parent-id": "7",
            "x-datadog-sampling-priority": "-1",
            "x-datadog-tags": "_dd.p.dm=-1, _dd.p.tid=nothex",
        }
    )
    assert span_hex(kept) == ("0" * 30 + "2a", "0" * 15 + "7", False)
    weird = prop.extract(
        {
            "x-datadog-trace-id": "42",
            "x-datadog-parent-id": "7",
            "x-datadog-sampling-priority": "x",
            "x-datadog-tags": "novalue,_dd.p.tid=0000000000000001",
        }
    )
    assert span_hex(weird) == ("0000000000000001" + "0" * 14 + "2a", "0" * 15 + "7", True)
    assert (
        sdk.extract_datadog_context({"x-datadog-trace-id": " ", "x-datadog-parent-id": "1"}) is None
    )
    assert prop.fields == {
        "x-datadog-trace-id",
        "x-datadog-parent-id",
        "x-datadog-sampling-priority",
        "x-datadog-tags",
    }
    carrier: dict[str, str] = {}
    prop.inject(carrier, otel_context.Context())
    assert carrier == {}


DD_HEADERS = {
    "x-datadog-trace-id": "5474458728733560230",
    "x-datadog-parent-id": "67667974448284343",
    "x-datadog-sampling-priority": "1",
    "x-datadog-tags": "_dd.p.tid=a3ce929d0e0e4736",
}
DD_TRACE = "a3ce929d0e0e47364bf92f3577b34da6"


def test_conflict_rules() -> None:
    prop = sdk.DatadogPropagator()
    # different trace id: Datadog wins, W3C becomes a terminated context
    conflict = {
        **DD_HEADERS,
        "traceparent": "00-11111111111111112222222222222222-0000000000000abc-01",
    }
    ctx = prop.extract(conflict)
    assert span_hex(ctx)[0] == DD_TRACE
    found = sdk.terminated_context(ctx)
    assert found is not None
    assert found.winner_span_id == "00f067aa0ba902b7"
    assert format(found.span_context.trace_id, "032x") == "11111111111111112222222222222222"
    links = sdk.terminated_context_links(ctx)
    assert len(links) == 1
    assert dict(links[0].attributes or {}) == {
        "reason": "terminated_context",
        "context_headers": "tracecontext",
    }
    assert sdk.terminated_context_links(otel_context.Context()) == []
    # same trace id: the W3C parent id wins, the Datadog sampling decision is kept
    same = {
        **DD_HEADERS,
        "x-datadog-sampling-priority": "0",
        "traceparent": f"00-{DD_TRACE}-0000000000000def-01",
    }
    assert span_hex(prop.extract(same)) == (DD_TRACE, "0000000000000def", False)
    # with tracecontext first (the composite order), the W3C context (and tracestate) is kept
    w3c_ctx = TraceContextTextMapPropagator().extract({**same, "tracestate": "dd=s:1"})
    kept = prop.extract(same, w3c_ctx)
    assert kept is w3c_ctx
    # invalid traceparent is ignored
    for bad in (
        "garbage",
        f"ff-{DD_TRACE}-0000000000000def-01",
        f"00-{'0' * 32}-0000000000000def-01",
    ):
        ctx2 = prop.extract({**DD_HEADERS, "traceparent": bad})
        assert span_hex(ctx2)[1] == "00f067aa0ba902b7"
        assert sdk.terminated_context(ctx2) is None


def test_extract_headers_with_list_getter() -> None:
    headers = {k.upper(): [v] for k, v in DD_HEADERS.items()}
    lowered = {k.lower(): v for k, v in headers.items()}
    ctx = sdk.DatadogPropagator().extract(lowered, getter=DefaultGetter())
    assert span_hex(ctx)[0] == DD_TRACE
    via_helper = sdk.extract_headers(
        {k.title(): v for k, v in DD_HEADERS.items()}, propagator=sdk.DatadogPropagator()
    )
    assert span_hex(via_helper)[0] == DD_TRACE


@pytest.fixture
def provider() -> Iterator[tuple[TracerProvider, InMemorySpanExporter]]:
    exporter = InMemorySpanExporter()
    tp = TracerProvider(resource=Resource.create({"service.name": "res-svc"}))
    tp.add_span_processor(sdk.DatadogSpanLinkProcessor())
    tp.add_span_processor(SimpleSpanProcessor(exporter))
    yield tp, exporter
    tp.shutdown()


def test_span_link_processor(provider: tuple[TracerProvider, InMemorySpanExporter]) -> None:
    tp, exporter = provider
    tracer = tp.get_tracer("t")
    conflict = {
        **DD_HEADERS,
        "traceparent": "00-11111111111111112222222222222222-0000000000000abc-01",
    }
    ctx = sdk.DatadogPropagator().extract(conflict)
    with tracer.start_as_current_span("first", context=ctx):
        with tracer.start_as_current_span("child"):
            pass
    with tracer.start_as_current_span("unrelated"):
        pass
    # a remote parent that is not the winner gets no link
    other = otel_context.set_value("unused", 1, ctx_of(DD_TRACE, "0000000000000001"))
    other = otel_context.set_value(
        sdk.propagator._TERMINATED_KEY,  # type: ignore[attr-defined]
        sdk.terminated_context(ctx),
        other,
    )
    with tracer.start_as_current_span("not-winner", context=other):
        pass
    by_name = {s.name: s for s in exporter.get_finished_spans()}
    links = by_name["first"].links
    assert len(links) == 1
    assert format(links[0].context.trace_id, "032x") == "11111111111111112222222222222222"
    assert links[0].attributes is not None and links[0].attributes["reason"] == "terminated_context"
    assert by_name["child"].links == ()
    assert by_name["unrelated"].links == ()
    assert by_name["not-winner"].links == ()
    processor = sdk.DatadogSpanLinkProcessor()
    processor.on_end(by_name["first"])
    processor.shutdown()
    assert processor.force_flush()


# --------------------------------------------------------------------------- log correlation


@pytest.mark.parametrize("vector", load_fixture("traceFields.ddtrace_log_correlation.json"))
def test_datadog_log_fields_match_ddtrace(vector: dict[str, Any]) -> None:
    expected = vector["ddtrace_log_correlation"]
    sc = SpanContext(
        int(vector["trace_id_hex"], 16),
        int(vector["span_id_hex"], 16),
        is_remote=False,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )
    token = otel_context.attach(otel_trace.set_span_in_context(NonRecordingSpan(sc)))
    try:
        fields = sdk.datadog_log_fields(
            service=expected["dd.service"], env=expected["dd.env"], version=expected["dd.version"]
        )
    finally:
        otel_context.detach(token)
    assert fields == expected


def test_datadog_log_fields_sources(provider: tuple[TracerProvider, InMemorySpanExporter]) -> None:
    tp, _ = provider
    sdk.set_datadog_log_defaults(service="default-svc", env="default-env", version="0.0.1")
    try:
        assert sdk.datadog_log_fields() == {
            "dd.service": "default-svc",
            "dd.env": "default-env",
            "dd.version": "0.0.1",
        }
        with tp.get_tracer("t").start_as_current_span("s") as span:
            fields = sdk.datadog_log_fields()
            sc = span.get_span_context()
        assert fields["dd.service"] == "res-svc"  # the span's resource wins over defaults
        assert fields["dd.env"] == "default-env"
        assert fields["dd.span_id"] == str(sc.span_id)
        legacy = TracerProvider(resource=Resource.create({"deployment.environment": "legacy"}))
        with legacy.get_tracer("t").start_as_current_span("s"):
            assert sdk.datadog_log_fields()["dd.env"] == "legacy"
        legacy.shutdown()
        sdk.set_datadog_log_defaults()
        assert sdk.datadog_log_fields() == {}
    finally:
        sdk.set_datadog_log_defaults()
