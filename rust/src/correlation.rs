//! Datadog log correlation. Same field format as ddtrace's log injection: `dd.trace_id` is 32
//! lower-case hex for a 128-bit id and the decimal low 64 bits for a 64-bit id; `dd.span_id` is
//! decimal.

use std::collections::BTreeMap;
use std::sync::RwLock;

use serde::Serialize;

use crate::ids::{datadog_trace_id_string, to_datadog_span_id};
use crate::tracing::current_span_context;

/// Key of [`DatadogLogFields::trace_id`].
pub const LOG_FIELD_TRACE_ID: &str = "dd.trace_id";
/// Key of [`DatadogLogFields::span_id`].
pub const LOG_FIELD_SPAN_ID: &str = "dd.span_id";
/// Key of [`DatadogLogFields::service`].
pub const LOG_FIELD_SERVICE: &str = "dd.service";
/// Key of [`DatadogLogFields::env`].
pub const LOG_FIELD_ENV: &str = "dd.env";
/// Key of [`DatadogLogFields::version`].
pub const LOG_FIELD_VERSION: &str = "dd.version";

/// Explicit unified-service-tagging values for [`datadog_log_fields`] (`None` = not given).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DatadogLogFieldsOptions {
    /// `dd.service`.
    pub service: Option<String>,
    /// `dd.env`.
    pub env: Option<String>,
    /// `dd.version`.
    pub version: Option<String>,
}

impl DatadogLogFieldsOptions {
    /// Sets the service.
    pub fn service(mut self, v: impl Into<String>) -> Self {
        self.service = Some(v.into());
        self
    }
    /// Sets the env.
    pub fn env(mut self, v: impl Into<String>) -> Self {
        self.env = Some(v.into());
        self
    }
    /// Sets the version.
    pub fn version(mut self, v: impl Into<String>) -> Self {
        self.version = Some(v.into());
        self
    }
}

/// Datadog log correlation fields; serializes with the `dd.*` keys (absent fields omitted).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct DatadogLogFields {
    /// `dd.trace_id`.
    #[serde(rename = "dd.trace_id", skip_serializing_if = "Option::is_none")]
    pub trace_id: Option<String>,
    /// `dd.span_id`.
    #[serde(rename = "dd.span_id", skip_serializing_if = "Option::is_none")]
    pub span_id: Option<String>,
    /// `dd.service`.
    #[serde(rename = "dd.service", skip_serializing_if = "Option::is_none")]
    pub service: Option<String>,
    /// `dd.env`.
    #[serde(rename = "dd.env", skip_serializing_if = "Option::is_none")]
    pub env: Option<String>,
    /// `dd.version`.
    #[serde(rename = "dd.version", skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
}

impl DatadogLogFields {
    /// The fields as a map with the `dd.*` keys (absent fields omitted).
    pub fn to_map(&self) -> BTreeMap<String, String> {
        [
            (LOG_FIELD_TRACE_ID, &self.trace_id),
            (LOG_FIELD_SPAN_ID, &self.span_id),
            (LOG_FIELD_SERVICE, &self.service),
            (LOG_FIELD_ENV, &self.env),
            (LOG_FIELD_VERSION, &self.version),
        ]
        .into_iter()
        .filter_map(|(k, v)| v.clone().map(|v| (k.to_owned(), v)))
        .collect()
    }
}

static DEFAULTS: RwLock<DatadogLogFieldsOptions> = RwLock::new(DatadogLogFieldsOptions {
    service: None,
    env: None,
    version: None,
});

/// Sets the service/env/version [`datadog_log_fields`] uses when none are given
/// ([`init_tracing`](crate::init_tracing) calls it with its effective config).
pub fn set_datadog_log_defaults(values: DatadogLogFieldsOptions) {
    *DEFAULTS.write().unwrap_or_else(|e| e.into_inner()) = values;
}

fn non_empty(v: Option<String>) -> Option<String> {
    v.filter(|s| !s.is_empty())
}

/// The Datadog log correlation fields for the active span, ready to merge into any structured
/// log record: `dd.trace_id`, `dd.span_id`, `dd.service`, `dd.env`, `dd.version`. Service, env
/// and version come from `options`, else the last [`init_tracing`](crate::init_tracing) config
/// (or [`set_datadog_log_defaults`]). Without a valid span only the service fields are set;
/// unknown fields are `None`.
///
/// ```no_run
/// let fields = grow_datadog_apm::datadog_log_fields(Default::default());
/// tracing::info!(dd.trace_id = fields.trace_id.as_deref(), "checkout done");
/// ```
pub fn datadog_log_fields(options: DatadogLogFieldsOptions) -> DatadogLogFields {
    let defaults = DEFAULTS.read().unwrap_or_else(|e| e.into_inner()).clone();
    let mut out = DatadogLogFields {
        service: non_empty(options.service).or(non_empty(defaults.service)),
        env: non_empty(options.env).or(non_empty(defaults.env)),
        version: non_empty(options.version).or(non_empty(defaults.version)),
        ..Default::default()
    };
    if let Some(sc) = current_span_context() {
        out.trace_id = datadog_trace_id_string(&sc.trace_id().to_string()).ok();
        out.span_id = to_datadog_span_id(&sc.span_id().to_string()).ok();
    }
    out
}
