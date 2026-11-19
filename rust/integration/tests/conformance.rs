// Created by sdk-kit 0.1.0; owned by this repository.
//! Integration suite against the real Datadog Agent (Docker): client round trips and the
//! conformance scenarios for `core` and `tracing`. The tracing query side is the trace-reader
//! sidecar (what the Agent forwarded to fakeintake), because the Datadog query API is SaaS-only.

use std::sync::Arc;

use grow_datadog_apm as sdk;
use grow_datadog_apm_integration as support;
use sdk::testing::tracing::{TracingHandler, TracingTarget};
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::tracing::{SamplerKind, TracingConfig};
use sdk::{ErrorCode, InitTracingOptions};
use support::{conformance_dir, env, service_available, TraceReaderQuery};

use sdk::blocking::DatadogApmClient as BlockingClient;

use sdk::DatadogApmClient as Client;
use sdk::DatadogApmConfig as Config;

fn client() -> Client {
    Client::new(Config::from_env().expect("config from env")).expect("client")
}

#[tokio::test]
async fn client_round_trip() {
    if !service_available() {
        return;
    }
    let c = client();
    let health = c.health().await.expect("health");
    assert!(health.healthy, "service not healthy: {health:?}");
    assert_eq!(health.details["version"], env("DD_AGENT_VERSION"));
    c.ping().await.expect("ping");
    assert_eq!(c.server_version().await.unwrap(), env("DD_AGENT_VERSION"));
    let info = c.agent_info().await.unwrap();
    assert!(info.endpoints.iter().any(|e| e == "/v0.4/traces"));
    // the query API is SaaS-only (unit-tested); constructing it needs both keys
    assert_eq!(
        c.spans().unwrap().config().api_url(),
        "https://api.datadoghq.com"
    );
    c.close().await;
    c.close().await;
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);

    let unreachable = Client::new(
        Config::builder()
            .url("http://127.0.0.1:1")
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(unreachable.health().await.unwrap().status, "unreachable");
}

#[test]
fn config_from_bootstrap_env() {
    if !service_available() {
        return;
    }
    let cfg = Config::from_env().unwrap();
    assert_eq!(
        cfg.url().as_str().trim_end_matches('/'),
        env("GROW_DATADOG_APM_URL")
    );
    assert_eq!(
        cfg.otlp_traces_endpoint(),
        format!("{}/v1/traces", env("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT"))
    );
    assert_eq!(
        cfg.otlp_traces_endpoint_for(sdk::tracing::Protocol::Grpc),
        format!("http://{}:4317", cfg.url().host_str().unwrap())
    );
    assert_eq!(cfg.site(), "datadoghq.com");
    assert!(cfg.has_query_credentials());
    let key = env("DD_API_KEY");
    assert!(
        !format!("{cfg:?}").contains(&key),
        "API key must be redacted"
    );
    let grpc = Config::builder()
        .protocol(sdk::tracing::Protocol::Grpc)
        .build_from_env()
        .unwrap();
    assert_eq!(
        grpc.otlp_traces_endpoint(),
        env("GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT")
    );
    assert!(sdk::DatadogSpansQuery::from_env().is_ok());
}

#[test]
fn blocking_round_trip() {
    if !service_available() {
        return;
    }
    let c = BlockingClient::from_env().expect("client");
    assert!(c.health().expect("health").healthy);
    c.ping().expect("ping");
    assert_eq!(c.server_version().unwrap(), env("DD_AGENT_VERSION"));
    assert_eq!(c.agent_info().unwrap().version, env("DD_AGENT_VERSION"));
    c.close();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance() {
    if !service_available() {
        return;
    }
    let service = "grow-conformance-rust".to_owned();
    let name = service.clone();
    let tracing = TracingHandler::new(move || {
        let name = name.clone();
        async move {
            let h = sdk::init_tracing_with_options(
                TracingConfig::new(name)
                    .with_set_global(false)
                    .with_sampler(SamplerKind::AlwaysOn, 1.0),
                InitTracingOptions::default(),
            )?;
            Ok(TracingTarget {
                handle: h.into_handle(),
                query: Some(Arc::new(TraceReaderQuery)),
            })
        }
    });
    let report = ConformanceRunner::new(conformance_dir())
        // "query" and "services" are served by the trace-reader (docs/compatibility.md).
        .capabilities(["version", "query", "services"])
        .var("serviceName", service)
        .handler(CoreHandler::new(Client::from_env))
        .handler(tracing)
        .run()
        .await
        .expect("scenarios load");
    println!("{report}");
    report.assert_success();
    assert!(report.passed() >= 3, "{report}");
}
