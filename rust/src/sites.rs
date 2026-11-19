//! Datadog sites and the API base URL (same site handling as the `datadog-logs` packages; this
//! copy is kept in sync by hand, there is no cross-crate dependency).

use crate::core::{Error, Result};

/// Default Datadog site (US1).
pub const DEFAULT_SITE: &str = "datadoghq.com";

/// The Datadog sites of the official OpenAPI spec (servers enum). Other host names are accepted
/// too (the spec's "any Datadog deployment" server), but never a scheme, port or path.
pub const DATADOG_SITES: &[&str] = &[
    "datadoghq.com",
    "us3.datadoghq.com",
    "us5.datadoghq.com",
    "ap1.datadoghq.com",
    "ap2.datadoghq.com",
    "uk1.datadoghq.com",
    "datadoghq.eu",
    "ddog-gov.com",
    "us2.ddog-gov.com",
];

/// Lower-cases and trims a site name.
pub fn normalize_site(site: &str) -> String {
    site.trim().to_ascii_lowercase()
}

/// Whether `site` is one of [`DATADOG_SITES`].
pub fn is_datadog_site(site: &str) -> bool {
    DATADOG_SITES.contains(&normalize_site(site).as_str())
}

fn is_label(label: &str) -> bool {
    let b = label.as_bytes();
    !b.is_empty()
        && b.iter()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
        && b[0] != b'-'
        && b[b.len() - 1] != b'-'
}

/// Validates a site name and returns it normalized. A value with a scheme, port, path or other
/// non-host characters (for example `https://app.datadoghq.com`) is a `CONFIG` error.
///
/// ```
/// assert_eq!(grow_datadog_apm::validate_site(" DatadogHQ.eu ").unwrap(), "datadoghq.eu");
/// assert!(grow_datadog_apm::validate_site("https://datadoghq.com").is_err());
/// ```
pub fn validate_site(site: &str) -> Result<String> {
    let s = normalize_site(site);
    let labels: Vec<&str> = s.split('.').collect();
    if s.len() > 253 || labels.len() < 2 || !labels.iter().all(|l| is_label(l)) {
        return Err(Error::config(
            "site must be a Datadog site host name such as datadoghq.com or datadoghq.eu (no scheme or path)",
        ));
    }
    Ok(s)
}

/// The API base URL of a site: `https://api.<site>`. An empty site means [`DEFAULT_SITE`].
///
/// ```
/// assert_eq!(grow_datadog_apm::api_url_for_site("datadoghq.eu").unwrap(), "https://api.datadoghq.eu");
/// ```
pub fn api_url_for_site(site: &str) -> Result<String> {
    let site = if site.trim().is_empty() {
        DEFAULT_SITE
    } else {
        site
    };
    Ok(format!("https://api.{}", validate_site(site)?))
}
