package datadogapm

// Datadog sites and the API base URL (API.md §0). Same site handling as the
// datadog-logs package of the TypeScript reference; this copy is kept in sync
// by hand (no cross-repo runtime dependency).

import (
	"regexp"
	"slices"
	"strings"
)

// DefaultSite is the default Datadog site (US1).
const DefaultSite = "datadoghq.com"

// DatadogSites are the Datadog sites of the official OpenAPI spec (servers
// enum). Other host names are accepted too (the spec's "any Datadog
// deployment" server), but never a scheme, port or path.
var DatadogSites = []string{
	"datadoghq.com",
	"us3.datadoghq.com",
	"us5.datadoghq.com",
	"ap1.datadoghq.com",
	"ap2.datadoghq.com",
	"uk1.datadoghq.com",
	"datadoghq.eu",
	"ddog-gov.com",
	"us2.ddog-gov.com",
}

var siteHost = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$`)

// NormalizeSite lower-cases and trims a site name.
func NormalizeSite(site string) string { return strings.ToLower(strings.TrimSpace(site)) }

// IsDatadogSite reports whether site is one of DatadogSites.
func IsDatadogSite(site string) bool { return slices.Contains(DatadogSites, NormalizeSite(site)) }

// ValidateSite validates a site name and returns it normalized. A value with a
// scheme, port, path or other non-host characters (for example
// "https://app.datadoghq.com") is a CONFIG error.
func ValidateSite(site string) (string, error) {
	s := NormalizeSite(site)
	if len(s) > 253 || !siteHost.MatchString(s) {
		return "", ConfigError("site must be a Datadog site host name such as datadoghq.com or datadoghq.eu (no scheme or path)")
	}
	return s, nil
}

// APIURLForSite returns the API base URL of a site: "https://api.<site>". An
// empty site means DefaultSite.
//
//	APIURLForSite("datadoghq.eu") // "https://api.datadoghq.eu"
func APIURLForSite(site string) (string, error) {
	if strings.TrimSpace(site) == "" {
		site = DefaultSite
	}
	s, err := ValidateSite(site)
	if err != nil {
		return "", err
	}
	return "https://api." + s, nil
}
