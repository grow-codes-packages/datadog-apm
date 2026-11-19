//! Datadog span formats → SPEC spans (Agent fixtures, trace-by-id and search examples).

use grow_datadog_apm as sdk;
use sdk::tracing::{SpanKind, StatusCode};
use sdk::*;
use serde_json::{json, Value};

fn fixture(name: &str) -> Value {
    let text = std::fs::read_to_string(format!(
        "{}/tests/fixtures/dd/{name}",
        env!("CARGO_MANIFEST_DIR")
    ))
    .unwrap();
    serde_json::from_str(&text).unwrap()
}

#[test]
fn agent_spans_from_otlp() {
    let spans = fixture("initTracing.otlp_http_json.agent_spans.json");
    let mapped: Vec<_> = spans
        .as_array()
        .unwrap()
        .iter()
        .map(|s| datadog_trace_span_to_span(s).unwrap())
        .collect();
    let root = &mapped[0];
    assert_eq!(root.trace_id, "0123456789abcdefffffffffffffffff");
    assert_eq!(root.span_id, "00000000000000ff");
    assert_eq!(root.parent_span_id, None);
    assert_eq!(root.name, "GET /items/{id}");
    assert_eq!(root.kind, SpanKind::Server);
    assert_eq!(root.service_name, "grow-apm-json-d0b66575");
    assert_eq!(root.duration_micros, 9000);
    assert_eq!(root.end_micros - root.start_micros, 9000);
    assert_eq!(root.attributes["operation_name"], "http.server.request");
    assert_eq!(root.attributes["span.type"], "web");
    assert_eq!(root.attributes["grow.ratio"], 0.5);
    assert_eq!(root.attributes["env"], "ci");
    assert_eq!(root.status.code, StatusCode::Unset);
    let client = &mapped[1];
    assert_eq!(client.parent_span_id.as_deref(), Some("00000000000000ff"));
    assert_eq!(client.kind, SpanKind::Client);
    assert_eq!(client.status.code, StatusCode::Error);
    assert_eq!(client.status.message.as_deref(), Some("boom"));
    for s in &mapped {
        assert_eq!(s.trace_id, root.trace_id);
    }

    let reader = fixture("traceReader.spans.ok.json");
    let s = datadog_trace_span_to_span(&reader["spans"][0]).unwrap();
    assert_eq!(s.trace_id, "69a2af8e68444220f1bf29ab7bd501bf");
    assert_eq!(s.name, "bootstrap.probe");
    assert_eq!(s.kind, SpanKind::Internal);
    for s in fixture("initTracing.otel_sdk_http.agent_spans.json")
        .as_array()
        .unwrap()
    {
        datadog_trace_span_to_span(s).unwrap();
    }
}

#[test]
fn trace_by_id_span() {
    let doc = fixture("getTraceById.docs_example.json");
    let raw = &doc["body"]["data"]["attributes"]["spans"][0];
    let s = datadog_trace_span_to_span(raw).unwrap();
    assert_eq!(s.trace_id, "0000000000000000abc1230000000000");
    assert_eq!(s.span_id, format!("{:016x}", 9876543210987654321u64));
    assert_eq!(s.parent_span_id, None);
    assert_eq!(s.start_micros, 1_716_800_000_000_000);
    assert_eq!(s.end_micros, 1_716_800_000_500_000);
    assert_eq!(s.duration_micros, 500_000);
    assert_eq!(s.name, "GET /products");
    assert_eq!(s.attributes["http.status_code"], 200);
    assert_eq!(s.kind, SpanKind::Unspecified);
}

#[test]
fn trace_span_variants() {
    // low id + _dd.p.tid, events and links as meta JSON, native events/links, string metrics
    let raw = json!({
        "trace_id": 1, "span_id": "2", "parent_id": "0", "start": 2_000_000, "end": 5_000_000,
        "name": "Internal", "service": "svc", "error": "1",
        "meta": {
            "_dd.p.tid": "ffffffffffffffff",
            "error.message": "bad",
            "otel.status_code": "Error",
            "events": "[{\"time_unix_nano\":3000000,\"name\":\"cache.miss\",\"attributes\":{\"k\":\"v\",\"n\":3,\"nil\":null,\"arr\":[1,null],\"obj\":{\"a\":1}}}]",
            "_dd.span_links": "[{\"trace_id\":\"a3ce929d0e0e47364bf92f3577b34da6\",\"span_id\":\"00f067aa0ba902b7\",\"attributes\":{\"reason\":\"x\"}},{\"trace_id\":\"\"}]",
            "span.kind": "PRODUCER",
            "num": 5
        },
        "metrics": {"m1": "1.5", "m2": "nan?", "m3": true, "m4": 2},
        "span_events": [{"name": "native", "time_unix_nano": "4000000", "attributes": {
            "s": {"type": 0, "string_value": "x"},
            "a": {"type": 4, "array_value": {"values": [{"type": 2, "int_value": 1}]}},
            "raw": 7
        }}],
        "span_links": [
            {"trace_id": "5", "trace_id_high": "1", "span_id": "6"},
            {"trace_id": "5", "trace_id_high": "00000000000000ff", "span_id": "abcdef0123456789"},
            {"trace_id": "00f067aa0ba902b7", "span_id": "7"},
            {"trace_id": "5", "trace_id_high": "notnumber", "span_id": "7"},
            {"trace_id": "5", "span_id": "0"}
        ]
    });
    let s = datadog_trace_span_to_span(&raw).unwrap();
    assert_eq!(s.trace_id, "ffffffffffffffff0000000000000001");
    assert_eq!(s.duration_micros, 3000);
    assert_eq!(s.status.code, StatusCode::Error);
    assert_eq!(s.status.message.as_deref(), Some("bad"));
    assert_eq!(s.kind, SpanKind::Producer);
    assert_eq!(s.events.len(), 2);
    assert_eq!(s.events[0].name, "cache.miss");
    assert_eq!(s.events[0].time_micros, 3000);
    assert_eq!(s.events[0].attributes["n"], 3);
    assert!(!s.events[0].attributes.contains_key("nil"));
    assert_eq!(s.events[0].attributes["arr"], json!([1]));
    assert_eq!(s.events[1].attributes["s"], "x");
    assert_eq!(s.events[1].attributes["a"], json!([1]));
    assert_eq!(s.events[1].attributes["raw"], 7);
    assert_eq!(s.links.len(), 4);
    assert_eq!(s.links[0].trace_id, "a3ce929d0e0e47364bf92f3577b34da6");
    assert_eq!(s.links[0].attributes["reason"], "x");
    assert_eq!(s.links[1].trace_id, "00000000000000010000000000000005");
    assert_eq!(s.links[2].span_id, "abcdef0123456789");
    assert_eq!(s.links[3].trace_id, "000000000000000000f067aa0ba902b7");
    assert_eq!(s.attributes["m1"], 1.5);
    assert!(!s.attributes.contains_key("m2") && !s.attributes.contains_key("m3"));
    assert_eq!(s.attributes["m4"], 2);
    assert!(!s.attributes.contains_key("events") && !s.attributes.contains_key("num"));

    // otel.trace_id (short) wins; OK status; duration only; missing ids
    let raw = json!({
        "spanID": 9, "startTime": 1000, "duration": 4000, "resource": "", "name": "op",
        "meta": {"otel.trace_id": "abc", "otel.status_code": "Ok"}, "error": 0
    });
    let s = datadog_trace_span_to_span(&raw).unwrap();
    assert_eq!(s.trace_id, "00000000000000000000000000000abc");
    assert_eq!(s.span_id, "0000000000000009");
    for missing in [
        json!({"trace_id": "1"}),
        json!({"trace_id": "1", "spanID": 0}),
    ] {
        let err = datadog_trace_span_to_span(&missing).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation);
    }
    assert_eq!(s.name, "op");
    assert_eq!(s.status.code, StatusCode::Ok);
    assert_eq!(s.end_micros, 5);
    let raw = json!({"trace_id": "7", "span_id": "1", "start": 0, "endTime": 3000, "error": 2.5,
        "meta": {"events": "not json", "_dd.span_links": "{}"}});
    let s = datadog_trace_span_to_span(&raw).unwrap();
    assert_eq!(s.duration_micros, 3);
    assert_eq!(s.status.code, StatusCode::Error);
    assert_eq!(s.status.message, None);
    // errors
    assert_eq!(
        datadog_trace_span_to_span(&json!({})).unwrap_err().code(),
        ErrorCode::Validation
    );
    assert!(datadog_trace_span_to_span(&json!({"trace_id": "1", "span_id": "x"})).is_err());
    assert!(datadog_trace_span_to_span(
        &json!({"trace_id": "1", "span_id": "1", "parent_id": "y"})
    )
    .is_err());
    assert!(datadog_trace_span_to_span(&json!({"traceIDFull": "zz"})).is_err());
    assert_eq!(datadog_kind(Some(&json!("Consumer"))), SpanKind::Consumer);
    assert_eq!(datadog_kind(Some(&json!("internal"))), SpanKind::Internal);
    assert_eq!(datadog_kind(Some(&json!(3))), SpanKind::Unspecified);
    assert_eq!(datadog_kind(None), SpanKind::Unspecified);
}

#[test]
fn search_spans() {
    let doc = fixture("searchSpans.docs_example.json");
    let item = &doc["body"]["data"][0];
    // the docs example has no ids; add them like the live API returns them
    let mut item = item.clone();
    item["attributes"]["trace_id"] = json!("a3ce929d0e0e47364bf92f3577b34da6");
    item["attributes"]["span_id"] = json!("67667974448284343");
    let s = datadog_search_span_to_span(&item).unwrap();
    assert_eq!(s.service_name, "test-service");
    assert_eq!(s.trace_id, "a3ce929d0e0e47364bf92f3577b34da6");
    assert_eq!(s.span_id, "00f067aa0ba902b7");
    assert_eq!(s.attributes["env"], "prod");

    let attrs = json!({
        "trace_id": "42", "span_id": "1", "parent_id": "0", "service": "web",
        "resource_name": "GET /a", "type": "web", "host": "h1", "env": "prod",
        "start_timestamp": "2024-05-27T08:53:20.123456Z",
        "end_timestamp": "2024-05-27T10:53:21+02:00",
        "tags": ["team:A", "novalue", ":x"],
        "custom": {"grow": {"run": "r1"}, "duration": 2_500_000, "nil": null},
        "attributes": {"span.kind": "client", "http": {"status_code": 500}},
        "status": "error", "ingestion_reason": "rule", "resource_hash": "abc"
    });
    let s = datadog_search_span_to_span(&attrs).unwrap();
    assert_eq!(s.trace_id, "0000000000000000000000000000002a");
    assert_eq!(s.parent_span_id, None);
    assert_eq!(s.name, "GET /a");
    assert_eq!(s.kind, SpanKind::Client);
    assert_eq!(s.start_micros, 1_716_800_000_123_456);
    assert_eq!(s.duration_micros, 2500);
    assert_eq!(s.end_micros, 1_716_800_001_000_000);
    assert_eq!(s.attributes["team"], "A");
    assert_eq!(s.attributes["grow.run"], "r1");
    assert_eq!(s.attributes["http.status_code"], 500);
    assert_eq!(s.attributes["span.type"], "web");
    assert_eq!(s.attributes["resource_hash"], "abc");
    assert!(!s.attributes.contains_key("nil"));
    assert_eq!(s.status.code, StatusCode::Error);

    // operation name fallback, end − start duration, OK status, error message
    let attrs = json!({
        "trace_id": "0000000000000000000000000000002a", "span_id": "2",
        "operation_name": "op", "start_timestamp": "2024-05-27T08:53:20Z",
        "end_timestamp": "2024-05-27T08:53:21.5Z",
        "attributes": {"otel.status_code": "OK"}
    });
    let s = datadog_search_span_to_span(&attrs).unwrap();
    assert_eq!(s.name, "op");
    assert_eq!(s.duration_micros, 1_500_000);
    assert_eq!(s.status.code, StatusCode::Ok);
    let attrs = json!({"trace_id": "1", "span_id": "2", "start_timestamp": "2024-05-27T08:53:20",
        "attributes": {"error": 1, "error.msg": "m", "operation_name": "a", "resource_name": "r", "status": "ok"}});
    let s = datadog_search_span_to_span(&attrs).unwrap();
    assert_eq!(s.status.message.as_deref(), Some("m"));
    assert_eq!(s.name, "r");
    assert_eq!(s.end_micros, s.start_micros);
    // bad timestamps are ignored, bad ids are VALIDATION errors
    for ts in [
        "",
        "2024-05-27",
        "2024-05-27X08:53:20Z",
        "2024-13-27T08:53:20Z",
        "2024-05-27T08:53:20.Z",
        "2024-05-27T08:53:20+0100x",
        "2024-05-27T08:53:20#",
        "2024-05-27T08:5a:20Z",
        "20245-05-27T08:53:20Z",
        "2024-05-27T08:53:20+01:00",
        "2024-05-27t08:53:20-0130",
    ] {
        let s = datadog_search_span_to_span(
            &json!({"trace_id": "1", "span_id": "2", "start_timestamp": ts}),
        )
        .unwrap();
        let _ = s.start_micros;
    }
    assert!(datadog_search_span_to_span(&json!({})).is_err());
    assert!(datadog_search_span_to_span(&json!({"trace_id": "1", "span_id": "z"})).is_err());
    assert!(datadog_search_span_to_span(
        &json!({"trace_id": "1", "span_id": "1", "parent_id": "q"})
    )
    .is_err());
    for missing in [
        json!({"trace_id": "1"}),
        json!({"trace_id": "1", "span_id": "0"}),
    ] {
        let err = datadog_search_span_to_span(&missing).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation);
    }
}
