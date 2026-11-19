//! `DatadogSpansQuery` — the Datadog SaaS spans query API. It needs an API key and an
//! application key with `apm_read`. No container can serve these endpoints, so they are
//! unit-tested against Datadog's documented examples and verified by the live sandbox job.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use async_trait::async_trait;
use futures::stream::{self, BoxStream, StreamExt as _};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::config::DatadogApmConfig;
use crate::core::retry::parse_retry_after;
use crate::core::transport::RequestBuilder;
use crate::core::{Error, ErrorCode, HttpTransport, Response, Result};
use crate::ids::{datadog_trace_id_string, trace_id_for_path};
use crate::mapping::{datadog_search_span_to_span, datadog_trace_span_to_span, str_of};
use crate::time::format_iso8601;
use crate::tracing::{
    normalize_trace_id, sort_spans, wait_for_trace, DependencyLink, DependencyOptions, Span, Trace,
    TraceQueryClient, TraceSearchQuery, TraceSummary, WaitForTraceOptions,
};

/// Default polling interval of [`DatadogSpansQuery`]'s `wait_for_trace` (Datadog allows 50
/// searches per minute).
pub const DATADOG_WAIT_INTERVAL: Duration = Duration::from_secs(2);

const DEFAULT_QUERY_WINDOW: Duration = Duration::from_secs(15 * 60);
const TRACE_QUERY_WINDOW: Duration = Duration::from_secs(24 * 3600);
const MAX_SPANS_PAGE: u32 = 1000;
const SEARCH_TRACES_MAX_PAGES: usize = 5;

/// A point in time for a query: an absolute time or a Datadog date expression (`now-15m`,
/// ISO-8601, epoch milliseconds).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DatadogTime {
    /// Absolute time (sent as ISO-8601 in UTC with milliseconds).
    At(SystemTime),
    /// Sent as is.
    Expr(String),
}

impl DatadogTime {
    /// The wire value.
    pub fn to_query_string(&self) -> String {
        match self {
            DatadogTime::At(t) => format_iso8601(*t),
            DatadogTime::Expr(e) => e.clone(),
        }
    }
}

impl From<SystemTime> for DatadogTime {
    fn from(t: SystemTime) -> Self {
        DatadogTime::At(t)
    }
}

impl From<&str> for DatadogTime {
    fn from(e: &str) -> Self {
        DatadogTime::Expr(e.to_owned())
    }
}

impl From<String> for DatadogTime {
    fn from(e: String) -> Self {
        DatadogTime::Expr(e)
    }
}

/// Options of [`DatadogSpansQuery::search_spans`] and
/// [`search_spans_page`](DatadogSpansQuery::search_spans_page).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchSpansOptions {
    /// Datadog span query, e.g. `service:web @http.status_code:500`. Default `*`.
    pub query: Option<String>,
    /// Window start. Default: 15 minutes ago (absolute, fixed for all pages).
    pub from: Option<DatadogTime>,
    /// Window end. Default: now (absolute, fixed for all pages).
    pub to: Option<DatadogTime>,
    /// Page size, 1..=1000. Default 10.
    pub limit: Option<u32>,
    /// `timestamp` (oldest first, default) or `-timestamp` (newest first).
    pub sort: Option<String>,
    /// Cursor of a previous page ([`DatadogSpansPage::next_cursor`]).
    pub cursor: Option<String>,
    /// Timezone for relative dates, e.g. `UTC` (default) or `Europe/Berlin`.
    pub timezone: Option<String>,
}

impl SearchSpansOptions {
    /// Options for a query.
    pub fn query(query: impl Into<String>) -> Self {
        SearchSpansOptions {
            query: Some(query.into()),
            ..Default::default()
        }
    }
    /// Sets the window start.
    pub fn from(mut self, from: impl Into<DatadogTime>) -> Self {
        self.from = Some(from.into());
        self
    }
    /// Sets the window end.
    pub fn to(mut self, to: impl Into<DatadogTime>) -> Self {
        self.to = Some(to.into());
        self
    }
    /// Sets the page size.
    pub fn limit(mut self, limit: u32) -> Self {
        self.limit = Some(limit);
        self
    }
    /// Sets the sort order.
    pub fn sort(mut self, sort: impl Into<String>) -> Self {
        self.sort = Some(sort.into());
        self
    }
    /// Sets the cursor.
    pub fn cursor(mut self, cursor: impl Into<String>) -> Self {
        self.cursor = Some(cursor.into());
        self
    }
    /// Sets the timezone.
    pub fn timezone(mut self, tz: impl Into<String>) -> Self {
        self.timezone = Some(tz.into());
        self
    }
}

/// A warning attached to a partial query result.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct DatadogQueryWarning {
    /// Warning code.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    /// Short title.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    /// Details.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

/// One span returned by the spans search API.
#[derive(Debug, Clone, PartialEq)]
pub struct DatadogSpan {
    /// Datadog event id of the span.
    pub id: String,
    /// The span in the SPEC §5 model (ids normalized to hex).
    pub span: Span,
    /// The attributes exactly as Datadog returned them.
    pub raw: Value,
}

/// One page of [`DatadogSpansQuery::search_spans_page`].
#[derive(Debug, Clone, PartialEq)]
pub struct DatadogSpansPage {
    /// Spans of this page.
    pub spans: Vec<DatadogSpan>,
    /// Cursor of the next page; `None` on the last page.
    pub next_cursor: Option<String>,
    /// `done`, or `timeout` for a partial result.
    pub status: String,
    /// Non-empty when the result is partial or the query was adjusted.
    pub warnings: Vec<DatadogQueryWarning>,
    /// `meta.request_id`, when present.
    pub request_id: Option<String>,
}

/// Aggregation functions of the spans aggregate API.
pub mod aggregation {
    /// Count.
    pub const COUNT: &str = "count";
    /// Distinct values.
    pub const CARDINALITY: &str = "cardinality";
    /// 75th percentile.
    pub const PC75: &str = "pc75";
    /// 90th percentile.
    pub const PC90: &str = "pc90";
    /// 95th percentile.
    pub const PC95: &str = "pc95";
    /// 98th percentile.
    pub const PC98: &str = "pc98";
    /// 99th percentile.
    pub const PC99: &str = "pc99";
    /// Sum.
    pub const SUM: &str = "sum";
    /// Minimum.
    pub const MIN: &str = "min";
    /// Maximum.
    pub const MAX: &str = "max";
    /// Average.
    pub const AVG: &str = "avg";
    /// Median.
    pub const MEDIAN: &str = "median";
}

/// One computation of [`DatadogSpansQuery::aggregate_spans`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct SpanCompute {
    /// One of the [`aggregation`] names.
    pub aggregation: String,
    /// Measure, e.g. `@duration` (not needed for `count`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metric: Option<String>,
    /// `total` (default) or `timeseries` (all computes must use the same type and interval).
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub compute_type: Option<String>,
    /// Time bucket for timeseries, e.g. `5m`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interval: Option<String>,
}

impl SpanCompute {
    /// A compute with an aggregation.
    pub fn new(aggregation: impl Into<String>) -> Self {
        SpanCompute {
            aggregation: aggregation.into(),
            ..Default::default()
        }
    }
    /// Sets the metric.
    pub fn metric(mut self, metric: impl Into<String>) -> Self {
        self.metric = Some(metric.into());
        self
    }
    /// Makes it a timeseries with an interval.
    pub fn timeseries(mut self, interval: impl Into<String>) -> Self {
        self.compute_type = Some("timeseries".into());
        self.interval = Some(interval.into());
        self
    }
}

/// Order of the groups of a [`SpanGroupBy`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct SpanGroupBySort {
    /// Aggregation to sort by.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub aggregation: Option<String>,
    /// Metric to sort by.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metric: Option<String>,
    /// `asc` or `desc`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub order: Option<String>,
    /// `alphabetical` or `measure`.
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub sort_type: Option<String>,
}

/// Buckets a numeric facet.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct SpanHistogram {
    /// Bucket size.
    pub interval: f64,
    /// Lowest value.
    pub min: f64,
    /// Highest value.
    pub max: f64,
}

/// One group-by facet of [`DatadogSpansQuery::aggregate_spans`].
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct SpanGroupBy {
    /// Facet, e.g. `service`, `resource_name`, `@http.status_code`.
    pub facet: String,
    /// Maximum number of groups (Datadog default 10).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
    /// Group order.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sort: Option<SpanGroupBySort>,
    /// Value used for spans without the facet (string or number).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub missing: Option<Value>,
    /// Adds a total bucket (`true`, or the bucket's value).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total: Option<Value>,
    /// Histogram buckets for a numeric facet.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub histogram: Option<SpanHistogram>,
}

impl SpanGroupBy {
    /// A group-by facet.
    pub fn facet(facet: impl Into<String>) -> Self {
        SpanGroupBy {
            facet: facet.into(),
            ..Default::default()
        }
    }
    /// Sets the limit.
    pub fn limit(mut self, limit: u32) -> Self {
        self.limit = Some(limit);
        self
    }
}

/// Filter of [`DatadogSpansQuery::aggregate_spans`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AggregateSpansOptions {
    /// Query, default `*`.
    pub query: Option<String>,
    /// Window start, default 15 minutes ago.
    pub from: Option<DatadogTime>,
    /// Window end, default now.
    pub to: Option<DatadogTime>,
    /// Timezone, default `UTC`.
    pub timezone: Option<String>,
}

/// A point of a timeseries compute.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct SpanTimeseriesPoint {
    /// Bucket time as Datadog returned it.
    pub time: String,
    /// Value.
    pub value: f64,
}

/// One compute result of a bucket.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(untagged)]
pub enum SpanComputeValue {
    /// A `total` compute.
    Number(f64),
    /// A `timeseries` compute.
    Timeseries(Vec<SpanTimeseriesPoint>),
    /// Anything else, as returned.
    Other(Value),
}

impl SpanComputeValue {
    /// The number of a `total` compute.
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            SpanComputeValue::Number(n) => Some(*n),
            _ => None,
        }
    }
}

/// One bucket of an aggregation.
#[derive(Debug, Clone, PartialEq)]
pub struct SpanAggregateBucket {
    /// Group-by values, e.g. `{"service": "web"}` (empty without group-by).
    pub by: BTreeMap<String, Value>,
    /// Results keyed `c0`, `c1`, … in the order of the compute list.
    pub computes: BTreeMap<String, SpanComputeValue>,
}

/// Result of [`DatadogSpansQuery::aggregate_spans`].
#[derive(Debug, Clone, PartialEq)]
pub struct SpanAggregateResult {
    /// Buckets.
    pub buckets: Vec<SpanAggregateBucket>,
    /// `done` or `timeout`.
    pub status: String,
    /// Warnings of a partial result.
    pub warnings: Vec<DatadogQueryWarning>,
    /// `meta.request_id`, when present.
    pub request_id: Option<String>,
}

/// Options of [`DatadogSpansQuery::get_trace_with_options`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct GetTraceOptions {
    /// Use the preview trace-by-id endpoint `GET /api/v2/trace/{id}` (full trace, `x-unstable`,
    /// 60 requests/minute) instead of searching indexed spans.
    pub preview: bool,
    /// Search window start (search mode). Default: 24 hours ago.
    pub from: Option<DatadogTime>,
    /// Search window end (search mode). Default: now.
    pub to: Option<DatadogTime>,
}

/// Options of [`DatadogSpansQuery::list_services_with_options`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ListServicesOptions {
    /// `filter[env]`; `*` = all environments. Default: the configured environment, else `*`.
    pub env: Option<String>,
}

/// Quotes a value for the Datadog query syntax when it contains spaces or special characters.
///
/// ```
/// use grow_datadog_apm::quote_query_value;
/// assert_eq!(quote_query_value("web-1"), "web-1");
/// assert_eq!(quote_query_value("GET /users"), "\"GET /users\"");
/// ```
pub fn quote_query_value(value: &str) -> String {
    let plain = !value.is_empty()
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.-/*@".contains(&b));
    if plain {
        value.to_owned()
    } else {
        format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
    }
}

/// The Datadog query string for a SPEC [`TraceSearchQuery`]: `service:`, `resource_name:`,
/// `@<tag>:` and `@duration:>=` / `<=` (nanoseconds). An empty query is `*`.
pub fn trace_search_query_string(q: &TraceSearchQuery) -> String {
    let mut parts = Vec::new();
    if let Some(s) = q.service.as_deref().filter(|s| !s.is_empty()) {
        parts.push(format!("service:{}", quote_query_value(s)));
    }
    if let Some(o) = q.operation.as_deref().filter(|s| !s.is_empty()) {
        parts.push(format!("resource_name:{}", quote_query_value(o)));
    }
    for (k, v) in &q.tags {
        parts.push(format!("@{k}:{}", quote_query_value(v)));
    }
    let nanos = |d: Duration| d.as_micros() * 1000;
    if let Some(d) = q.min_duration.filter(|d| !d.is_zero()) {
        parts.push(format!("@duration:>={}", nanos(d)));
    }
    if let Some(d) = q.max_duration.filter(|d| !d.is_zero()) {
        parts.push(format!("@duration:<={}", nanos(d)));
    }
    if parts.is_empty() {
        "*".to_owned()
    } else {
        parts.join(" ")
    }
}

/// The Datadog spans query client (SaaS API at `https://api.<site>`, headers `DD-API-KEY` and
/// `DD-APPLICATION-KEY`). It implements [`TraceQueryClient`] on top of the spans search and
/// aggregate APIs, plus the Datadog-specific [`search_spans`](Self::search_spans),
/// [`aggregate_spans`](Self::aggregate_spans) and [`ping`](Self::ping). Cheap to clone.
///
/// Errors: 400/413/422 → `VALIDATION`, 401 → `AUTHENTICATION`, 403 → `PERMISSION_DENIED`
/// (`AUTHENTICATION` from `ping`), 404 → `NOT_FOUND`, 408 → `TIMEOUT`, 429 → `RATE_LIMITED`
/// (waits `Retry-After` / `X-RateLimit-Reset` within the retry budget), 5xx → `UNAVAILABLE`.
#[derive(Debug, Clone)]
pub struct DatadogSpansQuery {
    inner: Arc<QueryInner>,
}

#[derive(Debug)]
struct QueryInner {
    config: DatadogApmConfig,
    transport: HttpTransport,
    base: String,
}

#[derive(Deserialize, Default)]
struct Meta {
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    request_id: Option<String>,
    #[serde(default)]
    page: Option<PageMeta>,
    #[serde(default)]
    warnings: Option<Vec<DatadogQueryWarning>>,
}

#[derive(Deserialize, Default)]
struct PageMeta {
    #[serde(default)]
    after: Option<String>,
}

#[derive(Deserialize)]
struct DataDoc {
    #[serde(default)]
    data: Option<Value>,
    #[serde(default)]
    meta: Option<Meta>,
}

fn invalid_json(operation: &str, resp: &Response) -> Error {
    let err = Error::unavailable(format!("{operation}: invalid JSON response"))
        .with_status(resp.status());
    match resp.request_id() {
        Some(id) => err.with_request_id(id),
        None => err,
    }
}

fn parse_doc(operation: &str, resp: &Response) -> Result<DataDoc> {
    if resp.bytes().iter().all(u8::is_ascii_whitespace) {
        return Ok(DataDoc {
            data: None,
            meta: None,
        });
    }
    serde_json::from_slice(resp.bytes()).map_err(|_| invalid_json(operation, resp))
}

fn compute_value(v: &Value) -> SpanComputeValue {
    match v {
        Value::Number(n) => SpanComputeValue::Number(n.as_f64().unwrap_or(f64::NAN)),
        Value::Array(items) => SpanComputeValue::Timeseries(
            items
                .iter()
                .map(|it| SpanTimeseriesPoint {
                    time: str_of(it.get("time")),
                    value: it.get("value").and_then(Value::as_f64).unwrap_or(0.0),
                })
                .collect(),
        ),
        other => SpanComputeValue::Other(other.clone()),
    }
}

fn request_id_of(meta: &Meta, resp: &Response) -> Option<String> {
    meta.request_id
        .clone()
        .filter(|s| !s.is_empty())
        .or_else(|| resp.request_id().map(str::to_owned))
}

impl DatadogSpansQuery {
    /// A query client. `CONFIG` error unless both the API key and the application key are
    /// configured.
    pub fn new(config: DatadogApmConfig) -> Result<Self> {
        config.validate()?;
        if !config.has_query_credentials() {
            return Err(Error::config(
                "the Datadog query API needs apiKey (DD_API_KEY) and appKey (DD_APP_KEY)",
            ));
        }
        // Requests use absolute URLs below the API base, so the common settings apply as they are.
        let transport = HttpTransport::new(config.common())?;
        for s in config.known_secrets() {
            transport.add_known_secret(s);
        }
        let base = config.api_url().trim_end_matches('/').to_owned();
        Ok(DatadogSpansQuery {
            inner: Arc::new(QueryInner {
                config,
                transport,
                base,
            }),
        })
    }

    /// A query client from the environment (`DD_API_KEY`, `DD_APP_KEY`, `DD_SITE`, …; see
    /// [`DatadogApmConfigBuilder::build_with_env`](crate::DatadogApmConfigBuilder::build_with_env)).
    pub fn from_env() -> Result<Self> {
        Self::new(DatadogApmConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &DatadogApmConfig {
        &self.inner.config
    }

    /// Releases connections; later calls fail with `CANCELLED`. Idempotent.
    pub fn close(&self) {
        self.inner.transport.close();
    }

    fn window(
        from: Option<&DatadogTime>,
        to: Option<&DatadogTime>,
        span: Duration,
    ) -> (String, String) {
        let now = SystemTime::now();
        let from = from.cloned().unwrap_or_else(|| DatadogTime::At(now - span));
        let to = to.cloned().unwrap_or(DatadogTime::At(now));
        (from.to_query_string(), to.to_query_string())
    }

    /// Sends a request with the Datadog keys; on 429 waits `Retry-After`, else
    /// `X-RateLimit-Reset`, else the backoff, within the retry and total-time budget.
    async fn send<'a>(
        &'a self,
        operation: &str,
        build: impl Fn(&'a HttpTransport, &str) -> RequestBuilder<'a>,
    ) -> Result<Response> {
        let t = &self.inner.transport;
        let base = self.inner.base.as_str();
        let cfg = &self.inner.config;
        let policy = cfg.common().retry().clone();
        let deadline = Instant::now() + cfg.common().total_timeout();
        let mut attempt = 0u32;
        loop {
            let mut req = build(t, base).operation(operation).ok_status(429);
            if let Some(k) = cfg.api_key() {
                req = req.secret_header("DD-API-KEY", k);
            }
            if let Some(k) = cfg.app_key() {
                req = req.secret_header("DD-APPLICATION-KEY", k);
            }
            let resp = req.send().await?;
            if resp.status() != 429 {
                return Ok(resp);
            }
            let hint = resp
                .header("retry-after")
                .filter(|v| !v.trim().is_empty())
                .or_else(|| resp.header("x-ratelimit-reset"))
                .and_then(|v| parse_retry_after(v.trim(), SystemTime::now()));
            let mut err = Error::from_status(
                429,
                format!(
                    "{operation}: {} (HTTP 429)",
                    ErrorCode::RateLimited.reason()
                ),
            );
            if let Some(id) = resp.request_id() {
                err = err.with_request_id(id);
            }
            if let Some(h) = hint {
                err = err.with_retry_after(h);
            }
            let delay = hint.unwrap_or_else(|| policy.backoff(attempt));
            if attempt >= policy.max_retries
                || delay > policy.max_retry_after
                || Instant::now() + delay >= deadline
            {
                return Err(err);
            }
            attempt += 1;
            tokio::time::sleep(delay).await;
        }
    }

    async fn page(
        &self,
        opts: &SearchSpansOptions,
        from: &str,
        to: &str,
        cursor: Option<&str>,
    ) -> Result<DatadogSpansPage> {
        let limit = opts.limit.unwrap_or(10);
        if !(1..=MAX_SPANS_PAGE).contains(&limit) {
            return Err(Error::config(
                "searchSpans: limit must be an integer between 1 and 1000",
            ));
        }
        let mut page = json!({ "limit": limit });
        if let Some(c) = cursor {
            page["cursor"] = Value::String(c.to_owned());
        }
        let body = json!({"data": {"type": "search_request", "attributes": {
            "filter": {"query": opts.query.as_deref().filter(|q| !q.is_empty()).unwrap_or("*"), "from": from, "to": to},
            "options": {"timezone": opts.timezone.as_deref().unwrap_or("UTC")},
            "page": page,
            "sort": opts.sort.as_deref().unwrap_or("timestamp"),
        }}});
        let resp = self
            .send("searchSpans", |t, base| {
                t.post(&format!("{base}/api/v2/spans/events/search"))
                    .json(&body)
                    .idempotent(true)
            })
            .await?;
        let doc = parse_doc("searchSpans", &resp)?;
        let meta = doc.meta.unwrap_or_default();
        let mut spans = Vec::new();
        if let Some(Value::Array(items)) = &doc.data {
            for item in items {
                let raw = item.get("attributes").cloned().unwrap_or_else(|| json!({}));
                spans.push(DatadogSpan {
                    id: str_of(item.get("id")),
                    span: datadog_search_span_to_span(&raw)?,
                    raw,
                });
            }
        }
        Ok(DatadogSpansPage {
            spans,
            next_cursor: meta
                .page
                .as_ref()
                .and_then(|p| p.after.clone())
                .filter(|c| !c.is_empty()),
            status: meta
                .status
                .clone()
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| "done".into()),
            warnings: meta.warnings.clone().unwrap_or_default(),
            request_id: request_id_of(&meta, &resp),
        })
    }

    /// One page of `POST /api/v2/spans/events/search`. Pass `next_cursor` back as `cursor`
    /// with the same absolute `from`/`to` for the next page. Status `timeout` or warnings mark
    /// a partial result.
    pub async fn search_spans_page(&self, opts: &SearchSpansOptions) -> Result<DatadogSpansPage> {
        let (from, to) = Self::window(opts.from.as_ref(), opts.to.as_ref(), DEFAULT_QUERY_WINDOW);
        self.page(opts, &from, &to, opts.cursor.as_deref()).await
    }

    /// Every span matching the search as a stream, following the cursor page by page (same
    /// absolute window for every page). A `TIMEOUT` error is yielded when Datadog reports a
    /// timed-out query that returned no spans (never a silently empty result); the stream ends
    /// after the first error.
    ///
    /// ```no_run
    /// # async fn demo(q: grow_datadog_apm::DatadogSpansQuery) -> grow_datadog_apm::Result<()> {
    /// use futures::TryStreamExt as _;
    /// use grow_datadog_apm::SearchSpansOptions;
    /// let spans: Vec<_> = q
    ///     .search_spans(SearchSpansOptions::query("service:checkout @grow.run:abc"))
    ///     .try_collect()
    ///     .await?;
    /// # Ok(()) }
    /// ```
    pub fn search_spans(
        &self,
        opts: SearchSpansOptions,
    ) -> BoxStream<'static, Result<DatadogSpan>> {
        let (from, to) = Self::window(opts.from.as_ref(), opts.to.as_ref(), DEFAULT_QUERY_WINDOW);
        let cursor = opts.cursor.clone();
        let state = (self.clone(), opts, from, to, cursor, false);
        stream::unfold(state, |(q, opts, from, to, cursor, done)| async move {
            if done {
                return None;
            }
            let result = q.page(&opts, &from, &to, cursor.as_deref()).await;
            let (items, next, stop) = match result {
                Ok(page) if page.status == "timeout" && page.spans.is_empty() => (
                    vec![Err(Error::timeout(
                        "searchSpans: the Datadog query timed out without results",
                    ))],
                    None,
                    true,
                ),
                Ok(page) => {
                    let stop = page.next_cursor.is_none() || page.next_cursor == cursor;
                    (
                        page.spans.into_iter().map(Ok).collect(),
                        page.next_cursor,
                        stop,
                    )
                }
                Err(e) => (vec![Err(e)], None, true),
            };
            Some((stream::iter(items), (q, opts, from, to, next, stop)))
        })
        .flatten()
        .boxed()
    }

    /// `POST /api/v2/spans/analytics/aggregate`: computes `compute` (results keyed `c0`, `c1`,
    /// …) per group of `group_by` (may be empty).
    ///
    /// ```no_run
    /// # async fn demo(q: grow_datadog_apm::DatadogSpansQuery) -> grow_datadog_apm::Result<()> {
    /// use grow_datadog_apm::{aggregation, SpanCompute, SpanGroupBy};
    /// let result = q
    ///     .aggregate_spans(
    ///         &[SpanCompute::new(aggregation::PC90).metric("@duration")],
    ///         &[SpanGroupBy::facet("service")],
    ///         &Default::default(),
    ///     )
    ///     .await?;
    /// # Ok(()) }
    /// ```
    pub async fn aggregate_spans(
        &self,
        compute: &[SpanCompute],
        group_by: &[SpanGroupBy],
        opts: &AggregateSpansOptions,
    ) -> Result<SpanAggregateResult> {
        if compute.is_empty() {
            return Err(Error::config("aggregateSpans: compute must not be empty"));
        }
        let (from, to) = Self::window(opts.from.as_ref(), opts.to.as_ref(), DEFAULT_QUERY_WINDOW);
        let mut attributes = json!({
            "compute": compute,
            "filter": {"query": opts.query.as_deref().filter(|q| !q.is_empty()).unwrap_or("*"), "from": from, "to": to},
            "options": {"timezone": opts.timezone.as_deref().unwrap_or("UTC")},
        });
        if !group_by.is_empty() {
            attributes["group_by"] = serde_json::to_value(group_by)?;
        }
        let body = json!({"data": {"type": "aggregate_request", "attributes": attributes}});
        let resp = self
            .send("aggregateSpans", |t, base| {
                t.post(&format!("{base}/api/v2/spans/analytics/aggregate"))
                    .json(&body)
                    .idempotent(true)
            })
            .await?;
        let doc = parse_doc("aggregateSpans", &resp)?;
        let meta = doc.meta.unwrap_or_default();
        let mut buckets = Vec::new();
        if let Some(Value::Array(items)) = &doc.data {
            for item in items {
                let attrs = item.get("attributes");
                let obj = |k: &str| {
                    attrs
                        .and_then(|a| a.get(k))
                        .and_then(Value::as_object)
                        .cloned()
                        .unwrap_or_default()
                };
                buckets.push(SpanAggregateBucket {
                    by: obj("by").into_iter().collect(),
                    computes: obj("compute")
                        .iter()
                        .map(|(k, v)| (k.clone(), compute_value(v)))
                        .collect(),
                });
            }
        }
        Ok(SpanAggregateResult {
            buckets,
            status: meta
                .status
                .clone()
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| "done".into()),
            warnings: meta.warnings.clone().unwrap_or_default(),
            request_id: request_id_of(&meta, &resp),
        })
    }

    /// [`get_trace`](TraceQueryClient::get_trace) with a search window or, with `preview`, the
    /// trace-by-id endpoint `GET /api/v2/trace/{id}` (full trace, preview API). `None` when
    /// nothing is found.
    pub async fn get_trace_with_options(
        &self,
        trace_id: &str,
        opts: &GetTraceOptions,
    ) -> Result<Option<Trace>> {
        let id = normalize_trace_id(trace_id)?;
        if opts.preview {
            return self.get_trace_preview(&id).await;
        }
        let (from, to) = Self::window(opts.from.as_ref(), opts.to.as_ref(), TRACE_QUERY_WINDOW);
        let search = SearchSpansOptions {
            query: Some(format!("trace_id:{}", datadog_trace_id_string(&id)?)),
            from: Some(DatadogTime::Expr(from)),
            to: Some(DatadogTime::Expr(to)),
            limit: Some(MAX_SPANS_PAGE),
            ..Default::default()
        };
        let mut by_id: HashMap<String, Span> = HashMap::new();
        let mut s = self.search_spans(search);
        while let Some(item) = s.next().await {
            let item = item?;
            by_id.insert(item.span.span_id.clone(), item.span);
        }
        if by_id.is_empty() {
            return Ok(None);
        }
        Ok(Some(Trace::new(id, by_id.into_values().collect())))
    }

    async fn get_trace_preview(&self, id: &str) -> Result<Option<Trace>> {
        let path = format!("/api/v2/trace/{}", trace_id_for_path(id)?);
        let resp = match self
            .send("getTrace", |t, base| t.get(&format!("{base}{path}")))
            .await
        {
            Err(e) if e.code() == ErrorCode::NotFound => return Ok(None),
            other => other?,
        };
        let doc = parse_doc("getTrace", &resp)?;
        let raw_spans = doc
            .data
            .as_ref()
            .and_then(|d| d.get("attributes"))
            .and_then(|a| a.get("spans"))
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let spans = raw_spans
            .iter()
            .map(datadog_trace_span_to_span)
            .collect::<Result<Vec<_>>>()?;
        if spans.is_empty() {
            return Ok(None);
        }
        Ok(Some(Trace::new(id.to_owned(), spans)))
    }

    /// `GET /api/v2/apm/services?filter[env]=…`: sorted names of the services that reported
    /// traces.
    pub async fn list_services_with_options(
        &self,
        opts: &ListServicesOptions,
    ) -> Result<Vec<String>> {
        let env = opts
            .env
            .clone()
            .filter(|e| !e.is_empty())
            .or_else(|| self.config().environment().map(str::to_owned))
            .unwrap_or_else(|| "*".to_owned());
        let resp = self
            .send("listServices", |t, base| {
                t.get(&format!("{base}/api/v2/apm/services"))
                    .query("filter[env]", env.clone())
            })
            .await?;
        let doc = parse_doc("listServices", &resp)?;
        let mut out: Vec<String> = doc
            .data
            .as_ref()
            .and_then(|d| d.get("attributes"))
            .and_then(|a| a.get("services"))
            .and_then(Value::as_array)
            .map(|items| {
                items
                    .iter()
                    .filter_map(|s| s.as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default();
        out.sort();
        Ok(out)
    }

    /// Authenticated round trip `GET /api/v2/validate_keys` (API key + application key);
    /// returns the latency. Invalid keys → `AUTHENTICATION` (Datadog answers 401 or 403).
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        match self
            .send("ping", |t, base| {
                t.get(&format!("{base}/api/v2/validate_keys"))
            })
            .await
        {
            Ok(_) => Ok(started.elapsed()),
            Err(e) if e.code() == ErrorCode::PermissionDenied => {
                let mut out = Error::new(ErrorCode::Authentication, e.message().to_owned())
                    .with_status(403)
                    .with_retryable(false);
                if let Some(id) = e.request_id() {
                    out = out.with_request_id(id);
                }
                Err(out.with_source(e))
            }
            Err(e) => Err(e),
        }
    }
}

fn summary(id: String, spans: Vec<Span>) -> TraceSummary {
    let mut spans = spans;
    sort_spans(&mut spans);
    let ids: BTreeSet<&str> = spans.iter().map(|s| s.span_id.as_str()).collect();
    let root = spans
        .iter()
        .find(|s| s.parent_span_id.as_deref().is_none_or(|p| !ids.contains(p)))
        .unwrap_or(&spans[0]);
    let start = spans.iter().map(|s| s.start_micros).min().unwrap_or(0);
    let end = spans.iter().map(|s| s.end_micros).max().unwrap_or(0);
    TraceSummary {
        trace_id: id,
        root_service_name: Some(root.service_name.clone()).filter(|s| !s.is_empty()),
        root_name: Some(root.name.clone()).filter(|s| !s.is_empty()),
        start_micros: start,
        duration_micros: end.saturating_sub(start),
        span_count: spans.len(),
    }
}

#[async_trait]
impl TraceQueryClient for DatadogSpansQuery {
    /// Reads a trace by searching the indexed spans with `trace_id:<id>` over the last 24 hours
    /// (all pages, merged by span id) — only spans kept by retention filters, so the trace may
    /// be partial. `None` when nothing is found. See
    /// [`get_trace_with_options`](DatadogSpansQuery::get_trace_with_options) for the preview
    /// trace-by-id API.
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.get_trace_with_options(trace_id, &GetTraceOptions::default())
            .await
    }

    /// Traces with spans matching the query (service, span name, `@tag` values, duration) in
    /// `start..end` (default: the last 15 minutes), newest first, up to `limit` distinct trace
    /// ids. Summaries are built from the matching spans only.
    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        let limit = if query.limit == 0 { 20 } else { query.limit };
        let (from, to) = Self::window(
            query.start.map(DatadogTime::At).as_ref(),
            query.end.map(DatadogTime::At).as_ref(),
            DEFAULT_QUERY_WINDOW,
        );
        let page_limit = (limit as u32).saturating_mul(10).clamp(100, MAX_SPANS_PAGE);
        let opts = SearchSpansOptions {
            query: Some(trace_search_query_string(query)),
            limit: Some(page_limit),
            sort: Some("-timestamp".into()),
            ..Default::default()
        };
        let mut traces: HashMap<String, Vec<Span>> = HashMap::new();
        let mut order: Vec<String> = Vec::new();
        let mut cursor: Option<String> = None;
        for _ in 0..SEARCH_TRACES_MAX_PAGES {
            if traces.len() >= limit {
                break;
            }
            let page = self.page(&opts, &from, &to, cursor.as_deref()).await?;
            for s in page.spans {
                let id = s.span.trace_id.clone();
                if let Some(list) = traces.get_mut(&id) {
                    list.push(s.span);
                } else if traces.len() < limit {
                    traces.insert(id.clone(), vec![s.span]);
                    order.push(id);
                }
            }
            match page.next_cursor {
                Some(c) => cursor = Some(c),
                None => break,
            }
        }
        Ok(order
            .into_iter()
            .map(|id| {
                let spans = traces.remove(&id).unwrap_or_default();
                summary(id, spans)
            })
            .collect())
    }

    /// [`list_services_with_options`](DatadogSpansQuery::list_services_with_options) with the
    /// configured environment (else `*`).
    async fn list_services(&self) -> Result<Vec<String>> {
        self.list_services_with_options(&ListServicesOptions::default())
            .await
    }

    /// Distinct span names (Datadog `resource_name`) of `service` in the last 24 hours, via
    /// `aggregate_spans` grouped by `resource_name` (up to 1000), sorted.
    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        let now = SystemTime::now();
        let result = self
            .aggregate_spans(
                &[SpanCompute::new(aggregation::COUNT)],
                &[SpanGroupBy::facet("resource_name").limit(1000)],
                &AggregateSpansOptions {
                    query: Some(format!("service:{}", quote_query_value(service))),
                    from: Some(DatadogTime::At(now - TRACE_QUERY_WINDOW)),
                    to: Some(DatadogTime::At(now)),
                    timezone: None,
                },
            )
            .await?;
        let set: BTreeSet<String> = result
            .buckets
            .iter()
            .map(|b| str_of(b.by.get("resource_name")))
            .filter(|s| !s.is_empty())
            .collect();
        Ok(set.into_iter().collect())
    }

    /// `UNSUPPORTED`: Datadog's public API offers no service dependency graph.
    async fn get_dependencies(&self, _opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        Err(Error::unsupported(
            "traces.getDependencies: the Datadog public API has no service dependency endpoint",
        ))
    }

    /// Polls `get_trace` until the trace has `min_spans` spans. The default 1 s interval of
    /// [`WaitForTraceOptions`] becomes [`DATADOG_WAIT_INTERVAL`] (2 s; Datadog allows 50
    /// searches per minute).
    async fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        let mut opts = opts;
        if opts.interval == WaitForTraceOptions::default().interval || opts.interval.is_zero() {
            opts.interval = DATADOG_WAIT_INTERVAL;
        }
        wait_for_trace(self, trace_id, opts).await
    }
}
