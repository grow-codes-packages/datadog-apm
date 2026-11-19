"""Configuration of the Datadog APM client (API.md §0, DECISIONS.md #1)."""

from __future__ import annotations

import dataclasses
import os
import re
from collections.abc import Mapping
from types import MappingProxyType
from typing import Any, ClassVar, Literal, TypeVar
from urllib.parse import urlsplit

from ._core import BaseConfig, ConfigError, EnvReader, Secret
from .sites import DEFAULT_SITE, normalize_site, validate_site

__all__ = [
    "DEFAULT_URL",
    "DEFAULT_PROTOCOL",
    "OTLP_HTTP_PORT",
    "OTLP_GRPC_PORT",
    "AGENT_TRACE_PORT",
    "DatadogOtlpProtocol",
    "DatadogApmConfig",
    "parse_datadog_tags",
    "otlp_traces_url",
]

#: Default Datadog Agent trace intake URL (``health``, ``ping`` and ``server_version`` use its
#: ``GET /info``).
DEFAULT_URL = "http://127.0.0.1:8126"
#: Default Agent OTLP/HTTP port.
OTLP_HTTP_PORT = 4318
#: Default Agent OTLP/gRPC port.
OTLP_GRPC_PORT = 4317
#: Default Agent trace intake port (``GET /info``).
AGENT_TRACE_PORT = 8126

DatadogOtlpProtocol = Literal["http/protobuf", "grpc"]
#: Default OTLP protocol of this package.
DEFAULT_PROTOCOL: DatadogOtlpProtocol = "http/protobuf"
_PROTOCOLS = ("http/protobuf", "grpc")

_PREFIX = "GROW_DATADOG_APM_"
_TAG_SPLIT = re.compile(r"[\s,]+")

_C = TypeVar("_C", bound="DatadogApmConfig")


def parse_datadog_tags(raw: str) -> dict[str, str]:
    """Parse Datadog tags (``DD_TAGS``): ``key:value`` pairs separated by commas and/or spaces.

    Entries without a ``:`` or with an empty key are ignored.

    >>> parse_datadog_tags("team:sdk, tier:web")
    {'team': 'sdk', 'tier': 'web'}
    """
    out: dict[str, str] = {}
    for part in _TAG_SPLIT.split(raw):
        key, sep, value = part.partition(":")
        if sep and key:
            out[key] = value
    return out


def _is_http_url(value: str) -> bool:
    try:
        parts = urlsplit(value)
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.netloc)


def _has_path(value: str) -> bool:
    try:
        path = urlsplit(value).path
    except ValueError:
        return False
    return path not in ("", "/")


def otlp_traces_url(protocol: str, endpoint: str) -> str:
    """Exporter URL for a protocol and an OTLP endpoint.

    For ``http/protobuf`` an endpoint without a path gets ``/v1/traces``; gRPC endpoints are used
    as they are.

    >>> otlp_traces_url("http/protobuf", "http://agent:4318")
    'http://agent:4318/v1/traces'
    """
    if protocol == "grpc" or _has_path(endpoint):
        return endpoint
    return endpoint.rstrip("/") + "/v1/traces"


def _first_env(env: Mapping[str, str], *names: str) -> tuple[str, str] | None:
    for name in names:
        value = env.get(name)
        if value is not None and value != "":
            return name, value
    return None


def _agent_url_from_env(env: Mapping[str, str]) -> str | None:
    """``http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}`` (``None`` when neither is set)."""
    host_found = _first_env(env, "DD_AGENT_HOST")
    port_found = _first_env(env, "DD_TRACE_AGENT_PORT")
    host = host_found[1].strip().strip("[]") if host_found else ""
    port = AGENT_TRACE_PORT
    if port_found is not None:
        raw = port_found[1].strip()
        if not raw.isdigit() or not 1 <= int(raw) <= 65535:
            raise ConfigError(f"config: {port_found[0]} must be a port number")
        port = int(raw)
    if not host and port_found is None:
        return None
    host = host or "127.0.0.1"
    if ":" in host:
        host = f"[{host}]"
    return f"http://{host}:{port}"


def _otlp_endpoint_from_env(reader: EnvReader, env: Mapping[str, str], protocol: str) -> str | None:
    specific = "OTLP_GRPC_ENDPOINT" if protocol == "grpc" else "OTLP_HTTP_ENDPOINT"
    value = reader.string(
        specific, (_PREFIX + "OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
    )
    if value is not None:
        return value.strip()
    found = _first_env(env, "OTEL_EXPORTER_OTLP_ENDPOINT")
    if found is None:
        return None
    base = found[1].strip()
    return base if protocol == "grpc" else base.rstrip("/") + "/v1/traces"


@dataclasses.dataclass(frozen=True, repr=False)
class DatadogApmConfig(BaseConfig):
    """Immutable settings for :class:`~grow_datadog_apm.DatadogApmClient`,
    :func:`~grow_datadog_apm.init_tracing` and :class:`~grow_datadog_apm.DatadogSpansQuery`.

    Common fields (``url``, timeouts, ``retry``, ``tls``, ``proxy``, ``headers``,
    ``user_agent_suffix``, ``hooks``, ``logger``) come from :class:`BaseConfig`; ``url`` is the
    Datadog Agent's trace intake (``GET /info``, default ``http://127.0.0.1:8126``). Durations
    are float seconds; ``datetime.timedelta`` is accepted. ``api_key`` / ``app_key`` accept a
    ``str`` and are stored as :class:`Secret`; ``repr()`` never shows them.

    The Agent needs no key: the SDK sends OTLP to the Agent, which holds ``DD_API_KEY``.
    ``api_key`` + ``app_key`` are only needed by the SaaS query API
    (:class:`~grow_datadog_apm.DatadogSpansQuery`).

    Environment (:meth:`from_env`; explicit value > ``GROW_DATADOG_APM_<NAME>`` > vendor
    variables > default):

    ================  ==========================================================
    Field             ``GROW_DATADOG_APM_*`` → vendor variables
    ================  ==========================================================
    url               ``URL`` → ``DD_TRACE_AGENT_URL``, else
                      ``http://$DD_AGENT_HOST:$DD_TRACE_AGENT_PORT``
    otlp_endpoint     ``OTLP_HTTP_ENDPOINT`` / ``OTLP_GRPC_ENDPOINT`` (by protocol),
                      ``OTLP_ENDPOINT`` → ``OTEL_EXPORTER_OTLP_TRACES_ENDPOINT``,
                      ``OTEL_EXPORTER_OTLP_ENDPOINT`` (+ ``/v1/traces`` for HTTP)
    protocol          ``OTLP_PROTOCOL`` → ``OTEL_EXPORTER_OTLP_TRACES_PROTOCOL``,
                      ``OTEL_EXPORTER_OTLP_PROTOCOL``
    service_name      ``SERVICE`` → ``DD_SERVICE``, ``OTEL_SERVICE_NAME``
    service_version   ``VERSION`` → ``DD_VERSION``
    environment       ``ENV`` → ``DD_ENV``
    tags              ``TAGS`` → ``DD_TAGS`` (explicit tags are merged over them)
    api_key           ``API_KEY`` → ``DD_API_KEY``
    app_key           ``APP_KEY`` → ``DD_APP_KEY``, ``DD_APPLICATION_KEY``
    site              ``SITE`` → ``DD_SITE``
    api_url           ``API_URL``
    ================  ==========================================================

    plus the common ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``, ``MAX_RETRIES``,
    ``CA_CERT``, ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY`` and ``PROXY``.

    ``api_url`` defaults to ``https://api.<site>`` (computed on construction; pass it again when
    you change ``site`` with :func:`dataclasses.replace`).
    """

    #: Vendor variables per GROW_* suffix, in precedence order.
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {"URL": ("DD_TRACE_AGENT_URL",)}

    url: str = DEFAULT_URL
    #: OTLP endpoint of the Agent. HTTP: a base URL (``http://agent:4318``; ``/v1/traces`` is
    #: appended when it has no path) or the full traces URL; gRPC: ``http://agent:4317``.
    #: ``None``: the host of ``url`` with port 4318 (HTTP) or 4317 (gRPC).
    otlp_endpoint: str | None = None
    #: OTLP protocol: ``http/protobuf`` (default) or ``grpc``.
    protocol: DatadogOtlpProtocol = DEFAULT_PROTOCOL
    #: ``service`` of unified service tagging (resource ``service.name``).
    service_name: str | None = None
    #: ``version`` of unified service tagging (resource ``service.version``).
    service_version: str | None = None
    #: ``env`` of unified service tagging (resource ``deployment.environment.name``).
    environment: str | None = None
    #: Extra resource attributes (``DD_TAGS``).
    tags: Mapping[str, str] = dataclasses.field(default_factory=dict)
    #: Datadog API key; needed only by the query API.
    api_key: Secret | None = None
    #: Datadog application key with ``apm_read``; needed only by the query API.
    app_key: Secret | None = None
    #: Datadog site, e.g. ``datadoghq.com`` (default) or ``datadoghq.eu``.
    site: str = DEFAULT_SITE
    #: Query API base URL; default ``https://api.<site>``.
    api_url: str | None = None

    def __post_init__(self) -> None:
        super().__post_init__()
        object.__setattr__(self, "tags", MappingProxyType(dict(self.tags)))
        protocol = str(self.protocol).strip().lower()
        object.__setattr__(self, "protocol", protocol)
        site = normalize_site(self.site) or DEFAULT_SITE
        object.__setattr__(self, "site", site)
        if self.api_url is None:
            object.__setattr__(self, "api_url", "https://api." + site)

    @property
    def otlp_traces_endpoint(self) -> str:
        """Resolved OTLP traces URL for :attr:`protocol` (see :func:`otlp_traces_url`):
        :attr:`otlp_endpoint` when set, else the host of :attr:`url` with port 4318 +
        ``/v1/traces`` (HTTP) or port 4317 (gRPC)."""
        if self.otlp_endpoint:
            return otlp_traces_url(self.protocol, self.otlp_endpoint)
        port = OTLP_GRPC_PORT if self.protocol == "grpc" else OTLP_HTTP_PORT
        scheme, host = "http", "127.0.0.1"
        try:
            parts = urlsplit(self.url)
            if parts.hostname:
                scheme, host = parts.scheme or "http", parts.hostname
        except ValueError:
            pass
        if ":" in host:
            host = f"[{host}]"
        return otlp_traces_url(self.protocol, f"{scheme}://{host}:{port}")

    @property
    def has_query_credentials(self) -> bool:
        """``True`` when both the API key and the application key are set."""
        return bool(self.api_key) and bool(self.app_key)

    def validate(self: _C) -> _C:
        """Common checks plus the Datadog rules; raises :class:`ConfigError`."""
        super().validate()
        problems: list[str] = []
        if self.protocol not in _PROTOCOLS:
            problems.append("protocol must be http/protobuf or grpc")
        if self.otlp_endpoint is not None and not _is_http_url(self.otlp_endpoint):
            problems.append("otlp_endpoint must be an http or https URL")
        if not _is_http_url(self.api_url or ""):
            problems.append("api_url must be an http or https URL")
        for name in ("service_name", "service_version", "environment"):
            value = getattr(self, name)
            if value is not None and not value.strip():
                problems.append(f"{name} must not be blank")
        for name in ("api_key", "app_key"):
            secret = getattr(self, name)
            if secret is not None and not secret.reveal().strip():
                problems.append(f"{name} must not be blank")
        if problems:
            raise ConfigError("config: " + "; ".join(problems))
        validate_site(self.site)
        return self

    @classmethod
    def from_env(cls: type[_C], env: Mapping[str, str] | None = None, **overrides: Any) -> _C:
        """Build from the environment (table above), then validate.

        ``env`` defaults to ``os.environ``; pass a dict in tests. Keyword overrides win over the
        environment (``None`` means "not given"); explicit ``tags`` are merged over ``DD_TAGS``.
        """
        source: Mapping[str, str] = os.environ if env is None else env
        reader = EnvReader(source)
        given = {k: v for k, v in overrides.items() if v is not None}
        values = cls._env_values(reader)
        if "url" not in values and "url" not in given:
            agent_url = _agent_url_from_env(source)
            if agent_url is not None:
                values["url"] = agent_url
        if "protocol" not in given:
            found = reader.lookup(
                "OTLP_PROTOCOL",
                ("OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"),
            )
            if found is not None:
                protocol = found[1].strip().lower()
                if protocol not in _PROTOCOLS:
                    raise ConfigError(f"config: {found[0]} must be grpc or http/protobuf")
                values["protocol"] = protocol
        protocol = str(given.get("protocol", values.get("protocol", DEFAULT_PROTOCOL)))
        if "otlp_endpoint" not in given:
            endpoint = _otlp_endpoint_from_env(reader, source, protocol.strip().lower())
            if endpoint is not None:
                values["otlp_endpoint"] = endpoint
        for field, suffix, vendor in (
            ("service_name", "SERVICE", ("DD_SERVICE", "OTEL_SERVICE_NAME")),
            ("service_version", "VERSION", ("DD_VERSION",)),
            ("environment", "ENV", ("DD_ENV",)),
            ("site", "SITE", ("DD_SITE",)),
            ("api_url", "API_URL", ()),
        ):
            text = reader.string(suffix, vendor)
            if text is not None:
                values[field] = text.strip()
        raw_tags = reader.string("TAGS", ("DD_TAGS",))
        if raw_tags is not None:
            tags = parse_datadog_tags(raw_tags)
            tags.update(given.pop("tags", {}))
            values["tags"] = tags
        for field, suffix, vendor in (
            ("api_key", "API_KEY", ("DD_API_KEY",)),
            ("app_key", "APP_KEY", ("DD_APP_KEY", "DD_APPLICATION_KEY")),
        ):
            secret = reader.string(suffix, vendor)
            if secret is not None:
                values[field] = Secret(secret)
        values.update(given)
        return cls(**values).validate()
