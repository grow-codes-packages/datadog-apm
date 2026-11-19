// Created by sdk-kit 0.1.0; owned by this repository.
//! Helpers for the integration tests: bootstrap credentials, the conformance directory and
//! test-only access to the spans the real Datadog Agent forwarded to fakeintake, decoded by the
//! `trace-reader` sidecar (`GET /spans?trace_id=<hex>&service=…`). The Datadog query API is
//! SaaS-only, so the suite reads the Agent's output here and maps it with the package's own
//! `datadog_trace_span_to_span`.

use std::collections::BTreeSet;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use grow_datadog_apm as sdk;
use sdk::tracing::{
    normalize_trace_id, wait_for_trace, DependencyLink, DependencyOptions, Trace, TraceQueryClient,
    TraceSearchQuery, TraceSummary, WaitForTraceOptions,
};
use sdk::{Error, Result};
use serde_json::Value;

/// Loads `KEY=value` lines from `$GROW_BOOTSTRAP_ENV` (default `/bootstrap/env`) into the
/// process environment; variables that are already set win. A missing file is not an error.
pub fn load_bootstrap_env() {
    let path = std::env::var("GROW_BOOTSTRAP_ENV").unwrap_or_else(|_| "/bootstrap/env".to_owned());
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            let key = key.trim().trim_start_matches("export ").trim();
            let value = value.trim().trim_matches('"').trim_matches('\'');
            if !key.is_empty() && std::env::var_os(key).is_none() {
                std::env::set_var(key, value);
            }
        }
    }
}

/// `$GROW_CONFORMANCE_DIR`, else `<repo>/conformance/scenarios`.
pub fn conformance_dir() -> PathBuf {
    sdk::testing::scenarios_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/.."))
}

/// Whether the Docker harness is running (else the tests return early).
pub fn service_available() -> bool {
    load_bootstrap_env();
    std::env::var_os("GROW_DATADOG_APM_URL").is_some()
        && std::env::var_os("TRACE_READER_URL").is_some()
}

/// The environment variable `name` (panics when unset).
pub fn env(name: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| panic!("{name} is not set"))
}

/// Unique id of this run (set by the compose harness).
pub fn run_id() -> String {
    std::env::var("GROW_TEST_RUN_ID").unwrap_or_else(|_| "local".to_owned())
}

/// A unique service name for one test.
pub fn it_service(kind: &str) -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or_default();
    format!("grow-rust-it-{kind}-{}-{:06x}", run_id(), nanos & 0xff_ffff)
}

/// `GET /spans` of the trace-reader with exact-match filters.
pub async fn reader_spans(filters: &[(&str, &str)]) -> Result<Vec<Value>> {
    let base = env("TRACE_READER_URL");
    let url =
        reqwest::Url::parse_with_params(&format!("{}/spans", base.trim_end_matches('/')), filters)
            .map_err(|e| Error::config(format!("trace-reader url: {e}")))?;
    let resp = reqwest::get(url)
        .await
        .map_err(|e| Error::unavailable(format!("trace-reader: {e}")))?;
    let status = resp.status();
    let doc: Value = resp
        .json()
        .await
        .map_err(|e| Error::unavailable(format!("trace-reader body: {e}")))?;
    if !status.is_success() {
        return Err(Error::unavailable(format!(
            "trace-reader: HTTP {status} {doc}"
        )));
    }
    if doc["undecodable_idx_payloads"].as_u64().unwrap_or(0) != 0 {
        return Err(Error::unavailable("trace-reader: undecodable payloads"));
    }
    Ok(doc["spans"].as_array().cloned().unwrap_or_default())
}

/// Polls [`reader_spans`] until at least `min` spans match (the Agent flushes every ~4 s).
pub async fn wait_for_spans(filters: &[(&str, &str)], min: usize) -> Vec<Value> {
    let deadline = Instant::now() + Duration::from_secs(45);
    let mut last = Vec::new();
    while Instant::now() < deadline {
        last = reader_spans(filters).await.expect("trace-reader");
        if last.len() >= min {
            return last;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    panic!("trace-reader: {}/{min} spans for {filters:?}", last.len());
}

/// A field as text (`""` when missing).
pub fn text(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// A [`TraceQueryClient`] over the trace-reader, so the shared conformance scenarios run against
/// what the real Agent made of the exported spans.
#[derive(Debug, Clone, Default)]
pub struct TraceReaderQuery;

impl TraceReaderQuery {
    async fn distinct(&self, filters: &[(&str, &str)], key: &str) -> Result<Vec<String>> {
        let set: BTreeSet<String> = reader_spans(filters)
            .await?
            .iter()
            .map(|s| text(&s[key]))
            .collect();
        Ok(set.into_iter().collect())
    }
}

#[async_trait]
impl TraceQueryClient for TraceReaderQuery {
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        let id = normalize_trace_id(trace_id)?;
        let raws = reader_spans(&[("trace_id", &id)]).await?;
        if raws.is_empty() {
            return Ok(None);
        }
        let spans = raws
            .iter()
            .map(sdk::datadog_trace_span_to_span)
            .collect::<Result<Vec<_>>>()?;
        Ok(Some(Trace::new(id, spans)))
    }

    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        let mut filters = Vec::new();
        if let Some(s) = &query.service {
            filters.push(("service", s.as_str()));
        }
        if let Some(o) = &query.operation {
            filters.push(("resource", o.as_str()));
        }
        let mut ids: Vec<String> = Vec::new();
        for s in reader_spans(&filters).await? {
            let id = text(&s["trace_id_hex"]);
            if !ids.contains(&id) {
                ids.push(id);
            }
        }
        let mut out = Vec::new();
        for id in ids.into_iter().take(query.limit.max(1)) {
            if let Some(t) = self.get_trace(&id).await? {
                out.push(t.summary());
            }
        }
        Ok(out)
    }

    async fn list_services(&self) -> Result<Vec<String>> {
        self.distinct(&[], "service").await
    }

    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.distinct(&[("service", service)], "resource").await
    }

    async fn get_dependencies(&self, _opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        Err(Error::unsupported(
            "traceReader.getDependencies: not offered",
        ))
    }

    async fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        wait_for_trace(self, trace_id, opts).await
    }
}
