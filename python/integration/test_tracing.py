"""The tracing half against the real Datadog Agent.

Spans are exported over OTLP (HTTP/protobuf and gRPC), converted by the Agent, forwarded to
fakeintake and read back through the trace-reader sidecar.
"""

from __future__ import annotations

import asyncio
import json
import random
import threading
from collections.abc import Iterator
from typing import Any
from wsgiref.simple_server import WSGIRequestHandler, make_server

import httpx
import pytest
import requests
from opentelemetry import context as otel_context
from opentelemetry import trace as otel_trace
from opentelemetry.trace import Link, NonRecordingSpan, SpanContext, SpanKind, TraceFlags
from reader import TraceReaderQuery, by_resource, reader_spans, wait_for_spans

import grow_datadog_apm as sdk


def service_name(run_id: str, kind: str) -> str:
    return f"grow-py-it-{kind}-{run_id}-{random.randrange(1 << 24):06x}"


@pytest.fixture(scope="module")
def handle(env: dict[str, str], run_id: str) -> Iterator[sdk.DatadogTracingHandle]:
    """The global tracing setup (the OpenTelemetry API allows one global provider per process)."""
    svc = service_name(run_id, "http")
    h = sdk.init_tracing(
        service_name=svc,
        service_version="1.2.3",
        environment="it",
        resource_attributes={"grow.run": svc},
        sampler="always_on",
    )
    assert h.is_global
    yield h
    h.shutdown()
    h.shutdown()


class TypeIssueError(Exception):
    pass


def test_init_tracing_http_to_agent(handle: sdk.DatadogTracingHandle, env: dict[str, str]) -> None:
    svc = handle.config.service_name
    assert handle.endpoint == env["GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT"] + "/v1/traces"
    link_target = SpanContext(
        int("a3ce929d0e0e47364bf92f3577b34da6", 16),
        int("00f067aa0ba902b7", 16),
        is_remote=True,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )
    captured: dict[str, Any] = {}

    @sdk.traced("it-traced")
    def traced_work() -> int:
        return 1

    def root_body(span: otel_trace.Span) -> str:
        captured["fields"] = sdk.datadog_log_fields()
        captured["ids"] = sdk.trace_fields()
        captured["current"] = (sdk.current_trace_id(), sdk.current_span_id())
        assert sdk.current_span() is span
        sdk.add_event("checkpoint", {"step": 1})
        sdk.with_span("it-child-ok", lambda s: None, attributes={"n": 7, "flag": True})
        with pytest.raises(ValueError, match="out of range"), sdk.with_span("it-child-fail"):
            raise ValueError("value out of range")
        with sdk.with_span("it-recorded"):
            sdk.record_exception(TypeIssueError("bad type"))
        with sdk.with_span("it-set-error"):
            sdk.set_error(RuntimeError("explicit failure"))
        with sdk.with_span("it-attrs") as attrs_span:
            sdk.set_error_attributes(TypeIssueError("attrs only"), attrs_span)
            assert sdk.datadog_error_attributes("x")["error.message"] == "x"
        with sdk.with_span("it-generic") as generic:
            generic.record_exception(KeyError("generic"))
        assert traced_work() == 1
        with sdk.with_span("it-linked", links=[Link(link_target)]):
            pass
        return format(span.get_span_context().trace_id, "032x")

    trace_id = sdk.with_span("it-root", root_body, kind="server")
    handle.force_flush(30)
    spans = wait_for_spans(9, trace_id=trace_id)
    res = by_resource(spans)
    root = res["it-root"]
    meta = root["meta"]
    assert root["service"] == svc
    assert (meta["env"], meta["version"], meta["grow.run"]) == ("it", "1.2.3", svc)
    assert meta["span.kind"] == "server"
    assert root["name"] == "server.request"
    assert root["trace_id_hex"] == trace_id

    # id helpers and log correlation against the Agent's own ids
    split = sdk.to_datadog_trace_id(trace_id)
    assert root["trace_id"] == split.low64_decimal
    assert meta["otel.trace_id"] == trace_id
    assert sdk.from_datadog_trace_id(root["trace_id"], split.high64_hex) == trace_id
    assert sdk.from_datadog_span_id(root["span_id"]) == root["span_id_hex"]
    assert sdk.to_datadog_span_id(root["span_id_hex"]) == root["span_id"]
    want = trace_id if split.is_128_bit else root["trace_id"]
    assert sdk.datadog_trace_id_string(trace_id) == want
    assert sdk.normalize_datadog_trace_id(root["trace_id_hex"]) == trace_id
    assert sdk.normalize_datadog_span_id(root["span_id"]) == root["span_id_hex"]
    assert captured["fields"] == {
        "dd.trace_id": want,
        "dd.span_id": root["span_id"],
        "dd.service": svc,
        "dd.env": "it",
        "dd.version": "1.2.3",
    }
    assert captured["ids"] == {"trace_id": trace_id, "span_id": root["span_id_hex"]}
    assert captured["current"] == (trace_id, root["span_id_hex"])

    # Datadog error attributes (DECISIONS.md #2)
    fail = res["it-child-fail"]
    assert fail["error"] == 1
    assert fail["meta"]["error.type"] == "ValueError"
    assert fail["meta"]["error.message"] == "value out of range"
    assert "Traceback" in fail["meta"]["error.stack"]
    assert res["it-recorded"]["meta"]["error.type"] == "TypeIssueError"
    assert res["it-recorded"]["error"] == 0
    set_error = res["it-set-error"]
    assert set_error["error"] == 1
    assert "explicit failure" in set_error["meta"]["error.msg"]
    assert set_error["meta"]["error.type"] == "RuntimeError"
    assert res["it-attrs"]["meta"]["error.message"] == "attrs only"
    assert res["it-generic"]["meta"]["error.type"] == "KeyError"
    ok = res["it-child-ok"]
    assert float(ok["metrics"]["n"]) == 7.0
    assert ok["meta"]["flag"] == "true"
    assert "it-traced" in res

    # normalized model from the Agent's span format
    model = {s.name: s for s in map(sdk.datadog_trace_span_to_span, spans)}
    m_root = model["it-root"]
    assert m_root.kind == "server"
    assert [(e.name, dict(e.attributes)) for e in m_root.events] == [("checkpoint", {"step": 1})]
    assert all(s.parent_span_id == m_root.span_id for n, s in model.items() if n != "it-root")
    linked = model["it-linked"]
    assert [(lk.trace_id, lk.span_id) for lk in linked.links] == [
        ("a3ce929d0e0e47364bf92f3577b34da6", "00f067aa0ba902b7")
    ]
    assert model["it-child-fail"].status.code == "error"


def test_init_tracing_grpc_via_client(env: dict[str, str], run_id: str) -> None:
    svc = service_name(run_id, "grpc")
    client = sdk.DatadogApmClient.from_env(env)
    h = client.init_tracing(service_name=svc, protocol="grpc", set_global=False)
    assert h.endpoint == env["GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT"]
    with h.tracer("it").start_as_current_span("it-grpc-span", attributes={"via": "grpc"}) as span:
        trace_id = format(span.get_span_context().trace_id, "032x")
    h.force_flush(30)
    spans = wait_for_spans(1, trace_id=trace_id)
    assert spans[0]["service"] == svc
    assert spans[0]["resource"] == "it-grpc-span"
    assert spans[0]["meta"]["via"] == "grpc"
    client.close()
    client.close()
    assert h.is_shut_down


def test_async_client_init_tracing(env: dict[str, str], run_id: str) -> None:
    svc = service_name(run_id, "async")

    async def run() -> str:
        async with sdk.AsyncDatadogApmClient.from_env(env) as c:
            h = c.init_tracing(service_name=svc, set_global=False)
            with h.tracer("it").start_as_current_span("it-async") as span:
                return format(span.get_span_context().trace_id, "032x")

    trace_id = asyncio.run(run())  # aclose flushes the handle
    assert wait_for_spans(1, trace_id=trace_id)[0]["service"] == svc


def test_datadog_propagation_through_agent(handle: sdk.DatadogTracingHandle) -> None:
    tracer = handle.tracer("it")
    parent = tracer.start_span("it-upstream", context=otel_context.Context())
    upstream_ctx = otel_trace.set_span_in_context(parent)
    headers = sdk.inject({}, context=upstream_ctx)
    dd_only_out: dict[str, str] = {}
    sdk.DatadogPropagator().inject(dd_only_out, upstream_ctx)
    sc = parent.get_span_context()
    trace_hex = format(sc.trace_id, "032x")
    split = sdk.to_datadog_trace_id(trace_hex)
    span_dec = sdk.to_datadog_span_id(format(sc.span_id, "016x"))
    want_tags = f"_dd.p.tid={split.high64_hex}" if split.is_128_bit else None
    assert headers["x-datadog-trace-id"] == split.low64_decimal
    assert headers["x-datadog-parent-id"] == span_dec
    assert headers["x-datadog-sampling-priority"] == "1"
    assert headers.get("x-datadog-tags") == want_tags
    assert trace_hex in headers["traceparent"]
    assert "traceparent" not in dd_only_out
    assert dd_only_out["x-datadog-trace-id"] == split.low64_decimal

    # downstream: only Datadog headers (what a ddtrace service sends)
    dd_only = {k: v for k, v in headers.items() if k.startswith("x-datadog-")}
    found = sdk.extract_datadog_context(dd_only)
    assert found is not None
    assert found.trace_id == sc.trace_id
    downstream = sdk.extract(dd_only)
    assert otel_trace.get_current_span(downstream).get_span_context().trace_id == sc.trace_id
    with tracer.start_as_current_span("it-downstream", context=downstream, kind=SpanKind.SERVER):
        pass

    # conflicting W3C context: Datadog wins, W3C becomes a span link
    conflict_headers = {
        **{k.upper(): v for k, v in dd_only.items()},
        "Traceparent": "00-11111111111111112222222222222222-0000000000000abc-01",
    }
    conflicting = sdk.extract_headers(conflict_headers)
    assert len(sdk.terminated_context_links(conflicting)) == 1
    terminated = sdk.terminated_context(conflicting)
    assert terminated is not None
    assert terminated.winner_span_id == format(sc.span_id, "016x")
    with tracer.start_as_current_span("it-conflict", context=conflicting):
        pass
    out = sdk.inject_headers({}, context=conflicting)
    assert out["x-datadog-parent-id"] == span_dec
    parent.end()
    handle.force_flush(30)

    spans = wait_for_spans(3, trace_id=trace_hex)
    res = by_resource(spans)
    up, down, conflict = res["it-upstream"], res["it-downstream"], res["it-conflict"]
    assert down["parent_id"] == up["span_id"]
    assert down["trace_id"] == up["trace_id"]
    assert conflict["parent_id"] == up["span_id"]
    links = json.loads(conflict["meta"]["_dd.span_links"])
    assert len(links) == 1
    assert links[0]["trace_id"] == "11111111111111112222222222222222"
    assert links[0]["span_id"] == "0000000000000abc"
    assert links[0]["attributes"]["reason"] == "terminated_context"


class _QuietHandler(WSGIRequestHandler):
    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        pass


def test_http_adapters_through_agent(handle: sdk.DatadogTracingHandle, env: dict[str, str]) -> None:
    svc = handle.config.service_name

    def wsgi_app(environ: dict[str, Any], start_response: Any) -> list[bytes]:
        start_response("204 No Content", [])
        return []

    server = make_server(
        "127.0.0.1", 0, sdk.TracingWSGIMiddleware(wsgi_app), handler_class=_QuietHandler
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    local = f"http://127.0.0.1:{server.server_port}/hello"

    async def asgi_app(scope: dict[str, Any], receive: Any, send: Any) -> None:
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    async def call_asgi(headers: dict[str, str]) -> int:
        transport = httpx.ASGITransport(app=sdk.TracingASGIMiddleware(asgi_app))
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://asgi.local",
            event_hooks=sdk.async_httpx_event_hooks(),
        ) as c:
            return (await c.get("/asgi", headers=headers)).status_code

    try:
        with sdk.with_span("it-fetch-parent") as parent:
            trace_id = sdk.current_trace_id()
            with httpx.Client(event_hooks=sdk.httpx_event_hooks()) as hc:
                assert hc.get(env["GROW_DATADOG_APM_URL"] + "/info").status_code == 200
                assert hc.get(local).status_code == 204
            session = sdk.instrument_requests_session(requests.Session())
            assert session.get(local, timeout=10).status_code == 204
            assert asyncio.run(call_asgi(sdk.inject_headers({}))) == 200
            assert parent.is_recording()
    finally:
        server.shutdown()
        server.server_close()
    assert trace_id is not None
    handle.force_flush(30)
    spans = wait_for_spans(8, trace_id=trace_id)
    kinds = [s["meta"].get("span.kind") for s in spans]
    assert kinds.count("client") == 4, kinds
    assert kinds.count("server") == 3, kinds
    for s in spans:
        if s["meta"].get("span.kind") == "client":
            assert "GET" in (s["meta"].get("http.method"), s["meta"].get("http.request.method"))
    same = reader_spans(trace_id=trace_id, service=svc)
    assert len(same) == len(spans)


def test_trace_reader_query_model(handle: sdk.DatadogTracingHandle) -> None:
    """The conformance query side (trace-reader) maps through datadog_trace_span_to_span."""

    with handle.tracer("it").start_as_current_span("it-reader-root") as root:
        with handle.tracer("it").start_as_current_span("it-reader-child"):
            pass
        trace_id = format(root.get_span_context().trace_id, "032x")
    handle.force_flush(30)
    query = TraceReaderQuery()
    trace = query.wait_for_trace(trace_id, timeout_s=45, interval_s=0.5, min_spans=2)
    assert [n.span.name for n in trace.tree()] == ["it-reader-root"]
    assert trace.tree()[0].children[0].span.name == "it-reader-child"
    svc = handle.config.service_name
    assert svc in query.list_services()
    assert "it-reader-child" in query.list_operations(svc)
    summaries = query.search_traces(sdk.TraceSearchQuery(service=svc, operation="it-reader-root"))
    assert [s.trace_id for s in summaries] == [trace_id]
    with pytest.raises(sdk.UnsupportedError):
        query.get_dependencies()
    noop = NonRecordingSpan(SpanContext(1, 1, is_remote=False))
    assert not noop.is_recording()
