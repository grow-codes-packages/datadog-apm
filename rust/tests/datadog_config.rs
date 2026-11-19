//! `DatadogApmConfig`: environment precedence, Agent/OTLP endpoints, validation, redaction.

use grow_datadog_apm as sdk;
use sdk::core::EnvReader;
use sdk::tracing::Protocol;
use sdk::{DatadogApmConfig as Config, ErrorCode};

fn env(pairs: &[(&str, &str)]) -> EnvReader {
    EnvReader::from_map(pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())))
}

#[test]
fn defaults_without_environment() {
    let c = Config::builder().build_with_env(&env(&[])).unwrap();
    assert_eq!(c.url().as_str(), "http://127.0.0.1:8126/");
    assert_eq!(c.protocol(), Protocol::HttpProtobuf);
    assert_eq!(c.otlp_endpoint(), None);
    assert_eq!(c.otlp_traces_endpoint(), "http://127.0.0.1:4318/v1/traces");
    assert_eq!(
        c.otlp_traces_endpoint_for(Protocol::Grpc),
        "http://127.0.0.1:4317"
    );
    assert_eq!(c.site(), "datadoghq.com");
    assert_eq!(c.api_url(), "https://api.datadoghq.com");
    assert!(!c.has_query_credentials());
    assert!(c.known_secrets().is_empty());
    assert!(c.service_name().is_none() && c.service_version().is_none());
    assert!(c.environment().is_none() && c.tags().is_empty());
    assert!(c.api_key().is_none() && c.app_key().is_none());
    assert_eq!(sdk::DEFAULT_URL, "http://127.0.0.1:8126");
    assert_eq!(
        (
            sdk::OTLP_HTTP_PORT,
            sdk::OTLP_GRPC_PORT,
            sdk::AGENT_TRACE_PORT
        ),
        (4318, 4317, 8126)
    );
    assert_eq!(c.common().url(), c.url());
}

#[test]
fn vendor_variables() {
    let e = env(&[
        ("DD_AGENT_HOST", "agent.local"),
        ("DD_TRACE_AGENT_PORT", "18126"),
        ("DD_SERVICE", "checkout"),
        ("DD_VERSION", "1.2.3"),
        ("DD_ENV", "prod"),
        ("DD_TAGS", "team:sdk tier:web"),
        ("DD_API_KEY", "api-key-1"),
        ("DD_APPLICATION_KEY", "app-key-1"),
        ("DD_SITE", "DatadogHQ.eu"),
        ("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc"),
        ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317"),
    ]);
    let c = Config::builder()
        .tag("tier", "api")
        .build_with_env(&e)
        .unwrap();
    assert_eq!(c.url().as_str(), "http://agent.local:18126/");
    assert_eq!(c.service_name(), Some("checkout"));
    assert_eq!(c.service_version(), Some("1.2.3"));
    assert_eq!(c.environment(), Some("prod"));
    assert_eq!(c.tags()["team"], "sdk");
    assert_eq!(c.tags()["tier"], "api", "explicit tags win");
    assert_eq!(c.api_key().unwrap().reveal(), "api-key-1");
    assert_eq!(c.app_key().unwrap().reveal(), "app-key-1");
    assert!(c.has_query_credentials());
    assert_eq!(c.known_secrets().len(), 2);
    assert_eq!(c.site(), "datadoghq.eu");
    assert_eq!(c.api_url(), "https://api.datadoghq.eu");
    assert_eq!(c.protocol(), Protocol::Grpc);
    assert_eq!(c.otlp_traces_endpoint(), "http://collector:4317");
    // the configured endpoint belongs to its protocol; HTTP falls back to the Agent host
    assert_eq!(
        c.otlp_traces_endpoint_for(Protocol::HttpProtobuf),
        "http://agent.local:4318/v1/traces"
    );

    let c = Config::builder()
        .build_with_env(&env(&[
            ("DD_TRACE_AGENT_URL", "http://ddagent:8126"),
            ("DD_AGENT_HOST", "ignored"),
            ("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318/"),
            ("OTEL_SERVICE_NAME", "otel-svc"),
            ("DD_APP_KEY", "app-2"),
        ]))
        .unwrap();
    assert_eq!(c.url().as_str(), "http://ddagent:8126/");
    assert_eq!(c.otlp_traces_endpoint(), "http://collector:4318/v1/traces");
    assert_eq!(c.service_name(), Some("otel-svc"));
    assert_eq!(c.app_key().unwrap().reveal(), "app-2");

    let c = Config::builder()
        .build_with_env(&env(&[("DD_TRACE_AGENT_PORT", "9126")]))
        .unwrap();
    assert_eq!(c.url().as_str(), "http://127.0.0.1:9126/");
    let c = Config::builder()
        .build_with_env(&env(&[("DD_AGENT_HOST", "::1")]))
        .unwrap();
    assert_eq!(c.url().as_str(), "http://[::1]:8126/");
    assert_eq!(c.otlp_traces_endpoint(), "http://[::1]:4318/v1/traces");
    let c = Config::builder()
        .build_with_env(&env(&[(
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            "http://c:4318/v1/traces",
        )]))
        .unwrap();
    assert_eq!(c.otlp_endpoint(), Some("http://c:4318/v1/traces"));
}

#[test]
fn grow_variables_win_over_vendor_and_explicit_wins_over_both() {
    let e = env(&[
        ("GROW_DATADOG_APM_URL", "http://grow-agent:8126"),
        ("DD_TRACE_AGENT_URL", "http://dd-agent:8126"),
        ("GROW_DATADOG_APM_OTLP_PROTOCOL", "http/protobuf"),
        ("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc"),
        (
            "GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT",
            "http://grow-agent:4318",
        ),
        (
            "GROW_DATADOG_APM_OTLP_GRPC_ENDPOINT",
            "http://grow-agent:4317",
        ),
        ("GROW_DATADOG_APM_OTLP_ENDPOINT", "http://generic:4318"),
        ("GROW_DATADOG_APM_SERVICE", "grow-svc"),
        ("DD_SERVICE", "dd-svc"),
        ("GROW_DATADOG_APM_TAGS", "a:1"),
        ("GROW_DATADOG_APM_API_KEY", "grow-api"),
        ("GROW_DATADOG_APM_APP_KEY", "grow-app"),
        ("GROW_DATADOG_APM_SITE", "us3.datadoghq.com"),
        ("GROW_DATADOG_APM_API_URL", "http://mock-api:8080"),
    ]);
    let c = Config::builder().build_with_env(&e).unwrap();
    assert_eq!(c.url().as_str(), "http://grow-agent:8126/");
    assert_eq!(c.protocol(), Protocol::HttpProtobuf);
    assert_eq!(c.otlp_traces_endpoint(), "http://grow-agent:4318/v1/traces");
    assert_eq!(c.service_name(), Some("grow-svc"));
    assert_eq!(c.tags()["a"], "1");
    assert_eq!(c.site(), "us3.datadoghq.com");
    assert_eq!(c.api_url(), "http://mock-api:8080");

    let c = Config::builder()
        .protocol(Protocol::Grpc)
        .build_with_env(&e)
        .unwrap();
    assert_eq!(c.otlp_traces_endpoint(), "http://grow-agent:4317");

    let c = Config::builder()
        .url("http://explicit:8126")
        .otlp_endpoint("http://explicit:4318/v1/traces")
        .service_name("explicit")
        .service_version("9")
        .environment("dev")
        .api_key("k")
        .app_key("a")
        .site("datadoghq.com")
        .api_url("https://api.example.test")
        .build_with_env(&e)
        .unwrap();
    assert_eq!(c.url().host_str(), Some("explicit"));
    assert_eq!(c.otlp_traces_endpoint(), "http://explicit:4318/v1/traces");
    assert_eq!(c.service_name(), Some("explicit"));
    assert_eq!(c.api_key().unwrap().reveal(), "k");
    assert_eq!(c.api_url(), "https://api.example.test");
    let generic = Config::builder()
        .build_with_env(&env(&[(
            "GROW_DATADOG_APM_OTLP_ENDPOINT",
            "http://generic:4318",
        )]))
        .unwrap();
    assert_eq!(
        generic.otlp_traces_endpoint(),
        "http://generic:4318/v1/traces"
    );
}

#[test]
fn invalid_values_are_config_errors() {
    let cases: Vec<(&str, EnvReader)> = vec![
        (
            "protocol",
            env(&[("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json")]),
        ),
        ("port", env(&[("DD_TRACE_AGENT_PORT", "abc")])),
        ("port 0", env(&[("DD_TRACE_AGENT_PORT", "0")])),
        ("site", env(&[("DD_SITE", "https://app.datadoghq.com")])),
        (
            "otlp",
            env(&[("GROW_DATADOG_APM_OTLP_HTTP_ENDPOINT", "ftp://x")]),
        ),
        ("api url", env(&[("GROW_DATADOG_APM_API_URL", "not a url")])),
        ("blank key", env(&[("DD_API_KEY", "  ")])),
        ("blank service", env(&[("DD_SERVICE", "  ")])),
    ];
    for (name, e) in cases {
        let err = Config::builder().build_with_env(&e).unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{name}");
        assert!(
            !err.message().contains("abc"),
            "{name}: no values in messages"
        );
    }
    let err = Config::builder()
        .protocol(Protocol::Native)
        .service_version(" ")
        .app_key(" ")
        .build()
        .unwrap_err();
    assert!(err.message().contains("protocol"));
    assert!(err.message().contains("serviceVersion"));
    assert!(err.message().contains("appKey"));
    let err = Config::builder()
        .build_with_env(&env(&[("OTEL_EXPORTER_OTLP_PROTOCOL", "zipkin")]))
        .unwrap_err();
    assert!(err.message().contains("OTEL_EXPORTER_OTLP_PROTOCOL"));
}

#[test]
fn debug_output_redacts_keys() {
    let c = Config::builder()
        .api_key("api-secret-value-1")
        .app_key("app-secret-value-1")
        .header("x-visible", "yes")
        .connect_timeout(std::time::Duration::from_secs(2))
        .request_timeout(std::time::Duration::from_secs(3))
        .total_timeout(std::time::Duration::from_secs(9))
        .retry(sdk::RetryPolicy::default())
        .max_retries(1)
        .tls(Default::default())
        .insecure_skip_verify(false)
        .user_agent_suffix("test")
        .common(|b| b)
        .build()
        .unwrap();
    let text = format!("{c:?} {:?}", Config::builder().api_key("builder-secret-1"));
    for s in [
        "api-secret-value-1",
        "app-secret-value-1",
        "builder-secret-1",
    ] {
        assert!(!text.contains(s), "{s} leaked");
    }
    assert!(text.contains("[REDACTED]"));
    assert!(Config::builder()
        .ca_cert_path("/nonexistent/ca.pem")
        .proxy("http://proxy:3128")
        .hooks(std::sync::Arc::new(NoHooks))
        .build()
        .is_ok());
    let _ = Config::from_env();
    let _ = Config::builder().build_from_env();
}

struct NoHooks;
impl sdk::core::Hooks for NoHooks {}
