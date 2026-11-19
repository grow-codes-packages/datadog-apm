// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `DatadogApmClient`: entry point of the SDK (SPEC §2.4). It talks to the local Datadog Agent
//! (`GET /info` on the trace intake port 8126, no authentication) and gives access to the
//! tracing setup and the SaaS spans query API.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde::Deserialize;
use serde_json::{Map, Value};

use crate::config::DatadogApmConfig;
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, ErrorCode, HealthStatus, HttpTransport, Result};
use crate::setup::{init_tracing_with_options, DatadogTracingHandle, InitTracingOptions};
use crate::spans::DatadogSpansQuery;
use crate::tracing::TracingConfig;

/// The Agent's `GET /info` document (fields this package reads).
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct AgentInfo {
    /// Agent version, e.g. `7.85.4` (empty when not reported).
    #[serde(default)]
    pub version: String,
    /// Agent build commit.
    #[serde(default)]
    pub git_commit: String,
    /// Trace intake endpoints the Agent serves.
    #[serde(default)]
    pub endpoints: Vec<String>,
    /// The Agent's reported configuration.
    #[serde(default)]
    pub config: Map<String, Value>,
    /// The whole document.
    #[serde(skip)]
    pub raw: Value,
}

/// Async client for Datadog APM. Cheap to clone (clones share one connection pool).
///
/// `health`, `ping` and `server_version` use the Datadog Agent's `GET /info`;
/// [`init_tracing`](Self::init_tracing) sets up OpenTelemetry exporting to the Agent;
/// [`spans`](Self::spans) is the SaaS query API (needs the API key and the application key).
///
/// ```no_run
/// # async fn demo() -> grow_datadog_apm::Result<()> {
/// use grow_datadog_apm::{tracing::TracingConfig, DatadogApmClient};
/// let client = DatadogApmClient::from_env()?;
/// let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
/// println!("agent {}", client.server_version().await?);
/// client.close().await; // flushes and shuts down the tracing it started
/// # Ok(()) }
/// ```
#[derive(Debug, Clone)]
pub struct DatadogApmClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    config: DatadogApmConfig,
    transport: HttpTransport,
    on_close: CloseGroup,
    handles: Mutex<Vec<DatadogTracingHandle>>,
    spans: Mutex<Option<DatadogSpansQuery>>,
}

impl DatadogApmClient {
    /// Creates a client from a configuration.
    pub fn new(config: DatadogApmConfig) -> Result<Self> {
        config.validate()?;
        let transport = HttpTransport::new(config.common())?;
        for s in config.known_secrets() {
            transport.add_known_secret(s);
        }
        Ok(DatadogApmClient {
            inner: Arc::new(Inner {
                config,
                transport,
                on_close: CloseGroup::new(),
                handles: Mutex::new(Vec::new()),
                spans: Mutex::new(None),
            }),
        })
    }

    /// Creates a client from the process environment (`GROW_DATADOG_APM_*`, `DD_*`; see
    /// [`DatadogApmConfigBuilder::build_with_env`](crate::DatadogApmConfigBuilder::build_with_env)).
    pub fn from_env() -> Result<Self> {
        Self::new(DatadogApmConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &DatadogApmConfig {
        &self.inner.config
    }

    /// The HTTP transport to the Agent (for service modules).
    pub fn transport(&self) -> &HttpTransport {
        &self.inner.transport
    }

    /// Registers work that [`DatadogApmClient::close`] runs (flush, stop watchers, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    /// The SaaS spans query API (created on first use, closed by [`close`](Self::close)).
    /// `CONFIG` error when the API key or the application key is missing.
    pub fn spans(&self) -> Result<DatadogSpansQuery> {
        let mut guard = self.inner.spans.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(q) = guard.as_ref() {
            return Ok(q.clone());
        }
        let q = DatadogSpansQuery::new(self.inner.config.clone())?;
        *guard = Some(q.clone());
        Ok(q)
    }

    /// `GET /info` of the Agent.
    pub async fn agent_info(&self) -> Result<AgentInfo> {
        let resp = self
            .transport()
            .get("/info")
            .operation("agentInfo")
            .send()
            .await?;
        if resp.bytes().iter().all(u8::is_ascii_whitespace) {
            return Ok(AgentInfo::default());
        }
        let raw: Value = resp.json()?;
        let mut info: AgentInfo = serde_json::from_value(raw.clone())
            .map_err(|_| Error::unavailable("agentInfo: unexpected /info document"))?;
        info.raw = raw;
        Ok(info)
    }

    /// Agent liveness with `GET /info`: healthy with status `ok` and details `version`,
    /// `git_commit` when the Agent answers; `unreachable` on network errors, `unhealthy` on HTTP
    /// errors (details `httpStatus`, `error`). Fails only for `CANCELLED` and `CONFIG`.
    pub async fn health(&self) -> Result<HealthStatus> {
        match self.agent_info().await {
            Ok(info) => {
                let mut status = HealthStatus::healthy("ok");
                if !info.version.is_empty() {
                    status = status.with_detail("version", info.version);
                }
                if !info.git_commit.is_empty() {
                    status = status.with_detail("git_commit", info.git_commit);
                }
                Ok(status)
            }
            Err(e) if matches!(e.code(), ErrorCode::Cancelled | ErrorCode::Config) => Err(e),
            Err(e) => Ok(match e.http_status() {
                Some(code) => HealthStatus::unhealthy("unhealthy")
                    .with_detail("httpStatus", code.to_string())
                    .with_detail("error", e.code().as_str()),
                None => {
                    HealthStatus::unhealthy("unreachable").with_detail("error", e.code().as_str())
                }
            }),
        }
    }

    /// Round trip to the Agent (`GET /info`); returns the latency. The Agent has no
    /// authentication; to check the Datadog API keys use [`spans`](Self::spans) and
    /// [`DatadogSpansQuery::ping`].
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        self.transport()
            .get("/info")
            .operation("ping")
            .send()
            .await?;
        Ok(started.elapsed())
    }

    /// The Datadog Agent version from `GET /info` (e.g. `7.85.4`); `UNSUPPORTED` when the
    /// Agent reports none.
    pub async fn server_version(&self) -> Result<String> {
        let info = self.agent_info().await?;
        if info.version.is_empty() {
            return Err(Error::unsupported(
                "serverVersion: the Datadog Agent did not report a version",
            ));
        }
        Ok(info.version)
    }

    /// [`init_tracing`](crate::init_tracing) with this client's configuration as the defaults
    /// (Agent host, OTLP endpoint and protocol, service/env/version, tags).
    /// [`close`](Self::close) shuts the handle down.
    pub fn init_tracing(&self, config: TracingConfig) -> Result<DatadogTracingHandle> {
        let handle = init_tracing_with_options(
            config,
            InitTracingOptions {
                config: Some(self.inner.config.clone()),
                ..Default::default()
            },
        )?;
        self.inner
            .handles
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .push(handle.clone());
        Ok(handle)
    }

    /// Flushes and shuts down every tracing handle started with
    /// [`init_tracing`](Self::init_tracing), runs the `on_close` work, closes the query API and
    /// the transport. Idempotent.
    pub async fn close(&self) {
        let handles: Vec<_> =
            std::mem::take(&mut *self.inner.handles.lock().unwrap_or_else(|e| e.into_inner()));
        for h in handles {
            let _ = h.shutdown().await;
        }
        self.inner.on_close.close().await;
        if let Some(q) = self
            .inner
            .spans
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            q.close();
        }
        self.inner.transport.close();
    }

    /// Whether [`DatadogApmClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.transport.is_closed()
    }
}
