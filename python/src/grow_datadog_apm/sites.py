"""Datadog sites and the API base URL (API.md §0).

Same site handling as the ``datadog-logs`` package; this copy is kept in sync by hand (no
cross-repo runtime dependency).
"""

from __future__ import annotations

import re

from ._core.errors import ConfigError

__all__ = [
    "DEFAULT_SITE",
    "DATADOG_SITES",
    "normalize_site",
    "is_datadog_site",
    "validate_site",
    "api_url_for_site",
]

#: Default Datadog site (US1).
DEFAULT_SITE = "datadoghq.com"

#: Datadog sites of the official OpenAPI spec (servers enum). Other host names are accepted too
#: (the spec's "any Datadog deployment" server), but never a scheme, port or path.
DATADOG_SITES: tuple[str, ...] = (
    "datadoghq.com",
    "us3.datadoghq.com",
    "us5.datadoghq.com",
    "ap1.datadoghq.com",
    "ap2.datadoghq.com",
    "uk1.datadoghq.com",
    "datadoghq.eu",
    "ddog-gov.com",
    "us2.ddog-gov.com",
)

_SITE_HOST = re.compile(
    r"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$",
)


def normalize_site(site: str) -> str:
    """Lower-case and trim a site name."""
    return site.strip().lower()


def is_datadog_site(site: str) -> bool:
    """``True`` when ``site`` is one of :data:`DATADOG_SITES`."""
    return normalize_site(site) in DATADOG_SITES


def validate_site(site: str) -> str:
    """Validate a site name and return it normalized.

    A value with a scheme, port, path or other non-host characters (for example
    ``"https://app.datadoghq.com"``) raises :class:`ConfigError`.
    """
    normalized = normalize_site(site)
    if len(normalized) > 253 or not _SITE_HOST.match(normalized):
        raise ConfigError(
            "config: site must be a Datadog site host name such as datadoghq.com or "
            "datadoghq.eu (no scheme or path)"
        )
    return normalized


def api_url_for_site(site: str = DEFAULT_SITE) -> str:
    """API base URL of a site: ``https://api.<site>`` (an empty site means the default).

    >>> api_url_for_site("datadoghq.eu")
    'https://api.datadoghq.eu'
    """
    return "https://api." + validate_site(site if site.strip() else DEFAULT_SITE)
