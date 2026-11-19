"""DatadogSpansQuery / AsyncDatadogSpansQuery against mocked Datadog API responses.

Bodies come from Datadog's documented examples (``tests/fixtures/*.docs_example.json``); the SaaS
query API cannot run in a container (API.md §6).
"""

from __future__ import annotations

import copy
import json
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import pytest

import grow_datadog_apm as sdk
from _support import fast_retry, fixture_body

API_KEY = "api-key-1234567890"
APP_KEY = "app-key-0987654321"
NOW = datetime(2026, 11, 18, 12, 0, tzinfo=timezone.utc)
TRACE_128 = "a3ce929d0e0e47364bf92f3577b34da6"
Handler = Callable[[httpx.Request], httpx.Response]


class Recorder:
    """httpx mock that records requests and answers with queued responses."""

    def __init__(self, *responses: httpx.Response | Handler) -> None:
        self.responses = list(responses)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        reply = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        return reply(request) if callable(reply) else reply

    def body(self, index: int = 0) -> Any:
        return json.loads(self.requests[index].content)

    def attrs(self, index: int = 0) -> Any:
        return self.body(index)["data"]["attributes"]


def ok(body: Any, **headers: str) -> httpx.Response:
    return httpx.Response(200, json=body, headers=headers)


def config(**kw: Any) -> sdk.DatadogApmConfig:
    kw.setdefault("retry", fast_retry())
    return sdk.DatadogApmConfig(api_key=API_KEY, app_key=APP_KEY, api_url="https://api.test", **kw)


def query(rec: Recorder, **kw: Any) -> sdk.DatadogSpansQuery:
    return sdk.DatadogSpansQuery(
        config(**kw), http_transport=httpx.MockTransport(rec), now=lambda: NOW
    )


def aquery(rec: Recorder, **kw: Any) -> sdk.AsyncDatadogSpansQuery:
    return sdk.AsyncDatadogSpansQuery(
        config(**kw), http_transport=httpx.MockTransport(rec), now=lambda: NOW
    )


def search_item(
    trace_id: str, span_id: str, parent: str = "0", name: str = "op", sec: int = 0
) -> dict[str, Any]:
    return {
        "id": f"ev-{span_id}",
        "type": "spans",
        "attributes": {
            "trace_id": trace_id,
            "span_id": span_id,
            "parent_id": parent,
            "service": "web",
            "resource_name": name,
            "start_timestamp": f"2026-11-18T11:5{sec}:00Z",
            "end_timestamp": f"2026-11-18T11:5{sec}:01Z",
        },
    }


def page(*items: dict[str, Any], after: str | None = None, status: str = "done") -> dict[str, Any]:
    meta: dict[str, Any] = {"status": status, "elapsed": 1}
    if after:
        meta["page"] = {"after": after}
    return {"data": list(items), "meta": meta}


def docs_search_body() -> dict[str, Any]:
    body = copy.deepcopy(fixture_body("searchSpans.docs_example.json"))
    attrs = body["data"][0]["attributes"]
    attrs["trace_id"], attrs["span_id"] = "42", "1"  # the docs example has no ids
    body["meta"]["request_id"] = "req-1"
    body["meta"]["page"] = {"after": "cur-2"}
    body["meta"]["warnings"] = [{"code": "w", "title": "t", "detail": "d"}, "junk"]
    return body


# --------------------------------------------------------------------------- construction


def test_needs_both_keys_and_from_env() -> None:
    with pytest.raises(sdk.ConfigError) as info:
        sdk.DatadogSpansQuery(sdk.DatadogApmConfig(api_key=API_KEY))
    assert API_KEY not in str(info.value)
    with pytest.raises(sdk.ConfigError):
        sdk.AsyncDatadogSpansQuery(sdk.DatadogApmConfig())
    q = sdk.DatadogSpansQuery.from_env(
        {"DD_API_KEY": API_KEY, "DD_APP_KEY": APP_KEY, "DD_SITE": "datadoghq.eu"}
    )
    assert q.config.api_url == "https://api.datadoghq.eu"
    assert API_KEY not in repr(q)
    q.close()
    q.close()


def test_default_config_from_process_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DD_API_KEY", API_KEY)
    monkeypatch.setenv("DD_APP_KEY", APP_KEY)
    with sdk.DatadogSpansQuery() as q:
        assert q.config.has_query_credentials


# --------------------------------------------------------------------------- search


def test_search_spans_page_request_and_response() -> None:
    rec = Recorder(ok(docs_search_body()))
    with query(rec) as q:
        result = q.search_spans_page(
            query="service:web*", limit=25, cursor="cur-1", sort="-timestamp", timezone="GMT"
        )
    req = rec.requests[0]
    assert req.method == "POST"
    assert str(req.url) == "https://api.test/api/v2/spans/events/search"
    assert req.headers["DD-API-KEY"] == API_KEY
    assert req.headers["DD-APPLICATION-KEY"] == APP_KEY
    assert req.headers["Content-Type"] == "application/json"
    assert rec.body()["data"]["type"] == "search_request"
    assert rec.attrs() == {
        "filter": {
            "query": "service:web*",
            "from": "2026-11-18T11:45:00.000Z",
            "to": "2026-11-18T12:00:00.000Z",
        },
        "options": {"timezone": "GMT"},
        "page": {"limit": 25, "cursor": "cur-1"},
        "sort": "-timestamp",
    }
    assert result.next_cursor == "cur-2"
    assert result.request_id == "req-1"
    assert result.status == "done"
    assert list(result.warnings) == [
        sdk.DatadogQueryWarning("w", "t", "d"),
        sdk.DatadogQueryWarning(),
    ]
    item = result.spans[0]
    assert item.id == "AAAAAWgN8Xwgr1vKDQAAAABBV2dOOFh3ZzZobm1mWXJFYTR0OA"
    assert item.raw["service"] == "test-service"
    assert item.span.service_name == "test-service"
    assert item.span.attributes["env"] == "prod"
    assert item.span.trace_id == "0" * 30 + "2a"


def test_search_defaults_and_time_formats() -> None:
    rec = Recorder(ok(page()))
    q = query(rec)
    empty = q.search_spans_page()
    assert empty.spans == [] and empty.next_cursor is None and empty.request_id is None
    assert rec.attrs(0) == {
        "filter": {
            "query": "*",
            "from": "2026-11-18T11:45:00.000Z",
            "to": "2026-11-18T12:00:00.000Z",
        },
        "options": {"timezone": "UTC"},
        "page": {"limit": 10},
        "sort": "timestamp",
    }
    q.search_spans_page(from_=datetime(2026, 1, 2, 3, 4, 5, 678900), to=1700000000123.9)
    assert rec.attrs(1)["filter"]["from"] == "2026-01-02T03:04:05.678Z"
    assert rec.attrs(1)["filter"]["to"] == "1700000000123"
    berlin = timezone(timedelta(hours=1))
    q.search_spans_page(from_="now-1h", to=datetime(2026, 1, 1, 1, 0, tzinfo=berlin))
    assert rec.attrs(2)["filter"] == {
        "query": "*",
        "from": "now-1h",
        "to": "2026-01-01T00:00:00.000Z",
    }
    for bad in (0, 1001, True, 2.5):
        with pytest.raises(sdk.ConfigError, match="limit"):
            q.search_spans_page(limit=bad)  # type: ignore[arg-type]
    with pytest.raises(sdk.ConfigError):
        q.search_spans_page(from_=True)
    assert len(rec.requests) == 3


def test_search_spans_follows_cursor_with_fixed_window() -> None:
    rec = Recorder(
        ok(page(search_item("42", "1"), search_item("42", "2"), after="c2")),
        ok(page(search_item("42", "3"), after="c3")),
        ok(page()),
    )
    q = query(rec)
    names = [s.span.span_id for s in q.search_spans(query="service:web", limit=2)]
    assert names == ["0000000000000001", "0000000000000002", "0000000000000003"]
    assert [rec.attrs(i)["page"].get("cursor") for i in range(3)] == [None, "c2", "c3"]
    assert len({json.dumps(rec.attrs(i)["filter"]) for i in range(3)}) == 1
    # a repeated cursor stops the iteration
    rec2 = Recorder(ok(page(search_item("42", "1"), after="same")))
    assert len(list(query(rec2).search_spans(cursor="same"))) == 1
    # stopping early sends no further request
    rec3 = Recorder(ok(page(search_item("42", "1"), search_item("42", "2"), after="next")))
    iterator = query(rec3).search_spans()
    next(iterator)
    iterator.close()
    assert len(rec3.requests) == 1


def test_search_spans_partial_results() -> None:
    rec = Recorder(ok(page(status="timeout")))
    with pytest.raises(sdk.TimeoutError) as info:
        list(query(rec).search_spans())
    assert info.value.retryable
    partial = Recorder(ok(page(search_item("42", "1"), status="timeout")))
    assert len(list(query(partial).search_spans())) == 1
    page_result = query(Recorder(ok(page(status="timeout")))).search_spans_page()
    assert page_result.status == "timeout"


def test_search_errors() -> None:
    bad = fixture_body("searchSpans.400.docs_example.json")
    rec = Recorder(httpx.Response(400, json=bad, headers={"x-datadog-request-id": "r-9"}))
    with pytest.raises(sdk.ValidationError) as info:
        query(rec).search_spans_page(query="service:(")
    assert "Missing required attribute in body" in str(info.value)
    assert info.value.http_status == 400
    assert info.value.request_id == "r-9"
    assert len(rec.requests) == 1
    for status, cls in (
        (401, sdk.AuthenticationError),
        (403, sdk.PermissionDeniedError),
        (404, sdk.NotFoundError),
        (408, sdk.TimeoutError),
        (422, sdk.ValidationError),
        (413, sdk.ValidationError),
    ):
        r = Recorder(httpx.Response(status, json={"errors": [f"{API_KEY} rejected"]}))
        with pytest.raises(cls) as err:
            query(r, retry=fast_retry(max_retries=0)).search_spans_page()
        assert API_KEY not in str(err.value)
    retried = Recorder(httpx.Response(503, json={"errors": ["down"]}), ok(page()))
    assert query(retried).search_spans_page().spans == []
    assert len(retried.requests) == 2  # POST search is idempotent → retried
    broken = Recorder(httpx.Response(200, content=b"{not json"))
    with pytest.raises(sdk.UnavailableError, match="invalid JSON"):
        query(broken).search_spans_page()
    not_object = Recorder(ok([1, 2]))
    assert query(not_object).search_spans_page().spans == []
    weird = Recorder(ok({"data": "x", "meta": {"page": "y", "warnings": "z"}}))
    assert query(weird).search_spans_page().warnings == []
    odd_items = Recorder(
        ok({"data": [{"id": "e", "attributes": {"trace_id": "42", "span_id": "1"}}, "junk"]})
    )
    with pytest.raises(sdk.ValidationError):
        query(odd_items).search_spans_page()


def test_rate_limit_uses_x_ratelimit_reset() -> None:
    limited = httpx.Response(
        429, json={"errors": ["Too many requests"]}, headers={"X-RateLimit-Reset": "0"}
    )
    rec = Recorder(limited, ok(page()))
    assert query(rec).search_spans_page().spans == []
    assert len(rec.requests) == 2
    too_long = Recorder(
        httpx.Response(
            429, json={"errors": ["Too many requests"]}, headers={"X-RateLimit-Reset": "30"}
        )
    )
    with pytest.raises(sdk.RateLimitError) as info:
        query(too_long, retry=fast_retry(max_retry_after=1.0)).search_spans_page()
    assert info.value.retry_after == 30.0
    assert len(too_long.requests) == 1
    explicit = Recorder(
        httpx.Response(429, headers={"Retry-After": "40", "X-RateLimit-Reset": "0"}),
    )
    with pytest.raises(sdk.RateLimitError) as info2:
        query(explicit, retry=fast_retry(max_retry_after=1.0)).search_spans_page()
    assert info2.value.retry_after == 40.0


# --------------------------------------------------------------------------- aggregate


def test_aggregate_spans() -> None:
    rec = Recorder(
        ok(
            fixture_body("aggregateSpans.docs_example.json")
            | {"meta": {"status": "done", "request_id": "agg-1"}}
        )
    )
    q = query(rec)
    result = q.aggregate_spans(
        [sdk.SpanCompute("pc90", "@duration", "timeseries", "5m"), {"aggregation": "count"}],
        [
            sdk.SpanGroupBy(
                "service",
                limit=20,
                sort=sdk.SpanGroupBySort("pc90", "@duration", "desc", "measure"),
                missing="none",
                total=True,
                histogram=sdk.SpanHistogram(10, 0, 100),
            ),
            {"facet": "host"},
        ],
        query="env:prod",
        from_="now-1h",
        to="now",
        timezone="GMT",
    )
    req = rec.requests[0]
    assert str(req.url) == "https://api.test/api/v2/spans/analytics/aggregate"
    assert rec.body()["data"]["type"] == "aggregate_request"
    assert rec.attrs() == {
        "compute": [
            {"aggregation": "pc90", "metric": "@duration", "type": "timeseries", "interval": "5m"},
            {"aggregation": "count"},
        ],
        "filter": {"query": "env:prod", "from": "now-1h", "to": "now"},
        "options": {"timezone": "GMT"},
        "group_by": [
            {
                "facet": "service",
                "limit": 20,
                "sort": {
                    "aggregation": "pc90",
                    "metric": "@duration",
                    "order": "desc",
                    "type": "measure",
                },
                "missing": "none",
                "total": True,
                "histogram": {"interval": 10, "min": 0, "max": 100},
            },
            {"facet": "host"},
        ],
    }
    assert result.buckets == [sdk.SpanAggregateBucket({"host": "my-hostname"}, {"c0": 19.0})]
    assert result.request_id == "agg-1"
    assert sdk.AGGREGATIONS[0] == "count" and len(sdk.AGGREGATIONS) == 12


def test_aggregate_timeseries_and_errors() -> None:
    body = {
        "data": [
            {
                "attributes": {
                    "by": {"service": "web"},
                    "compute": {
                        "c0": [{"time": "t1", "value": 2}, {"time": "t2"}, "x"],
                        "c1": "n/a",
                    },
                }
            },
            {"attributes": "junk"},
            "junk",
        ]
    }
    rec = Recorder(ok(body))
    result = query(rec).aggregate_spans([sdk.SpanCompute("count")])
    assert "group_by" not in rec.attrs()
    assert rec.attrs()["filter"]["query"] == "*"
    assert result.buckets[0].computes == {
        "c0": [
            sdk.SpanTimeseriesPoint("t1", 2.0),
            sdk.SpanTimeseriesPoint("t2", 0.0),
            sdk.SpanTimeseriesPoint("", 0.0),
        ],
        "c1": "n/a",
    }
    assert result.buckets[1] == sdk.SpanAggregateBucket({}, {})
    with pytest.raises(sdk.ConfigError, match="compute"):
        query(rec).aggregate_spans([])
    empty = Recorder(httpx.Response(200, content=b""))
    assert query(empty).aggregate_spans([sdk.SpanCompute("count")]).buckets == []


# --------------------------------------------------------------------------- traces


def test_get_trace_search_mode() -> None:
    rec = Recorder(
        ok(
            page(
                search_item(TRACE_128, "1", name="root"),
                search_item(TRACE_128, "2", "1", "child"),
                after="n",
            )
        ),
        ok(page(search_item(TRACE_128, "2", "1", "child", sec=1))),
    )
    q = query(rec)
    trace = q.get_trace(TRACE_128.upper())
    assert trace is not None
    assert trace.trace_id == TRACE_128
    assert [s.name for s in trace.spans] == ["root", "child"]
    attrs = rec.attrs(0)
    assert attrs["filter"]["query"] == "trace_id:" + TRACE_128
    assert attrs["filter"]["from"] == "2026-11-17T12:00:00.000Z"
    assert attrs["page"]["limit"] == 1000
    rec64 = Recorder(ok(page()))
    assert query(rec64).get_trace("2a") is None
    assert rec64.attrs()["filter"]["query"] == "trace_id:42"
    query(rec64).get_trace("2a", from_="now-1h", to="now")
    assert rec64.attrs(1)["filter"]["from"] == "now-1h"
    with pytest.raises(sdk.ValidationError):
        q.get_trace("not-hex")


def test_get_trace_preview() -> None:
    rec = Recorder(ok(fixture_body("getTraceById.docs_example.json")))
    q = query(rec)
    trace = q.get_trace("0000000000000000abc1230000000000", preview=True)
    assert trace is not None and len(trace.spans) == 1
    assert trace.spans[0].name == "GET /products"
    assert rec.requests[0].method == "GET"
    assert rec.requests[0].url.path == "/api/v2/trace/0000000000000000abc1230000000000"
    digits = Recorder(httpx.Response(404, json={"errors": ["not found"]}))
    assert query(digits).get_trace("00000000000000000020000000000001", preview=True) is None
    assert digits.requests[0].url.path == "/api/v2/trace/9007199254740993"
    assert query(Recorder(httpx.Response(200, content=b" "))).get_trace("2a", preview=True) is None
    assert (
        query(Recorder(ok({"data": {"attributes": {"spans": []}}}))).get_trace("2a", preview=True)
        is None
    )
    with pytest.raises(sdk.PermissionDeniedError):
        query(Recorder(httpx.Response(403, json={"errors": ["Forbidden"]}))).get_trace(
            "2a", preview=True
        )


def test_list_services_and_operations() -> None:
    rec = Recorder(ok({"data": {"attributes": {"services": ["web", "api", 3], "metadata": []}}}))
    q = query(rec, environment="prod")
    assert q.list_services() == ["api", "web"]
    assert rec.requests[0].url.path == "/api/v2/apm/services"
    assert rec.requests[0].url.params["filter[env]"] == "prod"
    q.list_services(env="staging")
    assert rec.requests[1].url.params["filter[env]"] == "staging"
    docs = Recorder(ok(fixture_body("listServices.docs_example.json")))
    assert query(docs).list_services() == ["test-service"]
    assert docs.requests[0].url.params["filter[env]"] == "*"
    assert query(Recorder(ok({"data": None}))).list_services() == []
    ops = Recorder(
        ok(
            {
                "data": [
                    {"attributes": {"by": {"resource_name": "GET /b"}}},
                    {"attributes": {"by": {"resource_name": "GET /a"}}},
                    {"attributes": {"by": {"resource_name": 5}}},
                    {"attributes": {"by": {"resource_name": ""}}},
                    {"attributes": {"by": {"resource_name": None}}},
                ]
            }
        )
    )
    assert query(ops).list_operations("my svc") == ["5", "GET /a", "GET /b"]
    attrs = ops.attrs()
    assert attrs["filter"]["query"] == 'service:"my svc"'
    assert attrs["filter"]["from"] == "2026-11-17T12:00:00.000Z"
    assert attrs["group_by"] == [{"facet": "resource_name", "limit": 1000}]
    assert attrs["compute"] == [{"aggregation": "count"}]


def test_search_traces() -> None:
    rec = Recorder(
        ok(
            page(
                search_item("1", "11", "10", "child", sec=2),
                search_item("1", "10", "0", "root", sec=1),
                search_item("2", "20", "0", "other", sec=3),
                after="p2",
            )
        ),
        ok(page(search_item("3", "30"), search_item("1", "12", "10", "late", sec=4))),
    )
    q = query(rec)
    summaries = q.search_traces(
        sdk.TraceSearchQuery(
            service="web",
            operation="GET /x",
            tags={"b": "2", "a": "x y"},
            min_duration_micros=5,
            max_duration_micros=7,
            start=datetime(2026, 11, 18, 11, 0),
            limit=2,
        )
    )
    assert [s.trace_id[-2:] for s in summaries] == ["01", "02"]
    first = summaries[0]
    assert (first.root_name, first.root_service_name, first.span_count) == ("root", "web", 2)
    assert first.duration_micros == 61_000_000
    attrs = rec.attrs(0)
    assert attrs["filter"]["query"] == (
        'service:web resource_name:"GET /x" @a:"x y" @b:2 @duration:>=5000 @duration:<=7000'
    )
    assert attrs["sort"] == "-timestamp" and attrs["page"]["limit"] == 100
    assert attrs["filter"]["from"] == "2026-11-18T11:00:00.000Z"
    assert len(rec.requests) == 1  # limit reached on the first page
    more = Recorder(
        ok(page(search_item("1", "10"), after="p2")),
        ok(page(search_item("2", "20"), search_item("1", "11", "10"))),
    )
    all_traces = query(more).search_traces()
    assert [s.span_count for s in all_traces] == [2, 1]
    assert more.attrs(0)["filter"]["query"] == "*"
    assert more.attrs(1)["page"]["cursor"] == "p2"
    orphan = Recorder(ok(page(search_item("1", "11", "99", "orphan"))))
    assert query(orphan).search_traces()[0].root_name == "orphan"


def test_query_helpers() -> None:
    assert sdk.quote_query_value("web-1.prod/*@x") == "web-1.prod/*@x"
    assert sdk.quote_query_value('say "hi" \\ now') == '"say \\"hi\\" \\\\ now"'
    assert sdk.trace_search_query_string(sdk.TraceSearchQuery()) == "*"


def test_dependencies_wait_and_ping() -> None:
    pong = Recorder(ok(fixture_body("ping.validate_keys.docs_example.json")))
    q = query(pong)
    with pytest.raises(sdk.UnsupportedError):
        q.get_dependencies()
    latency = q.ping()
    assert latency >= 0
    assert pong.requests[0].url.path == "/api/v2/validate_keys"
    assert pong.requests[0].headers["DD-APPLICATION-KEY"] == APP_KEY
    for status in (401, 403):
        r = Recorder(httpx.Response(status, json={"errors": ["Forbidden"]}))
        with pytest.raises(sdk.AuthenticationError) as info:
            query(r).ping()
        assert info.value.http_status == status
    assert query(Recorder(ok({}))).get_dependencies.__doc__
    waits = Recorder(ok(page()), ok(page(search_item(TRACE_128, "1"))))
    trace = query(waits).wait_for_trace(TRACE_128, interval_s=0.01, timeout_s=5)
    assert trace.trace_id == TRACE_128
    with pytest.raises(sdk.TimeoutError):
        query(Recorder(ok(page()))).wait_for_trace(TRACE_128, interval_s=0.01, timeout_s=0.05)
    with pytest.raises(sdk.AuthenticationError):
        query(Recorder(httpx.Response(401, json={"errors": ["no"]}))).wait_for_trace(
            TRACE_128, timeout_s=1
        )
    assert sdk.DATADOG_WAIT_INTERVAL_S == 2.0


# --------------------------------------------------------------------------- async


async def test_async_query_client() -> None:
    rec = Recorder(
        ok(docs_search_body()),  # search_spans_page
        ok(page(search_item("42", "1"), after="c2")),  # search_spans page 1
        ok(page(search_item("42", "2"))),  # search_spans page 2
        ok(fixture_body("aggregateSpans.docs_example.json")),
        ok(page(search_item(TRACE_128, "1"))),  # get_trace
        ok(fixture_body("getTraceById.docs_example.json")),  # preview
        httpx.Response(404, json={"errors": ["nope"]}),  # preview 404
        ok(fixture_body("listServices.docs_example.json")),
        ok({"data": [{"attributes": {"by": {"resource_name": "r"}}}]}),  # list_operations
        ok(page(search_item("1", "10"), after="x")),  # search_traces page 1
        ok(page(search_item("2", "20"))),  # search_traces page 2
        ok(page()),  # get_trace for wait (empty)
        ok(page(search_item(TRACE_128, "1"))),  # get_trace for wait
        ok({"status": "ok"}),  # ping
        httpx.Response(403, json={"errors": ["Forbidden"]}),  # ping 403
        ok(page()),  # get_trace → None
        ok(page(status="timeout")),
    )

    async with aquery(rec) as q:
        first = await q.search_spans_page(query="service:web*")
        assert first.next_cursor == "cur-2"
        ids = [s.span.span_id async for s in q.search_spans()]
        assert ids == ["0000000000000001", "0000000000000002"]
        agg = await q.aggregate_spans([sdk.SpanCompute("count")], [sdk.SpanGroupBy("host")])
        assert agg.buckets[0].by == {"host": "my-hostname"}
        trace = await q.get_trace(TRACE_128)
        assert trace is not None and len(trace.spans) == 1
        preview = await q.get_trace("0000000000000000abc1230000000000", preview=True)
        assert preview is not None and preview.spans[0].service_name == "web-store"
        assert await q.get_trace("2a", preview=True) is None
        assert await q.list_services(env="prod") == ["test-service"]
        assert await q.list_operations("web") == ["r"]
        summaries = await q.search_traces(sdk.TraceSearchQuery(limit=5))
        assert len(summaries) == 2
        waited = await q.wait_for_trace(TRACE_128, interval_s=0.01, timeout_s=5)
        assert waited.trace_id == TRACE_128
        assert await q.ping() >= 0
        with pytest.raises(sdk.AuthenticationError):
            await q.ping()
        assert await q.get_trace(TRACE_128) is None
        with pytest.raises(sdk.TimeoutError):
            async for _ in q.search_spans():
                pass
        with pytest.raises(sdk.UnsupportedError):
            await q.get_dependencies()
        assert API_KEY not in repr(q)
    await q.close()
    with pytest.raises(sdk.CancelledError):
        await q.ping()
    env_q = sdk.AsyncDatadogSpansQuery.from_env({"DD_API_KEY": API_KEY, "DD_APP_KEY": APP_KEY})
    await env_q.aclose()


async def test_async_rate_limit_and_cursor_repeat() -> None:
    rec = Recorder(
        httpx.Response(429, headers={"X-RateLimit-Reset": "0"}),
        ok(page(search_item("42", "1"), after="same")),
    )
    q = aquery(rec)
    items = [s async for s in q.search_spans(cursor="same")]
    assert len(items) == 1 and len(rec.requests) == 2
    await q.aclose()
