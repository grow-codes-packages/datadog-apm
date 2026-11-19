"""Client round trips against the real Datadog Agent plus the core conformance scenarios."""

from __future__ import annotations

import asyncio
import os
from typing import Any

import pytest
from factories import CORE_CAPABILITIES, async_core_client, core_client

import grow_datadog_apm as sdk
from grow_datadog_apm.testing import ConformanceRunner, assert_scenario, pytest_params


def test_health(client: sdk.DatadogApmClient, env: dict[str, str]) -> None:
    status = client.health()
    assert status.healthy, status
    assert status.status == "ok"
    assert status.details["version"] == env["DD_AGENT_VERSION"]
    assert status.details["git_commit"]


def test_ping(client: sdk.DatadogApmClient) -> None:
    assert client.ping() > 0


def test_server_version_and_agent_info(client: sdk.DatadogApmClient, env: dict[str, str]) -> None:
    version = client.server_version()
    assert version == env["DD_AGENT_VERSION"]
    info = client.agent_info()
    assert info.version == version
    assert "/v0.4/traces" in info.endpoints
    assert info.config["receiver_port"] == 8126
    raw = client.request(sdk.Request("GET", "/info"))
    assert raw.json()["version"] == version


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.ping()


def test_unknown_path_and_unreachable(client: sdk.DatadogApmClient) -> None:
    with pytest.raises(sdk.NotFoundError) as info:
        client.request(sdk.Request("GET", "/nope"))
    assert info.value.http_status == 404
    dead = sdk.DatadogApmClient(
        sdk.DatadogApmConfig(
            url="http://127.0.0.1:1", connect_timeout=1, retry=sdk.RetryPolicy(max_retries=0)
        )
    )
    status = dead.health()
    assert (status.healthy, status.status) == (False, "unreachable")
    dead.close()


def test_config_from_bootstrap_env(env: dict[str, str]) -> None:
    cfg = sdk.DatadogApmConfig.from_env(env)
    assert cfg.url == env["GROW_DATADOG_APM_URL"]
    assert cfg.otlp_traces_endpoint == env["GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT"] + "/v1/traces"
    assert cfg.site == "datadoghq.com"
    assert cfg.has_query_credentials
    grpc = sdk.DatadogApmConfig.from_env(env, protocol="grpc")
    assert grpc.otlp_traces_endpoint == env["GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT"]
    assert env["DD_API_KEY"] not in repr(cfg)
    assert env["DD_API_KEY"] not in str(cfg)
    agent_only = sdk.DatadogApmConfig.from_env(
        {"DD_AGENT_HOST": env["DD_AGENT_HOST"], "DD_TRACE_AGENT_PORT": env["DD_TRACE_AGENT_PORT"]}
    )
    with sdk.DatadogApmClient(agent_only) as c:
        assert c.server_version() == env["DD_AGENT_VERSION"]
    assert os.environ["GROW_DATADOG_APM_URL"] == env["GROW_DATADOG_APM_URL"]


def test_query_client_construction(client: sdk.DatadogApmClient) -> None:
    """The Spans API is SaaS-only (unit-tested); constructing it needs both keys."""
    spans = client.spans
    assert spans.config.api_url == "https://api.datadoghq.com"
    with pytest.raises(sdk.ConfigError):
        sdk.DatadogSpansQuery(sdk.DatadogApmConfig(api_key="only-one-key"))
    sdk.DatadogSpansQuery.from_env().close()
    with pytest.raises(sdk.UnsupportedError):
        spans.get_dependencies()


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() > 0
            assert await c.server_version() == env["DD_AGENT_VERSION"]
            assert (await c.agent_info()).git_commit
            assert (await c.request(sdk.Request("GET", "/info"))).status == 200
            assert c.spans.config.has_query_credentials
        await c.aclose()

    asyncio.run(run())


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance_async(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: async_core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)
