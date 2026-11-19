// Created by sdk-kit 0.1.0; owned by this repository.
//! Basics of the Datadog APM Rust SDK: configuration from the environment, Agent health and
//! version, OpenTelemetry tracing to the Datadog Agent (OTLP), Datadog log correlation and,
//! when an API key and an application key are set, the spans query API.
//!
//! ```sh
//! DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev cargo run
//! # optional query API: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
//! ```

use std::sync::Arc;
use std::time::Duration;

use grow_datadog_apm as sdk;
use opentelemetry::KeyValue;
use sdk::core::{ErrorInfo, Hooks};
use sdk::tracing::TraceQueryClient as _;
use sdk::tracing::{add_event, current_trace_id, traced, with_span, SpanOptions, TracingConfig};
use sdk::{datadog_log_fields, to_datadog_trace_id, ErrorCode};

use sdk::DatadogApmClient as Client;
use sdk::DatadogApmConfig as Config;

/// Prints every retry.
struct RetryLogger;

impl Hooks for RetryLogger {
    fn on_error(&self, info: &ErrorInfo) {
        if info.will_retry {
            eprintln!(
                "retrying {} {} (attempt {})",
                info.method, info.url, info.attempt
            );
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error [{}]: {err}", err.code());
        std::process::exit(1);
    }
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_DATADOG_APM_* > DD_* / OTEL_* > defaults; invalid values name the
    // variable, never its value.
    let config = Config::builder()
        .hooks(Arc::new(RetryLogger))
        .build_from_env()?;
    println!("config: {config:?}"); // keys print as [REDACTED]

    let client = Client::new(config)?;
    let health = client.health().await?; // never fails for an unreachable Agent
    println!("agent health: {} ({})", health.healthy, health.status);
    match client.server_version().await {
        Ok(version) => println!("agent version: {version}"),
        Err(e) if e.code() == ErrorCode::Unsupported => println!("agent version: not reported"),
        Err(e) => eprintln!("agent version: {e}"),
    }

    // An empty service name means DD_SERVICE (or GROW_DATADOG_APM_SERVICE).
    let service = client
        .config()
        .service_name()
        .unwrap_or("grow-datadog-apm-example")
        .to_owned();
    let tracing = client.init_tracing(TracingConfig::new(service))?;
    println!("exporting spans to {}", tracing.endpoint());

    let trace_id = with_span(
        "checkout",
        SpanOptions::default().attribute(KeyValue::new("order.id", "o-123")),
        async {
            let fields = datadog_log_fields(Default::default());
            println!(
                "log fields: {}",
                serde_json::to_string(&fields).unwrap_or_default()
            );
            traced("charge-card", async {
                add_event("card.validated", vec![KeyValue::new("attempt", 1)]);
                Ok::<_, sdk::Error>(())
            })
            .await?;
            Ok::<_, sdk::Error>(current_trace_id().unwrap_or_default())
        },
    )
    .await?;
    tracing.force_flush(Some(Duration::from_secs(10))).await?;
    let dd = to_datadog_trace_id(&trace_id)?;
    println!("trace {trace_id} (Datadog trace_id {})", dd.low64_decimal);

    match client.spans() {
        Err(e) if e.code() == ErrorCode::Config => {
            println!("query API skipped: set DD_API_KEY and DD_APP_KEY")
        }
        Err(e) => return Err(e),
        Ok(spans) => {
            spans.ping().await?; // AUTHENTICATION for invalid keys
            println!("services: {:?}", spans.list_services().await?);
        }
    }
    client.close().await; // flushes and shuts down the tracing above
    Ok(())
}
