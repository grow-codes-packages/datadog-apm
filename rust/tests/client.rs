//! `DatadogApmClient` against a mock Datadog Agent (`GET /info`, recorded fixture).

mod common;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use grow_datadog_apm as sdk;
use sdk::tracing::TracingConfig;
use sdk::{DatadogApmClient as Client, DatadogApmConfig as Config, ErrorCode};
use serde_json::Value;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn fixture(name: &str) -> Value {
    let text = std::fs::read_to_string(format!(
        "{}/tests/fixtures/dd/{name}",
        env!("CARGO_MANIFEST_DIR")
    ))
    .unwrap();
    serde_json::from_str(&text).unwrap()
}

async fn agent(status: u16, body: Value) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/info"))
        .respond_with(ResponseTemplate::new(status).set_body_json(body))
        .mount(&server)
        .await;
    server
}

fn client(server: &MockServer) -> Client {
    Client::new(
        Config::builder()
            .url(server.uri())
            .retry(common::fast_retry(0.0))
            .build()
            .unwrap(),
    )
    .unwrap()
}

#[tokio::test]
async fn health_ping_version_and_info_from_agent() {
    let info = fixture("health.info.ok.json")["body"].clone();
    let server = agent(200, info).await;
    let c = client(&server);
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.status, "ok");
    assert_eq!(h.details["version"], "7.85.4");
    assert_eq!(h.details["git_commit"], "52568a4d");
    assert!(c.ping().await.unwrap() > Duration::ZERO);
    assert_eq!(c.server_version().await.unwrap(), "7.85.4");
    let info = c.agent_info().await.unwrap();
    assert!(info.endpoints.iter().any(|e| e == "/v0.4/traces"));
    assert_eq!(info.config["receiver_port"], 8126);
    assert_eq!(info.raw["version"], "7.85.4");
    assert_eq!(c.config().url().as_str(), format!("{}/", server.uri()));
    assert!(!c.transport().is_closed());
    let closed = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&closed);
    c.on_close(move || {
        Box::pin(async move {
            flag.store(true, Ordering::SeqCst);
        })
    });
    c.close().await;
    c.close().await;
    assert!(closed.load(Ordering::SeqCst));
    assert!(c.is_closed());
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert_eq!(c.health().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn health_unhealthy_unreachable_and_versionless() {
    let server = agent(503, serde_json::json!({"errors": ["down"]})).await;
    let c = Client::new(
        Config::builder()
            .url(server.uri())
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unhealthy");
    assert_eq!(h.details["httpStatus"], "503");
    assert_eq!(h.details["error"], "UNAVAILABLE");

    let c = Client::new(
        Config::builder()
            .url("http://127.0.0.1:1")
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert_eq!(h.status, "unreachable");
    assert_eq!(h.details["error"], "UNAVAILABLE");

    let server = agent(200, serde_json::json!({"endpoints": []})).await;
    let c = client(&server);
    assert_eq!(
        c.server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    assert!(c.health().await.unwrap().details.is_empty());

    // an empty body is an empty document; a non-object body is UNAVAILABLE
    let server = MockServer::start().await;
    Mock::given(path("/info"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    assert_eq!(client(&server).agent_info().await.unwrap().version, "");
    let server = agent(200, serde_json::json!(["not", "an", "object"])).await;
    assert_eq!(
        client(&server).agent_info().await.unwrap_err().code(),
        ErrorCode::Unavailable
    );
}

#[tokio::test]
async fn spans_needs_both_keys_and_is_cached() {
    let c = Client::new(Config::builder().api_key("only-api").build().unwrap()).unwrap();
    assert_eq!(c.spans().unwrap_err().code(), ErrorCode::Config);
    let c = Client::new(
        Config::builder()
            .api_key("k-1")
            .app_key("a-1")
            .site("datadoghq.eu")
            .build()
            .unwrap(),
    )
    .unwrap();
    let q = c.spans().unwrap();
    assert_eq!(q.config().api_url(), "https://api.datadoghq.eu");
    let again = c.spans().unwrap();
    assert_eq!(again.config().site(), "datadoghq.eu");
    c.close().await;
    // the cached query client is closed with the client
    assert_eq!(q.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn init_tracing_via_client_is_shut_down_by_close() {
    let otlp = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v1/traces"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&otlp)
        .await;
    let c = Client::new(
        Config::builder()
            .otlp_endpoint(otlp.uri())
            .service_name("svc-from-config")
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c
        .init_tracing(TracingConfig::new("").with_set_global(false))
        .unwrap();
    assert_eq!(h.endpoint(), format!("{}/v1/traces", otlp.uri()));
    assert_eq!(h.service_name(), "svc-from-config");
    {
        use opentelemetry::trace::{Span as _, Tracer as _};
        let mut span = h.tracer("client-test", None).start("client-span");
        span.end();
    }
    c.close().await;
    assert!(h.is_shutdown());
    let received = otlp.received_requests().await.unwrap();
    assert!(!received.is_empty(), "spans were flushed on close");
}
