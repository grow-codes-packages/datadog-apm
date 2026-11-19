"""Tracing conformance scenarios: init_tracing → real Agent → fakeintake → trace-reader."""

from __future__ import annotations

from typing import Any

import pytest
from factories import TRACING_CAPABILITIES, tracing_target

from grow_datadog_apm.testing import ConformanceRunner, assert_scenario, pytest_params


@pytest.mark.parametrize("scenario", pytest_params(categories=["tracing"]))
def test_tracing_conformance(env: dict[str, str], run_id: str, scenario: dict[str, Any]) -> None:
    service = f"grow-conformance-python-{run_id}"
    runner = ConformanceRunner(
        {"tracing": lambda ctx: tracing_target(env, ctx.scenario, service)},
        capabilities=TRACING_CAPABILITIES,
        service_name=service,
    )
    assert_scenario(runner, scenario)
