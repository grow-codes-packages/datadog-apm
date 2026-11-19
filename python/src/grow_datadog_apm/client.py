"""Datadog APM clients (sync and async), API.md §1.

They talk to the local Datadog Agent (``GET /info`` on the trace intake port 8126, no
authentication) and give access to the tracing setup (:meth:`DatadogApmClient.init_tracing`) and
the SaaS spans query API (:attr:`DatadogApmClient.spans`).
"""

from __future__ import annotations

import asyncio
import dataclasses
import threading
import time
from collections.abc import Mapping, Sequence
from types import MappingProxyType, TracebackType
from typing import Any

import httpx
from opentelemetry.sdk.trace import SpanProcessor

from ._core import (
    AsyncHttpTransport,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    HttpTransport,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    UnsupportedError,
)
from ._tracing import TracingConfig
from .config import DatadogApmConfig
from .setup import DatadogTracingHandle, init_tracing
from .spans import AsyncDatadogSpansQuery, DatadogSpansQuery

__all__ = ["AgentInfo", "DatadogApmClient", "AsyncDatadogApmClient"]


@dataclasses.dataclass(frozen=True)
class AgentInfo:
    """The Agent's ``GET /info`` document (fields this package reads)."""

    #: Agent version, e.g. ``7.85.4``.
    version: str | None = None
    #: Agent build commit.
    git_commit: str | None = None
    #: Trace intake endpoints the Agent serves.
    endpoints: tuple[str, ...] = ()
    #: The Agent's reported configuration.
    config: Mapping[str, Any] = dataclasses.field(default_factory=dict)
    #: The whole document.
    raw: Mapping[str, Any] = dataclasses.field(default_factory=dict)

    @classmethod
    def from_json(cls, doc: Any) -> AgentInfo:
        """Parse a decoded ``GET /info`` body (anything that is not an object gives defaults)."""
        if not isinstance(doc, Mapping):
            return cls()
        version, commit = doc.get("version"), doc.get("git_commit")
        endpoints, config = doc.get("endpoints"), doc.get("config")
        return cls(
            version=version if isinstance(version, str) and version else None,
            git_commit=commit if isinstance(commit, str) and commit else None,
            endpoints=tuple(e for e in endpoints if isinstance(e, str))
            if isinstance(endpoints, list)
            else (),
            config=MappingProxyType(dict(config)) if isinstance(config, Mapping) else {},
            raw=MappingProxyType(dict(doc)),
        )


_INFO = Request("GET", "/info", operation="agent_info")
_HEALTH = Request("GET", "/info", operation="health", max_retries=0)
_PING = Request("GET", "/info", operation="ping")


def _info_from_response(resp: Response) -> AgentInfo:
    if not resp.content.strip():
        return AgentInfo()
    return AgentInfo.from_json(resp.json())


def _health_ok(info: AgentInfo) -> HealthStatus:
    details: dict[str, str] = {}
    if info.version:
        details["version"] = info.version
    if info.git_commit:
        details["git_commit"] = info.git_commit
    return HealthStatus(healthy=True, status="ok", details=details)


def _health_from_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    if err.http_status is not None:
        return HealthStatus(
            healthy=False,
            status="unhealthy",
            details={"httpStatus": str(err.http_status), "error": err.code.value},
        )
    return HealthStatus(healthy=False, status="unreachable", details={"error": err.code.value})


def _version(info: AgentInfo) -> str:
    if not info.version:
        raise UnsupportedError("server_version: the Datadog Agent did not report a version")
    return info.version


class DatadogApmClient:
    """Synchronous Datadog APM client. Thread-safe; use as a context manager or call
    :meth:`close`.

    :meth:`health`, :meth:`ping` and :meth:`server_version` use the Datadog Agent's
    ``GET /info``; :meth:`init_tracing` sets up OpenTelemetry exporting to the Agent;
    :attr:`spans` is the SaaS query API (needs ``api_key`` + ``app_key``). Every I/O method
    accepts ``cancel=`` (a :class:`CancelToken`) to abort from another thread.

    >>> client = DatadogApmClient.from_env()  # doctest: +SKIP
    >>> handle = client.init_tracing(service_name="checkout")  # doctest: +SKIP
    >>> client.server_version()  # doctest: +SKIP
    '7.85.4'
    >>> client.close()  # flushes and shuts down the tracing it started  # doctest: +SKIP
    """

    def __init__(
        self,
        config: DatadogApmConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        if config is None:
            config = DatadogApmConfig.from_env()
        self.config = config.validate()
        self._http_transport = http_transport
        self._resources = ResourceRegistry()
        self._transport = HttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._lock = threading.Lock()
        self._handles: list[DatadogTracingHandle] = []
        self._spans: DatadogSpansQuery | None = None
        self._closed = False

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> DatadogApmClient:
        """Build a client from ``GROW_DATADOG_APM_*`` and ``DD_*`` variables (see
        :meth:`DatadogApmConfig.from_env`)."""
        return cls(DatadogApmConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`close`."""
        return self._resources

    @property
    def spans(self) -> DatadogSpansQuery:
        """The SaaS spans query API (created on first use, closed by :meth:`close`).

        Raises :class:`ConfigError` when ``api_key`` or ``app_key`` is missing.
        """
        with self._lock:
            if self._spans is None:
                self._spans = DatadogSpansQuery(self.config, http_transport=self._http_transport)
            return self._spans

    def request(self, req: Request, *, cancel: CancelToken | None = None) -> Response:
        """Send a raw request to the Agent (retries, hooks, redaction)."""
        return self._transport.request(req, cancel=cancel)

    def agent_info(self, *, cancel: CancelToken | None = None) -> AgentInfo:
        """``GET /info`` of the Agent."""
        return _info_from_response(self._transport.request(_INFO, cancel=cancel))

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Agent liveness via ``GET /info``.

        ``healthy`` with status ``ok`` and details ``version``, ``git_commit`` when the Agent
        answers; ``unreachable`` on network errors, ``unhealthy`` on HTTP errors (details
        ``httpStatus``, ``error``). Raises only :class:`CancelledError` and
        :class:`ConfigError`.
        """
        try:
            info = _info_from_response(self._transport.request(_HEALTH, cancel=cancel))
        except ServiceError as err:
            return _health_from_error(err)
        return _health_ok(info)

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Round trip to the Agent (``GET /info``); returns the latency in milliseconds.

        The Agent has no authentication; to check Datadog API credentials use
        ``client.spans.ping()``.
        """
        started = time.monotonic()
        self._transport.request(_PING, cancel=cancel)
        return (time.monotonic() - started) * 1000.0

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """Datadog Agent version from ``GET /info`` (e.g. ``7.85.4``); :class:`UnsupportedError`
        when the Agent reports none."""
        return _version(self.agent_info(cancel=cancel))

    def init_tracing(
        self,
        config: TracingConfig | Mapping[str, Any] | None = None,
        *,
        span_processors: Sequence[SpanProcessor] = (),
        **fields: Any,
    ) -> DatadogTracingHandle:
        """:func:`~grow_datadog_apm.init_tracing` with this client's configuration as the
        defaults (Agent host, OTLP endpoint and protocol, service/env/version, tags).
        :meth:`close` shuts the handle down."""
        handle = init_tracing(
            config, apm_config=self.config, span_processors=span_processors, **fields
        )
        with self._lock:
            self._handles.append(handle)
        return handle

    def _take(self) -> tuple[list[DatadogTracingHandle], Any] | None:
        with self._lock:
            if self._closed:
                return None
            self._closed = True
            handles, self._handles = self._handles, []
            return handles, self._spans

    def close(self) -> None:
        """Flush and shut down every tracing handle started with :meth:`init_tracing`, close the
        query API and release connections (idempotent)."""
        taken = self._take()
        if taken is None:
            return
        handles, spans = taken
        try:
            for handle in handles:
                handle.shutdown()
        finally:
            if spans is not None:
                spans.close()
            self._resources.stop_all()
            self._transport.close()

    def __enter__(self) -> DatadogApmClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"DatadogApmClient(config={self.config!r})"


class AsyncDatadogApmClient:
    """Asynchronous Datadog APM client; use ``async with`` or call :meth:`aclose`.

    Cancel a call by cancelling its task: the SDK raises :class:`CancelledError`, which is also
    an :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: DatadogApmConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if config is None:
            config = DatadogApmConfig.from_env()
        self.config = config.validate()
        self._http_transport = http_transport
        self._resources = ResourceRegistry()
        self._transport = AsyncHttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._handles: list[DatadogTracingHandle] = []
        self._spans: AsyncDatadogSpansQuery | None = None
        self._closed = False

    @classmethod
    def from_env(
        cls, env: Mapping[str, str] | None = None, **overrides: Any
    ) -> AsyncDatadogApmClient:
        """Build an async client from the environment (see :meth:`DatadogApmConfig.from_env`)."""
        return cls(DatadogApmConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`aclose`."""
        return self._resources

    @property
    def spans(self) -> AsyncDatadogSpansQuery:
        """The async SaaS spans query API (created on first use, closed by :meth:`aclose`)."""
        if self._spans is None:
            self._spans = AsyncDatadogSpansQuery(self.config, http_transport=self._http_transport)
        return self._spans

    async def request(self, req: Request) -> Response:
        """Send a raw request to the Agent."""
        return await self._transport.request(req)

    async def agent_info(self) -> AgentInfo:
        """See :meth:`DatadogApmClient.agent_info`."""
        return _info_from_response(await self._transport.request(_INFO))

    async def health(self) -> HealthStatus:
        """See :meth:`DatadogApmClient.health`."""
        try:
            info = _info_from_response(await self._transport.request(_HEALTH))
        except ServiceError as err:
            return _health_from_error(err)
        return _health_ok(info)

    async def ping(self) -> float:
        """See :meth:`DatadogApmClient.ping`."""
        started = time.monotonic()
        await self._transport.request(_PING)
        return (time.monotonic() - started) * 1000.0

    async def server_version(self) -> str:
        """See :meth:`DatadogApmClient.server_version`."""
        return _version(await self.agent_info())

    def init_tracing(
        self,
        config: TracingConfig | Mapping[str, Any] | None = None,
        *,
        span_processors: Sequence[SpanProcessor] = (),
        **fields: Any,
    ) -> DatadogTracingHandle:
        """See :meth:`DatadogApmClient.init_tracing` (no I/O, so not a coroutine)."""
        handle = init_tracing(
            config, apm_config=self.config, span_processors=span_processors, **fields
        )
        self._handles.append(handle)
        return handle

    async def aclose(self) -> None:
        """Flush and shut down the tracing handles (in a worker thread), close the query API and
        release connections (idempotent)."""
        if self._closed:
            return
        self._closed = True
        handles, self._handles = self._handles, []
        try:
            for handle in handles:
                await asyncio.to_thread(handle.shutdown)
        finally:
            if self._spans is not None:
                await self._spans.aclose()
            self._resources.stop_all()
            await self._transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncDatadogApmClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncDatadogApmClient(config={self.config!r})"
