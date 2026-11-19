"""``init_tracing`` for Datadog — the OpenTelemetry SDK exporting OTLP to the Datadog Agent.

OTLP/HTTP protobuf on 4318 by default, or gRPC on 4317; unified service tagging, the Datadog + W3C
propagators, the Datadog error attributes and the terminated-context links (API.md §2,
DECISIONS.md #2, #5).
"""

from __future__ import annotations

import dataclasses
from collections.abc import Mapping, Sequence
from typing import Any

from opentelemetry.propagators.textmap import TextMapPropagator
from opentelemetry.sdk.trace import SpanProcessor, TracerProvider
from opentelemetry.sdk.trace.export import SpanExporter

from ._core.config import TlsConfig
from ._core.errors import ConfigError
from ._tracing import TracingConfig, TracingHandle, init_tracing_with
from .config import DatadogApmConfig, otlp_traces_url
from .correlation import set_datadog_log_defaults
from .propagator import DatadogPropagator, DatadogSpanLinkProcessor
from .span_errors import DatadogErrorExporter

__all__ = [
    "DATADOG_DEFAULT_PROPAGATORS",
    "DatadogTracingHandle",
    "datadog_exporter_factory",
    "init_tracing",
    "order_propagators",
]

#: Default propagators of this package: Datadog headers, W3C trace context and baggage.
DATADOG_DEFAULT_PROPAGATORS: tuple[str, ...] = ("datadog", "tracecontext", "baggage")

_FIELDS = {f.name: f for f in dataclasses.fields(TracingConfig)}
_NATIVE = "tracing: the Datadog package exports OTLP; use http/protobuf or grpc"


def order_propagators(names: Sequence[str]) -> list[str]:
    """Put ``tracecontext`` before ``datadog`` so the Datadog propagator sees the W3C result and
    applies the conflict rules (Datadog wins); every other name keeps its position. Names are
    trimmed and lower-cased.

    >>> order_propagators(["datadog", "tracecontext", "baggage"])
    ['tracecontext', 'datadog', 'baggage']
    """
    items = [n.strip().lower() for n in names]
    if "datadog" not in items or "tracecontext" not in items:
        return items
    dd, tc = items.index("datadog"), items.index("tracecontext")
    if tc < dd:
        return items
    del items[tc]
    items.insert(dd, "tracecontext")
    return items


class DatadogTracingHandle(TracingHandle):
    """A running Datadog tracing setup (:class:`TracingHandle` plus the OTLP ``endpoint``)."""

    def __init__(
        self,
        config: TracingConfig,
        tracer_provider: TracerProvider,
        propagator: TextMapPropagator,
        *,
        is_global: bool,
        endpoint: str,
    ) -> None:
        super().__init__(config, tracer_provider, propagator, is_global=is_global)
        #: The OTLP traces URL spans are exported to.
        self.endpoint = endpoint


def _read_pem(path: str | None) -> bytes | None:
    if path is None:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        raise ConfigError("tracing: cannot read a TLS file (check the configured path)") from None


def _grpc_exporter(config: TracingConfig, url: str, tls: TlsConfig) -> SpanExporter:
    try:
        import grpc  # noqa: PLC0415 - optional extra
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (  # noqa: PLC0415
            OTLPSpanExporter as GrpcSpanExporter,
        )
    except ImportError as exc:  # pragma: no cover - depends on the installed extras
        raise ConfigError(
            "tracing: protocol grpc needs the 'grpc' extra (opentelemetry-exporter-otlp-proto-grpc)"
        ) from exc
    root = _read_pem(tls.ca_cert_path)
    if root is None and tls.ca_cert_pem:
        root = tls.ca_cert_pem.encode()
    cert = _read_pem(tls.client_cert_path)
    key = _read_pem(tls.client_key_path)
    secure = url.startswith("https:") or root is not None or cert is not None
    credentials = (
        grpc.ssl_channel_credentials(
            root_certificates=root, private_key=key, certificate_chain=cert
        )
        if secure
        else None
    )
    compression = (
        grpc.Compression.Gzip if config.compression == "gzip" else grpc.Compression.NoCompression
    )
    return GrpcSpanExporter(
        endpoint=url,
        insecure=not secure,
        credentials=credentials,
        headers=dict(config.headers),
        timeout=config.batch.export_timeout_s,
        compression=compression,
    )


def _http_exporter(config: TracingConfig, url: str, tls: TlsConfig) -> SpanExporter:
    from opentelemetry.exporter.otlp.proto.http import Compression  # noqa: PLC0415
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (  # noqa: PLC0415
        OTLPSpanExporter,
    )

    if tls.ca_cert_pem and not tls.ca_cert_path:
        raise ConfigError("tracing: the OTLP/HTTP exporter needs ca_cert_path (not ca_cert_pem)")
    return OTLPSpanExporter(
        endpoint=url,
        certificate_file=tls.ca_cert_path,
        client_key_file=tls.client_key_path,
        client_certificate_file=tls.client_cert_path,
        headers=dict(config.headers),
        timeout=config.batch.export_timeout_s,
        compression=Compression.Gzip if config.compression == "gzip" else Compression.NoCompression,
    )


def datadog_exporter_factory(config: TracingConfig) -> SpanExporter:
    """The exporter factory of this package (``init_tracing_with``): OTLP ``http/protobuf`` or
    ``grpc`` to ``config.endpoint`` (default: the local Agent), wrapped in a
    :class:`~grow_datadog_apm.DatadogErrorExporter`.

    Headers, compression, export timeout and TLS files come from the config;
    ``tls.insecure_skip_verify`` is not supported by the OpenTelemetry OTLP exporters and is
    ignored. ``native`` raises :class:`ConfigError` (the package exports OTLP only).
    """
    if config.protocol == "native":
        raise ConfigError(_NATIVE)
    endpoint = config.endpoint or DatadogApmConfig(protocol=config.protocol).otlp_traces_endpoint
    url = otlp_traces_url(config.protocol, endpoint)
    tls = config.tls or TlsConfig()
    if config.protocol == "grpc":
        exporter = _grpc_exporter(config, url, tls)
    else:
        exporter = _http_exporter(config, url, tls)
    return DatadogErrorExporter(exporter)


def _explicit_fields(
    config: TracingConfig | Mapping[str, Any] | None, fields: Mapping[str, Any]
) -> dict[str, Any]:
    explicit: dict[str, Any] = {}
    if isinstance(config, TracingConfig):
        for name, f in _FIELDS.items():
            value = getattr(config, name)
            if f.default is not dataclasses.MISSING:
                default: Any = f.default
            elif f.default_factory is not dataclasses.MISSING:
                default = f.default_factory()
            else:
                default = None
            if name == "propagators":
                default = tuple(default)
            if value != default and not (name == "service_name" and not value):
                explicit[name] = value
    elif config is not None:
        explicit.update(config)
    explicit.update(fields)
    for name in explicit:
        if name not in _FIELDS:
            raise ConfigError(f"tracing: unknown option {name!r}")
    return {k: v for k, v in explicit.items() if v is not None}


def _apm_config(
    protocol: str | None,
    apm_config: DatadogApmConfig | None,
    env: Mapping[str, str] | None,
    ignore_env: bool,
) -> DatadogApmConfig:
    if apm_config is not None:
        return apm_config.validate()
    if ignore_env:
        if protocol is None:
            return DatadogApmConfig().validate()
        return DatadogApmConfig(protocol=protocol).validate()  # type: ignore[arg-type]
    return DatadogApmConfig.from_env(env, protocol=protocol)


def init_tracing(
    config: TracingConfig | Mapping[str, Any] | None = None,
    *,
    apm_config: DatadogApmConfig | None = None,
    env: Mapping[str, str] | None = None,
    ignore_env: bool = False,
    span_processors: Sequence[SpanProcessor] = (),
    **fields: Any,
) -> DatadogTracingHandle:
    """Set up OpenTelemetry tracing that exports to the Datadog Agent (SPEC §5).

    * OTLP ``http/protobuf`` to ``http://<agent>:4318/v1/traces`` by default, or ``grpc`` to
      ``http://<agent>:4317`` (the Agent needs its OTLP receiver enabled);
    * unified service tagging: ``service_name`` / ``service_version`` / ``environment`` →
      resource ``service.name`` / ``service.version`` / ``deployment.environment.name``
      (Datadog ``service``, ``version``, ``env``), plus ``DD_TAGS`` as resource attributes;
    * propagators ``datadog``, ``tracecontext``, ``baggage`` by default (Datadog headers win on
      conflict);
    * the Datadog error attributes (:class:`DatadogErrorExporter`) and the terminated-context
      link (:class:`DatadogSpanLinkProcessor`).

    Tracing options are any :class:`TracingConfig` field, given as a ``TracingConfig``, a mapping
    or keyword arguments (keywords win). Unset fields come from ``apm_config`` or
    :meth:`DatadogApmConfig.from_env` (``DD_SERVICE``, ``DD_VERSION``, ``DD_ENV``, ``DD_TAGS``,
    ``DD_AGENT_HOST``, ``GROW_DATADOG_APM_*``, ``OTEL_EXPORTER_OTLP_*``); ``env`` replaces
    ``os.environ`` and ``ignore_env=True`` reads no environment. In a ``TracingConfig`` instance
    a field equal to its default counts as unset. ``span_processors`` are added after the Datadog
    ones.

    >>> handle = init_tracing(service_name="checkout", environment="prod")  # doctest: +SKIP
    >>> with with_span("charge"):  # doctest: +SKIP
    ...     ...
    >>> handle.shutdown()  # doctest: +SKIP
    """
    explicit = _explicit_fields(config, fields)
    protocol = explicit.get("protocol")
    if protocol == "native":
        raise ConfigError(_NATIVE)
    if protocol is not None and protocol not in ("grpc", "http/protobuf"):
        raise ConfigError("tracing protocol must be grpc, http/protobuf or native")
    apm = _apm_config(protocol, apm_config, env, ignore_env)
    protocol = protocol or apm.protocol
    if explicit.get("endpoint"):
        endpoint = otlp_traces_url(protocol, explicit["endpoint"])
    elif protocol == apm.protocol:
        endpoint = apm.otlp_traces_endpoint
    else:
        endpoint = DatadogApmConfig(url=apm.url, protocol=protocol).otlp_traces_endpoint
    service_name = explicit.get("service_name") or apm.service_name or ""
    service_version = explicit.get("service_version") or apm.service_version
    environment = explicit.get("environment") or apm.environment
    attributes: dict[str, Any] = {**apm.tags, **dict(explicit.get("resource_attributes") or {})}
    if environment and "deployment.environment.name" not in attributes:
        attributes["deployment.environment.name"] = environment
    tls = explicit.get("tls")
    if tls is None and apm.tls != TlsConfig():
        tls = apm.tls
    effective = TracingConfig(
        **{
            **explicit,
            "service_name": service_name,
            "service_version": service_version,
            "environment": environment,
            "resource_attributes": attributes,
            "endpoint": endpoint,
            "protocol": protocol,
            "propagators": order_propagators(
                explicit.get("propagators") or DATADOG_DEFAULT_PROPAGATORS
            ),
            "tls": tls,
        }
    )
    handle = init_tracing_with(
        effective,
        datadog_exporter_factory,
        propagator_factories={"datadog": DatadogPropagator},
        span_processors=[DatadogSpanLinkProcessor(), *span_processors],
    )
    set_datadog_log_defaults(service=service_name, env=environment, version=service_version)
    return DatadogTracingHandle(
        handle.config,
        handle.tracer_provider,
        handle.propagator,
        is_global=handle.is_global,
        endpoint=endpoint,
    )
