"""Test-only access to the spans the real Datadog Agent forwarded to fakeintake.

The trace-reader sidecar decodes them (``GET /spans?trace_id=<hex>&service=…``). Not part of the
package: the Datadog query API is SaaS-only, so the integration suite reads the Agent's output
here and maps it with the package's own ``datadog_trace_span_to_span``.
"""

from __future__ import annotations

import os
import time
from datetime import datetime
from typing import Any

import httpx

import grow_datadog_apm as sdk

ReaderSpan = dict[str, Any]


def reader_url() -> str:
    return os.environ.get("TRACE_READER_URL", "").rstrip("/")


def reader_spans(**filters: str) -> list[ReaderSpan]:
    """``GET /spans`` with exact-match filters."""
    resp = httpx.get(reader_url() + "/spans", params=filters, timeout=10.0)
    if resp.status_code != 200:
        raise AssertionError(f"trace-reader: HTTP {resp.status_code} {resp.text}")
    doc = resp.json()
    if doc.get("undecodable_idx_payloads"):
        raise AssertionError(
            f"trace-reader: {doc['undecodable_idx_payloads']} undecodable payloads"
        )
    spans: list[ReaderSpan] = doc["spans"]
    return spans


def wait_for_spans(min_count: int, timeout_s: float = 45.0, **filters: str) -> list[ReaderSpan]:
    """Poll until at least ``min_count`` spans match (the Agent flushes every ~4 s)."""
    deadline = time.monotonic() + timeout_s
    last: list[ReaderSpan] = []
    while time.monotonic() < deadline:
        last = reader_spans(**filters)
        if len(last) >= min_count:
            return last
        time.sleep(0.5)
    raise AssertionError(f"trace-reader: {len(last)}/{min_count} spans for {filters}")


def by_resource(spans: list[ReaderSpan]) -> dict[str, ReaderSpan]:
    return {str(s["resource"]): s for s in spans}


class TraceReaderQuery(sdk.BaseTraceQueryClient):
    """A ``TraceQueryClient`` over the trace-reader, so the shared conformance scenarios run
    against what the real Agent made of the exported spans."""

    def get_trace(self, trace_id: str) -> sdk.Trace | None:
        wanted = sdk.normalize_trace_id(trace_id)
        raws = reader_spans(trace_id=wanted)
        if not raws:
            return None
        return sdk.Trace(wanted, [sdk.datadog_trace_span_to_span(r) for r in raws])

    def search_traces(self, query: sdk.TraceSearchQuery | None = None) -> list[sdk.TraceSummary]:
        q = query or sdk.TraceSearchQuery()
        filters = {}
        if q.service:
            filters["service"] = q.service
        if q.operation:
            filters["resource"] = q.operation
        ids: list[str] = []
        for raw in reader_spans(**filters):
            if raw["trace_id_hex"] not in ids:
                ids.append(raw["trace_id_hex"])
        out = []
        for trace_id in ids[: q.limit]:
            trace = self.get_trace(trace_id)
            if trace is not None:
                out.append(sdk.TraceSummary.from_trace(trace))
        return out

    def list_services(self) -> list[str]:
        return sorted({str(s["service"]) for s in reader_spans()})

    def list_operations(self, service: str) -> list[str]:
        return sorted({str(s["resource"]) for s in reader_spans(service=service)})

    def get_dependencies(
        self, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[sdk.DependencyLink]:
        raise sdk.UnsupportedError("trace_reader.get_dependencies: not offered")
