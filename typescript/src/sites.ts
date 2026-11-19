// Service-owned: Datadog sites and the API base URL (API.md §0). Same site handling as the
// `datadog-logs` package; this copy is kept in sync by hand (no cross-repo runtime dependency).

import { ConfigError } from "./core/index.js";

/** Default Datadog site (US1). */
export const DEFAULT_SITE = "datadoghq.com";

/**
 * The Datadog sites of the official OpenAPI spec (`servers` enum). Other host names are accepted
 * too (the spec's "any Datadog deployment" server), but never a scheme, port or path.
 */
export const DATADOG_SITES: readonly string[] = Object.freeze([
  "datadoghq.com",
  "us3.datadoghq.com",
  "us5.datadoghq.com",
  "ap1.datadoghq.com",
  "ap2.datadoghq.com",
  "uk1.datadoghq.com",
  "datadoghq.eu",
  "ddog-gov.com",
  "us2.ddog-gov.com",
]);

const HOST = /^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;

/** Lower-cases and trims a site name. */
export function normalizeSite(site: string): string {
  return site.trim().toLowerCase();
}

/** True when `site` is one of {@link DATADOG_SITES}. */
export function isDatadogSite(site: string): boolean {
  return DATADOG_SITES.includes(normalizeSite(site));
}

/**
 * Validates a site name and returns it normalized. Throws {@link ConfigError} for a value with a
 * scheme, port, path or other non-host characters (e.g. `https://app.datadoghq.com`).
 */
export function validateSite(site: string): string {
  const normalized = normalizeSite(site);
  if (!HOST.test(normalized)) {
    throw new ConfigError(
      "invalid configuration: site must be a Datadog site host name such as datadoghq.com or datadoghq.eu (no scheme or path)",
    );
  }
  return normalized;
}

/**
 * The API base URL of a site: `https://api.<site>`.
 *
 * @example
 * apiUrlForSite("datadoghq.eu") // "https://api.datadoghq.eu"
 */
export function apiUrlForSite(site: string = DEFAULT_SITE): string {
  return `https://api.${validateSite(site)}`;
}
