//! `init_tracing` for Datadog — the OpenTelemetry SDK exporting OTLP to the Datadog Agent
//! (HTTP/protobuf on 4318 by default, or gRPC on 4317), unified service tagging, the Datadog +
//! W3C propagators, the Datadog error attributes and the terminated-context links.

use std::collections::HashMap;
use std::ops::Deref;

use crate::config::{otlp_traces_url, DatadogApmConfig, DEFAULT_PROTOCOL};
use crate::core::{EnvReader, Error, Result};
use crate::correlation::{set_datadog_log_defaults, DatadogLogFieldsOptions};
use crate::propagator::{DatadogPropagator, PROPAGATOR_DATADOG};
use crate::span_errors::DatadogSpanExporter;
use crate::tracing::{
    init_tracing_with_propagators, otlp_exporter, AttrValue, BoxedPropagator, PropagatorKind,
    Protocol, TracingConfig, TracingHandle,
};

/// The default propagators of this package: Datadog headers, W3C trace context and baggage.
pub const DATADOG_DEFAULT_PROPAGATORS: &[&str] = &["datadog", "tracecontext", "baggage"];

fn default_propagators() -> Vec<PropagatorKind> {
    DATADOG_DEFAULT_PROPAGATORS
        .iter()
        .map(|n| n.parse().expect("valid propagator name"))
        .collect()
}

/// Puts `tracecontext` before `datadog` so the Datadog propagator sees the W3C result and
/// applies the conflict rules (Datadog wins); every other propagator keeps its position.
///
/// ```
/// use grow_datadog_apm::{order_propagators, tracing::PropagatorKind};
/// let ordered = order_propagators(&[
///     PropagatorKind::Other("datadog".into()),
///     PropagatorKind::TraceContext,
///     PropagatorKind::Baggage,
/// ]);
/// assert_eq!(ordered[0], PropagatorKind::TraceContext);
/// assert_eq!(ordered[1], PropagatorKind::Other("datadog".into()));
/// ```
pub fn order_propagators(kinds: &[PropagatorKind]) -> Vec<PropagatorKind> {
    let is_dd =
        |k: &PropagatorKind| matches!(k, PropagatorKind::Other(n) if n == PROPAGATOR_DATADOG);
    let dd = kinds.iter().position(is_dd);
    let tc = kinds
        .iter()
        .position(|k| *k == PropagatorKind::TraceContext);
    match (dd, tc) {
        (Some(dd), Some(tc)) if tc > dd => {
            let mut out = kinds[..dd].to_vec();
            out.push(PropagatorKind::TraceContext);
            out.extend(
                kinds[dd..]
                    .iter()
                    .enumerate()
                    .filter(|(i, _)| i + dd != tc)
                    .map(|(_, k)| k.clone()),
            );
            out
        }
        _ => kinds.to_vec(),
    }
}

/// The exporter factory of this package: OTLP `http/protobuf` or `grpc` to `config.endpoint`
/// (default: the local Agent), wrapped in a [`DatadogSpanExporter`]. `native` is a `CONFIG`
/// error (the package exports OTLP only).
pub fn datadog_exporter_factory(
    config: &TracingConfig,
) -> Result<DatadogSpanExporter<opentelemetry_otlp::SpanExporter>> {
    if config.protocol == Protocol::Native {
        return Err(native_error());
    }
    let mut config = config.clone();
    let endpoint = match &config.endpoint {
        Some(e) => otlp_traces_url(config.protocol, e),
        None => DatadogApmConfig::builder()
            .protocol(config.protocol)
            .build()?
            .otlp_traces_endpoint(),
    };
    config.endpoint = Some(endpoint);
    Ok(DatadogSpanExporter::new(otlp_exporter(&config)?))
}

fn native_error() -> Error {
    Error::config("tracing: the Datadog package exports OTLP; use http/protobuf or grpc")
}

/// Extra options of [`init_tracing_with_options`].
#[derive(Debug, Clone, Default)]
pub struct InitTracingOptions {
    /// Agent/service settings beneath the tracing config (used as is). `None`: read from the
    /// environment ([`DatadogApmConfig::from_env`] rules).
    pub config: Option<DatadogApmConfig>,
    /// Replaces the process environment when the config is read from the environment.
    pub env: Option<EnvReader>,
    /// Builds the default config without reading any environment.
    pub ignore_env: bool,
}

/// A running Datadog tracing setup. Dereferences to the generic [`TracingHandle`]
/// (`tracer`, `tracer_provider`, `force_flush`, `shutdown`, …). Cheap to clone.
#[derive(Debug, Clone)]
pub struct DatadogTracingHandle {
    handle: TracingHandle,
    endpoint: String,
}

impl DatadogTracingHandle {
    /// The OTLP traces URL spans are exported to.
    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }

    /// The generic tracing handle.
    pub fn handle(&self) -> &TracingHandle {
        &self.handle
    }

    /// Unwraps the generic tracing handle.
    pub fn into_handle(self) -> TracingHandle {
        self.handle
    }
}

impl Deref for DatadogTracingHandle {
    type Target = TracingHandle;
    fn deref(&self) -> &TracingHandle {
        &self.handle
    }
}

/// Sets up OpenTelemetry tracing that exports to the Datadog Agent (SPEC §5):
///
/// - OTLP `http/protobuf` to `http://<agent>:4318/v1/traces` by default, or `grpc` to
///   `http://<agent>:4317` (the Agent needs its OTLP receiver enabled);
/// - unified service tagging: service name / version / environment → resource
///   `service.name` / `service.version` / `deployment.environment.name` (Datadog `service`,
///   `version`, `env`), plus `DD_TAGS` as resource attributes;
/// - propagators `datadog`, `tracecontext`, `baggage` by default (Datadog headers win on
///   conflict);
/// - the Datadog error attributes and the terminated-context links ([`DatadogSpanExporter`]).
///
/// Unset values come from the environment ([`DatadogApmConfig::from_env`]: `DD_SERVICE`,
/// `DD_VERSION`, `DD_ENV`, `DD_TAGS`, `DD_AGENT_HOST`, `GROW_DATADOG_APM_*`,
/// `OTEL_EXPORTER_OTLP_*`). An empty `service_name` means "from the environment"; the template
/// defaults of `TracingConfig` (protocol `http/protobuf`, propagators `tracecontext,baggage`)
/// mean "the package default" (the configured protocol, the Datadog propagators). Must be called
/// inside a tokio runtime.
///
/// ```no_run
/// # async fn demo() -> grow_datadog_apm::Result<()> {
/// use grow_datadog_apm::{init_tracing, tracing::TracingConfig};
/// let handle = init_tracing(TracingConfig::new("checkout").with_environment("prod"))?;
/// handle.shutdown().await?;
/// # Ok(()) }
/// ```
pub fn init_tracing(config: TracingConfig) -> Result<DatadogTracingHandle> {
    init_tracing_with_options(config, InitTracingOptions::default())
}

fn apm_config_for(protocol: Protocol, opts: InitTracingOptions) -> Result<DatadogApmConfig> {
    if let Some(c) = opts.config {
        c.validate()?;
        return Ok(c);
    }
    let mut builder = DatadogApmConfig::builder();
    if protocol == Protocol::Grpc {
        builder = builder.protocol(protocol);
    }
    if opts.ignore_env {
        return builder.build();
    }
    match opts.env {
        Some(env) => builder.build_with_env(&env),
        None => builder.build_from_env(),
    }
}

/// [`init_tracing`] with an explicit [`DatadogApmConfig`] or environment.
pub fn init_tracing_with_options(
    config: TracingConfig,
    opts: InitTracingOptions,
) -> Result<DatadogTracingHandle> {
    if config.protocol == Protocol::Native {
        return Err(native_error());
    }
    let apm = apm_config_for(config.protocol, opts)?;
    let protocol = if config.protocol == DEFAULT_PROTOCOL {
        apm.protocol()
    } else {
        config.protocol
    };
    let endpoint = match &config.endpoint {
        Some(e) => otlp_traces_url(protocol, e),
        None => apm.otlp_traces_endpoint_for(protocol),
    };
    let mut effective = config.clone();
    effective.protocol = protocol;
    effective.endpoint = Some(endpoint.clone());
    if effective.service_name.trim().is_empty() {
        effective.service_name = apm.service_name().unwrap_or_default().to_owned();
    }
    if effective.service_version.is_none() {
        effective.service_version = apm.service_version().map(str::to_owned);
    }
    if effective.environment.is_none() {
        effective.environment = apm.environment().map(str::to_owned);
    }
    let template_default = [PropagatorKind::TraceContext, PropagatorKind::Baggage];
    let propagators = if config.propagators.is_empty() || config.propagators == template_default {
        default_propagators()
    } else {
        config.propagators.clone()
    };
    effective.propagators = order_propagators(&propagators);
    let mut attrs: crate::tracing::Attributes = apm
        .tags()
        .iter()
        .map(|(k, v)| (k.clone(), AttrValue::String(v.clone())))
        .collect();
    attrs.extend(config.resource_attributes.clone());
    if let Some(env) = &effective.environment {
        attrs
            .entry("deployment.environment.name".to_owned())
            .or_insert_with(|| AttrValue::String(env.clone()));
    }
    effective.resource_attributes = attrs;

    let mut extra: HashMap<String, BoxedPropagator> = HashMap::new();
    extra.insert(
        PROPAGATOR_DATADOG.to_owned(),
        Box::new(DatadogPropagator::new()),
    );
    let handle = init_tracing_with_propagators(effective.clone(), datadog_exporter_factory, extra)?;
    set_datadog_log_defaults(DatadogLogFieldsOptions {
        service: Some(effective.service_name.clone()),
        env: effective.environment.clone(),
        version: effective.service_version.clone(),
    });
    Ok(DatadogTracingHandle { handle, endpoint })
}
