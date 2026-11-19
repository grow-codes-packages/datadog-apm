// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `DatadogApmConfig`: common settings (SPEC §2.2) plus the Datadog Agent / OTLP endpoint,
//! unified service tagging and the query API credentials.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig,
};
use crate::sites::{validate_site, DEFAULT_SITE};
use crate::tracing::Protocol;

/// Default Datadog Agent trace intake URL (`health`, `ping` and `server_version` use its
/// `GET /info`).
pub const DEFAULT_URL: &str = "http://127.0.0.1:8126";
/// Default Agent OTLP/HTTP port.
pub const OTLP_HTTP_PORT: u16 = 4318;
/// Default Agent OTLP/gRPC port.
pub const OTLP_GRPC_PORT: u16 = 4317;
/// Default Agent trace intake port (`GET /info`).
pub const AGENT_TRACE_PORT: u16 = 8126;
/// Default OTLP protocol of this package.
pub const DEFAULT_PROTOCOL: Protocol = Protocol::HttpProtobuf;

const VENDOR_URL: &[&str] = &["DD_TRACE_AGENT_URL"];
const VENDOR_SERVICE: &[&str] = &["DD_SERVICE", "OTEL_SERVICE_NAME"];
const VENDOR_VERSION: &[&str] = &["DD_VERSION"];
const VENDOR_ENV: &[&str] = &["DD_ENV"];
const VENDOR_TAGS: &[&str] = &["DD_TAGS"];
const VENDOR_API_KEY: &[&str] = &["DD_API_KEY"];
const VENDOR_APP_KEY: &[&str] = &["DD_APP_KEY", "DD_APPLICATION_KEY"];
const VENDOR_SITE: &[&str] = &["DD_SITE"];
const VENDOR_PROTOCOL: &[&str] = &[
    "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
    "OTEL_EXPORTER_OTLP_PROTOCOL",
];

/// Parses Datadog tags (`DD_TAGS`): `key:value` pairs separated by commas and/or spaces.
/// Entries without a `:` or with an empty key are ignored.
///
/// ```
/// let tags = grow_datadog_apm::parse_datadog_tags("team:sdk, tier:web");
/// assert_eq!(tags["team"], "sdk");
/// assert_eq!(tags["tier"], "web");
/// ```
pub fn parse_datadog_tags(raw: &str) -> BTreeMap<String, String> {
    raw.split(|c: char| c == ',' || c.is_whitespace())
        .filter_map(|part| part.split_once(':'))
        .filter(|(k, _)| !k.is_empty())
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect()
}

fn has_path(endpoint: &str) -> bool {
    url::Url::parse(endpoint)
        .map(|u| !u.path().is_empty() && u.path() != "/")
        .unwrap_or(false)
}

fn is_http_url(value: &str) -> bool {
    url::Url::parse(value)
        .map(|u| matches!(u.scheme(), "http" | "https") && u.host_str().is_some())
        .unwrap_or(false)
}

/// The exporter URL for a protocol and an OTLP endpoint: for `http/protobuf` an endpoint without
/// a path gets `/v1/traces`; gRPC endpoints are used as they are.
///
/// ```
/// use grow_datadog_apm::{otlp_traces_url, tracing::Protocol};
/// assert_eq!(otlp_traces_url(Protocol::HttpProtobuf, "http://agent:4318"), "http://agent:4318/v1/traces");
/// assert_eq!(otlp_traces_url(Protocol::Grpc, "http://agent:4317"), "http://agent:4317");
/// ```
pub fn otlp_traces_url(protocol: Protocol, endpoint: &str) -> String {
    if protocol == Protocol::Grpc || has_path(endpoint) {
        endpoint.to_owned()
    } else {
        format!("{}/v1/traces", endpoint.trim_end_matches('/'))
    }
}

/// Immutable client configuration; build it with [`DatadogApmConfig::builder`] or
/// [`DatadogApmConfig::from_env`]. `Debug` output redacts every credential.
///
/// The Agent path (health, `init_tracing`) needs no key: the SDK sends OTLP to the Datadog
/// Agent, which holds `DD_API_KEY`. The API key and the application key are needed only by
/// [`DatadogSpansQuery`](crate::DatadogSpansQuery).
#[derive(Debug, Clone)]
pub struct DatadogApmConfig {
    common: CommonConfig,
    otlp_endpoint: Option<String>,
    protocol: Protocol,
    service_name: Option<String>,
    service_version: Option<String>,
    environment: Option<String>,
    tags: BTreeMap<String, String>,
    api_key: Option<Secret>,
    app_key: Option<Secret>,
    site: String,
    api_url: String,
}

impl DatadogApmConfig {
    /// A builder with nothing set.
    pub fn builder() -> DatadogApmConfigBuilder {
        DatadogApmConfigBuilder::default()
    }

    /// Configuration from the process environment
    /// (explicit > `GROW_DATADOG_APM_*` > vendor variables > defaults); see
    /// [`DatadogApmConfigBuilder::build_with_env`].
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// Agent trace intake base URL (`GET /info`).
    pub fn url(&self) -> &url::Url {
        self.common.url()
    }

    /// Configured OTLP endpoint, when set (see [`otlp_traces_endpoint`](Self::otlp_traces_endpoint)).
    pub fn otlp_endpoint(&self) -> Option<&str> {
        self.otlp_endpoint.as_deref()
    }

    /// OTLP protocol: `http/protobuf` (default) or `grpc`.
    pub fn protocol(&self) -> Protocol {
        self.protocol
    }

    /// Unified service tagging `service` (resource `service.name`).
    pub fn service_name(&self) -> Option<&str> {
        self.service_name.as_deref()
    }

    /// Unified service tagging `version` (resource `service.version`).
    pub fn service_version(&self) -> Option<&str> {
        self.service_version.as_deref()
    }

    /// Unified service tagging `env` (resource `deployment.environment.name`).
    pub fn environment(&self) -> Option<&str> {
        self.environment.as_deref()
    }

    /// Extra resource attributes (`DD_TAGS`).
    pub fn tags(&self) -> &BTreeMap<String, String> {
        &self.tags
    }

    /// Datadog API key (query API only).
    pub fn api_key(&self) -> Option<&Secret> {
        self.api_key.as_ref()
    }

    /// Datadog application key with `apm_read` (query API only).
    pub fn app_key(&self) -> Option<&Secret> {
        self.app_key.as_ref()
    }

    /// Datadog site, e.g. `datadoghq.com` (default) or `datadoghq.eu`.
    pub fn site(&self) -> &str {
        &self.site
    }

    /// Query API base URL (default `https://api.<site>`).
    pub fn api_url(&self) -> &str {
        &self.api_url
    }

    /// Whether both the API key and the application key are set (the query API is usable).
    pub fn has_query_credentials(&self) -> bool {
        self.api_key.is_some() && self.app_key.is_some()
    }

    /// The credential values to redact from errors and logs.
    pub fn known_secrets(&self) -> Vec<&Secret> {
        self.api_key.iter().chain(self.app_key.iter()).collect()
    }

    /// The resolved OTLP traces URL for the configured protocol: the OTLP endpoint when set
    /// (see [`otlp_traces_url`]), else the host of the Agent URL with port 4318 + `/v1/traces`
    /// (HTTP) or port 4317 (gRPC).
    pub fn otlp_traces_endpoint(&self) -> String {
        self.otlp_traces_endpoint_for(self.protocol)
    }

    /// [`otlp_traces_endpoint`](Self::otlp_traces_endpoint) for another protocol: the configured
    /// endpoint only when the protocols match, else the Agent host with that protocol's port.
    pub fn otlp_traces_endpoint_for(&self, protocol: Protocol) -> String {
        if let (Some(endpoint), true) = (&self.otlp_endpoint, protocol == self.protocol) {
            return otlp_traces_url(protocol, endpoint);
        }
        let port = if protocol == Protocol::Grpc {
            OTLP_GRPC_PORT
        } else {
            OTLP_HTTP_PORT
        };
        let url = self.url();
        let host = match url.host() {
            Some(url::Host::Ipv6(ip)) => format!("[{ip}]"),
            Some(h) => h.to_string(),
            None => "127.0.0.1".to_owned(),
        };
        otlp_traces_url(protocol, &format!("{}://{host}:{port}", url.scheme()))
    }

    /// Checks the service rules (common rules are checked when building).
    pub fn validate(&self) -> Result<()> {
        let mut problems = Vec::new();
        if self.protocol == Protocol::Native {
            problems.push("protocol must be http/protobuf or grpc".to_owned());
        }
        if let Some(e) = &self.otlp_endpoint {
            if !is_http_url(e) {
                problems.push("otlpEndpoint must be an http or https URL".to_owned());
            }
        }
        if !is_http_url(&self.api_url) {
            problems.push("apiUrl must be an http or https URL".to_owned());
        }
        for (name, value) in [
            ("serviceName", &self.service_name),
            ("serviceVersion", &self.service_version),
            ("environment", &self.environment),
        ] {
            if value.as_deref().is_some_and(|v| v.trim().is_empty()) {
                problems.push(format!("{name} must not be blank"));
            }
        }
        for (name, value) in [("apiKey", &self.api_key), ("appKey", &self.app_key)] {
            if value.as_ref().is_some_and(|v| v.reveal().trim().is_empty()) {
                problems.push(format!("{name} must not be blank"));
            }
        }
        if !problems.is_empty() {
            return Err(Error::config(problems.join("; ")));
        }
        validate_site(&self.site).map(|_| ())
    }
}

/// Builder for [`DatadogApmConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct DatadogApmConfigBuilder {
    common: CommonConfigBuilder,
    url: Option<String>,
    otlp_endpoint: Option<String>,
    protocol: Option<Protocol>,
    service_name: Option<String>,
    service_version: Option<String>,
    environment: Option<String>,
    tags: Option<BTreeMap<String, String>>,
    api_key: Option<Secret>,
    app_key: Option<Secret>,
    site: Option<String>,
    api_url: Option<String>,
}

impl DatadogApmConfigBuilder {
    /// Agent trace intake URL (default [`DEFAULT_URL`]).
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.url = Some(url.into());
        self
    }
    /// OTLP endpoint of the Agent. HTTP: a base URL (`http://agent:4318`, `/v1/traces` is
    /// appended when the URL has no path) or the full traces URL; gRPC: `http://agent:4317`.
    pub fn otlp_endpoint(mut self, endpoint: impl Into<String>) -> Self {
        self.otlp_endpoint = Some(endpoint.into());
        self
    }
    /// OTLP protocol: `http/protobuf` (default) or `grpc`.
    pub fn protocol(mut self, protocol: Protocol) -> Self {
        self.protocol = Some(protocol);
        self
    }
    /// Unified service tagging `service`.
    pub fn service_name(mut self, name: impl Into<String>) -> Self {
        self.service_name = Some(name.into());
        self
    }
    /// Unified service tagging `version`.
    pub fn service_version(mut self, version: impl Into<String>) -> Self {
        self.service_version = Some(version.into());
        self
    }
    /// Unified service tagging `env`.
    pub fn environment(mut self, env: impl Into<String>) -> Self {
        self.environment = Some(env.into());
        self
    }
    /// Adds an extra resource attribute (merged over `DD_TAGS`).
    pub fn tag(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.tags
            .get_or_insert_with(BTreeMap::new)
            .insert(key.into(), value.into());
        self
    }
    /// Datadog API key (query API only).
    pub fn api_key(mut self, key: impl Into<Secret>) -> Self {
        self.api_key = Some(key.into());
        self
    }
    /// Datadog application key with `apm_read` (query API only).
    pub fn app_key(mut self, key: impl Into<Secret>) -> Self {
        self.app_key = Some(key.into());
        self
    }
    /// Datadog site (default [`DEFAULT_SITE`]).
    pub fn site(mut self, site: impl Into<String>) -> Self {
        self.site = Some(site.into());
        self
    }
    /// Query API base URL (default `https://api.<site>`).
    pub fn api_url(mut self, url: impl Into<String>) -> Self {
        self.api_url = Some(url.into());
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header.
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting.
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment.
    pub fn build(self) -> Result<DatadogApmConfig> {
        let mut common = self.common;
        if let Some(url) = self.url {
            common = common.url(url);
        }
        let site = match self.site.filter(|s| !s.trim().is_empty()) {
            Some(s) => s.trim().to_ascii_lowercase(),
            None => DEFAULT_SITE.to_owned(),
        };
        let api_url = self
            .api_url
            .unwrap_or_else(|| format!("https://api.{site}"));
        let config = DatadogApmConfig {
            common: common.build(DEFAULT_URL)?,
            otlp_endpoint: self.otlp_endpoint,
            protocol: self.protocol.unwrap_or(DEFAULT_PROTOCOL),
            service_name: self.service_name,
            service_version: self.service_version,
            environment: self.environment,
            tags: self.tags.unwrap_or_default(),
            api_key: self.api_key,
            app_key: self.app_key,
            site,
            api_url,
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<DatadogApmConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds. Precedence: explicit values >
    /// `GROW_DATADOG_APM_<NAME>` > vendor variables (in this order) > defaults.
    ///
    /// | Field | `GROW_DATADOG_APM_*` | Vendor variables |
    /// | - | - | - |
    /// | url | `URL` | `DD_TRACE_AGENT_URL`, else `http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}` |
    /// | otlp_endpoint | `OTLP_HTTP_ENDPOINT` / `OTLP_GRPC_ENDPOINT` (by protocol), `OTLP_ENDPOINT` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` (+ `/v1/traces` for HTTP) |
    /// | protocol | `OTLP_PROTOCOL` | `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` |
    /// | service_name | `SERVICE` | `DD_SERVICE`, `OTEL_SERVICE_NAME` |
    /// | service_version | `VERSION` | `DD_VERSION` |
    /// | environment | `ENV` | `DD_ENV` |
    /// | tags | `TAGS` | `DD_TAGS` (explicit tags are merged over them) |
    /// | api_key | `API_KEY` | `DD_API_KEY` |
    /// | app_key | `APP_KEY` | `DD_APP_KEY`, `DD_APPLICATION_KEY` |
    /// | site | `SITE` | `DD_SITE` |
    /// | api_url | `API_URL` | — |
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<DatadogApmConfig> {
        self.common = self.common.apply_env(env, VENDOR_URL)?;
        let vendor = env.clone().with_prefix("");
        if self.url.is_none() {
            self.url = match env.string("URL", VENDOR_URL) {
                Some(u) => Some(u.trim().to_owned()),
                None => agent_url_from_env(&vendor)?,
            };
        }
        if self.protocol.is_none() {
            if let Some((var, value)) = env.lookup("OTLP_PROTOCOL", VENDOR_PROTOCOL) {
                self.protocol = Some(match value.trim().to_ascii_lowercase().as_str() {
                    "grpc" => Protocol::Grpc,
                    "http/protobuf" => Protocol::HttpProtobuf,
                    _ => {
                        return Err(Error::config(format!(
                            "{var} must be grpc or http/protobuf"
                        )))
                    }
                });
            }
        }
        if self.otlp_endpoint.is_none() {
            let protocol = self.protocol.unwrap_or(DEFAULT_PROTOCOL);
            self.otlp_endpoint = otlp_endpoint_from_env(env, &vendor, protocol);
        }
        let fill = |dst: &mut Option<String>, name: &str, vendors: &[&str]| {
            if dst.is_none() {
                *dst = env.string(name, vendors).map(|v| v.trim().to_owned());
            }
        };
        fill(&mut self.service_name, "SERVICE", VENDOR_SERVICE);
        fill(&mut self.service_version, "VERSION", VENDOR_VERSION);
        fill(&mut self.environment, "ENV", VENDOR_ENV);
        fill(&mut self.site, "SITE", VENDOR_SITE);
        fill(&mut self.api_url, "API_URL", &[]);
        if let Some(raw) = env.string("TAGS", VENDOR_TAGS) {
            let mut tags = parse_datadog_tags(&raw);
            tags.extend(self.tags.take().unwrap_or_default());
            self.tags = Some(tags);
        }
        if self.api_key.is_none() {
            self.api_key = env.secret("API_KEY", VENDOR_API_KEY);
        }
        if self.app_key.is_none() {
            self.app_key = env.secret("APP_KEY", VENDOR_APP_KEY);
        }
        self.build()
    }
}

/// `http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}` (`None` when neither is set).
fn agent_url_from_env(vendor: &EnvReader) -> Result<Option<String>> {
    let host = vendor
        .string("DD_AGENT_HOST", &[])
        .map(|h| h.trim().trim_matches(|c| c == '[' || c == ']').to_owned())
        .filter(|h| !h.is_empty());
    let port = match vendor.string("DD_TRACE_AGENT_PORT", &[]) {
        None => None,
        Some(raw) => {
            let t = raw.trim();
            match t.parse::<u16>() {
                Ok(p) if p > 0 && t.bytes().all(|b| b.is_ascii_digit()) => Some(p),
                _ => return Err(Error::config("DD_TRACE_AGENT_PORT must be a port number")),
            }
        }
    };
    if host.is_none() && port.is_none() {
        return Ok(None);
    }
    let host = host.unwrap_or_else(|| "127.0.0.1".to_owned());
    let host = if host.contains(':') {
        format!("[{host}]")
    } else {
        host
    };
    Ok(Some(format!(
        "http://{host}:{}",
        port.unwrap_or(AGENT_TRACE_PORT)
    )))
}

fn otlp_endpoint_from_env(
    env: &EnvReader,
    vendor: &EnvReader,
    protocol: Protocol,
) -> Option<String> {
    let specific = if protocol == Protocol::Grpc {
        "OTLP_GRPC_ENDPOINT"
    } else {
        "OTLP_HTTP_ENDPOINT"
    };
    let grow_generic = format!("{}OTLP_ENDPOINT", env.prefix());
    if let Some(v) = env.string(
        specific,
        &[grow_generic.as_str(), "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"],
    ) {
        return Some(v.trim().to_owned());
    }
    let base = vendor.string("OTEL_EXPORTER_OTLP_ENDPOINT", &[])?;
    let base = base.trim();
    Some(if protocol == Protocol::Grpc {
        base.to_owned()
    } else {
        format!("{}/v1/traces", base.trim_end_matches('/'))
    })
}
