//! Datadog error attributes and the exporter that fills them.
//!
//! The Datadog Agent keeps an OTLP exception only inside the `events` JSON and does not fill
//! `error.type` / `error.stack`, so this package sets the `error.type`, `error.message` and
//! `error.stack` span attributes itself, which Datadog Error Tracking reads.

use std::backtrace::Backtrace;
use std::collections::BTreeMap;
use std::fmt;
use std::future::Future;
use std::time::Duration;

use opentelemetry::trace::{Status, TraceContextExt};
use opentelemetry::{Key, KeyValue, Value};
use opentelemetry_sdk::error::OTelSdkResult;
use opentelemetry_sdk::trace::{SpanData, SpanExporter};
use opentelemetry_sdk::Resource;

use crate::propagator::terminated_link_for;
use crate::tracing::active_context;

/// The `error.type` span attribute (error type name).
pub const ATTR_ERROR_TYPE: &str = "error.type";
/// The `error.message` span attribute.
pub const ATTR_ERROR_MESSAGE: &str = "error.message";
/// The `error.stack` span attribute.
pub const ATTR_ERROR_STACK: &str = "error.stack";

/// The Datadog error attributes for `err`: `error.type` (the Rust type name of `E`),
/// `error.message` (`err.to_string()`) and `error.stack` (a backtrace of the caller; Rust errors
/// carry no stack).
pub fn datadog_error_attributes<E: fmt::Display + ?Sized>(err: &E) -> BTreeMap<String, String> {
    BTreeMap::from([
        (
            ATTR_ERROR_TYPE.to_owned(),
            std::any::type_name::<E>().to_owned(),
        ),
        (ATTR_ERROR_MESSAGE.to_owned(), err.to_string()),
        (
            ATTR_ERROR_STACK.to_owned(),
            Backtrace::force_capture().to_string(),
        ),
    ])
}

/// Sets the Datadog error attributes (`error.type`, `error.message`, `error.stack`) for `err` on
/// the active span. No-op without a recording span.
pub fn set_error_attributes<E: fmt::Display + ?Sized>(err: &E) {
    let cx = active_context();
    let span = cx.span();
    if !span.is_recording() {
        return;
    }
    span.set_attributes(
        datadog_error_attributes(err)
            .into_iter()
            .map(|(k, v)| KeyValue::new(k, v)),
    );
}

/// Records `err` as an `exception` event on the active span (like
/// [`tracing::record_exception`](crate::tracing::record_exception)) and also sets the Datadog
/// error attributes. The span status is unchanged.
///
/// The generic helpers (`tracing::record_exception`, `tracing::set_error`, `with_span`,
/// `traced`, the HTTP adapters) also get `error.type` / `error.message` when the spans are
/// exported through [`init_tracing`](crate::init_tracing): its exporter fills them from the
/// exception event.
pub fn record_exception<E: fmt::Display + ?Sized>(err: &E) {
    crate::tracing::record_exception(err);
    set_error_attributes(err);
}

/// Sets `ERROR` status (with the error message) on the active span, records the exception
/// event (like [`tracing::set_error`](crate::tracing::set_error)) and sets the Datadog error
/// attributes.
pub fn set_error<E: fmt::Display + ?Sized>(err: &E) {
    crate::tracing::set_error(err);
    set_error_attributes(err);
}

/// Alias of [`record_exception`] (Go name `RecordDatadogException`).
pub fn record_datadog_exception<E: fmt::Display + ?Sized>(err: &E) {
    record_exception(err);
}

/// Alias of [`set_error`] (Go name `SetDatadogError`).
pub fn set_datadog_error<E: fmt::Display + ?Sized>(err: &E) {
    set_error(err);
}

fn string_of(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.as_str().to_owned()),
        _ => None,
    }
}

/// Adds the Datadog error attributes to `span` when it has none yet: from its last `exception`
/// event (`exception.type`, `exception.message`, `exception.stacktrace`) or, for an `ERROR`
/// status without an event, `error.message` from the status description.
pub fn with_datadog_error_attributes(mut span: SpanData) -> SpanData {
    let has = |k: &str| span.attributes.iter().any(|kv| kv.key.as_str() == k);
    if has(ATTR_ERROR_TYPE) || has(ATTR_ERROR_MESSAGE) {
        return span;
    }
    let mut add: BTreeMap<&'static str, String> = BTreeMap::new();
    if let Some(event) = span
        .events
        .events
        .iter()
        .rev()
        .find(|e| e.name == "exception")
    {
        for kv in &event.attributes {
            let target = match kv.key.as_str() {
                "exception.type" => ATTR_ERROR_TYPE,
                "exception.message" => ATTR_ERROR_MESSAGE,
                "exception.stacktrace" => ATTR_ERROR_STACK,
                _ => continue,
            };
            if let Some(s) = string_of(&kv.value) {
                add.insert(target, s);
            }
        }
    }
    if let Status::Error { description } = &span.status {
        if !add.contains_key(ATTR_ERROR_MESSAGE) && !description.is_empty() {
            add.insert(ATTR_ERROR_MESSAGE, description.to_string());
        }
    }
    for k in [ATTR_ERROR_TYPE, ATTR_ERROR_MESSAGE, ATTR_ERROR_STACK] {
        if let Some(v) = add.remove(k) {
            span.attributes
                .push(KeyValue::new(Key::from_static_str(k), v));
        }
    }
    span
}

/// Adds the terminated-context link (see
/// [`terminated_context_links`](crate::terminated_context_links)) to a span whose remote parent
/// is a Datadog context that won against a conflicting W3C context.
pub fn with_terminated_context_link(mut span: SpanData) -> SpanData {
    if !span.parent_span_is_remote {
        return span;
    }
    if let Some(link) = terminated_link_for(span.span_context.trace_id(), span.parent_span_id) {
        let present = span
            .links
            .links
            .iter()
            .any(|l| l.span_context == link.span_context);
        if !present {
            span.links.links.push(link);
        }
    }
    span
}

/// Span exporter wrapper installed by [`init_tracing`](crate::init_tracing): fills the Datadog
/// error attributes ([`with_datadog_error_attributes`]) and adds the terminated-context links
/// ([`with_terminated_context_link`]) before handing the spans to the wrapped exporter. It
/// covers `with_span`, `traced`, the HTTP adapters and plain OpenTelemetry calls (the Rust SDK
/// has no span processor hook that may still change an ending span).
#[derive(Debug)]
pub struct DatadogSpanExporter<E> {
    inner: E,
}

impl<E: SpanExporter> DatadogSpanExporter<E> {
    /// Wraps `inner`.
    pub fn new(inner: E) -> Self {
        DatadogSpanExporter { inner }
    }

    /// The wrapped exporter.
    pub fn inner(&self) -> &E {
        &self.inner
    }
}

impl<E: SpanExporter> SpanExporter for DatadogSpanExporter<E> {
    fn export(&self, batch: Vec<SpanData>) -> impl Future<Output = OTelSdkResult> + Send {
        let batch = batch
            .into_iter()
            .map(|s| with_terminated_context_link(with_datadog_error_attributes(s)))
            .collect();
        self.inner.export(batch)
    }

    fn shutdown_with_timeout(&mut self, timeout: Duration) -> OTelSdkResult {
        self.inner.shutdown_with_timeout(timeout)
    }

    fn force_flush(&mut self) -> OTelSdkResult {
        self.inner.force_flush()
    }

    fn set_resource(&mut self, resource: &Resource) {
        self.inner.set_resource(resource);
    }
}
