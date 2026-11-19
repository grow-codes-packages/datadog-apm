//! Datadog span formats → the SPEC §5 trace model.
//!
//! Two Datadog shapes are mapped:
//! - a trace span as the Agent forwards it and as `GET /api/v2/trace/{id}` returns it (`meta`,
//!   `metrics`, nanosecond `start`/`startTime`, decimal ids; camelCase or snake_case keys);
//! - a span event of the spans search API (`attributes.trace_id`, `start_timestamp`, …).
//!
//! For spans that came in over OTLP the Datadog resource is the OpenTelemetry span name and the
//! Datadog name is the operation (`Internal`, `server.request`, `http.server.request`, …), so the
//! normalized name is the resource; the Datadog operation is kept as attribute `operation_name`.
//!
//! `serde_json` parses unsigned 64-bit integers exactly, so ids and nanosecond timestamps above
//! 2^53 need no special decoding.

use serde_json::{Map, Number, Value};

use crate::core::Result;
use crate::ids::normalize_datadog_trace_id;
use crate::ids::{from_datadog_trace_id, is_digits, is_hex16, normalize_datadog_span_id};
use crate::time::parse_iso8601_micros;
use crate::tracing::{
    AttrValue, Attributes, Span, SpanEvent, SpanKind, SpanLink, SpanStatus, StatusCode,
};

/// A raw Datadog span object (any Datadog span shape) decoded from JSON.
pub type RawDatadogSpan = Value;

const ZERO_SPAN: &str = "0000000000000000";

/// Converts a Datadog `span.kind` (or OpenTelemetry kind name) to a [`SpanKind`]; unknown values
/// are `Unspecified`.
pub fn datadog_kind(value: Option<&Value>) -> SpanKind {
    match value
        .and_then(Value::as_str)
        .map(str::to_ascii_lowercase)
        .as_deref()
    {
        Some("internal") => SpanKind::Internal,
        Some("server") => SpanKind::Server,
        Some("client") => SpanKind::Client,
        Some("producer") => SpanKind::Producer,
        Some("consumer") => SpanKind::Consumer,
        _ => SpanKind::Unspecified,
    }
}

static EMPTY: std::sync::LazyLock<Map<String, Value>> = std::sync::LazyLock::new(Map::new);

fn record(v: Option<&Value>) -> &Map<String, Value> {
    v.and_then(Value::as_object).unwrap_or(&EMPTY)
}

fn first<'a>(m: &'a Map<String, Value>, keys: &[&str]) -> Option<&'a Value> {
    keys.iter().find_map(|k| m.get(*k).filter(|v| !v.is_null()))
}

/// Strings and numbers as text; `""` for anything else.
pub(crate) fn str_of(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => number_text(n),
        _ => String::new(),
    }
}

fn number_text(n: &Number) -> String {
    if let Some(f) = n.as_f64().filter(|_| n.is_f64()) {
        if f.fract() == 0.0 && f.abs() < 1e21 {
            return format!("{f:.0}");
        }
    }
    n.to_string()
}

fn i64_of(v: Option<&Value>) -> Option<i64> {
    match v? {
        Value::Number(n) => n
            .as_i64()
            .or_else(|| n.as_u64().map(|u| u as i64))
            .or_else(|| n.as_f64().filter(|f| f.is_finite()).map(|f| f as i64)),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

fn attr_value(v: &Value) -> Option<AttrValue> {
    match v {
        Value::Null => None,
        Value::Array(items) => Some(Value::Array(items.iter().filter_map(attr_value).collect())),
        Value::Object(m) => Some(Value::Object(
            m.iter()
                .filter_map(|(k, v)| attr_value(v).map(|a| (k.clone(), a)))
                .collect(),
        )),
        other => Some(other.clone()),
    }
}

fn attrs_of(v: Option<&Value>) -> Attributes {
    record(v)
        .iter()
        .filter_map(|(k, v)| attr_value(v).map(|a| (k.clone(), a)))
        .collect()
}

fn parse_json_array(v: Option<&Value>) -> Vec<Map<String, Value>> {
    let Some(text) = v.and_then(Value::as_str).filter(|s| !s.is_empty()) else {
        return Vec::new();
    };
    match serde_json::from_str::<Value>(text) {
        Ok(Value::Array(items)) => items
            .into_iter()
            .map(|it| match it {
                Value::Object(m) => m,
                _ => Map::new(),
            })
            .collect(),
        _ => Vec::new(),
    }
}

fn nanos_to_micros(ns: i64) -> u64 {
    u64::try_from(ns / 1000).unwrap_or(0)
}

/// A native Datadog span event attribute (`{type, string_value, …}`) as a plain value.
fn native_attr(v: &Value) -> Value {
    let m = record(Some(v));
    for k in ["string_value", "int_value", "double_value", "bool_value"] {
        if let Some(x) = m.get(k) {
            return x.clone();
        }
    }
    if let Some(arr) = m.get("array_value") {
        let values = record(Some(arr))
            .get("values")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        return Value::Array(values.iter().map(native_attr).collect());
    }
    v.clone()
}

fn events_of(meta: &Map<String, Value>, raw: &Map<String, Value>) -> Vec<SpanEvent> {
    let mut out: Vec<SpanEvent> = parse_json_array(meta.get("events"))
        .into_iter()
        .map(|e| SpanEvent {
            name: str_of(e.get("name")),
            time_micros: nanos_to_micros(i64_of(e.get("time_unix_nano")).unwrap_or(0)),
            attributes: attrs_of(e.get("attributes")),
        })
        .collect();
    if let Some(native) = first(raw, &["span_events", "spanEvents"]).and_then(Value::as_array) {
        for item in native {
            let e = record(Some(item));
            let attributes: Map<String, Value> = record(e.get("attributes"))
                .iter()
                .map(|(k, v)| (k.clone(), native_attr(v)))
                .collect();
            out.push(SpanEvent {
                name: str_of(e.get("name")),
                time_micros: nanos_to_micros(
                    i64_of(first(e, &["time_unix_nano", "timeUnixNano"])).unwrap_or(0),
                ),
                attributes: attrs_of(Some(&Value::Object(attributes))),
            });
        }
    }
    out
}

fn link_trace_id(l: &Map<String, Value>) -> Option<String> {
    let id = str_of(first(l, &["trace_id", "traceId", "traceID"]));
    if id.is_empty() {
        return None;
    }
    let high = str_of(first(l, &["trace_id_high", "traceIdHigh"]));
    if !high.is_empty() && is_digits(&id) {
        let high_hex = if is_hex16(&high) {
            high
        } else {
            format!("{:016x}", high.parse::<u64>().ok()?)
        };
        return from_datadog_trace_id(&id, Some(&high_hex)).ok();
    }
    if is_hex16(&id) {
        return Some(format!("{ZERO_SPAN}{id}"));
    }
    normalize_datadog_trace_id(&id).ok()
}

fn link_span_id(l: &Map<String, Value>) -> Option<String> {
    let id = str_of(first(l, &["span_id", "spanId", "spanID"]));
    if id.is_empty() {
        return None;
    }
    if is_hex16(&id) && id.bytes().any(|b| b.is_ascii_alphabetic()) {
        return Some(id);
    }
    normalize_datadog_span_id(&id).ok().flatten()
}

fn links_of(meta: &Map<String, Value>, raw: &Map<String, Value>) -> Vec<SpanLink> {
    let mut items = parse_json_array(meta.get("_dd.span_links"));
    if let Some(native) = first(raw, &["span_links", "spanLinks"]).and_then(Value::as_array) {
        items.extend(native.iter().map(|it| record(Some(it)).clone()));
    }
    items
        .iter()
        .filter_map(|l| {
            Some(SpanLink {
                trace_id: link_trace_id(l)?,
                span_id: link_span_id(l)?,
                attributes: attrs_of(l.get("attributes")),
            })
        })
        .collect()
}

/// JavaScript's `Number(v) !== 0` for flags such as `error`.
fn numeric_truthy(v: Option<&Value>) -> bool {
    match v {
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_f64().is_some_and(|f| f != 0.0),
        Some(Value::String(s)) => {
            let t = s.trim();
            !t.is_empty() && t.parse::<f64>().map(|f| f != 0.0).unwrap_or(true)
        }
        _ => false,
    }
}

fn status_of(error: bool, message: String, status_code: &str) -> SpanStatus {
    if error {
        SpanStatus {
            code: StatusCode::Error,
            message: (!message.is_empty()).then_some(message),
        }
    } else if status_code.eq_ignore_ascii_case("ok") {
        SpanStatus {
            code: StatusCode::Ok,
            message: None,
        }
    } else {
        SpanStatus::default()
    }
}

/// Maps a Datadog trace span — the Agent's format (`trace_id`, `span_id`, `parent_id`, `start`,
/// `duration`, `meta`, `metrics`) or the trace-by-id API format (`traceID`, `traceIDFull`,
/// `spanID`, `parentID`, `startTime`, `endTime`, `duration`) — to a SPEC [`Span`].
///
/// The full trace id comes from `traceIDFull` / `trace_id_hex` / meta `otel.trace_id`, else the
/// decimal low 64 bits plus meta `_dd.p.tid`. Name = Datadog resource (the OTel span name for
/// OTLP spans), kind = meta `span.kind`, status = `error` flag (message from `error.message`,
/// `error.msg` or `otel.status_description`) or meta `otel.status_code`. Events come from meta
/// `events` and `span_events`, links from meta `_dd.span_links` and `span_links`. Attributes are
/// the remaining meta (strings) and metrics (numbers), plus `operation_name`, `resource_name`
/// and `span.type`. Invalid ids are `VALIDATION` errors.
pub fn datadog_trace_span_to_span(raw: &RawDatadogSpan) -> Result<Span> {
    let raw = record(Some(raw));
    let meta = record(raw.get("meta"));
    let metrics = record(raw.get("metrics"));
    let mut full = str_of(first(raw, &["traceIDFull", "trace_id_hex"]));
    if full.is_empty() {
        full = str_of(meta.get("otel.trace_id"));
    }
    let trace_id = if !full.is_empty() {
        normalize_datadog_trace_id(&format!("{full:0>32}"))?
    } else {
        let mut low = str_of(first(raw, &["traceID", "trace_id", "traceId"]));
        if low.is_empty() {
            low = "0".into();
        }
        let mut high = str_of(meta.get("_dd.p.tid"));
        if high.is_empty() {
            high = str_of(raw.get("trace_id_high"));
        }
        from_datadog_trace_id(&low, Some(&high))?
    };
    let mut span_raw = str_of(first(raw, &["spanID", "span_id", "spanId"]));
    if span_raw.is_empty() {
        span_raw = "0".into();
    }
    let span_id = normalize_datadog_span_id(&span_raw)?.unwrap_or_else(|| ZERO_SPAN.to_owned());
    let parent_span_id =
        normalize_datadog_span_id(&str_of(first(raw, &["parentID", "parent_id", "parentId"])))?;
    let start_ns = i64_of(first(raw, &["startTime", "start"])).unwrap_or(0);
    let end = i64_of(first(raw, &["endTime", "end"]));
    let duration = i64_of(raw.get("duration"));
    let duration_ns = duration.unwrap_or_else(|| end.map_or(0, |e| e - start_ns));
    let end_ns = end.unwrap_or(start_ns + duration_ns);

    let dd_name = str_of(raw.get("name"));
    let resource = str_of(first(raw, &["resource", "resource_name"]));
    let span_type = str_of(raw.get("type"));
    let mut attributes = Attributes::new();
    for (k, v) in meta {
        if let (Value::String(_), false) = (v, k == "events" || k == "_dd.span_links") {
            attributes.insert(k.clone(), v.clone());
        }
    }
    for (k, v) in metrics {
        match v {
            Value::Number(_) => {
                attributes.insert(k.clone(), v.clone());
            }
            Value::String(s) => {
                if let Some(n) = s.trim().parse::<f64>().ok().and_then(Number::from_f64) {
                    attributes.insert(k.clone(), Value::Number(n));
                }
            }
            _ => {}
        }
    }
    for (k, v) in [
        ("operation_name", &dd_name),
        ("resource_name", &resource),
        ("span.type", &span_type),
    ] {
        if !v.is_empty() {
            attributes.insert(k.to_owned(), Value::String(v.clone()));
        }
    }
    let status = status_of(
        numeric_truthy(raw.get("error")),
        str_of(first(
            meta,
            &["error.message", "error.msg", "otel.status_description"],
        )),
        &str_of(meta.get("otel.status_code")),
    );
    let name = if resource.is_empty() {
        dd_name
    } else {
        resource
    };
    Ok(Span {
        trace_id,
        span_id,
        parent_span_id,
        name,
        kind: datadog_kind(meta.get("span.kind")),
        service_name: str_of(raw.get("service")),
        start_micros: nanos_to_micros(start_ns),
        end_micros: nanos_to_micros(end_ns),
        duration_micros: nanos_to_micros(duration_ns),
        status,
        attributes,
        events: events_of(meta, raw),
        links: links_of(meta, raw),
    })
}

fn flatten(prefix: &str, v: &Value, out: &mut Attributes) {
    if let Value::Object(m) = v {
        for (k, it) in m {
            let key = if prefix.is_empty() {
                k.clone()
            } else {
                format!("{prefix}.{k}")
            };
            flatten(&key, it, out);
        }
        return;
    }
    if prefix.is_empty() {
        return;
    }
    if let Some(a) = attr_value(v) {
        out.insert(prefix.to_owned(), a);
    }
}

/// Maps one span of the spans search API (`data[i]` of `POST /api/v2/spans/events/search`, or
/// its `attributes`) to a SPEC [`Span`]. Nested span attributes (`attributes`, `custom`) are
/// flattened to dotted keys; `tags` (`key:value`) are added as attributes. Name =
/// `resource_name`, kind from `span.kind`, status error when `status` is `error` or `error` is
/// set; duration from the `duration` attribute (nanoseconds) when present, else end − start.
pub fn datadog_search_span_to_span(item: &RawDatadogSpan) -> Result<Span> {
    let outer = record(Some(item));
    let a = match (
        outer.get("attributes"),
        outer.get("type").and_then(Value::as_str),
    ) {
        (Some(inner), Some("spans")) => record(Some(inner)),
        _ => outer,
    };
    let mut attributes = Attributes::new();
    if let Some(tags) = a.get("tags").and_then(Value::as_array) {
        for t in tags.iter().filter_map(Value::as_str) {
            if let Some((k, v)) = t.split_once(':').filter(|(k, _)| !k.is_empty()) {
                attributes.insert(k.to_owned(), Value::String(v.to_owned()));
            }
        }
    }
    if let Some(c) = a.get("custom") {
        flatten("", c, &mut attributes);
    }
    if let Some(c) = a.get("attributes") {
        flatten("", c, &mut attributes);
    }
    for key in ["env", "host", "type", "resource_hash", "ingestion_reason"] {
        let v = str_of(a.get(key));
        if !v.is_empty() {
            let k = if key == "type" { "span.type" } else { key };
            attributes.insert(k.to_owned(), Value::String(v));
        }
    }
    let mut op_name = str_of(a.get("operation_name"));
    if op_name.is_empty() {
        op_name = str_of(attributes.get("operation_name"));
    }
    if !op_name.is_empty() {
        attributes.insert("operation_name".into(), Value::String(op_name.clone()));
    }
    let mut resource = str_of(a.get("resource_name"));
    if resource.is_empty() {
        resource = str_of(attributes.get("resource_name"));
    }
    if !resource.is_empty() {
        attributes.insert("resource_name".into(), Value::String(resource.clone()));
    }
    let start = a
        .get("start_timestamp")
        .and_then(Value::as_str)
        .and_then(parse_iso8601_micros)
        .unwrap_or(0);
    let end = a
        .get("end_timestamp")
        .and_then(Value::as_str)
        .and_then(parse_iso8601_micros);
    let duration = match i64_of(attributes.get("duration")) {
        Some(d) => d / 1000,
        None => end.map_or(0, |e| e - start),
    };
    let end = end.unwrap_or(start + duration);
    let mut status_word = str_of(a.get("status")).to_ascii_lowercase();
    if status_word.is_empty() {
        status_word = str_of(attributes.get("status")).to_ascii_lowercase();
    }
    let status = status_of(
        status_word == "error" || numeric_truthy(attributes.get("error")),
        str_of(first(
            &attr_map(&attributes),
            &["error.message", "error.msg"],
        )),
        &str_of(attributes.get("otel.status_code")),
    );
    let mut trace_raw = str_of(a.get("trace_id"));
    if trace_raw.is_empty() {
        trace_raw = "0".into();
    }
    let trace_id = normalize_datadog_trace_id(&trace_raw)?;
    let mut span_raw = str_of(a.get("span_id"));
    if span_raw.is_empty() {
        span_raw = "0".into();
    }
    let span_id = normalize_datadog_span_id(&span_raw)?.unwrap_or_else(|| ZERO_SPAN.to_owned());
    let parent_span_id = normalize_datadog_span_id(&str_of(a.get("parent_id")))?;
    let name = if resource.is_empty() {
        op_name
    } else {
        resource
    };
    let micros = |v: i64| u64::try_from(v).unwrap_or(0);
    Ok(Span {
        trace_id,
        span_id,
        parent_span_id,
        name,
        kind: datadog_kind(attributes.get("span.kind")),
        service_name: str_of(a.get("service")),
        start_micros: micros(start),
        end_micros: micros(end),
        duration_micros: micros(duration),
        status,
        attributes,
        events: Vec::new(),
        links: Vec::new(),
    })
}

fn attr_map(a: &Attributes) -> Map<String, Value> {
    a.iter().map(|(k, v)| (k.clone(), v.clone())).collect()
}
