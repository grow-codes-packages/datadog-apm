// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-datadog-apm
//!
//! Rust SDK for [Datadog APM](https://grow-codes-packages.github.io/datadog-apm/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_datadog_apm::Result<()> {
//! use grow_datadog_apm::{DatadogApmClient, DatadogApmConfig};
//!
//! let client = DatadogApmClient::new(DatadogApmConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::DEFAULT_URL;

pub use crate::client::DatadogApmClient;

pub use crate::config::DatadogApmConfig;

pub use crate::config::DatadogApmConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type DatadogApmError = Error;
