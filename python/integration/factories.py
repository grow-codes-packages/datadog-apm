"""Factories the integration suite and the conformance runner use (owned by this repository).

Capabilities: ``version`` (Agent ``GET /info``), and for tracing ``query`` + ``services`` served
by the trace-reader sidecar (what the real Datadog Agent forwarded to fakeintake), because the
Datadog query API is SaaS-only (docs/compatibility.md). ``dependencies`` is left out: Datadog's
public API has no dependency graph.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from reader import TraceReaderQuery

import grow_datadog_apm as sdk
from grow_datadog_apm.testing import TracingTarget

URL_VAR = "GROW_DATADOG_APM_URL"

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = {"version"}


def core_client(env: Mapping[str, str]) -> sdk.DatadogApmClient:
    """A client configured from the test environment (/bootstrap/env)."""
    return sdk.DatadogApmClient.from_env(env)


def async_core_client(env: Mapping[str, str]) -> sdk.AsyncDatadogApmClient:
    return sdk.AsyncDatadogApmClient.from_env(env)


#: Capabilities for the tracing scenarios: query, services (no dependencies).
TRACING_CAPABILITIES: set[str] = {"query", "services"}


def tracing_target(env: Mapping[str, str], scenario: Mapping[str, Any], service_name: str) -> Any:
    """This package's ``init_tracing`` (OTLP to the Agent) with the trace-reader as query side."""
    handle = sdk.init_tracing(
        service_name=service_name, sampler="always_on", set_global=False, env=env
    )
    return TracingTarget(handle=handle, query=TraceReaderQuery())
