//! Datadog propagator (ddtrace-verified vectors), conflict rules and terminated-context links.

use std::borrow::Cow;
use std::collections::HashMap;
use std::time::SystemTime;

use grow_datadog_apm as sdk;
use opentelemetry::propagation::{TextMapCompositePropagator, TextMapPropagator};
use opentelemetry::trace::{
    SpanContext, SpanId, SpanKind, Status, TraceContextExt, TraceFlags, TraceId, TraceState,
};
use opentelemetry::{Context, InstrumentationScope};
use opentelemetry_sdk::propagation::TraceContextPropagator;
use opentelemetry_sdk::trace::{SpanData, SpanEvents, SpanLinks};
use sdk::*;
use serde_json::Value;

fn fixture(name: &str) -> Value {
    let text = std::fs::read_to_string(format!(
        "{}/tests/fixtures/dd/{name}",
        env!("CARGO_MANIFEST_DIR")
    ))
    .unwrap();
    serde_json::from_str(&text).unwrap()
}

fn sc(trace: &str, span: &str, sampled: bool) -> SpanContext {
    SpanContext::new(
        TraceId::from_hex(trace).unwrap(),
        SpanId::from_hex(span).unwrap(),
        if sampled {
            TraceFlags::SAMPLED
        } else {
            TraceFlags::default()
        },
        false,
        TraceState::default(),
    )
}

fn carrier(v: &Value) -> HashMap<String, String> {
    v.as_object()
        .unwrap()
        .iter()
        .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
        .collect()
}

fn composite() -> TextMapCompositePropagator {
    TextMapCompositePropagator::new(vec![
        Box::new(TraceContextPropagator::new()),
        Box::new(DatadogPropagator::new()),
    ])
}

#[test]
fn inject_matches_ddtrace() {
    let p = DatadogPropagator::default();
    for v in fixture("propagation.datadog_inject.vectors.json")
        .as_array()
        .unwrap()
    {
        let c = &v["context"];
        let cx = Context::new().with_remote_span_context(sc(
            c["trace_id_hex"].as_str().unwrap(),
            c["span_id_hex"].as_str().unwrap(),
            c["sampling_priority"] == 1,
        ));
        let mut out = HashMap::new();
        p.inject_context(&cx, &mut out);
        assert_eq!(out, carrier(&v["reference_headers"]));
    }
    // no span: nothing injected
    let mut out = HashMap::new();
    p.inject_context(&Context::new(), &mut out);
    assert!(out.is_empty());
    assert_eq!(p.fields().count(), 4);
}

#[test]
fn extract_matches_ddtrace() {
    let p = composite();
    for v in fixture("propagation.datadog_extract.vectors.json")
        .as_array()
        .unwrap()
    {
        let headers = carrier(&v["headers"]);
        let want = &v["ddtrace_context"];
        let cx = p.extract(&headers);
        let got = cx.span().span_context().clone();
        assert_eq!(
            got.trace_id().to_string(),
            want["trace_id_hex"].as_str().unwrap()
        );
        assert_eq!(
            got.span_id().to_string(),
            want["span_id_hex"].as_str().unwrap()
        );
        assert!(got.is_remote());
        if let Some(p) = want.get("sampling_priority").and_then(Value::as_i64) {
            assert_eq!(got.is_sampled(), p > 0);
        }
        let links = terminated_context_links(&cx);
        if v.get("span_links").is_some() {
            assert_eq!(links.len(), 1);
            let t = terminated_context(&cx).unwrap();
            assert_eq!(t.winner_span_id, "00000000000000ff");
            assert_eq!(
                t.span_context.trace_id().to_string(),
                "11111111111111112222222222222222"
            );
            assert_eq!(links[0].attributes.len(), 2);
        } else {
            assert!(links.is_empty());
        }
        // extract_datadog_context alone ignores traceparent
        let dd = extract_datadog_context(&headers).unwrap();
        assert_eq!(dd.span_id().to_string().len(), 16);
    }
}

#[test]
fn extract_edge_cases() {
    let p = DatadogPropagator::new();
    let get = |pairs: &[(&str, &str)]| -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    };
    for bad in [
        get(&[]),
        get(&[("x-datadog-trace-id", "1")]),
        get(&[("x-datadog-trace-id", "abc"), ("x-datadog-parent-id", "1")]),
        get(&[("x-datadog-trace-id", "0"), ("x-datadog-parent-id", "1")]),
        get(&[("x-datadog-trace-id", "1"), ("x-datadog-parent-id", "0")]),
        get(&[
            ("x-datadog-trace-id", "99999999999999999999"),
            ("x-datadog-parent-id", "1"),
        ]),
        get(&[
            ("x-datadog-trace-id", "123456789012345678901"),
            ("x-datadog-parent-id", "1"),
        ]),
    ] {
        assert!(extract_datadog_context(&bad).is_none(), "{bad:?}");
        let cx = p.extract(&bad);
        assert!(!cx.span().span_context().is_valid());
    }
    // missing priority = sampled; bad tid ignored; other tags skipped
    let h = get(&[
        ("x-datadog-trace-id", "42"),
        ("x-datadog-parent-id", "7"),
        ("x-datadog-tags", "_dd.p.dm=-1,broken,_dd.p.tid=XYZ"),
    ]);
    let sc = extract_datadog_context(&h).unwrap();
    assert!(sc.is_sampled());
    assert_eq!(
        sc.trace_id().to_string(),
        "0000000000000000000000000000002a"
    );
    let h = get(&[
        ("x-datadog-trace-id", "42"),
        ("x-datadog-parent-id", "7"),
        ("x-datadog-sampling-priority", "-1"),
    ]);
    assert!(!extract_datadog_context(&h).unwrap().is_sampled());
    let h = get(&[
        ("x-datadog-trace-id", "42"),
        ("x-datadog-parent-id", "7"),
        ("x-datadog-sampling-priority", "x"),
    ]);
    assert!(extract_datadog_context(&h).unwrap().is_sampled());

    // invalid traceparents are ignored by the conflict rules
    for tp in [
        "garbage",
        "ff-11111111111111112222222222222222-0000000000000abc-01",
        "00-1111-0000000000000abc-01",
        "00-00000000000000000000000000000000-0000000000000abc-01",
    ] {
        let mut h = get(&[("x-datadog-trace-id", "42"), ("x-datadog-parent-id", "7")]);
        h.insert("traceparent".into(), tp.into());
        let cx = p.extract(&h);
        assert_eq!(
            cx.span().span_context().span_id().to_string(),
            "0000000000000007"
        );
        assert!(terminated_context(&cx).is_none());
    }
    // same trace id via the Datadog propagator alone: W3C parent id, Datadog sampling
    let mut h = get(&[
        ("x-datadog-trace-id", "42"),
        ("x-datadog-parent-id", "7"),
        ("x-datadog-sampling-priority", "0"),
    ]);
    h.insert(
        "traceparent".into(),
        "00-0000000000000000000000000000002a-0000000000000abc-01-extra".into(),
    );
    let got = p.extract(&h).span().span_context().clone();
    assert_eq!(got.span_id().to_string(), "0000000000000abc");
    assert!(!got.is_sampled());
    // with tracecontext first, its context (and tracestate) is kept
    h.insert("tracestate".into(), "dd=s:1".into());
    h.insert(
        "traceparent".into(),
        "00-0000000000000000000000000000002a-0000000000000abc-01".into(),
    );
    let got = composite().extract(&h).span().span_context().clone();
    assert_eq!(got.span_id().to_string(), "0000000000000abc");
    assert_eq!(got.trace_state().header(), "dd=s:1");
}

fn span_data(trace: &str, parent: &str, remote: bool) -> SpanData {
    SpanData {
        span_context: sc(trace, "00000000000000aa", true),
        parent_span_id: SpanId::from_hex(parent).unwrap(),
        parent_span_is_remote: remote,
        span_kind: SpanKind::Internal,
        name: Cow::Borrowed("s"),
        start_time: SystemTime::now(),
        end_time: SystemTime::now(),
        attributes: vec![],
        dropped_attributes_count: 0,
        events: SpanEvents::default(),
        links: SpanLinks::default(),
        status: Status::Unset,
        instrumentation_scope: InstrumentationScope::builder("t").build(),
    }
}

#[test]
fn exporter_adds_terminated_context_link_once() {
    let headers: HashMap<String, String> = [
        ("x-datadog-trace-id", "5474458728733560230"),
        ("x-datadog-parent-id", "4660"),
        ("x-datadog-tags", "_dd.p.tid=a3ce929d0e0e4736"),
        (
            "traceparent",
            "00-99999999999999998888888888888888-0000000000000def-01",
        ),
    ]
    .iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    let cx = composite().extract(&headers);
    assert!(terminated_context(&cx).is_some());
    // twice: remembered once
    let _ = composite().extract(&headers);
    let trace = "a3ce929d0e0e47364bf92f3577b34da6";
    let s = with_terminated_context_link(span_data(trace, "0000000000001234", true));
    assert_eq!(s.links.len(), 1);
    assert_eq!(
        s.links[0].span_context.trace_id().to_string(),
        "99999999999999998888888888888888"
    );
    // already present → not duplicated
    let s = with_terminated_context_link(s);
    assert_eq!(s.links.len(), 1);
    // local parent, other parent, other trace → untouched
    assert!(
        with_terminated_context_link(span_data(trace, "0000000000001234", false))
            .links
            .is_empty()
    );
    assert!(
        with_terminated_context_link(span_data(trace, "0000000000004321", true))
            .links
            .is_empty()
    );
    assert!(with_terminated_context_link(span_data(
        "a3ce929d0e0e47364bf92f3577b34da7",
        "0000000000001234",
        true
    ))
    .links
    .is_empty());
    // run after the checks above: it evicts their entries
    many_terminated_contexts_are_bounded();
}

fn many_terminated_contexts_are_bounded() {
    for i in 1..=1100u64 {
        let headers: HashMap<String, String> = [
            ("x-datadog-trace-id".to_string(), i.to_string()),
            ("x-datadog-parent-id".to_string(), "1".to_string()),
            (
                "traceparent".to_string(),
                "00-77777777777777776666666666666666-0000000000000def-01".to_string(),
            ),
        ]
        .into_iter()
        .collect();
        let _ = DatadogPropagator::new().extract(&headers);
    }
    let newest = format!("{:032x}", 1100u64);
    let s = with_terminated_context_link(span_data(&newest, "0000000000000001", true));
    assert_eq!(s.links.len(), 1);
    let oldest = format!("{:032x}", 1u64);
    let s = with_terminated_context_link(span_data(&oldest, "0000000000000001", true));
    assert!(s.links.is_empty(), "oldest entry evicted");
}

#[test]
fn propagator_constants() {
    assert_eq!(DATADOG_TRACE_ID_HEADER, "x-datadog-trace-id");
    assert_eq!(DATADOG_PARENT_ID_HEADER, "x-datadog-parent-id");
    assert_eq!(
        DATADOG_SAMPLING_PRIORITY_HEADER,
        "x-datadog-sampling-priority"
    );
    assert_eq!(DATADOG_TAGS_HEADER, "x-datadog-tags");
    assert_eq!(DATADOG_ORIGIN_HEADER, "x-datadog-origin");
    assert_eq!(PROPAGATOR_DATADOG, "datadog");
}
