//! Id helpers (Agent-verified vectors), sites, tags, OTLP URLs and query time values.

use std::time::{Duration, UNIX_EPOCH};

use grow_datadog_apm as sdk;
use sdk::tracing::Protocol;
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

#[test]
fn trace_id_vectors_round_trip() {
    for v in fixture("toDatadogTraceId.vectors.json").as_array().unwrap() {
        let hex = v["otel_trace_id_hex"].as_str().unwrap();
        let low = v["datadog_trace_id_low64_decimal"].as_str().unwrap();
        let high = v["datadog_trace_id_high64_hex"].as_str().unwrap();
        let id = to_datadog_trace_id(hex).unwrap();
        assert_eq!(id.low64_decimal, low, "{hex}");
        assert_eq!(id.high64_hex, high, "{hex}");
        assert_eq!(id.is_128_bit, !v["x-datadog-tags"].is_null(), "{hex}");
        assert_eq!(from_datadog_trace_id(low, Some(high)).unwrap(), hex);
        if let Some(observed) = v["agent_observed"]["agent_trace_id"].as_str() {
            assert_eq!(observed, low);
        }
        let dd = datadog_trace_id_string(hex).unwrap();
        assert_eq!(dd, if id.is_128_bit { hex } else { low });
        assert_eq!(normalize_datadog_trace_id(&dd).unwrap(), hex);
    }
    let json = serde_json::to_value(to_datadog_trace_id("2a").unwrap()).unwrap();
    assert_eq!(json["low64Decimal"], "42");
    assert_eq!(json["is128Bit"], false);
}

#[test]
fn id_errors_and_edge_cases() {
    for bad in [
        "",
        "xyz",
        "0",
        "00000000000000000000000000000000",
        &"f".repeat(33),
    ] {
        assert_eq!(
            to_datadog_trace_id(bad).unwrap_err().code(),
            ErrorCode::Validation,
            "{bad:?}"
        );
    }
    assert!(from_datadog_trace_id("abc", None).is_err());
    assert!(from_datadog_trace_id("18446744073709551616", None).is_err());
    assert!(from_datadog_trace_id("1", Some("123")).is_err());
    assert!(from_datadog_trace_id("0", None).is_err());
    assert_eq!(
        from_datadog_trace_id(" 42 ", Some("")).unwrap(),
        "0000000000000000000000000000002a"
    );
    assert_eq!(
        to_datadog_span_id("00f067aa0ba902b7").unwrap(),
        "67667974448284343"
    );
    assert_eq!(to_datadog_span_id("FF").unwrap(), "255");
    assert!(to_datadog_span_id("").is_err());
    assert!(to_datadog_span_id("12345678901234567").is_err());
    assert_eq!(from_datadog_span_id("255").unwrap(), "00000000000000ff");
    assert!(from_datadog_span_id("-1").is_err());
    assert_eq!(normalize_datadog_span_id("").unwrap(), None);
    assert_eq!(normalize_datadog_span_id("000").unwrap(), None);
    assert_eq!(
        normalize_datadog_span_id("1").unwrap().as_deref(),
        Some("0000000000000001")
    );
    assert_eq!(
        normalize_datadog_trace_id("ABC").unwrap(),
        "00000000000000000000000000000abc"
    );
    assert_eq!(
        normalize_datadog_trace_id("123456789012345678901").unwrap(),
        "00000000000123456789012345678901"
    );
    assert!(normalize_datadog_trace_id("0".repeat(32).as_str()).is_err());
    assert!(datadog_trace_id_string("zz").is_err());
    assert_eq!(
        trace_id_for_path("0000000000000000abc1230000000000").unwrap(),
        "0000000000000000abc1230000000000"
    );
    assert_eq!(
        trace_id_for_path("10000000000000000000000000000000").unwrap(),
        "21267647932558653966460912964485513216"
    );
    assert!(trace_id_for_path("").is_err());
}

#[test]
fn sites_and_api_urls() {
    assert_eq!(DEFAULT_SITE, "datadoghq.com");
    assert_eq!(DATADOG_SITES.len(), 9);
    assert!(is_datadog_site(" US5.datadoghq.com"));
    assert!(!is_datadog_site("example.com"));
    assert_eq!(normalize_site(" A.B "), "a.b");
    assert_eq!(
        validate_site("my-dd.example.com").unwrap(),
        "my-dd.example.com"
    );
    for bad in [
        "https://datadoghq.com",
        "datadoghq.com/",
        "localhost",
        "datadoghq.com:443",
        "-bad.com",
        "bad-.com",
        "a..com",
    ] {
        assert_eq!(
            validate_site(bad).unwrap_err().code(),
            ErrorCode::Config,
            "{bad}"
        );
    }
    assert!(validate_site(&format!("{}.com", "a".repeat(260))).is_err());
    assert_eq!(api_url_for_site("").unwrap(), "https://api.datadoghq.com");
    assert_eq!(
        api_url_for_site("ddog-gov.com").unwrap(),
        "https://api.ddog-gov.com"
    );
    assert!(api_url_for_site("http://x").is_err());
}

#[test]
fn tags_and_otlp_urls() {
    let tags = parse_datadog_tags("team:sdk tier:web,,bad :novalue x:y:z");
    assert_eq!(tags.len(), 3);
    assert_eq!(tags["x"], "y:z");
    assert!(parse_datadog_tags("").is_empty());
    assert_eq!(
        otlp_traces_url(Protocol::HttpProtobuf, "http://a:4318/"),
        "http://a:4318/v1/traces"
    );
    assert_eq!(
        otlp_traces_url(Protocol::HttpProtobuf, "http://a:4318/custom"),
        "http://a:4318/custom"
    );
    assert_eq!(
        otlp_traces_url(Protocol::Grpc, "http://a:4317"),
        "http://a:4317"
    );
    assert_eq!(
        otlp_traces_url(Protocol::HttpProtobuf, "nope"),
        "nope/v1/traces"
    );
}

#[test]
fn query_time_values() {
    let t = UNIX_EPOCH + Duration::from_millis(1_716_800_000_123);
    assert_eq!(
        DatadogTime::from(t).to_query_string(),
        "2024-05-27T08:53:20.123Z"
    );
    assert_eq!(
        DatadogTime::from(UNIX_EPOCH).to_query_string(),
        "1970-01-01T00:00:00.000Z"
    );
    assert_eq!(
        DatadogTime::from(UNIX_EPOCH + Duration::from_secs(951_782_400)).to_query_string(),
        "2000-02-29T00:00:00.000Z"
    );
    assert_eq!(DatadogTime::from("now-1h").to_query_string(), "now-1h");
    assert_eq!(
        DatadogTime::from(String::from("1716800000000")).to_query_string(),
        "1716800000000"
    );
}
