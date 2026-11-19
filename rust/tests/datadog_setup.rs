//! `init_tracing` (OTLP to a mock Agent), the Datadog exporter wrapper (error attributes),
//! log correlation and propagator ordering. One binary, global state used in one test only.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use grow_datadog_apm as sdk;
use opentelemetry::trace::{Status, TraceContextExt};
use opentelemetry::KeyValue;
use opentelemetry_sdk::error::OTelSdkResult;
use opentelemetry_sdk::trace::{SpanData, SpanExporter};
use sdk::core::EnvReader;
use sdk::tracing::{
    init_tracing_with, with_span, PropagatorKind, Protocol, SamplerKind, SpanOptions,
    TracingConfig, TracingHandle,
};
use sdk::*;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

#[derive(Debug, Clone, Default)]
struct Recorder(Arc<Mutex<Vec<SpanData>>>);

impl SpanExporter for Recorder {
    async fn export(&self, batch: Vec<SpanData>) -> OTelSdkResult {
        self.0.lock().unwrap().extend(batch);
        Ok(())
    }
}

impl Recorder {
    fn span(&self, name: &str) -> SpanData {
        self.0
            .lock()
            .unwrap()
            .iter()
            .find(|s| s.name == name)
            .cloned()
            .unwrap_or_else(|| panic!("span {name} not exported"))
    }
}

/// `init_tracing*` sets process-wide state (global provider, propagator, Datadog log defaults):
/// tests that call it must not run in parallel.
static GLOBAL_STATE: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn attr(s: &SpanData, key: &str) -> Option<String> {
    s.attributes
        .iter()
        .find(|kv| kv.key.as_str() == key)
        .map(|kv| kv.value.to_string())
}

#[derive(Debug)]
struct TypeError(&'static str);
impl std::fmt::Display for TypeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

fn recording(service: &str) -> (TracingHandle, Recorder) {
    let rec = Recorder::default();
    let r = rec.clone();
    let handle = init_tracing_with(
        TracingConfig::new(service)
            .with_set_global(false)
            .with_sampler(SamplerKind::AlwaysOn, 1.0),
        move |_| Ok(DatadogSpanExporter::new(r)),
    )
    .unwrap();
    (handle, rec)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn exporter_fills_datadog_error_attributes() {
    let _serial = GLOBAL_STATE.lock().await;
    let (handle, rec) = recording("errors");
    let opts = || SpanOptions::default().tracer(handle.tracer("t", None));
    let _ = with_span("generic-fail", opts(), async {
        Err::<(), _>(TypeError("value out of range"))
    })
    .await;
    let _ = with_span("dd-record", opts(), async {
        record_exception(&TypeError("bad type"));
        Ok::<_, TypeError>(())
    })
    .await;
    let _ = with_span("dd-set-error", opts(), async {
        set_error(&TypeError("explicit failure"));
        Ok::<_, TypeError>(())
    })
    .await;
    let _ = with_span("dd-aliases", opts(), async {
        record_datadog_exception("str error");
        set_datadog_error("str status");
        Ok::<_, TypeError>(())
    })
    .await;
    let _ = with_span("attrs-only", opts(), async {
        set_error_attributes(&TypeError("attrs only"));
        Ok::<_, TypeError>(())
    })
    .await;
    let _ = with_span("status-only", opts(), async {
        sdk::tracing::active_context()
            .span()
            .set_status(Status::error("status text"));
        Ok::<_, TypeError>(())
    })
    .await;
    let _ = with_span("stacktrace-event", opts(), async {
        sdk::tracing::add_event(
            "exception",
            vec![
                KeyValue::new("exception.type", "E"),
                KeyValue::new("exception.stacktrace", "frame 1"),
                KeyValue::new("exception.escaped", true),
            ],
        );
        Ok::<_, TypeError>(())
    })
    .await;
    let _ = with_span("ok", opts(), async { Ok::<_, TypeError>(()) }).await;
    set_error_attributes(&TypeError("no span"));
    handle
        .force_flush(Some(Duration::from_secs(10)))
        .await
        .unwrap();

    let s = rec.span("generic-fail");
    assert!(attr(&s, ATTR_ERROR_TYPE).unwrap().ends_with("TypeError"));
    assert_eq!(
        attr(&s, ATTR_ERROR_MESSAGE).as_deref(),
        Some("value out of range")
    );
    assert_eq!(attr(&s, ATTR_ERROR_STACK), None);
    let s = rec.span("dd-record");
    assert_eq!(attr(&s, ATTR_ERROR_MESSAGE).as_deref(), Some("bad type"));
    assert!(attr(&s, ATTR_ERROR_STACK).is_some());
    assert!(matches!(s.status, Status::Unset));
    let s = rec.span("dd-set-error");
    assert!(matches!(s.status, Status::Error { .. }));
    assert_eq!(
        attr(&s, ATTR_ERROR_MESSAGE).as_deref(),
        Some("explicit failure")
    );
    let s = rec.span("dd-aliases");
    assert!(attr(&s, ATTR_ERROR_MESSAGE).is_some_and(|m| m.starts_with("str ")));
    assert_eq!(
        attr(&rec.span("attrs-only"), ATTR_ERROR_MESSAGE).as_deref(),
        Some("attrs only")
    );
    let s = rec.span("status-only");
    assert_eq!(attr(&s, ATTR_ERROR_MESSAGE).as_deref(), Some("status text"));
    assert_eq!(attr(&s, ATTR_ERROR_TYPE), None);
    let s = rec.span("stacktrace-event");
    assert_eq!(attr(&s, ATTR_ERROR_TYPE).as_deref(), Some("E"));
    assert_eq!(attr(&s, ATTR_ERROR_STACK).as_deref(), Some("frame 1"));
    assert_eq!(attr(&rec.span("ok"), ATTR_ERROR_TYPE), None);

    let attrs = datadog_error_attributes(&TypeError("x"));
    assert_eq!(attrs.len(), 3);
    assert_eq!(attrs[ATTR_ERROR_MESSAGE], "x");
    handle.shutdown().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_tracing_exports_otlp_to_the_agent() {
    let _serial = GLOBAL_STATE.lock().await;
    let otlp = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/traces"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&otlp)
        .await;
    let apm = DatadogApmConfig::builder()
        .otlp_endpoint(otlp.uri())
        .service_version("1.2.3")
        .environment("it")
        .tag("team", "sdk")
        .build()
        .unwrap();
    let h = init_tracing_with_options(
        TracingConfig::new("checkout")
            .with_set_global(false)
            .with_resource_attribute("grow.run", "r1"),
        InitTracingOptions {
            config: Some(apm),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(h.endpoint(), format!("{}/v1/traces", otlp.uri()));
    assert_eq!(h.handle().service_name(), "checkout");
    {
        use opentelemetry::trace::{Span as _, Tracer as _};
        let mut span = h.tracer("it", None).start("root");
        span.end();
    }
    h.force_flush(Some(Duration::from_secs(10))).await.unwrap();
    let received = otlp.received_requests().await.unwrap();
    assert!(!received.is_empty());
    assert_eq!(
        received[0].headers.get("content-type").unwrap(),
        "application/x-protobuf"
    );
    let handle: TracingHandle = h.clone().into_handle();
    handle.shutdown().await.unwrap();
    assert!(h.is_shutdown());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_tracing_options_and_errors() {
    let _serial = GLOBAL_STATE.lock().await;
    // environment: grpc protocol and endpoint, service from DD_SERVICE
    let env = EnvReader::from_map([
        ("GROW_DATADOG_APM_OTLP_PROTOCOL", "grpc"),
        (
            "GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT",
            "http://127.0.0.1:4317",
        ),
        ("DD_SERVICE", "env-svc"),
        ("DD_ENV", "staging"),
        ("DD_VERSION", "2"),
    ]);
    let h = init_tracing_with_options(
        TracingConfig::new("").with_set_global(false),
        InitTracingOptions {
            env: Some(env.clone()),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(h.endpoint(), "http://127.0.0.1:4317");
    assert_eq!(h.service_name(), "env-svc");
    h.shutdown().await.unwrap();

    // explicit grpc protocol with explicit endpoint; explicit propagator list kept
    let h = init_tracing_with_options(
        TracingConfig::new("svc")
            .with_set_global(false)
            .with_protocol(Protocol::Grpc)
            .with_endpoint("http://127.0.0.1:14317")
            .with_propagators(vec![PropagatorKind::B3]),
        InitTracingOptions {
            ignore_env: true,
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(h.endpoint(), "http://127.0.0.1:14317");
    h.shutdown().await.unwrap();
    // explicit grpc without endpoint: Agent host with the gRPC port
    let h = init_tracing_with_options(
        TracingConfig::new("svc")
            .with_set_global(false)
            .with_protocol(Protocol::Grpc),
        InitTracingOptions {
            ignore_env: true,
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(h.endpoint(), "http://127.0.0.1:4317");
    h.shutdown().await.unwrap();

    let native = TracingConfig::new("svc").with_protocol(Protocol::Native);
    assert_eq!(
        init_tracing(native.clone()).unwrap_err().code(),
        ErrorCode::Config
    );
    assert_eq!(
        datadog_exporter_factory(&native).unwrap_err().code(),
        ErrorCode::Config
    );
    assert!(datadog_exporter_factory(&TracingConfig::new("svc")).is_ok());
    let err = init_tracing_with_options(
        TracingConfig::new(" ").with_set_global(false),
        InitTracingOptions {
            ignore_env: true,
            ..Default::default()
        },
    )
    .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    let err = init_tracing_with_options(
        TracingConfig::new("svc"),
        InitTracingOptions {
            env: Some(EnvReader::from_map([("DD_SITE", "http://bad")])),
            ..Default::default()
        },
    )
    .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn global_propagation_and_log_correlation() {
    let _serial = GLOBAL_STATE.lock().await;
    let otlp = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&otlp)
        .await;
    let h = init_tracing_with_options(
        TracingConfig::new("global-svc").with_environment("prod"),
        InitTracingOptions {
            config: Some(
                DatadogApmConfig::builder()
                    .otlp_endpoint(format!("{}/v1/traces", otlp.uri()))
                    .build()
                    .unwrap(),
            ),
            ..Default::default()
        },
    )
    .unwrap();
    let (headers, fields, trace_id, span_id) = with_span("outer", SpanOptions::default(), async {
        let mut headers = HashMap::new();
        sdk::tracing::inject(&mut headers);
        let fields = datadog_log_fields(DatadogLogFieldsOptions::default().version("v9"));
        Ok::<_, TypeError>((
            headers,
            fields,
            sdk::tracing::current_trace_id().unwrap(),
            sdk::tracing::current_span_id().unwrap(),
        ))
    })
    .await
    .unwrap();
    let dd = to_datadog_trace_id(&trace_id).unwrap();
    assert_eq!(headers["x-datadog-trace-id"], dd.low64_decimal);
    assert_eq!(
        headers["x-datadog-parent-id"],
        to_datadog_span_id(&span_id).unwrap()
    );
    assert_eq!(headers["x-datadog-sampling-priority"], "1");
    assert!(headers["traceparent"].contains(&trace_id));
    assert_eq!(
        headers.contains_key("x-datadog-tags"),
        dd.is_128_bit,
        "{headers:?}"
    );
    assert_eq!(
        fields.trace_id.unwrap(),
        datadog_trace_id_string(&trace_id).unwrap()
    );
    assert_eq!(
        fields.span_id.unwrap(),
        to_datadog_span_id(&span_id).unwrap()
    );
    assert_eq!(fields.service.as_deref(), Some("global-svc"));
    assert_eq!(fields.env.as_deref(), Some("prod"));
    assert_eq!(fields.version.as_deref(), Some("v9"));

    // conflicting W3C context through the global composite propagator
    let mut conflict: HashMap<String, String> = headers
        .iter()
        .filter(|(k, _)| k.starts_with("x-datadog"))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    conflict.insert(
        "traceparent".into(),
        "00-11111111111111112222222222222222-0000000000000abc-01".into(),
    );
    let cx = sdk::tracing::extract(&conflict);
    assert_eq!(cx.span().span_context().trace_id().to_string(), trace_id);
    assert_eq!(terminated_context_links(&cx).len(), 1);

    set_datadog_log_defaults(DatadogLogFieldsOptions::default().service("explicit"));
    let f = datadog_log_fields(DatadogLogFieldsOptions::default().env("e").service(""));
    assert_eq!(f.service.as_deref(), Some("explicit"));
    let map = f.to_map();
    assert_eq!(map[LOG_FIELD_SERVICE], "explicit");
    assert_eq!(map[LOG_FIELD_ENV], "e");
    assert!(!map.contains_key(LOG_FIELD_TRACE_ID) && !map.contains_key(LOG_FIELD_SPAN_ID));
    assert!(!map.contains_key(LOG_FIELD_VERSION));
    let json = serde_json::to_value(&f).unwrap();
    assert_eq!(json["dd.service"], "explicit");
    assert!(json.get("dd.trace_id").is_none());
    h.shutdown().await.unwrap();
}

#[test]
fn propagator_ordering() {
    let dd = PropagatorKind::Other("datadog".into());
    let tc = PropagatorKind::TraceContext;
    let bg = PropagatorKind::Baggage;
    let b3 = PropagatorKind::B3;
    assert_eq!(
        order_propagators(&[b3.clone(), dd.clone(), bg.clone(), tc.clone()]),
        vec![b3.clone(), tc.clone(), dd.clone(), bg.clone()]
    );
    assert_eq!(
        order_propagators(&[tc.clone(), dd.clone()]),
        vec![tc.clone(), dd.clone()]
    );
    assert_eq!(
        order_propagators(std::slice::from_ref(&dd)),
        vec![dd.clone()]
    );
    assert_eq!(order_propagators(&[]), Vec::<PropagatorKind>::new());
    assert_eq!(
        DATADOG_DEFAULT_PROPAGATORS,
        ["datadog", "tracecontext", "baggage"]
    );
}
