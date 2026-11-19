"""DatadogApmConfig, from_env precedence and the site helpers (API.md §0, DECISIONS.md #1)."""

from __future__ import annotations

import dataclasses

import pytest

import grow_datadog_apm as sdk

P = sdk.ENV_PREFIX


def test_defaults() -> None:
    cfg = sdk.DatadogApmConfig().validate()
    assert cfg.url == sdk.DEFAULT_URL == "http://127.0.0.1:8126"
    assert cfg.protocol == sdk.DEFAULT_PROTOCOL == "http/protobuf"
    assert cfg.site == sdk.DEFAULT_SITE == "datadoghq.com"
    assert cfg.api_url == "https://api.datadoghq.com"
    assert cfg.otlp_traces_endpoint == "http://127.0.0.1:4318/v1/traces"
    assert not cfg.has_query_credentials
    assert dict(cfg.tags) == {}
    assert cfg.known_secrets() == []


def test_otlp_endpoint_resolution() -> None:
    cfg = sdk.DatadogApmConfig(url="https://agent.local:8126")
    assert cfg.otlp_traces_endpoint == "https://agent.local:4318/v1/traces"
    grpc = sdk.DatadogApmConfig(url="http://agent:8126", protocol="grpc")
    assert grpc.otlp_traces_endpoint == "http://agent:4317"
    v6 = sdk.DatadogApmConfig(url="http://[::1]:8126")
    assert v6.otlp_traces_endpoint == "http://[::1]:4318/v1/traces"
    explicit = sdk.DatadogApmConfig(otlp_endpoint="http://otel:4318/")
    assert explicit.otlp_traces_endpoint == "http://otel:4318/v1/traces"
    full = sdk.DatadogApmConfig(otlp_endpoint="http://otel:4318/custom/traces")
    assert full.otlp_traces_endpoint == "http://otel:4318/custom/traces"
    broken = sdk.DatadogApmConfig(url="http://[::1")
    assert broken.otlp_traces_endpoint == "http://127.0.0.1:4318/v1/traces"
    assert sdk.otlp_traces_url("grpc", "http://a:4317") == "http://a:4317"
    assert sdk.otlp_traces_url("http/protobuf", "http://[::1") == "http://[::1/v1/traces"


def test_site_normalization_and_api_url() -> None:
    cfg = sdk.DatadogApmConfig(site="  DatadogHQ.EU ")
    assert cfg.site == "datadoghq.eu"
    assert cfg.api_url == "https://api.datadoghq.eu"
    assert sdk.DatadogApmConfig(site="").site == "datadoghq.com"
    custom = sdk.DatadogApmConfig(site="datadoghq.eu", api_url="http://mock:1")
    assert custom.api_url == "http://mock:1"


def test_secrets_and_repr() -> None:
    cfg = sdk.DatadogApmConfig(api_key="api-key-123456", app_key="app-key-654321", tags={"a": "b"})
    assert isinstance(cfg.api_key, sdk.Secret)
    assert cfg.has_query_credentials
    assert sorted(cfg.known_secrets()) == ["api-key-123456", "app-key-654321"]
    text = repr(cfg)
    assert "api-key-123456" not in text and "app-key-654321" not in text
    assert "[REDACTED]" in text
    with pytest.raises(TypeError):
        cfg.tags["x"] = "y"  # type: ignore[index]


@pytest.mark.parametrize(
    ("kwargs", "fragment"),
    [
        ({"protocol": "native"}, "protocol"),
        ({"otlp_endpoint": "otel:4318"}, "otlp_endpoint"),
        ({"api_url": "ftp://x"}, "api_url"),
        ({"service_name": "  "}, "service_name"),
        ({"service_version": ""}, "service_version"),
        ({"environment": " "}, "environment"),
        ({"api_key": " "}, "api_key"),
        ({"app_key": ""}, "app_key"),
        ({"site": "https://app.datadoghq.com"}, "site"),
        ({"url": "ftp://agent"}, "url"),
    ],
)
def test_validate(kwargs: dict[str, object], fragment: str) -> None:
    with pytest.raises(sdk.ConfigError) as info:
        sdk.DatadogApmConfig(**kwargs).validate()  # type: ignore[arg-type]
    assert fragment in str(info.value)


def test_validate_reports_bad_api_url_from_parse_error() -> None:
    with pytest.raises(sdk.ConfigError, match="api_url"):
        sdk.DatadogApmConfig(api_url="http://[::1").validate()


def test_from_env_full_bootstrap_style() -> None:
    env = {
        P + "URL": "http://datadog-apm:8126",
        P + "OTLP_ENDPOINT": "http://datadog-apm:4318",
        P + "OTLP_HTTP_ENDPOINT": "http://datadog-apm:4318",
        P + "OTLP_GRPC_ENDPOINT": "http://datadog-apm:4317",
        "DD_AGENT_HOST": "ignored-host",
        "DD_API_KEY": "feedfacefeedfacefeedfacefeedface",
        "DD_APP_KEY": "appkeyappkeyappkey",
        "DD_SITE": "datadoghq.com",
        "DD_SERVICE": " checkout ",
        "DD_ENV": "ci",
        "DD_VERSION": "1.2.3",
        "DD_TAGS": "team:sdk, tier:web nocolon :empty",
    }
    cfg = sdk.DatadogApmConfig.from_env(env)
    assert cfg.url == "http://datadog-apm:8126"
    assert cfg.otlp_traces_endpoint == "http://datadog-apm:4318/v1/traces"
    assert (cfg.service_name, cfg.environment, cfg.service_version) == ("checkout", "ci", "1.2.3")
    assert dict(cfg.tags) == {"team": "sdk", "tier": "web"}
    assert cfg.has_query_credentials
    assert cfg.api_url == "https://api.datadoghq.com"
    grpc = sdk.DatadogApmConfig.from_env(env, protocol="grpc")
    assert grpc.otlp_traces_endpoint == "http://datadog-apm:4317"


def test_from_env_precedence() -> None:
    env = {
        P + "SERVICE": "grow",
        "DD_SERVICE": "dd",
        "OTEL_SERVICE_NAME": "otel",
        P + "APP_KEY": "grow-app-key",
        "DD_APP_KEY": "dd-app-key",
        "DD_APPLICATION_KEY": "dd-application-key",
        P + "TAGS": "a:1",
        "DD_TAGS": "b:2",
        P + "API_URL": "http://api.mock",
        P + "SITE": "datadoghq.eu",
    }
    cfg = sdk.DatadogApmConfig.from_env(env, tags={"c": "3"}, service_name=None)
    assert cfg.service_name == "grow"
    assert cfg.app_key is not None and cfg.app_key.reveal() == "grow-app-key"
    assert dict(cfg.tags) == {"a": "1", "c": "3"}
    assert cfg.api_url == "http://api.mock"
    assert cfg.site == "datadoghq.eu"
    assert sdk.DatadogApmConfig.from_env({"OTEL_SERVICE_NAME": "otel"}).service_name == "otel"
    only = sdk.DatadogApmConfig.from_env({"DD_APPLICATION_KEY": "dd-application-key"})
    assert only.app_key is not None and only.app_key.reveal() == "dd-application-key"
    explicit = sdk.DatadogApmConfig.from_env(env, service_name="explicit", tags={"z": "9"})
    assert explicit.service_name == "explicit"
    assert sdk.DatadogApmConfig.from_env({}, tags={"z": "9"}).tags == {"z": "9"}


def test_from_env_agent_url() -> None:
    assert sdk.DatadogApmConfig.from_env({}).url == sdk.DEFAULT_URL
    assert sdk.DatadogApmConfig.from_env({"DD_AGENT_HOST": "agent"}).url == "http://agent:8126"
    assert (
        sdk.DatadogApmConfig.from_env(
            {"DD_AGENT_HOST": "agent", "DD_TRACE_AGENT_PORT": " 9126 "}
        ).url
        == "http://agent:9126"
    )
    assert (
        sdk.DatadogApmConfig.from_env({"DD_TRACE_AGENT_PORT": "9126"}).url
        == "http://127.0.0.1:9126"
    )
    assert sdk.DatadogApmConfig.from_env({"DD_AGENT_HOST": "::1"}).url == "http://[::1]:8126"
    assert (
        sdk.DatadogApmConfig.from_env(
            {"DD_TRACE_AGENT_URL": "http://ddtrace:8126", "DD_AGENT_HOST": "agent"}
        ).url
        == "http://ddtrace:8126"
    )
    assert (
        sdk.DatadogApmConfig.from_env({"DD_AGENT_HOST": "agent"}, url="http://explicit:1").url
        == "http://explicit:1"
    )
    derived = sdk.DatadogApmConfig.from_env({"DD_AGENT_HOST": "agent"})
    assert derived.otlp_traces_endpoint == "http://agent:4318/v1/traces"
    for bad in ("x", "0", "70000", "-1"):
        with pytest.raises(sdk.ConfigError) as info:
            sdk.DatadogApmConfig.from_env({"DD_TRACE_AGENT_PORT": bad})
        assert "DD_TRACE_AGENT_PORT" in str(info.value)


def test_from_env_protocol_and_otel_endpoints() -> None:
    cfg = sdk.DatadogApmConfig.from_env({"OTEL_EXPORTER_OTLP_PROTOCOL": " GRPC "})
    assert cfg.protocol == "grpc"
    assert cfg.otlp_traces_endpoint == "http://127.0.0.1:4317"
    traces = sdk.DatadogApmConfig.from_env(
        {
            "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL": "http/protobuf",
            "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc",
        }
    )
    assert traces.protocol == "http/protobuf"
    with pytest.raises(sdk.ConfigError) as info:
        sdk.DatadogApmConfig.from_env({P + "OTLP_PROTOCOL": "http/json"})
    assert P + "OTLP_PROTOCOL" in str(info.value) and "http/json" not in str(info.value)
    base = sdk.DatadogApmConfig.from_env({"OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318/"})
    assert base.otlp_endpoint == "http://otel:4318/v1/traces"
    grpc = sdk.DatadogApmConfig.from_env(
        {"OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4317", "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc"}
    )
    assert grpc.otlp_endpoint == "http://otel:4317"
    signal = sdk.DatadogApmConfig.from_env(
        {
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT": "http://t:4318/v1/traces",
            "OTEL_EXPORTER_OTLP_ENDPOINT": "http://ignored:4318",
        }
    )
    assert signal.otlp_endpoint == "http://t:4318/v1/traces"
    explicit = sdk.DatadogApmConfig.from_env(
        {P + "OTLP_ENDPOINT": "http://env:4318"}, otlp_endpoint="http://explicit:4318"
    )
    assert explicit.otlp_endpoint == "http://explicit:4318"


def test_parse_datadog_tags() -> None:
    assert sdk.parse_datadog_tags("team:sdk, tier:web") == {"team": "sdk", "tier": "web"}
    assert sdk.parse_datadog_tags("a:b:c x:") == {"a": "b:c", "x": ""}
    assert sdk.parse_datadog_tags("") == {}


def test_sites() -> None:
    assert len(sdk.DATADOG_SITES) == 9
    assert sdk.is_datadog_site(" DATADOGHQ.EU")
    assert not sdk.is_datadog_site("example.com")
    assert sdk.validate_site("US5.datadoghq.com") == "us5.datadoghq.com"
    assert sdk.validate_site("custom.example.org") == "custom.example.org"
    for bad in ("https://app.datadoghq.com", "datadoghq.com:443", "datadoghq", "a" * 254 + ".com"):
        with pytest.raises(sdk.ConfigError):
            sdk.validate_site(bad)
    assert sdk.api_url_for_site("datadoghq.eu") == "https://api.datadoghq.eu"
    assert sdk.api_url_for_site() == "https://api.datadoghq.com"
    assert sdk.api_url_for_site("  ") == "https://api.datadoghq.com"
    assert sdk.normalize_site(" Ddog-Gov.com ") == "ddog-gov.com"


def test_replace_keeps_api_url() -> None:
    cfg = sdk.DatadogApmConfig()
    moved = dataclasses.replace(cfg, site="datadoghq.eu")
    assert moved.api_url == "https://api.datadoghq.com"  # documented: pass api_url again
