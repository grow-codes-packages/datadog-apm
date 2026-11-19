"""init_tracing: OTLP export to a mock Agent, unified service tagging, propagators, exporters."""

from __future__ import annotations

import gzip
from typing import Any

import pytest
from opentelemetry import context as otel_context
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest
from opentelemetry.sdk.trace import SpanProcessor

import grow_datadog_apm as sdk
from _support import MockServer, Reply

IGNORE = {"ignore_env": True, "set_global": False}


def exported(server: MockServer) -> ExportTraceServiceRequest:
    req = ExportTraceServiceRequest()
    for seen in server.seen:
        if seen.path == "/v1/traces":
            body = seen.body
            if {k.lower(): v for k, v in seen.headers.items()}.get("content-encoding") == "gzip":
                body = gzip.decompress(body)
            part = ExportTraceServiceRequest()
            part.ParseFromString(body)
            req.resource_spans.extend(part.resource_spans)
    return req


def attr_map(attrs: Any) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for kv in attrs:
        value = kv.value
        out[kv.key] = getattr(value, value.WhichOneof("value"))
    return out


@pytest.fixture
def agent(server: MockServer) -> MockServer:
    server.defaults["/v1/traces"] = Reply(200, {"partialSuccess": {}})
    return server


def test_init_tracing_exports_to_agent(agent: MockServer) -> None:
    apm = sdk.DatadogApmConfig(
        otlp_endpoint=agent.url,
        service_name="from-config",
        environment="ci",
        service_version="1.2.3",
        tags={"team": "sdk"},
    )
    handle = sdk.init_tracing(
        {"resource_attributes": {"grow.run": "r1"}},
        apm_config=apm,
        set_global=False,
        headers={"X-Test": "1"},
    )
    assert isinstance(handle, sdk.DatadogTracingHandle)
    assert handle.endpoint == agent.url + "/v1/traces"
    assert handle.config.propagators == ("tracecontext", "datadog", "baggage")
    assert sdk.datadog_log_fields() == {
        "dd.service": "from-config",
        "dd.env": "ci",
        "dd.version": "1.2.3",
    }
    tracer = handle.tracer("t", "0.1")
    with tracer.start_as_current_span("root"):
        with sdk.with_span("child", tracer=tracer):
            sdk.set_error(ValueError("bad value"))
    carrier = sdk.inject({}, propagator=handle.propagator)
    assert carrier == {}  # no active span any more
    handle.force_flush(5)
    handle.shutdown()
    handle.shutdown()
    req = exported(agent)
    resource = attr_map(req.resource_spans[0].resource.attributes)
    assert resource["service.name"] == "from-config"
    assert resource["service.version"] == "1.2.3"
    assert resource["deployment.environment.name"] == "ci"
    assert resource["team"] == "sdk" and resource["grow.run"] == "r1"
    spans = {s.name: s for rs in req.resource_spans for ss in rs.scope_spans for s in ss.spans}
    child = attr_map(spans["child"].attributes)
    assert child["error.type"] == "ValueError"
    assert child["error.message"] == "bad value"
    seen = next(s for s in agent.seen if s.path == "/v1/traces")
    lowered = {k.lower(): v for k, v in seen.headers.items()}
    assert lowered["x-test"] == "1"
    assert lowered["content-type"] == "application/x-protobuf"
    sdk.set_datadog_log_defaults()


def test_terminated_link_through_init_tracing(agent: MockServer) -> None:
    handle = sdk.init_tracing(
        service_name="links",
        endpoint=agent.url,
        compression="none",
        **IGNORE,
    )
    headers = {
        "x-datadog-trace-id": "5474458728733560230",
        "x-datadog-parent-id": "67667974448284343",
        "x-datadog-tags": "_dd.p.tid=a3ce929d0e0e4736",
        "traceparent": "00-11111111111111112222222222222222-0000000000000abc-01",
    }
    ctx = sdk.extract_headers(headers, propagator=handle.propagator)
    token = otel_context.attach(ctx)
    try:
        with handle.tracer("t").start_as_current_span("downstream"):
            out = sdk.inject({}, propagator=handle.propagator)
    finally:
        otel_context.detach(token)
    assert out["x-datadog-trace-id"] == "5474458728733560230"
    assert out["traceparent"].startswith("00-a3ce929d0e0e47364bf92f3577b34da6-")
    handle.shutdown()
    span = exported(agent).resource_spans[0].scope_spans[0].spans[0]
    assert span.trace_id.hex() == "a3ce929d0e0e47364bf92f3577b34da6"
    assert span.parent_span_id.hex() == "00f067aa0ba902b7"
    assert len(span.links) == 1
    assert span.links[0].trace_id.hex() == "11111111111111112222222222222222"
    assert attr_map(span.links[0].attributes)["reason"] == "terminated_context"
    sdk.set_datadog_log_defaults()


def test_init_tracing_env_and_options(agent: MockServer) -> None:
    env = {
        sdk.ENV_PREFIX + "URL": "http://agent.local:8126",
        "DD_SERVICE": "env-svc",
        "DD_ENV": "env",
        "DD_TAGS": "a:1",
    }
    handle = sdk.init_tracing(env=env, set_global=False, resource_attributes={"a": "2"})
    assert handle.endpoint == "http://agent.local:4318/v1/traces"
    assert handle.config.service_name == "env-svc"
    assert handle.config.resource_attributes["a"] == "2"
    handle.shutdown()
    grpc = sdk.init_tracing(env=env, set_global=False, protocol="grpc")
    assert grpc.endpoint == "http://agent.local:4317"
    assert isinstance(
        grpc.tracer_provider._active_span_processor._span_processors[0].span_exporter,  # type: ignore[attr-defined]
        sdk.DatadogErrorExporter,
    )
    grpc.shutdown()
    other = sdk.init_tracing(
        apm_config=sdk.DatadogApmConfig(url="http://a:8126", service_name="x"),
        protocol="grpc",
        set_global=False,
    )
    assert other.endpoint == "http://a:4317"
    other.shutdown()
    defaults = sdk.init_tracing(service_name="d", ignore_env=True, set_global=False)
    assert defaults.endpoint == "http://127.0.0.1:4318/v1/traces"
    defaults.shutdown()
    grpc_default = sdk.init_tracing(service_name="d", protocol="grpc", **IGNORE)
    assert grpc_default.endpoint == "http://127.0.0.1:4317"
    grpc_default.shutdown()
    sdk.set_datadog_log_defaults()


def test_init_tracing_tracing_config_instance(agent: MockServer) -> None:
    cfg = sdk.TracingConfig(
        service_name="inst",
        endpoint=agent.url + "/v1/traces",
        propagators=("tracecontext", "baggage"),  # equals the default: Datadog defaults apply
        sampler="always_on",
        set_global=False,
    )
    handle = sdk.init_tracing(cfg, ignore_env=True)
    assert handle.config.propagators == ("tracecontext", "datadog", "baggage")
    assert handle.config.sampler == "always_on"
    assert handle.endpoint == agent.url + "/v1/traces"
    handle.shutdown()
    blank = sdk.TracingConfig(service_name="", set_global=False, propagators=("b3",))
    handle2 = sdk.init_tracing(
        blank, apm_config=sdk.DatadogApmConfig(service_name="apm-svc", otlp_endpoint=agent.url)
    )
    assert handle2.config.service_name == "apm-svc"
    assert handle2.config.propagators == ("b3",)
    handle2.shutdown()
    sdk.set_datadog_log_defaults()


def test_init_tracing_errors() -> None:
    with pytest.raises(sdk.ConfigError, match="exports OTLP"):
        sdk.init_tracing(service_name="x", protocol="native", **IGNORE)
    with pytest.raises(sdk.ConfigError, match="protocol"):
        sdk.init_tracing(service_name="x", protocol="http/json", **IGNORE)
    with pytest.raises(sdk.ConfigError, match="unknown option"):
        sdk.init_tracing(service_name="x", colour="blue", **IGNORE)
    with pytest.raises(sdk.ConfigError, match="service_name"):
        sdk.init_tracing(**IGNORE)
    with pytest.raises(sdk.ConfigError, match="exports OTLP"):
        sdk.datadog_exporter_factory(sdk.TracingConfig(service_name="x", protocol="native"))


def test_exporter_factory_tls(tmp_path: Any) -> None:
    ca = tmp_path / "ca.pem"
    ca.write_text("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n")
    tls = sdk.TlsConfig(ca_cert_path=str(ca))
    http = sdk.datadog_exporter_factory(
        sdk.TracingConfig(service_name="x", endpoint="https://agent:4318", tls=tls)
    )
    assert isinstance(http, sdk.DatadogErrorExporter)
    http.shutdown()
    default = sdk.datadog_exporter_factory(sdk.TracingConfig(service_name="x", compression="none"))
    default.shutdown()
    grpc = sdk.datadog_exporter_factory(
        sdk.TracingConfig(service_name="x", protocol="grpc", endpoint="https://agent:4317", tls=tls)
    )
    grpc.shutdown()
    pem = sdk.datadog_exporter_factory(
        sdk.TracingConfig(
            service_name="x",
            protocol="grpc",
            compression="none",
            tls=sdk.TlsConfig(ca_cert_pem=ca.read_text()),
        )
    )
    pem.shutdown()
    with pytest.raises(sdk.ConfigError, match="ca_cert_path"):
        sdk.datadog_exporter_factory(
            sdk.TracingConfig(service_name="x", tls=sdk.TlsConfig(ca_cert_pem="PEM"))
        )
    with pytest.raises(sdk.ConfigError, match="TLS file"):
        sdk.datadog_exporter_factory(
            sdk.TracingConfig(
                service_name="x",
                protocol="grpc",
                tls=sdk.TlsConfig(ca_cert_path=str(tmp_path / "missing.pem")),
            )
        )
    apm = sdk.DatadogApmConfig(service_name="tls", tls=tls)
    handle = sdk.init_tracing(apm_config=apm, set_global=False)
    assert handle.config.tls == tls
    handle.shutdown()
    sdk.set_datadog_log_defaults()


def test_extra_span_processors_and_order() -> None:
    seen: list[str] = []

    class Recorder(SpanProcessor):
        def on_start(self, span: Any, parent_context: Any = None) -> None:
            seen.append(span.name)

    handle = sdk.init_tracing(service_name="p", span_processors=[Recorder()], **IGNORE)
    with handle.tracer("t").start_as_current_span("s"):
        pass
    assert seen == ["s"]
    handle.shutdown()
    assert sdk.order_propagators(["datadog", "tracecontext", "baggage"]) == [
        "tracecontext",
        "datadog",
        "baggage",
    ]
    assert sdk.order_propagators([" TraceContext ", "datadog"]) == ["tracecontext", "datadog"]
    assert sdk.order_propagators(["b3", "datadog"]) == ["b3", "datadog"]
    assert sdk.order_propagators(["b3", "datadog", "x", "tracecontext"]) == [
        "b3",
        "tracecontext",
        "datadog",
        "x",
    ]
    assert sdk.DATADOG_DEFAULT_PROPAGATORS == ("datadog", "tracecontext", "baggage")
    sdk.set_datadog_log_defaults()
