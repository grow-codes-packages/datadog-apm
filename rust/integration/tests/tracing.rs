//! Integration tests of the tracing half against the real Datadog Agent: spans are exported over
//! OTLP (HTTP/protobuf and gRPC), converted by the Agent, forwarded to fakeintake and read back
//! through the trace-reader sidecar.

use std::collections::HashMap;
use std::time::Duration;

use grow_datadog_apm as sdk;
use grow_datadog_apm_integration as support;
use opentelemetry::propagation::TextMapPropagator as _;
use opentelemetry::trace::{
    Link, SpanContext, SpanId, SpanKind, TraceContextExt as _, TraceFlags, TraceId, TraceState,
    Tracer as _,
};
use opentelemetry::KeyValue;
use sdk::tracing::{
    add_event, current_span_id, current_trace_id, extract, inject,
    record_exception as generic_record, set_error as generic_set_error, start_span, trace_fields,
    traced, with_span, SamplerKind, SpanOptions, TracingConfig,
};
use sdk::*;
use serde_json::Value;
use support::{env, it_service, reader_spans, service_available, text, wait_for_spans};

#[derive(Debug)]
struct TypeError(&'static str);

impl std::fmt::Display for TypeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

fn by_resource(spans: &[Value]) -> HashMap<String, Value> {
    spans
        .iter()
        .map(|s| (text(&s["resource"]), s.clone()))
        .collect()
}

fn meta(s: &Value, key: &str) -> String {
    text(&s["meta"][key])
}

fn init_it(service: &str) -> DatadogTracingHandle {
    init_tracing(
        TracingConfig::new(service)
            .with_service_version("1.2.3")
            .with_environment("it")
            .with_resource_attribute("grow.run", service)
            .with_sampler(SamplerKind::AlwaysOn, 1.0),
    )
    .expect("init_tracing")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_tracing_http_to_agent() {
    if !service_available() {
        return;
    }
    let service = it_service("http");
    let h = init_it(&service);
    assert_eq!(
        h.endpoint(),
        format!("{}/v1/traces", env("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT"))
    );
    let linked = SpanContext::new(
        TraceId::from_hex("a3ce929d0e0e47364bf92f3577b34da6").unwrap(),
        SpanId::from_hex("00f067aa0ba902b7").unwrap(),
        TraceFlags::SAMPLED,
        false,
        TraceState::default(),
    );
    let (trace_id, fields, ids, cur_span) = with_span(
        "it-root",
        SpanOptions::default().kind(SpanKind::Server),
        async {
            let fields = datadog_log_fields(DatadogLogFieldsOptions::default());
            let ids = trace_fields();
            let cur_span = current_span_id().unwrap();
            add_event("checkpoint", vec![KeyValue::new("step", 1)]);
            let _ = with_span(
                "it-child-ok",
                SpanOptions::default()
                    .attribute(KeyValue::new("n", 7))
                    .attribute(KeyValue::new("flag", true)),
                async { Ok::<_, TypeError>(()) },
            )
            .await;
            let _ = traced("it-child-fail", async {
                Err::<(), _>(TypeError("value out of range"))
            })
            .await;
            let _ = traced("it-recorded", async {
                record_exception(&TypeError("bad type"));
                Ok::<_, TypeError>(())
            })
            .await;
            let _ = traced("it-set-error", async {
                set_error(&TypeError("explicit failure"));
                Ok::<_, TypeError>(())
            })
            .await;
            let _ = traced("it-aliases", async {
                record_datadog_exception(&TypeError("alias record"));
                set_datadog_error(&TypeError("alias status"));
                Ok::<_, TypeError>(())
            })
            .await;
            let _ = traced("it-generic", async {
                generic_record(&TypeError("generic"));
                generic_set_error(&TypeError("generic status"));
                Ok::<_, TypeError>(())
            })
            .await;
            let _ = traced("it-attrs", async {
                set_error_attributes(&TypeError("attrs only"));
                Ok::<_, TypeError>(())
            })
            .await;
            let _ = with_span(
                "it-linked",
                SpanOptions::default().link(Link::with_context(linked.clone())),
                async { Ok::<_, TypeError>(()) },
            )
            .await;
            Ok::<_, TypeError>((current_trace_id().unwrap(), fields, ids, cur_span))
        },
    )
    .await
    .unwrap();
    h.force_flush(Some(Duration::from_secs(20))).await.unwrap();
    let spans = wait_for_spans(&[("trace_id", &trace_id)], 9).await;
    let res = by_resource(&spans);
    let root = &res["it-root"];
    assert_eq!(text(&root["service"]), service);
    assert_eq!(meta(root, "env"), "it");
    assert_eq!(meta(root, "version"), "1.2.3");
    assert_eq!(meta(root, "grow.run"), service);
    assert_eq!(meta(root, "span.kind"), "server");
    assert_eq!(text(&root["name"]), "server.request");
    assert_eq!(text(&root["trace_id_hex"]), trace_id);

    // id helpers and log correlation against the Agent's own ids
    let dd = to_datadog_trace_id(&trace_id).unwrap();
    assert_eq!(text(&root["trace_id"]), dd.low64_decimal);
    assert_eq!(meta(root, "otel.trace_id"), trace_id);
    assert_eq!(
        from_datadog_trace_id(&text(&root["trace_id"]), Some(&dd.high64_hex)).unwrap(),
        trace_id
    );
    assert_eq!(
        from_datadog_span_id(&text(&root["span_id"])).unwrap(),
        text(&root["span_id_hex"])
    );
    assert_eq!(
        to_datadog_span_id(&text(&root["span_id_hex"])).unwrap(),
        text(&root["span_id"])
    );
    let want_dd = if dd.is_128_bit {
        trace_id.clone()
    } else {
        dd.low64_decimal.clone()
    };
    assert_eq!(datadog_trace_id_string(&trace_id).unwrap(), want_dd);
    assert_eq!(normalize_datadog_trace_id(&want_dd).unwrap(), trace_id);
    assert_eq!(
        normalize_datadog_span_id(&text(&root["span_id"])).unwrap(),
        Some(text(&root["span_id_hex"]))
    );
    assert_eq!(fields.trace_id.as_deref(), Some(want_dd.as_str()));
    assert_eq!(fields.span_id, Some(text(&root["span_id"])));
    assert_eq!(fields.service.as_deref(), Some(service.as_str()));
    assert_eq!(fields.env.as_deref(), Some("it"));
    assert_eq!(fields.version.as_deref(), Some("1.2.3"));
    assert_eq!(ids.trace_id.as_deref(), Some(trace_id.as_str()));
    assert_eq!(ids.span_id, Some(text(&root["span_id_hex"])));
    assert_eq!(cur_span, text(&root["span_id_hex"]));

    let fail = &res["it-child-fail"];
    assert_eq!(text(&fail["error"]), "1");
    assert!(meta(fail, ATTR_ERROR_TYPE).ends_with("TypeError"), "{fail}");
    assert_eq!(meta(fail, ATTR_ERROR_MESSAGE), "value out of range");
    let rec = &res["it-recorded"];
    assert!(meta(rec, ATTR_ERROR_TYPE).ends_with("TypeError"));
    assert!(!meta(rec, ATTR_ERROR_STACK).is_empty());
    let se = &res["it-set-error"];
    assert_eq!(text(&se["error"]), "1");
    assert_eq!(meta(se, "error.msg"), "explicit failure");
    assert_eq!(meta(se, ATTR_ERROR_MESSAGE), "explicit failure");
    assert_eq!(text(&res["it-aliases"]["error"]), "1");
    let g = &res["it-generic"];
    assert_eq!(meta(g, ATTR_ERROR_MESSAGE), "generic status");
    assert!(meta(g, ATTR_ERROR_TYPE).ends_with("TypeError"));
    assert_eq!(meta(&res["it-attrs"], ATTR_ERROR_MESSAGE), "attrs only");
    let ok = &res["it-child-ok"];
    assert_eq!(ok["metrics"]["n"].as_f64(), Some(7.0));
    assert_eq!(meta(ok, "flag"), "true");

    // normalized model from the Agent's span format
    let model: HashMap<String, sdk::tracing::Span> = spans
        .iter()
        .map(|s| {
            let span = datadog_trace_span_to_span(s).unwrap();
            (span.name.clone(), span)
        })
        .collect();
    let m_root = &model["it-root"];
    assert_eq!(m_root.kind, sdk::tracing::SpanKind::Server);
    assert_eq!(datadog_kind(Some(&root["meta"]["span.kind"])), m_root.kind);
    assert_eq!(m_root.events.len(), 1);
    assert_eq!(m_root.events[0].name, "checkpoint");
    assert_eq!(m_root.events[0].attributes["step"], 1);
    for (name, s) in &model {
        if name != "it-root" {
            assert_eq!(
                s.parent_span_id.as_deref(),
                Some(m_root.span_id.as_str()),
                "{name}"
            );
        }
    }
    let l = &model["it-linked"].links;
    assert_eq!(l.len(), 1);
    assert_eq!(l[0].trace_id, "a3ce929d0e0e47364bf92f3577b34da6");
    assert_eq!(l[0].span_id, "00f067aa0ba902b7");
    assert_eq!(
        model["it-child-fail"].status.code,
        sdk::tracing::StatusCode::Error
    );
    h.shutdown().await.unwrap();
    h.shutdown().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_tracing_grpc_via_client() {
    if !service_available() {
        return;
    }
    let service = it_service("grpc");
    let c = DatadogApmClient::from_env().unwrap();
    let h = c
        .init_tracing(
            TracingConfig::new(&service)
                .with_protocol(sdk::tracing::Protocol::Grpc)
                .with_set_global(false),
        )
        .unwrap();
    assert_eq!(h.endpoint(), env("GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT"));
    let tracer = h.tracer("it", None);
    let span = tracer
        .span_builder("it-grpc-span")
        .with_attributes(vec![KeyValue::new("via", "grpc")])
        .start(&tracer);
    let cx = opentelemetry::Context::new().with_span(span);
    let trace_id = cx.span().span_context().trace_id().to_string();
    cx.span().end();
    h.force_flush(Some(Duration::from_secs(20))).await.unwrap();
    let spans = wait_for_spans(&[("trace_id", &trace_id)], 1).await;
    assert_eq!(text(&spans[0]["service"]), service);
    assert_eq!(text(&spans[0]["resource"]), "it-grpc-span");
    assert_eq!(meta(&spans[0], "via"), "grpc");
    c.close().await;
    c.close().await;
    assert!(h.is_shutdown());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn datadog_propagation_through_agent() {
    if !service_available() {
        return;
    }
    let service = it_service("prop");
    let h = init_it(&service);
    let tracer = h.tracer("it", None);
    let upstream = start_span(
        "it-upstream",
        SpanOptions::default()
            .tracer(tracer.clone())
            .parent(opentelemetry::Context::new()),
    );
    let sc = upstream.span().span_context().clone();
    let trace_id = sc.trace_id().to_string();
    let span_dec = to_datadog_span_id(&sc.span_id().to_string()).unwrap();
    let dd = to_datadog_trace_id(&trace_id).unwrap();

    let mut headers = HashMap::new();
    {
        let _guard = upstream.clone().attach();
        inject(&mut headers);
    }
    let mut dd_only = HashMap::new();
    DatadogPropagator::new().inject_context(&upstream, &mut dd_only);
    assert_eq!(headers["x-datadog-trace-id"], dd.low64_decimal);
    assert_eq!(headers["x-datadog-parent-id"], span_dec);
    assert_eq!(headers["x-datadog-sampling-priority"], "1");
    assert_eq!(
        headers.get("x-datadog-tags").cloned(),
        dd.is_128_bit
            .then(|| format!("_dd.p.tid={}", dd.high64_hex))
    );
    assert!(headers["traceparent"].contains(&trace_id));
    assert_eq!(dd_only["x-datadog-trace-id"], dd.low64_decimal);
    assert!(!dd_only.contains_key("traceparent"));

    // downstream: only Datadog headers (what a ddtrace service sends)
    let got = extract_datadog_context(&dd_only).unwrap();
    assert_eq!(got.trace_id(), sc.trace_id());
    let downstream = extract(&dd_only);
    assert_eq!(downstream.span().span_context().trace_id(), sc.trace_id());
    let _ = with_span(
        "it-downstream",
        SpanOptions::default()
            .tracer(tracer.clone())
            .parent(downstream)
            .kind(SpanKind::Server),
        async { Ok::<_, TypeError>(()) },
    )
    .await;

    // conflicting W3C context: Datadog wins, W3C becomes a span link added by the exporter
    let mut conflict = dd_only.clone();
    conflict.insert(
        "traceparent".into(),
        "00-11111111111111112222222222222222-0000000000000abc-01".into(),
    );
    let conflicting = extract(&conflict);
    assert_eq!(terminated_context_links(&conflicting).len(), 1);
    let t = terminated_context(&conflicting).unwrap();
    assert_eq!(t.winner_span_id, sc.span_id().to_string());
    let _ = with_span(
        "it-conflict",
        SpanOptions::default()
            .tracer(tracer.clone())
            .parent(conflicting.clone()),
        async { Ok::<_, TypeError>(()) },
    )
    .await;
    let mut out = HashMap::new();
    sdk::tracing::inject_context(&conflicting, &mut out);
    assert_eq!(out["x-datadog-parent-id"], span_dec);
    upstream.span().end();
    h.force_flush(Some(Duration::from_secs(20))).await.unwrap();

    let spans = wait_for_spans(&[("trace_id", &trace_id)], 3).await;
    let res = by_resource(&spans);
    let (up, down, conflict) = (
        &res["it-upstream"],
        &res["it-downstream"],
        &res["it-conflict"],
    );
    assert_eq!(text(&down["parent_id"]), text(&up["span_id"]));
    assert_eq!(text(&down["trace_id"]), text(&up["trace_id"]));
    assert_eq!(text(&conflict["parent_id"]), text(&up["span_id"]));
    let links: Value = serde_json::from_str(&meta(conflict, "_dd.span_links")).unwrap();
    assert_eq!(links.as_array().unwrap().len(), 1, "{links}");
    assert_eq!(links[0]["trace_id"], "11111111111111112222222222222222");
    assert_eq!(links[0]["span_id"], "0000000000000abc");
    assert_eq!(links[0]["attributes"]["reason"], "terminated_context");
    // service filter of the reader: every span of the trace belongs to this service
    let all = reader_spans(&[("trace_id", &trace_id), ("service", &service)])
        .await
        .unwrap();
    assert_eq!(all.len(), spans.len());
    h.shutdown().await.unwrap();
}
