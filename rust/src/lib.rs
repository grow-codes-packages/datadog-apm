// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-datadog-apm
//!
//! Rust SDK for [Datadog APM](https://grow-codes-packages.github.io/datadog-apm/) by Grow Codes.
//!
//! Two halves:
//!
//! - **Tracing** to the local Datadog Agent: [`init_tracing`] (OpenTelemetry, OTLP to the Agent,
//!   unified service tagging, [`DatadogPropagator`], Datadog error attributes), the id helpers
//!   ([`to_datadog_trace_id`], …) and log correlation ([`datadog_log_fields`]). The client's
//!   `health`, `ping` and `server_version` use the Agent's `GET /info`.
//! - **Query** via the Datadog SaaS API: [`DatadogSpansQuery`] (spans search, aggregate,
//!   `get_trace`, `list_services`, …; needs `DD_API_KEY` + `DD_APP_KEY`).
//!
//! ```no_run
//! # async fn demo() -> grow_datadog_apm::Result<()> {
//! use grow_datadog_apm::{datadog_log_fields, tracing::{traced, TracingConfig}, DatadogApmClient};
//!
//! let client = DatadogApmClient::from_env()?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
//! traced("charge-card", async {
//!     println!("{:?}", datadog_log_fields(Default::default()));
//!     Ok::<_, std::io::Error>(())
//! })
//! .await
//! .ok();
//! client.close().await; // flushes the spans
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;
mod correlation;
mod ids;
mod mapping;
mod propagator;
mod setup;
mod sites;
mod span_errors;
mod spans;
mod time;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::client::{AgentInfo, DatadogApmClient};
pub use crate::config::{
    otlp_traces_url, parse_datadog_tags, DatadogApmConfig, DatadogApmConfigBuilder,
    AGENT_TRACE_PORT, DEFAULT_PROTOCOL, DEFAULT_URL, OTLP_GRPC_PORT, OTLP_HTTP_PORT,
};
pub use crate::correlation::{
    datadog_log_fields, set_datadog_log_defaults, DatadogLogFields, DatadogLogFieldsOptions,
    LOG_FIELD_ENV, LOG_FIELD_SERVICE, LOG_FIELD_SPAN_ID, LOG_FIELD_TRACE_ID, LOG_FIELD_VERSION,
};
pub use crate::ids::{
    datadog_trace_id_string, from_datadog_span_id, from_datadog_trace_id,
    normalize_datadog_span_id, normalize_datadog_trace_id, to_datadog_span_id, to_datadog_trace_id,
    trace_id_for_path, DatadogTraceId,
};
pub use crate::mapping::{
    datadog_kind, datadog_search_span_to_span, datadog_trace_span_to_span, RawDatadogSpan,
};
pub use crate::propagator::{
    extract_datadog_context, terminated_context, terminated_context_links, DatadogPropagator,
    TerminatedContext, DATADOG_ORIGIN_HEADER, DATADOG_PARENT_ID_HEADER,
    DATADOG_SAMPLING_PRIORITY_HEADER, DATADOG_TAGS_HEADER, DATADOG_TRACE_ID_HEADER,
    PROPAGATOR_DATADOG,
};
pub use crate::setup::{
    datadog_exporter_factory, init_tracing, init_tracing_with_options, order_propagators,
    DatadogTracingHandle, InitTracingOptions, DATADOG_DEFAULT_PROPAGATORS,
};
pub use crate::sites::{
    api_url_for_site, is_datadog_site, normalize_site, validate_site, DATADOG_SITES, DEFAULT_SITE,
};
pub use crate::span_errors::{
    datadog_error_attributes, record_datadog_exception, record_exception, set_datadog_error,
    set_error, set_error_attributes, with_datadog_error_attributes, with_terminated_context_link,
    DatadogSpanExporter, ATTR_ERROR_MESSAGE, ATTR_ERROR_STACK, ATTR_ERROR_TYPE,
};
pub use crate::spans::{
    aggregation, quote_query_value, trace_search_query_string, AggregateSpansOptions,
    DatadogQueryWarning, DatadogSpan, DatadogSpansPage, DatadogSpansQuery, DatadogTime,
    GetTraceOptions, ListServicesOptions, SearchSpansOptions, SpanAggregateBucket,
    SpanAggregateResult, SpanCompute, SpanComputeValue, SpanGroupBy, SpanGroupBySort,
    SpanHistogram, SpanTimeseriesPoint, DATADOG_WAIT_INTERVAL,
};

/// Service-prefixed alias of [`Error`].
pub type DatadogApmError = Error;
