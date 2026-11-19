"""Datadog span formats → SPEC model and the Datadog error attributes (API.md §2.1, DECISIONS)."""

from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

import pytest
from opentelemetry import trace as otel_trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor, SpanExportResult
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import Status, StatusCode

import grow_datadog_apm as sdk
from _support import fixture_body, load_fixture

TRACE = "0123456789abcdefffffffffffffffff"


# --------------------------------------------------------------------------- agent format


def test_agent_spans_from_otlp_json() -> None:
    spans = {
        s.name: s
        for s in map(
            sdk.datadog_trace_span_to_span,
            load_fixture("initTracing.otlp_http_json.agent_spans.json"),
        )
    }
    assert set(spans) == {"GET /items/{id}", "POST", "compute", "orders publish", "orders process"}
    root = spans["GET /items/{id}"]
    assert root.trace_id == TRACE
    assert root.span_id == "00000000000000ff"
    assert root.parent_span_id is None
    assert root.kind == "server"
    assert root.service_name == "grow-apm-json-d0b66575"
    assert root.duration_micros == 9000
    assert root.start_micros == 1795035814558726
    assert root.end_micros == root.start_micros + 9000
    assert root.status == sdk.SpanStatus("unset")
    assert root.attributes["operation_name"] == "http.server.request"
    assert root.attributes["resource_name"] == "GET /items/{id}"
    assert root.attributes["http.status_code"] == 200.0
    assert root.attributes["env"] == "ci"
    client = spans["POST"]
    assert client.status == sdk.SpanStatus("error", "boom")
    assert client.parent_span_id == "00000000000000ff"
    compute = spans["compute"]
    assert compute.kind == "internal"
    assert compute.events == (sdk.SpanEvent("cache.miss", 1795035814562226, {"key": "k1", "n": 3}),)
    assert compute.links == (
        sdk.SpanLink(
            "a3ce929d0e0e47364bf92f3577b34da6", "00f067aa0ba902b7", {"link.kind": "follows"}
        ),
    )
    assert "events" not in compute.attributes and "_dd.span_links" not in compute.attributes
    assert spans["orders process"].status.code == "ok"
    assert spans["orders publish"].kind == "producer"


def test_trace_reader_span_and_sdk_fixture() -> None:
    raw = load_fixture("traceReader.spans.ok.json")["spans"][0]
    span = sdk.datadog_trace_span_to_span(raw)
    assert span.trace_id == "69a2af8e68444220f1bf29ab7bd501bf"
    assert span.name == "bootstrap.probe"
    assert span.attributes["span.type"] == "custom"
    for item in load_fixture("initTracing.otel_sdk_http.agent_spans.json"):
        assert sdk.datadog_trace_span_to_span(item).service_name


def test_trace_by_id_format() -> None:
    raw = fixture_body("getTraceById.docs_example.json")["data"]["attributes"]["spans"][0]
    span = sdk.datadog_trace_span_to_span(raw)
    assert span.trace_id == "0000000000000000abc1230000000000"
    assert span.span_id == format(9876543210987654321, "016x")
    assert span.parent_span_id is None
    assert span.name == "GET /products"
    assert span.duration_micros == 500000
    assert span.end_micros == 1716800000500000
    assert span.kind == "unspecified"
    assert span.attributes["http.status_code"] == 200
    assert span.attributes["span.type"] == "web"


def test_trace_span_variants() -> None:
    base: dict[str, Any] = {"trace_id": "42", "span_id": "7", "service": "s", "name": "op"}
    span = sdk.datadog_trace_span_to_span({**base, "meta": {"_dd.p.tid": "ffffffffffffffff"}})
    assert span.trace_id == "ffffffffffffffff000000000000002a"
    assert span.name == "op" and span.kind == "unspecified"
    high = sdk.datadog_trace_span_to_span({**base, "trace_id_high": "0000000000000001"})
    assert high.trace_id == "0000000000000001000000000000002a"
    short = sdk.datadog_trace_span_to_span({**base, "meta": {"otel.trace_id": "2a"}})
    assert short.trace_id == "0" * 30 + "2a"
    timed = sdk.datadog_trace_span_to_span({**base, "start": 2000, "end": 7000})
    assert (timed.start_micros, timed.end_micros, timed.duration_micros) == (2, 7, 5)
    numbers = sdk.datadog_trace_span_to_span(
        {
            **base,
            "error": "1",
            "metrics": {"a": "1.5", "b": "nan", "c": "x", "d": "", "e": True, "f": 2},
            "meta": {"error.message": "m", "n": 1, "otel.status_description": "d"},
        }
    )
    assert numbers.status == sdk.SpanStatus("error", "m")
    assert numbers.attributes["a"] == 1.5 and numbers.attributes["f"] == 2
    assert "b" not in numbers.attributes and "n" not in numbers.attributes
    for flag in ("0", "", " ", 0.0, None, {"x": 1}, False):
        assert sdk.datadog_trace_span_to_span({**base, "error": flag}).status.code == "unset"
    for flag in ("abc", True, 1.5, 1):
        assert sdk.datadog_trace_span_to_span({**base, "error": flag}).status.code == "error"
    native = sdk.datadog_trace_span_to_span(
        {
            **base,
            "spanEvents": [
                {
                    "name": "e",
                    "timeUnixNano": 5000,
                    "attributes": {
                        "s": {"type": 0, "string_value": "v"},
                        "a": {"array_value": {"values": [{"int_value": 1}, {"bool_value": True}]}},
                        "bad": {"array_value": {"values": "x"}},
                        "plain": 3,
                    },
                }
            ],
            "span_links": [
                {"trace_id": "42", "trace_id_high": "1", "span_id": "7", "attributes": {"k": "v"}},
                {"traceId": "00000000000000aa", "spanId": "00000000000000ab"},
                {"trace_id": "42", "trace_id_high": "00000000000000ff", "span_id": "8"},
                {"trace_id": "xyz", "span_id": "1"},
                {"trace_id": "42", "span_id": "zz"},
                {"trace_id": "42"},
                {"span_id": "1"},
                {"trace_id": "42", "trace_id_high": "q", "span_id": "1"},
            ],
            "meta": {"events": "not json", "_dd.span_links": "{}"},
        }
    )
    assert native.events == (
        sdk.SpanEvent("e", 5, {"s": "v", "a": [1, True], "bad": [], "plain": 3}),
    )
    assert [(link.trace_id, link.span_id) for link in native.links] == [
        ("0000000000000001000000000000002a", "0000000000000007"),
        ("0" * 30 + "aa", "00000000000000ab"),
        ("00000000000000ff000000000000002a", "0000000000000008"),
    ]
    floats = sdk.datadog_trace_span_to_span({**base, "trace_id": 42.0, "span_id": 7, "start": 1e6})
    assert floats.trace_id.endswith("2a") and floats.start_micros == 1000
    with pytest.raises(sdk.ValidationError):
        sdk.datadog_trace_span_to_span({"trace_id": "42"})
    with pytest.raises(sdk.ValidationError):
        sdk.datadog_trace_span_to_span({"span_id": "1"})
    weird = sdk.datadog_trace_span_to_span(
        {**base, "start": "nope", "duration": float("inf"), "type": 5, "resource": ["x"]}
    )
    assert weird.start_micros == 0 and weird.name == "op"


def test_datadog_kind() -> None:
    assert sdk.datadog_kind("SERVER") == "server"
    assert sdk.datadog_kind("weird") == "unspecified"
    assert sdk.datadog_kind(None) == "unspecified"


# --------------------------------------------------------------------------- search format


def search_item(**attrs: Any) -> dict[str, Any]:
    base = {"trace_id": "42", "span_id": "7", "service": "web"}
    base.update(attrs)
    return {"id": "ev", "type": "spans", "attributes": base}


def test_search_span_mapping() -> None:
    span = sdk.datadog_search_span_to_span(
        search_item(
            parent_id="5",
            resource_name="GET /x",
            operation_name="http.request",
            env="prod",
            host="h1",
            type="web",
            tags=["team:sdk", "nocolon", 3, ":x"],
            start_timestamp="2026-11-18T12:00:00.123456789Z",
            end_timestamp="2026-11-18T12:00:01.5Z",
            attributes={
                "duration": 2_000_000,
                "http": {"status_code": 500},
                "span": {"kind": "client"},
            },
            custom={"grow": {"run": "abc"}, "nil": None},
            status="error",
        )
    )
    assert span.trace_id == "0" * 30 + "2a"
    assert span.parent_span_id == "0000000000000005"
    assert span.name == "GET /x"
    assert span.kind == "client"
    assert span.duration_micros == 2000
    assert span.start_micros % 1_000_000 == 123456
    assert span.end_micros % 1_000_000 == 500000
    assert span.status.code == "error"
    assert span.attributes["team"] == "sdk"
    assert span.attributes["http.status_code"] == 500
    assert span.attributes["grow.run"] == "abc"
    assert span.attributes["span.type"] == "web"
    assert span.attributes["host"] == "h1"
    assert span.events == () and span.links == ()


def test_search_span_variants() -> None:
    plain = sdk.datadog_search_span_to_span(
        {"trace_id": "a3ce929d0e0e47364bf92f3577b34da6", "span_id": "1"}
    )
    assert plain.trace_id == "a3ce929d0e0e47364bf92f3577b34da6"
    assert plain.service_name == "" and plain.name == ""
    times = sdk.datadog_search_span_to_span(
        search_item(
            start_timestamp="2026-11-18T12:00:00+00:00",
            end_timestamp="2026-11-18T12:00:02",
            attributes={
                "operation_name": "op",
                "resource_name": "res",
                "error": 1,
                "error.msg": "m",
            },
        )
    )
    assert times.duration_micros == 2_000_000
    assert times.name == "res" and times.attributes["operation_name"] == "op"
    assert times.status == sdk.SpanStatus("error", "m")
    ok = sdk.datadog_search_span_to_span(
        search_item(start_timestamp="bad", attributes={"otel.status_code": "OK", "status": "ok"})
    )
    assert ok.status.code == "ok" and ok.start_micros == 0
    no_end = sdk.datadog_search_span_to_span(
        search_item(start_timestamp="2026-11-18T12:00:00Z", attributes={"duration": 3000})
    )
    assert no_end.end_micros - no_end.start_micros == 3
    unset = sdk.datadog_search_span_to_span(search_item(start_timestamp=5))
    assert unset.status.code == "unset" and unset.duration_micros == 0
    with pytest.raises(sdk.ValidationError):
        sdk.datadog_search_span_to_span({"trace_id": "42"})


# --------------------------------------------------------------------------- error attributes


@pytest.fixture
def tp() -> Iterator[tuple[TracerProvider, InMemorySpanExporter]]:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(sdk.DatadogErrorExporter(exporter)))
    yield provider, exporter
    provider.shutdown()


class BadTypeError(Exception):
    pass


def test_datadog_error_attributes() -> None:
    try:
        raise BadTypeError("bad type")
    except BadTypeError as exc:
        attrs = sdk.datadog_error_attributes(exc)
    assert attrs["error.type"] == "BadTypeError"
    assert attrs["error.message"] == "bad type"
    assert "Traceback" in attrs["error.stack"] and "BadTypeError: bad type" in attrs["error.stack"]
    assert sdk.datadog_error_attributes("plain") == {
        "error.type": "Error",
        "error.message": "plain",
    }
    assert sdk.datadog_error_attributes({"a": 1}) == {
        "error.type": "dict",
        "error.message": '{"a": 1}',
    }
    odd = sdk.datadog_error_attributes(object())
    assert odd["error.type"] == "object" and odd["error.message"].startswith("<object")
    assert (sdk.ATTR_ERROR_TYPE, sdk.ATTR_ERROR_MESSAGE, sdk.ATTR_ERROR_STACK) == (
        "error.type",
        "error.message",
        "error.stack",
    )


def test_error_helpers_and_exporter(tp: tuple[TracerProvider, InMemorySpanExporter]) -> None:
    provider, exporter = tp
    tracer = provider.get_tracer("t")
    with tracer.start_as_current_span("recorded"):
        sdk.record_exception(BadTypeError("recorded"), {"extra": "1"})
    with tracer.start_as_current_span("set-error"):
        sdk.set_error(ValueError("explicit failure"))
    with tracer.start_as_current_span("set-error-str"):
        sdk.set_error("just text")
    with tracer.start_as_current_span("attrs-only") as span:
        sdk.set_error_attributes(BadTypeError("attrs"), span)
    with tracer.start_as_current_span("generic-event") as span:
        span.record_exception(KeyError("k"))
    with tracer.start_as_current_span("status-only") as span:
        span.set_status(Status(StatusCode.ERROR, "status message"))
    with tracer.start_as_current_span("status-no-message") as span:
        span.set_status(Status(StatusCode.ERROR))
    with tracer.start_as_current_span("other-event") as span:
        span.add_event("exception", {"exception.type": 1})
        span.add_event("not-exception")
    with tracer.start_as_current_span("ok"):
        pass
    with pytest.raises(RuntimeError), sdk.with_span("with-span-fail", tracer=tracer):
        raise RuntimeError("boom")
    spans = {s.name: dict(s.attributes or {}) for s in exporter.get_finished_spans()}
    assert spans["recorded"]["error.type"] == "BadTypeError"
    assert spans["set-error"]["error.message"] == "explicit failure"
    assert spans["set-error-str"] == {"error.type": "Error", "error.message": "just text"}
    assert spans["attrs-only"]["error.message"] == "attrs"
    assert spans["generic-event"]["error.type"] == "KeyError"
    assert "error.stack" in spans["generic-event"]
    assert spans["status-only"] == {"error.message": "status message"}
    assert spans["status-no-message"] == {}
    assert spans["other-event"] == {}
    assert spans["ok"] == {}
    assert spans["with-span-fail"]["error.type"] == "RuntimeError"
    assert spans["with-span-fail"]["error.message"] == "boom"
    # no active span: the helpers are no-ops
    sdk.record_exception(ValueError("x"))
    sdk.set_error("x")
    assert otel_trace.get_current_span().is_recording() is False


def test_error_exporter_delegates() -> None:
    inner = InMemorySpanExporter()
    exporter = sdk.DatadogErrorExporter(inner)
    assert exporter.export([]) is SpanExportResult.SUCCESS
    assert exporter.force_flush()
    exporter.shutdown()
    assert exporter.export([]) is SpanExportResult.FAILURE


def test_with_datadog_error_attributes_keeps_existing(
    tp: tuple[TracerProvider, InMemorySpanExporter],
) -> None:
    provider, exporter = tp
    with provider.get_tracer("t").start_as_current_span("pre") as span:
        span.set_attribute("error.type", "Custom")
        span.record_exception(ValueError("v"))
    finished = exporter.get_finished_spans()[0]
    assert dict(finished.attributes or {})["error.type"] == "Custom"
    assert sdk.with_datadog_error_attributes(finished) is finished
    assert json.dumps(dict(finished.attributes or {}))
