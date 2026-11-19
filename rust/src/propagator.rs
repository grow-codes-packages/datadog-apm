//! Datadog header propagation (`x-datadog-*`) with ddtrace's conflict rules. Implemented from
//! Datadog's documented header format; no ddtrace code is used.

use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};

use opentelemetry::propagation::TextMapPropagator;
use opentelemetry::propagation::{text_map_propagator::FieldIter, Extractor, Injector};
use opentelemetry::trace::{
    Link, SpanContext, SpanId, TraceContextExt, TraceFlags, TraceId, TraceState,
};
use opentelemetry::{Context, KeyValue};

use crate::ids::{from_datadog_span_id, from_datadog_trace_id, is_digits, is_hex16};

/// Carries the low 64 bits of the trace id, unsigned decimal.
pub const DATADOG_TRACE_ID_HEADER: &str = "x-datadog-trace-id";
/// Carries the span id, unsigned decimal.
pub const DATADOG_PARENT_ID_HEADER: &str = "x-datadog-parent-id";
/// `1` (sampled) or `0` (not sampled).
pub const DATADOG_SAMPLING_PRIORITY_HEADER: &str = "x-datadog-sampling-priority";
/// Propagated tags, e.g. `_dd.p.tid=<high 64 bits hex>`.
pub const DATADOG_TAGS_HEADER: &str = "x-datadog-tags";
/// Origin of the trace (synthetics, rum, …).
pub const DATADOG_ORIGIN_HEADER: &str = "x-datadog-origin";
/// Propagator name of [`DatadogPropagator`] in `TracingConfig::propagators`
/// (`PropagatorKind::Other("datadog")`).
pub const PROPAGATOR_DATADOG: &str = "datadog";

/// Most terminated contexts remembered for [`DatadogSpanExporter`](crate::DatadogSpanExporter).
const TERMINATED_CAPACITY: usize = 1024;

/// A W3C context that lost against Datadog headers in [`DatadogPropagator`]'s extract.
#[derive(Debug, Clone, PartialEq)]
pub struct TerminatedContext {
    /// The W3C span context from `traceparent`.
    pub span_context: SpanContext,
    /// Span id (16 hex) of the Datadog context that won (the parent of the next span).
    pub winner_span_id: String,
}

fn header<'a>(extractor: &'a dyn Extractor, name: &str) -> Option<&'a str> {
    extractor.get(name).map(str::trim).filter(|v| !v.is_empty())
}

fn parse_tid(tags: Option<&str>) -> Option<String> {
    for part in tags?.split(',') {
        let Some((k, v)) = part.split_once('=') else {
            continue;
        };
        if k.trim() != "_dd.p.tid" {
            continue;
        }
        let v = v.trim().to_ascii_lowercase();
        return is_hex16(&v).then_some(v);
    }
    None
}

fn parse_traceparent(value: Option<&str>) -> Option<SpanContext> {
    let v = value?.to_ascii_lowercase();
    let parts: Vec<&str> = v.split('-').collect();
    if parts.len() < 4 {
        return None;
    }
    let (version, trace, span, flags) = (parts[0], parts[1], parts[2], parts[3]);
    let hex = |s: &str, n: usize| s.len() == n && crate::ids::is_hex(s);
    if !hex(version, 2) || !hex(trace, 32) || !hex(span, 16) || !hex(flags, 2) || version == "ff" {
        return None;
    }
    let flags = u8::from_str_radix(flags, 16).ok()? & TraceFlags::SAMPLED.to_u8();
    let sc = SpanContext::new(
        TraceId::from_hex(trace).ok()?,
        SpanId::from_hex(span).ok()?,
        TraceFlags::new(flags),
        true,
        TraceState::default(),
    );
    sc.is_valid().then_some(sc)
}

fn is_short_digits(s: &str) -> bool {
    s.len() <= 20 && is_digits(s)
}

/// Reads the Datadog headers of a carrier into a remote span context; `None` when they are
/// missing or invalid. A missing sampling priority counts as sampled.
pub fn extract_datadog_context(extractor: &dyn Extractor) -> Option<SpanContext> {
    let trace_raw = header(extractor, DATADOG_TRACE_ID_HEADER)?;
    let parent_raw = header(extractor, DATADOG_PARENT_ID_HEADER)?;
    if !is_short_digits(trace_raw) || !is_short_digits(parent_raw) {
        return None;
    }
    let tid = parse_tid(header(extractor, DATADOG_TAGS_HEADER));
    let trace_hex = from_datadog_trace_id(trace_raw, tid.as_deref()).ok()?;
    let span_hex = from_datadog_span_id(parent_raw).ok()?;
    let mut flags = TraceFlags::SAMPLED;
    if let Some(p) = header(extractor, DATADOG_SAMPLING_PRIORITY_HEADER) {
        if p.parse::<i64>().is_ok_and(|n| n <= 0) {
            flags = TraceFlags::default();
        }
    }
    let sc = SpanContext::new(
        TraceId::from_hex(&trace_hex).ok()?,
        SpanId::from_hex(&span_hex).ok()?,
        flags,
        true,
        TraceState::default(),
    );
    sc.is_valid().then_some(sc)
}

/// Datadog propagator: injects and extracts `x-datadog-trace-id` (low 64 bits, decimal),
/// `x-datadog-parent-id` (decimal), `x-datadog-sampling-priority` (`1`/`0`) and, for 128-bit
/// ids, `x-datadog-tags: _dd.p.tid=<high 64 bits hex>`.
///
/// Conflict rules on extract (ddtrace's behaviour with style order `datadog,tracecontext`): when
/// the carrier also holds a valid `traceparent`,
/// - same trace id → the W3C parent id wins (the context from `traceparent` is kept),
/// - different trace id → the Datadog context wins and the W3C context is remembered in the
///   returned context ([`terminated_context`]); [`DatadogSpanExporter`](crate::DatadogSpanExporter)
///   (installed by [`init_tracing`](crate::init_tracing)) adds it to the spans started from that
///   context as a link with `reason=terminated_context` (see [`terminated_context_links`]).
///
/// The rules apply whatever position `datadog` has in the propagator list; put `tracecontext`
/// before it ([`order_propagators`](crate::order_propagators)) so an existing `tracestate` is kept.
#[derive(Debug, Clone)]
pub struct DatadogPropagator {
    fields: Vec<String>,
}

impl Default for DatadogPropagator {
    fn default() -> Self {
        DatadogPropagator::new()
    }
}

impl DatadogPropagator {
    /// A new propagator.
    pub fn new() -> Self {
        DatadogPropagator {
            fields: [
                DATADOG_TRACE_ID_HEADER,
                DATADOG_PARENT_ID_HEADER,
                DATADOG_SAMPLING_PRIORITY_HEADER,
                DATADOG_TAGS_HEADER,
            ]
            .iter()
            .map(|s| s.to_string())
            .collect(),
        }
    }
}

impl TextMapPropagator for DatadogPropagator {
    fn inject_context(&self, cx: &Context, injector: &mut dyn Injector) {
        let span = cx.span();
        let sc = span.span_context();
        if !sc.is_valid() {
            return;
        }
        let v = u128::from_be_bytes(sc.trace_id().to_bytes());
        let high = (v >> 64) as u64;
        injector.set(DATADOG_TRACE_ID_HEADER, (v as u64).to_string());
        injector.set(
            DATADOG_PARENT_ID_HEADER,
            u64::from_be_bytes(sc.span_id().to_bytes()).to_string(),
        );
        let priority = if sc.is_sampled() { "1" } else { "0" };
        injector.set(DATADOG_SAMPLING_PRIORITY_HEADER, priority.to_owned());
        if high != 0 {
            injector.set(DATADOG_TAGS_HEADER, format!("_dd.p.tid={high:016x}"));
        }
    }

    fn extract_with_context(&self, cx: &Context, extractor: &dyn Extractor) -> Context {
        let Some(dd) = extract_datadog_context(extractor) else {
            return cx.clone();
        };
        let Some(w3c) = parse_traceparent(header(extractor, "traceparent")) else {
            return cx.with_remote_span_context(dd);
        };
        if w3c.trace_id() == dd.trace_id() {
            // Same trace: ddtrace takes the W3C parent id; keep tracestate set by the W3C propagator.
            let current = cx.span().span_context().clone();
            if current.is_valid()
                && current.trace_id() == w3c.trace_id()
                && current.span_id() == w3c.span_id()
            {
                return cx.with_remote_span_context(current);
            }
            let keep = SpanContext::new(
                w3c.trace_id(),
                w3c.span_id(),
                dd.trace_flags(),
                true,
                w3c.trace_state().clone(),
            );
            return cx.with_remote_span_context(keep);
        }
        let terminated = TerminatedContext {
            span_context: w3c,
            winner_span_id: dd.span_id().to_string(),
        };
        remember_terminated(dd.trace_id(), dd.span_id(), &terminated.span_context);
        cx.with_remote_span_context(dd).with_value(terminated)
    }

    fn fields(&self) -> FieldIter<'_> {
        FieldIter::new(&self.fields)
    }
}

/// The W3C context that lost against Datadog headers during extract, if any.
pub fn terminated_context(cx: &Context) -> Option<TerminatedContext> {
    cx.get::<TerminatedContext>().cloned()
}

fn terminated_link(span_context: SpanContext) -> Link {
    Link::new(
        span_context,
        vec![
            KeyValue::new("reason", "terminated_context"),
            KeyValue::new("context_headers", "tracecontext"),
        ],
        0,
    )
}

/// Span links for a span started from an extracted context: one link to the terminated W3C
/// context (`reason=terminated_context`, `context_headers=tracecontext`), or none. Pass them to
/// `SpanOptions::link` when you export spans without [`init_tracing`](crate::init_tracing)'s
/// exporter.
pub fn terminated_context_links(cx: &Context) -> Vec<Link> {
    terminated_context(cx)
        .map(|t| vec![terminated_link(t.span_context)])
        .unwrap_or_default()
}

type TerminatedEntry = (TraceId, SpanId, SpanContext);

fn registry() -> &'static Mutex<VecDeque<TerminatedEntry>> {
    static REGISTRY: OnceLock<Mutex<VecDeque<TerminatedEntry>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(VecDeque::new()))
}

fn remember_terminated(trace_id: TraceId, winner: SpanId, w3c: &SpanContext) {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    if reg
        .iter()
        .any(|(t, s, c)| *t == trace_id && *s == winner && c == w3c)
    {
        return;
    }
    if reg.len() >= TERMINATED_CAPACITY {
        reg.pop_front();
    }
    reg.push_back((trace_id, winner, w3c.clone()));
}

/// The terminated-context link for a span whose remote parent is a winning Datadog context.
pub(crate) fn terminated_link_for(trace_id: TraceId, parent: SpanId) -> Option<Link> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    reg.iter()
        .rev()
        .find(|(t, s, _)| *t == trace_id && *s == parent)
        .map(|(_, _, c)| terminated_link(c.clone()))
}
