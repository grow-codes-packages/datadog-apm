"""``DatadogSpansQuery`` — the Datadog SaaS spans query API (API.md §4).

It needs an API key and an application key with ``apm_read``. No container can serve these
endpoints, so they are unit-tested against Datadog's documented examples and verified by the
live sandbox job.

Errors: 400/413/422 → :class:`ValidationError`, 401 → :class:`AuthenticationError`, 403 →
:class:`PermissionDeniedError` (:class:`AuthenticationError` from ``ping``), 404 →
:class:`NotFoundError` (``None`` from ``get_trace``), 408 → :class:`TimeoutError`, 429 →
:class:`RateLimitError` (waits ``Retry-After`` / ``X-RateLimit-Reset`` within the retry budget),
5xx → :class:`UnavailableError`.
"""

from __future__ import annotations

import dataclasses
import re
import time
from collections.abc import AsyncIterator, Callable, Iterator, Mapping, Sequence
from datetime import datetime, timedelta, timezone
from types import TracebackType
from typing import Any, Literal, TypeAlias

import httpx

from ._core import (
    AsyncHttpTransport,
    AuthenticationError,
    CancelToken,
    ConfigError,
    HttpTransport,
    NotFoundError,
    PermissionDeniedError,
    Request,
    Response,
    UnavailableError,
    UnsupportedError,
)
from ._core import TimeoutError as SdkTimeoutError
from ._core.env import to_seconds
from ._tracing import (
    AsyncBaseTraceQueryClient,
    BaseTraceQueryClient,
    DependencyLink,
    Span,
    Trace,
    TraceSearchQuery,
    TraceSummary,
    async_wait_for_trace,
    normalize_trace_id,
    sort_spans,
    wait_for_trace,
)
from .config import DatadogApmConfig
from .ids import datadog_trace_id_string, trace_id_for_path
from .mapping import datadog_search_span_to_span, datadog_trace_span_to_span

__all__ = [
    "DATADOG_WAIT_INTERVAL_S",
    "AGGREGATIONS",
    "DatadogTime",
    "SpanSort",
    "DatadogQueryWarning",
    "DatadogSpan",
    "DatadogSpansPage",
    "SpanCompute",
    "SpanGroupBySort",
    "SpanHistogram",
    "SpanGroupBy",
    "SpanTimeseriesPoint",
    "SpanAggregateBucket",
    "SpanAggregateResult",
    "DatadogSpansQuery",
    "AsyncDatadogSpansQuery",
    "quote_query_value",
    "trace_search_query_string",
]

#: Default polling interval of ``wait_for_trace`` (Datadog allows 50 searches per minute).
DATADOG_WAIT_INTERVAL_S = 2.0
#: Aggregation functions of the spans aggregate API.
AGGREGATIONS: tuple[str, ...] = (
    "count",
    "cardinality",
    "pc75",
    "pc90",
    "pc95",
    "pc98",
    "pc99",
    "sum",
    "min",
    "max",
    "avg",
    "median",
)

#: A point in time for a query: a ``datetime`` (naive = UTC), epoch milliseconds, or a Datadog
#: string (``now-15m``, ISO-8601).
DatadogTime: TypeAlias = datetime | int | float | str
SpanSort = Literal["timestamp", "-timestamp"]

_DEFAULT_WINDOW = timedelta(minutes=15)
_TRACE_WINDOW = timedelta(hours=24)
_MAX_PAGE = 1000
_SEARCH_TRACES_MAX_PAGES = 5
_PLAIN_VALUE = re.compile(r"^[A-Za-z0-9_.\-/*@]+$")


# --------------------------------------------------------------------------- model


@dataclasses.dataclass(frozen=True)
class DatadogQueryWarning:
    """A warning attached to a partial query result."""

    code: str | None = None
    title: str | None = None
    detail: str | None = None


@dataclasses.dataclass(frozen=True)
class DatadogSpan:
    """One span returned by the spans search API."""

    #: Datadog event id of the span.
    id: str
    #: The span in the SPEC §5 model (ids normalized to hex).
    span: Span
    #: The attributes exactly as Datadog returned them.
    raw: Mapping[str, Any]


@dataclasses.dataclass(frozen=True)
class DatadogSpansPage:
    """One page of :meth:`DatadogSpansQuery.search_spans_page`."""

    spans: Sequence[DatadogSpan]
    #: Cursor of the next page, ``None`` on the last page.
    next_cursor: str | None = None
    #: ``done``, or ``timeout`` for a partial result.
    status: str = "done"
    #: Non-empty when the result is partial or the query was adjusted.
    warnings: Sequence[DatadogQueryWarning] = ()
    #: ``meta.request_id`` (or the request id header), when present.
    request_id: str | None = None


def _drop_none(values: Mapping[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in values.items() if v is not None}


@dataclasses.dataclass(frozen=True)
class SpanCompute:
    """One computation of :meth:`DatadogSpansQuery.aggregate_spans`."""

    #: One of :data:`AGGREGATIONS`.
    aggregation: str
    #: The measure, e.g. ``@duration`` (not needed for ``count``).
    metric: str | None = None
    #: ``total`` (default) or ``timeseries`` (all computes must use the same type and interval).
    type: str | None = None
    #: Time bucket for ``timeseries``, e.g. ``5m``.
    interval: str | None = None

    def to_json(self) -> dict[str, Any]:
        return _drop_none(dataclasses.asdict(self))


@dataclasses.dataclass(frozen=True)
class SpanGroupBySort:
    """Order of the groups of a :class:`SpanGroupBy`."""

    aggregation: str | None = None
    metric: str | None = None
    #: ``asc`` or ``desc``.
    order: str | None = None
    #: ``alphabetical`` or ``measure``.
    type: str | None = None

    def to_json(self) -> dict[str, Any]:
        return _drop_none(dataclasses.asdict(self))


@dataclasses.dataclass(frozen=True)
class SpanHistogram:
    """Buckets for a numeric facet."""

    interval: float
    min: float
    max: float


@dataclasses.dataclass(frozen=True)
class SpanGroupBy:
    """One group-by facet of :meth:`DatadogSpansQuery.aggregate_spans`."""

    #: Facet, e.g. ``service``, ``resource_name``, ``@http.status_code``.
    facet: str
    #: Maximum number of groups (Datadog default 10).
    limit: int | None = None
    sort: SpanGroupBySort | None = None
    #: Value used for spans without the facet (string or number).
    missing: str | float | None = None
    #: Adds a total bucket (``True``, or the bucket's value).
    total: bool | str | float | None = None
    histogram: SpanHistogram | None = None

    def to_json(self) -> dict[str, Any]:
        out: dict[str, Any] = {"facet": self.facet}
        if self.limit is not None:
            out["limit"] = self.limit
        if self.sort is not None:
            out["sort"] = self.sort.to_json()
        if self.missing is not None:
            out["missing"] = self.missing
        if self.total is not None:
            out["total"] = self.total
        if self.histogram is not None:
            out["histogram"] = dataclasses.asdict(self.histogram)
        return out


@dataclasses.dataclass(frozen=True)
class SpanTimeseriesPoint:
    """A point of a ``timeseries`` compute."""

    time: str
    value: float


@dataclasses.dataclass(frozen=True)
class SpanAggregateBucket:
    """One bucket of an aggregation."""

    #: Group-by values, e.g. ``{"service": "web"}`` (empty without ``group_by``).
    by: Mapping[str, Any]
    #: Results keyed ``c0``, ``c1``, … in the order of the compute list: a ``float`` for total
    #: computes, a list of :class:`SpanTimeseriesPoint` for timeseries.
    computes: Mapping[str, Any]


@dataclasses.dataclass(frozen=True)
class SpanAggregateResult:
    """The result of :meth:`DatadogSpansQuery.aggregate_spans`."""

    buckets: Sequence[SpanAggregateBucket]
    status: str = "done"
    warnings: Sequence[DatadogQueryWarning] = ()
    request_id: str | None = None


# --------------------------------------------------------------------------- helpers


def quote_query_value(value: str) -> str:
    """Quote a value for the Datadog query syntax when it contains spaces or special characters.

    >>> quote_query_value("GET /api/v1/users")
    '"GET /api/v1/users"'
    """
    if _PLAIN_VALUE.match(value):
        return value
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def trace_search_query_string(query: TraceSearchQuery) -> str:
    """Datadog query string for a SPEC :class:`TraceSearchQuery`: ``service:``,
    ``resource_name:``, ``@<tag>:`` and ``@duration:>=`` / ``<=`` (nanoseconds). Empty → ``*``."""
    parts: list[str] = []
    if query.service:
        parts.append("service:" + quote_query_value(query.service))
    if query.operation:
        parts.append("resource_name:" + quote_query_value(query.operation))
    for key in sorted(query.tags):
        parts.append(f"@{key}:" + quote_query_value(query.tags[key]))
    if query.min_duration_micros:
        parts.append(f"@duration:>={query.min_duration_micros * 1000}")
    if query.max_duration_micros:
        parts.append(f"@duration:<={query.max_duration_micros * 1000}")
    return " ".join(parts) if parts else "*"


def _format_time(value: DatadogTime) -> str:
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        utc = value.astimezone(timezone.utc)
        return utc.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc.microsecond // 1000:03d}Z"
    if isinstance(value, bool):
        raise ConfigError("query time must be a datetime, epoch milliseconds or a string")
    if isinstance(value, (int, float)):
        return str(int(value))
    return value


def _rate_limit_hint(response: httpx.Response) -> None:
    """Copy ``X-RateLimit-Reset`` (seconds) into ``Retry-After`` on 429 without ``Retry-After``,
    so the transport honours it."""
    if response.status_code == 429 and not response.headers.get("retry-after", "").strip():
        reset = response.headers.get("x-ratelimit-reset", "").strip()
        if reset:
            response.headers["Retry-After"] = reset


async def _async_rate_limit_hint(response: httpx.Response) -> None:
    _rate_limit_hint(response)


def _warnings(meta: Mapping[str, Any]) -> list[DatadogQueryWarning]:
    items = meta.get("warnings")
    out: list[DatadogQueryWarning] = []
    for item in items if isinstance(items, list) else []:
        record = item if isinstance(item, Mapping) else {}
        out.append(
            DatadogQueryWarning(
                *(
                    record.get(k) if isinstance(record.get(k), str) else None
                    for k in ("code", "title", "detail")
                )
            )
        )
    return out


def _body(resp: Response, operation: str) -> Mapping[str, Any]:
    if not resp.content.strip():
        return {}
    try:
        doc = resp.json()
    except Exception:  # noqa: BLE001 - mapped to UNAVAILABLE like the other languages
        raise UnavailableError(
            f"{operation}: invalid JSON response",
            http_status=resp.status,
            request_id=resp.request_id,
        ) from None
    return doc if isinstance(doc, Mapping) else {}


def _meta(doc: Mapping[str, Any]) -> Mapping[str, Any]:
    meta = doc.get("meta")
    return meta if isinstance(meta, Mapping) else {}


def _parse_page(resp: Response) -> DatadogSpansPage:
    doc = _body(resp, "search_spans")
    meta = _meta(doc)
    page = meta.get("page")
    after = page.get("after") if isinstance(page, Mapping) else None
    spans: list[DatadogSpan] = []
    data = doc.get("data")
    for item in data if isinstance(data, list) else []:
        record = item if isinstance(item, Mapping) else {}
        attributes = record.get("attributes")
        raw = attributes if isinstance(attributes, Mapping) else {}
        spans.append(
            DatadogSpan(str(record.get("id") or ""), datadog_search_span_to_span(raw), raw)
        )
    return DatadogSpansPage(
        spans=spans,
        next_cursor=after if isinstance(after, str) and after else None,
        status=str(meta.get("status") or "done"),
        warnings=_warnings(meta),
        request_id=str(meta.get("request_id") or "") or resp.request_id,
    )


def _compute_value(value: Any) -> Any:
    if isinstance(value, list):
        points = []
        for item in value:
            record = item if isinstance(item, Mapping) else {}
            number = record.get("value")
            points.append(
                SpanTimeseriesPoint(
                    str(record.get("time") or ""),
                    float(number) if isinstance(number, (int, float)) else 0.0,
                )
            )
        return points
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    return value


def _parse_aggregate(resp: Response) -> SpanAggregateResult:
    doc = _body(resp, "aggregate_spans")
    meta = _meta(doc)
    buckets: list[SpanAggregateBucket] = []
    data = doc.get("data")
    for item in data if isinstance(data, list) else []:
        record = item if isinstance(item, Mapping) else {}
        attributes = record.get("attributes")
        attrs = attributes if isinstance(attributes, Mapping) else {}
        by = attrs.get("by")
        compute = attrs.get("compute")
        buckets.append(
            SpanAggregateBucket(
                dict(by) if isinstance(by, Mapping) else {},
                {str(k): _compute_value(v) for k, v in compute.items()}
                if isinstance(compute, Mapping)
                else {},
            )
        )
    return SpanAggregateResult(
        buckets=buckets,
        status=str(meta.get("status") or "done"),
        warnings=_warnings(meta),
        request_id=str(meta.get("request_id") or "") or resp.request_id,
    )


def _parse_trace(resp: Response, trace_id: str) -> Trace | None:
    doc = _body(resp, "get_trace")
    data = doc.get("data")
    attributes = data.get("attributes") if isinstance(data, Mapping) else None
    raw_spans = attributes.get("spans") if isinstance(attributes, Mapping) else None
    spans = [
        datadog_trace_span_to_span(s)
        for s in (raw_spans if isinstance(raw_spans, list) else [])
        if isinstance(s, Mapping)
    ]
    return Trace(trace_id, spans) if spans else None


def _parse_services(resp: Response) -> list[str]:
    doc = _body(resp, "list_services")
    data = doc.get("data")
    attributes = data.get("attributes") if isinstance(data, Mapping) else None
    services = attributes.get("services") if isinstance(attributes, Mapping) else None
    return sorted(s for s in (services if isinstance(services, list) else []) if isinstance(s, str))


def _operations(result: SpanAggregateResult) -> list[str]:
    names = set()
    for bucket in result.buckets:
        value = bucket.by.get("resource_name")
        if isinstance(value, (str, int, float)) and not isinstance(value, bool) and value != "":
            names.add(str(value))
    return sorted(names)


def _summaries(order: list[str], traces: Mapping[str, list[Span]]) -> list[TraceSummary]:
    out: list[TraceSummary] = []
    for trace_id in order:
        spans = sort_spans(traces[trace_id])
        ids = {s.span_id for s in spans}
        root = next(
            (s for s in spans if s.parent_span_id is None or s.parent_span_id not in ids), spans[0]
        )
        start = min(s.start_micros for s in spans)
        end = max(s.end_micros for s in spans)
        out.append(
            TraceSummary(
                trace_id=trace_id,
                start_micros=start,
                duration_micros=end - start,
                span_count=len(spans),
                root_service_name=root.service_name,
                root_name=root.name,
            )
        )
    return out


def _collect(
    page: DatadogSpansPage, traces: dict[str, list[Span]], order: list[str], limit: int
) -> None:
    for item in page.spans:
        trace_id = item.span.trace_id
        if trace_id in traces:
            traces[trace_id].append(item.span)
        elif len(traces) < limit:
            traces[trace_id] = [item.span]
            order.append(trace_id)


def _timeout_without_results() -> SdkTimeoutError:
    return SdkTimeoutError(
        "search_spans: the Datadog query timed out without results", retryable=True
    )


def _as_auth_error(err: PermissionDeniedError) -> AuthenticationError:
    return AuthenticationError(err.message, http_status=err.http_status, request_id=err.request_id)


def _unsupported_dependencies() -> UnsupportedError:
    return UnsupportedError(
        "traces.get_dependencies: the Datadog public API has no service dependency endpoint"
    )


class _QueryCore:
    """Request building shared by the sync and async query clients."""

    def __init__(self, config: DatadogApmConfig | None, now: Callable[[], datetime] | None) -> None:
        if config is None:
            config = DatadogApmConfig.from_env()
        config = config.validate()
        if not config.has_query_credentials:
            raise ConfigError(
                "config: the Datadog query API needs api_key (DD_API_KEY) and app_key (DD_APP_KEY)"
            )
        self.config = config
        self.transport_config = dataclasses.replace(config, url=config.api_url or "")
        self.now = now or (lambda: datetime.now(timezone.utc))

    def headers(self) -> dict[str, str]:
        assert self.config.api_key is not None
        assert self.config.app_key is not None
        return {
            "DD-API-KEY": self.config.api_key.reveal(),
            "DD-APPLICATION-KEY": self.config.app_key.reveal(),
        }

    def window(
        self, start: DatadogTime | None, end: DatadogTime | None, span: timedelta
    ) -> tuple[str, str]:
        now = self.now()
        return (
            _format_time(now - span if start is None else start),
            _format_time(now if end is None else end),
        )

    def search_request(
        self,
        *,
        query: str | None,
        start: str,
        end: str,
        limit: int | None,
        sort: str | None,
        cursor: str | None,
        timezone_name: str | None,
    ) -> Request:
        size = 10 if limit is None else limit
        if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= _MAX_PAGE:
            raise ConfigError("search_spans: limit must be an integer between 1 and 1000")
        page: dict[str, Any] = {"limit": size}
        if cursor:
            page["cursor"] = cursor
        body = {
            "data": {
                "type": "search_request",
                "attributes": {
                    "filter": {"query": query or "*", "from": start, "to": end},
                    "options": {"timezone": timezone_name or "UTC"},
                    "page": page,
                    "sort": sort or "timestamp",
                },
            }
        }
        return Request(
            "POST",
            "/api/v2/spans/events/search",
            operation="search_spans",
            json=body,
            headers=self.headers(),
            idempotent=True,
        )

    def aggregate_request(
        self,
        compute: Sequence[SpanCompute | Mapping[str, Any]],
        group_by: Sequence[SpanGroupBy | Mapping[str, Any]] | None,
        *,
        query: str | None,
        start: DatadogTime | None,
        end: DatadogTime | None,
        timezone_name: str | None,
    ) -> Request:
        if not compute:
            raise ConfigError("aggregate_spans: compute must not be empty")
        from_, to = self.window(start, end, _DEFAULT_WINDOW)
        attributes: dict[str, Any] = {
            "compute": [c.to_json() if isinstance(c, SpanCompute) else dict(c) for c in compute],
            "filter": {"query": query or "*", "from": from_, "to": to},
            "options": {"timezone": timezone_name or "UTC"},
        }
        if group_by:
            attributes["group_by"] = [
                g.to_json() if isinstance(g, SpanGroupBy) else dict(g) for g in group_by
            ]
        return Request(
            "POST",
            "/api/v2/spans/analytics/aggregate",
            operation="aggregate_spans",
            json={"data": {"type": "aggregate_request", "attributes": attributes}},
            headers=self.headers(),
            idempotent=True,
        )

    def trace_request(self, trace_id: str) -> Request:
        return Request(
            "GET",
            "/api/v2/trace/" + trace_id_for_path(trace_id),
            operation="get_trace",
            headers=self.headers(),
        )

    def services_request(self, env: str | None) -> Request:
        return Request(
            "GET",
            "/api/v2/apm/services",
            operation="list_services",
            params={"filter[env]": env or self.config.environment or "*"},
            headers=self.headers(),
        )

    def operations_request(self, service: str) -> Request:
        now = self.now()
        return self.aggregate_request(
            [SpanCompute("count")],
            [SpanGroupBy("resource_name", limit=1000)],
            query="service:" + quote_query_value(service),
            start=now - _TRACE_WINDOW,
            end=now,
            timezone_name=None,
        )

    def ping_request(self) -> Request:
        return Request("GET", "/api/v2/validate_keys", operation="ping", headers=self.headers())


# --------------------------------------------------------------------------- sync


class DatadogSpansQuery(BaseTraceQueryClient):
    """Datadog spans query client (SaaS API at ``https://api.<site>``, headers ``DD-API-KEY`` and
    ``DD-APPLICATION-KEY``).

    Implements the SPEC :class:`~grow_datadog_apm.TraceQueryClient` on top of the spans search
    and aggregate APIs, plus the Datadog-specific :meth:`search_spans`, :meth:`aggregate_spans`
    and :meth:`ping`. Thread-safe; use as a context manager or call :meth:`close`. It raises
    :class:`ConfigError` unless both ``api_key`` and ``app_key`` are configured.

    >>> query = DatadogSpansQuery.from_env()  # doctest: +SKIP
    >>> for item in query.search_spans(query="service:checkout @grow.run:abc"):  # doctest: +SKIP
    ...     print(item.span.name, item.span.duration_micros)
    """

    def __init__(
        self,
        config: DatadogApmConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._core = _QueryCore(config, now)
        self.config = self._core.config
        self._transport = HttpTransport(
            self._core.transport_config,
            known_secrets=self.config.known_secrets,
            http_transport=http_transport,
        )
        self._transport._client.event_hooks["response"].append(_rate_limit_hint)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> DatadogSpansQuery:
        """Query client from ``DD_API_KEY``, ``DD_APP_KEY``, ``DD_SITE``, … (see
        :meth:`DatadogApmConfig.from_env`)."""
        return cls(DatadogApmConfig.from_env(env, **overrides))

    def _send(self, req: Request, cancel: CancelToken | None) -> Response:
        return self._transport.request(req, cancel=cancel)

    def search_spans_page(
        self,
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        sort: SpanSort | None = None,
        cursor: str | None = None,
        timezone: str | None = None,
        cancel: CancelToken | None = None,
    ) -> DatadogSpansPage:
        """One page of ``POST /api/v2/spans/events/search``.

        ``query`` defaults to ``*``, ``from_``/``to`` to the last 15 minutes, ``limit`` to 10
        (max 1000), ``sort`` to ``timestamp`` (oldest first; ``-timestamp`` = newest first),
        ``timezone`` to ``UTC``. Pass ``next_cursor`` back as ``cursor`` with the same absolute
        window for the next page. Status ``timeout`` or ``warnings`` mark a partial result.
        """
        start, end = self._core.window(from_, to, _DEFAULT_WINDOW)
        req = self._core.search_request(
            query=query, start=start, end=end, limit=limit, sort=sort, cursor=cursor,
            timezone_name=timezone,
        )  # fmt: skip
        return _parse_page(self._send(req, cancel))

    def search_spans(
        self,
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        sort: SpanSort | None = None,
        cursor: str | None = None,
        timezone: str | None = None,
        cancel: CancelToken | None = None,
    ) -> Iterator[DatadogSpan]:
        """Iterate over every matching span, following the cursor page by page.

        Arguments as :meth:`search_spans_page`; the window is fixed (absolute) for all pages.
        A Datadog query that timed out without spans raises :class:`TimeoutError` (never a
        silently empty result).
        """
        start, end = self._core.window(from_, to, _DEFAULT_WINDOW)
        current = cursor
        while True:
            req = self._core.search_request(
                query=query, start=start, end=end, limit=limit, sort=sort, cursor=current,
                timezone_name=timezone,
            )  # fmt: skip
            page = _parse_page(self._send(req, cancel))
            if page.status == "timeout" and not page.spans:
                raise _timeout_without_results()
            yield from page.spans
            if page.next_cursor is None or page.next_cursor == current:
                return
            current = page.next_cursor

    def aggregate_spans(
        self,
        compute: Sequence[SpanCompute | Mapping[str, Any]],
        group_by: Sequence[SpanGroupBy | Mapping[str, Any]] | None = None,
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        timezone: str | None = None,
        cancel: CancelToken | None = None,
    ) -> SpanAggregateResult:
        """``POST /api/v2/spans/analytics/aggregate``: ``compute`` (results keyed ``c0``,
        ``c1``, …) per group of ``group_by``.

        >>> query.aggregate_spans([SpanCompute("pc90", "@duration")], [SpanGroupBy("service")])
        ... # doctest: +SKIP
        """
        req = self._core.aggregate_request(
            compute, group_by, query=query, start=from_, end=to, timezone_name=timezone
        )
        return _parse_aggregate(self._send(req, cancel))

    def get_trace(
        self,
        trace_id: str,
        *,
        preview: bool = False,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        cancel: CancelToken | None = None,
    ) -> Trace | None:
        """Read a trace; ``None`` when nothing is found.

        Default: search the indexed spans with ``trace_id:<id>`` over the last 24 hours
        (``from_``/``to`` change the window; all pages, merged by span id) — only spans kept by
        retention filters, so the trace may be partial. ``preview=True`` uses the preview
        trace-by-id endpoint ``GET /api/v2/trace/{id}`` (full trace, ``x-unstable``, 60
        requests/minute).
        """
        wanted = normalize_trace_id(trace_id)
        if preview:
            try:
                resp = self._send(self._core.trace_request(wanted), cancel)
            except NotFoundError:
                return None
            return _parse_trace(resp, wanted)
        start, end = self._core.window(from_, to, _TRACE_WINDOW)
        by_id: dict[str, Span] = {}
        for item in self.search_spans(
            query="trace_id:" + datadog_trace_id_string(wanted),
            from_=start,
            to=end,
            limit=_MAX_PAGE,
            cancel=cancel,
        ):
            by_id[item.span.span_id] = item.span
        return Trace(wanted, list(by_id.values())) if by_id else None

    def list_services(
        self, *, env: str | None = None, cancel: CancelToken | None = None
    ) -> list[str]:
        """``GET /api/v2/apm/services?filter[env]=…``: sorted names of the services that reported
        traces. ``env`` defaults to the configured environment, else ``*`` (all)."""
        return _parse_services(self._send(self._core.services_request(env), cancel))

    def list_operations(self, service: str, *, cancel: CancelToken | None = None) -> list[str]:
        """Distinct span names (Datadog ``resource_name``) of ``service`` in the last 24 hours,
        via :meth:`aggregate_spans` grouped by ``resource_name`` (up to 1000), sorted."""
        resp = self._send(self._core.operations_request(service), cancel)
        return _operations(_parse_aggregate(resp))

    def search_traces(
        self, query: TraceSearchQuery | None = None, *, cancel: CancelToken | None = None
    ) -> list[TraceSummary]:
        """Traces with spans matching ``query`` (service, span name, ``@tag`` values, duration)
        in ``start``..``end`` (default: the last 15 minutes), newest first, up to ``limit``
        (default 20) distinct trace ids. Summaries are built from the matching spans only."""
        q = query or TraceSearchQuery()
        start, end = self._core.window(q.start, q.end, _DEFAULT_WINDOW)
        page_limit = min(_MAX_PAGE, max(q.limit * 10, 100))
        traces: dict[str, list[Span]] = {}
        order: list[str] = []
        cursor: str | None = None
        for _ in range(_SEARCH_TRACES_MAX_PAGES):
            req = self._core.search_request(
                query=trace_search_query_string(q), start=start, end=end, limit=page_limit,
                sort="-timestamp", cursor=cursor, timezone_name=None,
            )  # fmt: skip
            page = _parse_page(self._send(req, cancel))
            _collect(page, traces, order, q.limit)
            if page.next_cursor is None or len(traces) >= q.limit:
                break
            cursor = page.next_cursor
        return _summaries(order, traces)

    def get_dependencies(
        self, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[DependencyLink]:
        """Always :class:`UnsupportedError`: Datadog's public API has no dependency graph."""
        raise _unsupported_dependencies()

    def wait_for_trace(
        self,
        trace_id: str,
        *,
        timeout_s: float | timedelta = 30.0,
        interval_s: float | timedelta = DATADOG_WAIT_INTERVAL_S,
        min_spans: int = 1,
    ) -> Trace:
        """Poll :meth:`get_trace` until the trace has ``min_spans`` spans (default interval 2 s,
        because Datadog allows 50 searches per minute); :class:`TimeoutError` otherwise."""
        return wait_for_trace(
            self, trace_id, timeout_s=timeout_s, interval_s=interval_s, min_spans=min_spans
        )

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Authenticated round trip ``GET /api/v2/validate_keys`` (API key + application key);
        returns the latency in milliseconds. Invalid keys → :class:`AuthenticationError`
        (Datadog answers 401 or 403)."""
        started = time.monotonic()
        try:
            self._send(self._core.ping_request(), cancel)
        except PermissionDeniedError as err:
            raise _as_auth_error(err) from err
        return (time.monotonic() - started) * 1000.0

    def close(self) -> None:
        """Release connections (idempotent)."""
        self._transport.close()

    def __enter__(self) -> DatadogSpansQuery:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"DatadogSpansQuery(config={self.config!r})"


# --------------------------------------------------------------------------- async


class AsyncDatadogSpansQuery(AsyncBaseTraceQueryClient):
    """Asynchronous :class:`DatadogSpansQuery` (same methods, awaited; :meth:`search_spans` is an
    async iterator). Cancel a call by cancelling its task."""

    def __init__(
        self,
        config: DatadogApmConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._core = _QueryCore(config, now)
        self.config = self._core.config
        self._transport = AsyncHttpTransport(
            self._core.transport_config,
            known_secrets=self.config.known_secrets,
            http_transport=http_transport,
        )
        self._transport._client.event_hooks["response"].append(_async_rate_limit_hint)

    @classmethod
    def from_env(
        cls, env: Mapping[str, str] | None = None, **overrides: Any
    ) -> AsyncDatadogSpansQuery:
        """Async query client from the environment (see :meth:`DatadogApmConfig.from_env`)."""
        return cls(DatadogApmConfig.from_env(env, **overrides))

    async def search_spans_page(
        self,
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        sort: SpanSort | None = None,
        cursor: str | None = None,
        timezone: str | None = None,
    ) -> DatadogSpansPage:
        """See :meth:`DatadogSpansQuery.search_spans_page`."""
        start, end = self._core.window(from_, to, _DEFAULT_WINDOW)
        req = self._core.search_request(
            query=query, start=start, end=end, limit=limit, sort=sort, cursor=cursor,
            timezone_name=timezone,
        )  # fmt: skip
        return _parse_page(await self._transport.request(req))

    async def search_spans(
        self,
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        limit: int | None = None,
        sort: SpanSort | None = None,
        cursor: str | None = None,
        timezone: str | None = None,
    ) -> AsyncIterator[DatadogSpan]:
        """See :meth:`DatadogSpansQuery.search_spans` (``async for``)."""
        start, end = self._core.window(from_, to, _DEFAULT_WINDOW)
        current = cursor
        while True:
            req = self._core.search_request(
                query=query, start=start, end=end, limit=limit, sort=sort, cursor=current,
                timezone_name=timezone,
            )  # fmt: skip
            page = _parse_page(await self._transport.request(req))
            if page.status == "timeout" and not page.spans:
                raise _timeout_without_results()
            for item in page.spans:
                yield item
            if page.next_cursor is None or page.next_cursor == current:
                return
            current = page.next_cursor

    async def aggregate_spans(
        self,
        compute: Sequence[SpanCompute | Mapping[str, Any]],
        group_by: Sequence[SpanGroupBy | Mapping[str, Any]] | None = None,
        *,
        query: str | None = None,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
        timezone: str | None = None,
    ) -> SpanAggregateResult:
        """See :meth:`DatadogSpansQuery.aggregate_spans`."""
        req = self._core.aggregate_request(
            compute, group_by, query=query, start=from_, end=to, timezone_name=timezone
        )
        return _parse_aggregate(await self._transport.request(req))

    async def get_trace(
        self,
        trace_id: str,
        *,
        preview: bool = False,
        from_: DatadogTime | None = None,
        to: DatadogTime | None = None,
    ) -> Trace | None:
        """See :meth:`DatadogSpansQuery.get_trace`."""
        wanted = normalize_trace_id(trace_id)
        if preview:
            try:
                resp = await self._transport.request(self._core.trace_request(wanted))
            except NotFoundError:
                return None
            return _parse_trace(resp, wanted)
        start, end = self._core.window(from_, to, _TRACE_WINDOW)
        by_id: dict[str, Span] = {}
        async for item in self.search_spans(
            query="trace_id:" + datadog_trace_id_string(wanted),
            from_=start,
            to=end,
            limit=_MAX_PAGE,
        ):
            by_id[item.span.span_id] = item.span
        return Trace(wanted, list(by_id.values())) if by_id else None

    async def list_services(self, *, env: str | None = None) -> list[str]:
        """See :meth:`DatadogSpansQuery.list_services`."""
        return _parse_services(await self._transport.request(self._core.services_request(env)))

    async def list_operations(self, service: str) -> list[str]:
        """See :meth:`DatadogSpansQuery.list_operations`."""
        resp = await self._transport.request(self._core.operations_request(service))
        return _operations(_parse_aggregate(resp))

    async def search_traces(self, query: TraceSearchQuery | None = None) -> list[TraceSummary]:
        """See :meth:`DatadogSpansQuery.search_traces`."""
        q = query or TraceSearchQuery()
        start, end = self._core.window(q.start, q.end, _DEFAULT_WINDOW)
        page_limit = min(_MAX_PAGE, max(q.limit * 10, 100))
        traces: dict[str, list[Span]] = {}
        order: list[str] = []
        cursor: str | None = None
        for _ in range(_SEARCH_TRACES_MAX_PAGES):
            req = self._core.search_request(
                query=trace_search_query_string(q), start=start, end=end, limit=page_limit,
                sort="-timestamp", cursor=cursor, timezone_name=None,
            )  # fmt: skip
            page = _parse_page(await self._transport.request(req))
            _collect(page, traces, order, q.limit)
            if page.next_cursor is None or len(traces) >= q.limit:
                break
            cursor = page.next_cursor
        return _summaries(order, traces)

    async def get_dependencies(
        self, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[DependencyLink]:
        """Always :class:`UnsupportedError`."""
        raise _unsupported_dependencies()

    async def wait_for_trace(
        self,
        trace_id: str,
        *,
        timeout_s: float | timedelta = 30.0,
        interval_s: float | timedelta = DATADOG_WAIT_INTERVAL_S,
        min_spans: int = 1,
    ) -> Trace:
        """See :meth:`DatadogSpansQuery.wait_for_trace`."""
        return await async_wait_for_trace(
            self,
            trace_id,
            timeout_s=to_seconds(timeout_s),
            interval_s=to_seconds(interval_s),
            min_spans=min_spans,
        )

    async def ping(self) -> float:
        """See :meth:`DatadogSpansQuery.ping`."""
        started = time.monotonic()
        try:
            await self._transport.request(self._core.ping_request())
        except PermissionDeniedError as err:
            raise _as_auth_error(err) from err
        return (time.monotonic() - started) * 1000.0

    async def aclose(self) -> None:
        """Release connections (idempotent)."""
        await self._transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncDatadogSpansQuery:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncDatadogSpansQuery(config={self.config!r})"
