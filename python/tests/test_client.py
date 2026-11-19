"""Datadog APM client (sync + async) against a mock Agent, plus the polling helpers."""

from __future__ import annotations

import asyncio
import time
from typing import Any

import pytest

import grow_datadog_apm as sdk
from _support import (
    MockServer,
    Reply,
    closed_port_url,
    fast_retry,
    fixture_body,
    make_config,
    spawn_capturing,
)

API_KEY = "client-api-key-789"
APP_KEY = "client-app-key-456"
INFO = fixture_body("health.info.ok.json")


def client(url: str, **kw: Any) -> sdk.DatadogApmClient:
    return sdk.DatadogApmClient(make_config(url, **kw))


def aclient(url: str, **kw: Any) -> sdk.AsyncDatadogApmClient:
    return sdk.AsyncDatadogApmClient(make_config(url, **kw))


# --------------------------------------------------------------------------- sync


def test_health_ping_version_agent_info(server: MockServer) -> None:
    server.defaults["/info"] = Reply(200, INFO)
    with client(server.url, api_key=API_KEY, app_key=APP_KEY) as c:
        assert c.health() == sdk.HealthStatus(
            True, "ok", {"version": "7.85.4", "git_commit": "52568a4d"}
        )
        latency = c.ping()
        assert isinstance(latency, float) and latency >= 0
        assert c.server_version() == "7.85.4"
        info = c.agent_info()
        assert info.version == "7.85.4" and info.git_commit == "52568a4d"
        assert "/v0.4/traces" in info.endpoints
        assert info.config["receiver_port"] == 8126
        assert info.raw["version"] == "7.85.4"
        assert API_KEY not in repr(c) and APP_KEY not in repr(c)
    assert {s.path for s in server.seen} == {"/info"}
    assert all("DD-API-KEY" not in s.headers for s in server.seen)  # the Agent has no auth


def test_agent_info_variants(server: MockServer) -> None:
    c = client(server.url)
    server.enqueue("/info", Reply(200, ""))
    assert c.agent_info() == sdk.AgentInfo()
    server.enqueue("/info", Reply(200, [1, 2]))
    assert c.agent_info() == sdk.AgentInfo()
    server.enqueue("/info", Reply(200, {"version": "", "endpoints": "x", "config": 1}))
    assert c.agent_info() == sdk.AgentInfo(raw={"version": "", "endpoints": "x", "config": 1})


def test_health_variants(server: MockServer) -> None:
    c = client(server.url)
    server.enqueue("/info", Reply(503, {"status": "degraded"}))
    assert c.health() == sdk.HealthStatus(
        False, "unhealthy", {"httpStatus": "503", "error": "UNAVAILABLE"}
    )
    server.enqueue("/info", Reply(404, "404 page not found\n"))
    assert c.health() == sdk.HealthStatus(
        False, "unhealthy", {"httpStatus": "404", "error": "NOT_FOUND"}
    )
    server.enqueue("/info", Reply(200, "not json"))
    assert c.health().status == "unhealthy"
    server.enqueue("/info", Reply(200, ""))
    assert c.health() == sdk.HealthStatus(True, "ok", {})
    assert len([s for s in server.seen if s.path == "/info"]) == 4  # health never retries


def test_core_health_unreachable() -> None:
    """core.health.unreachable"""
    c = client(closed_port_url())
    status = c.health()
    assert status.healthy is False
    assert status.status == "unreachable"
    assert status.details == {"error": "UNAVAILABLE"}


def test_health_timeout_is_unreachable(server: MockServer) -> None:
    server.enqueue("/info", Reply(200, delay=1.0))
    c = client(server.url, request_timeout=0.1)
    status = c.health()
    assert status.status == "unreachable"
    assert status.details == {"error": "TIMEOUT"}


def test_health_raises_only_cancelled_and_config(server: MockServer) -> None:
    c = client(server.url)
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.health()
    token = sdk.CancelToken()
    token.cancel()
    with pytest.raises(sdk.CancelledError):
        client(server.url).health(cancel=token)
    bad = sdk.DatadogApmClient.__new__(sdk.DatadogApmClient)
    bad.config = sdk.DatadogApmConfig(url="ftp://svc")
    bad._transport = sdk.HttpTransport(bad.config)
    with pytest.raises(sdk.ConfigError):
        bad.health()


def test_ping_errors(server: MockServer) -> None:
    server.enqueue("/info", Reply(404, fixture_body("health.unknown_path.404.json")))
    with pytest.raises(sdk.NotFoundError) as info:
        client(server.url).ping()
    assert "404 page not found" in str(info.value)
    with pytest.raises(sdk.UnavailableError):
        client(closed_port_url(), retry=fast_retry(max_retries=0)).ping()


def test_server_version_errors(server: MockServer) -> None:
    c = client(server.url)
    server.enqueue("/info", Reply(200, {"git_commit": "x"}))
    with pytest.raises(sdk.UnsupportedError):
        c.server_version()
    server.enqueue("/info", Reply(200, "not json"))
    with pytest.raises(sdk.ValidationError):
        c.server_version()
    server.enqueue("/info", Reply(403))
    with pytest.raises(sdk.PermissionDeniedError):
        c.server_version()


def test_core_close_idempotent(server: MockServer) -> None:
    """core.close.idempotent"""
    c = client(server.url)
    stopped: list[int] = []

    class Res:
        def stop(self) -> None:
            stopped.append(1)

    c.resources.register(Res())
    c.close()
    c.close()
    assert stopped == [1]
    c.resources.register(Res())  # registering after close stops immediately
    assert stopped == [1, 1]


def test_close_shuts_down_tracing_and_spans(server: MockServer) -> None:
    c = client(server.url, api_key=API_KEY, app_key=APP_KEY, service_name="svc")
    handle = c.init_tracing(set_global=False)
    assert handle.endpoint == f"{server.url.rsplit(':', 1)[0]}:4318/v1/traces"
    spans = c.spans
    assert c.spans is spans
    assert spans.config.api_url == "https://api.datadoghq.com"
    c.close()
    assert handle.is_shut_down
    c.close()
    with pytest.raises(sdk.CancelledError):
        spans.ping()


def test_spans_needs_both_keys(server: MockServer) -> None:
    with pytest.raises(sdk.ConfigError) as info:
        _ = client(server.url, api_key=API_KEY).spans
    assert API_KEY not in str(info.value)


def test_from_env_and_raw_request(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    env = {sdk.ENV_PREFIX + "URL": server.url, "DD_API_KEY": API_KEY}
    with sdk.DatadogApmClient.from_env(env) as c:
        assert c.config.url == server.url
        resp = c.request(sdk.Request("GET", "/anything"))
        assert resp.status == 200
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    with sdk.DatadogApmClient() as c2:
        assert c2.config.url == server.url
    with pytest.raises(sdk.ConfigError):
        sdk.DatadogApmClient.from_env({sdk.ENV_PREFIX + "URL": "nope://x"})


# --------------------------------------------------------------------------- async


async def test_async_client(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    server.defaults["/info"] = Reply(200, INFO)
    async with aclient(server.url, api_key=API_KEY, app_key=APP_KEY, service_name="svc") as c:
        assert (await c.health()).details["version"] == "7.85.4"
        assert await c.ping() >= 0
        assert await c.server_version() == "7.85.4"
        assert (await c.agent_info()).git_commit == "52568a4d"
        assert (await c.request(sdk.Request("GET", "/raw"))).status == 200
        assert API_KEY not in repr(c)
        assert c.resources is not None
        handle = c.init_tracing(set_global=False)
        spans = c.spans
        assert c.spans is spans
    assert handle.is_shut_down
    await c.close()
    with pytest.raises(sdk.CancelledError):
        await c.health()
    with pytest.raises(sdk.CancelledError):
        await spans.ping()
    env = {sdk.ENV_PREFIX + "URL": server.url}
    c2 = sdk.AsyncDatadogApmClient.from_env(env)
    server.enqueue("/info", Reply(200, {}))
    with pytest.raises(sdk.UnsupportedError):
        await c2.server_version()
    server.enqueue("/info", Reply(500))
    assert (await c2.health()).status == "unhealthy"
    await c2.aclose()
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    c3 = sdk.AsyncDatadogApmClient()
    assert c3.config.url == server.url
    await c3.aclose()


async def test_async_health_unreachable() -> None:
    """core.health.unreachable (async)"""
    c = aclient(closed_port_url())
    status = await c.health()
    assert (status.healthy, status.status) == (False, "unreachable")
    await c.aclose()


async def test_async_health_cancel(server: MockServer) -> None:
    server.enqueue("/info", Reply(200, delay=2.0))
    c = aclient(server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(c.health(), errors)
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    await c.aclose()


# --------------------------------------------------------------------------- polling


def test_poll_until() -> None:
    values = iter([None, [], [1]])
    assert sdk.poll_until(lambda: next(values), interval_s=0.001) == [1]
    attempts = iter([0, 1, 2, 3])
    assert sdk.poll_until(lambda: next(attempts), predicate=lambda v: v >= 2, interval_s=0) == 2
    errors = iter([sdk.UnavailableError("flaky"), "ok"])

    def flaky() -> str:
        item = next(errors)
        if isinstance(item, Exception):
            raise item
        return item

    assert sdk.poll_until(flaky, interval_s=0) == "ok"
    with pytest.raises(sdk.NotFoundError):
        sdk.poll_until(lambda: (_ for _ in ()).throw(sdk.NotFoundError("gone")))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError, match=r"wait\.thing") as info:
        sdk.poll_until(
            lambda: (_ for _ in ()).throw(sdk.UnavailableError("down")),
            timeout_s=0.05,
            interval_s=0.01,
            operation="wait.thing",
        )
    assert isinstance(info.value.cause, sdk.UnavailableError)
    assert time.monotonic() - started < 1.0
    with pytest.raises(sdk.TimeoutError):
        sdk.poll_until(lambda: None, timeout_s=0.02, interval_s=0.005)


async def test_apoll_until() -> None:
    values = iter([None, "", "done"])

    async def fetch() -> str | None:
        return next(values)

    assert await sdk.apoll_until(fetch, interval_s=0.001) == "done"

    async def failing() -> str:
        raise sdk.UnavailableError("down")

    with pytest.raises(sdk.TimeoutError):
        await sdk.apoll_until(failing, timeout_s=0.03, interval_s=0.01)

    async def fatal() -> str:
        raise sdk.AuthenticationError("no")

    with pytest.raises(sdk.AuthenticationError):
        await sdk.apoll_until(fatal)

    async def numbers() -> int:
        return 5

    assert await sdk.apoll_until(numbers, predicate=lambda n: n == 5) == 5


def test_resource_registry() -> None:
    reg = sdk.ResourceRegistry()
    calls: list[str] = []

    class Good:
        def stop(self) -> None:
            calls.append("good")

    class Bad:
        def stop(self) -> None:
            raise RuntimeError("ignored")

    good, bad = Good(), Bad()
    reg.register(bad)
    reg.register(good)
    reg.unregister(good)
    reg.unregister(good)
    reg.register(good)
    assert isinstance(good, sdk.Stoppable)
    assert not reg.closed
    reg.stop_all()
    reg.stop_all()
    assert reg.closed and calls == ["good"]
    reg.register(bad)  # stopped immediately, error ignored


def test_get_logger_is_silent() -> None:
    logger = sdk.get_logger()
    assert logger.name == sdk.LOGGER_NAME == "grow_datadog_apm"
    assert any(type(h).__name__ == "NullHandler" for h in logger.handlers)
