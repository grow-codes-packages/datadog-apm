#!/usr/bin/env python3
"""trace-reader: decode the Datadog Agent's trace payloads stored by fakeintake into plain JSON.

Test-only sidecar for the datadog-apm Docker suite (GRO-1381). Python >= 3.14 standard library only
(``compression.zstd``). Written from the public .proto definitions in DataDog/datadog-agent
(pkg/proto/datadog/trace/{agent_payload,tracer_payload,span}.proto, Apache-2.0); no Datadog code is
copied.

Why it exists: the trace-agent ships ``AgentPayload`` protobuf, zstd-compressed, to
``/api/v0.2/traces``. fakeintake stores it raw and does NOT decode it on the server
(``format=json`` answers ``{"errors":["invalid route parameter"]}`` for this route), so every
language test would need protobuf + zstd. This sidecar does it once and serves JSON:

  GET  /health                      {"status":"ok"}
  GET  /spans[?filters]             {"spans":[...], "payloads":N, "idx_tracer_payloads":N, "undecodable_idx_payloads":N}
       filters: trace_id=<hex, 1..32 chars>  trace_id_dec=<decimal>  span_id= / span_id_dec=
                parent_id= / parent_id_dec=  service=  name=  resource=  type=  env=  meta.<key>=<value>
                (exact match, AND-ed; ids are hex unless the parameter ends in _dec)
  GET  /payloads                    every decoded AgentPayload (full structure)
  POST /flush                       drop every payload stored in fakeintake (all routes)

Every 64-bit id is returned as a decimal STRING (``trace_id``, ``span_id``, ``parent_id``) plus a
lower-case hex form (``*_hex``); nanosecond timestamps are strings too (they exceed 2^53).
``trace_id_hex`` is the full 128-bit id: in the classic format the high 64 bits come from span meta
``_dd.p.tid`` or, for OTLP ingest, from ``otel.trace_id`` (zeros when neither is present); in the "idx"
format (AgentPayload field 11, used by the Agent for spans from ddtrace) from TraceChunk.traceID.
Id filters are hex (`trace_id`, `span_id`, `parent_id`) or decimal (`*_dec`), never guessed: an all-digit
hex id such as 00000000000000000020000000000001 is ambiguous (Datadog's own APIs treat it as decimal).

Env: FAKEINTAKE_URL (default http://fakeintake:80), PORT (default 8080).
"""

from __future__ import annotations

import base64
import gzip
import json
import os
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from compression import zstd  # Python >= 3.14
except ImportError:  # pragma: no cover - the image pins 3.15
    zstd = None

FAKEINTAKE_URL = os.environ.get("FAKEINTAKE_URL", "http://fakeintake:80").rstrip("/")
TRACES_ROUTE = "/api/v0.2/traces"
U64 = (1 << 64) - 1


# --------------------------------------------------------------------------- protobuf wire format
def _varint(buf: bytes, i: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, i
        shift += 7


def _fields(buf: bytes):
    """Yield (field_number, wire_type, value) for one message; value is int or bytes."""
    i, n = 0, len(buf)
    while i < n:
        key, i = _varint(buf, i)
        field, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(buf, i)
        elif wt == 1:
            v = buf[i : i + 8]
            i += 8
        elif wt == 2:
            ln, i = _varint(buf, i)
            v = buf[i : i + ln]
            i += ln
        elif wt == 5:
            v = buf[i : i + 4]
            i += 4
        else:
            raise ValueError(f"unsupported wire type {wt}")
        yield field, wt, v


def _s(v: bytes) -> str:
    return v.decode("utf-8", "replace")


def _i64(v: int) -> int:
    return v - (1 << 64) if v >= 1 << 63 else v


def _i32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >= 1 << 31 else v


def _f64(v: bytes) -> float:
    return struct.unpack("<d", v)[0]


def _map_entry(buf: bytes, value_kind: str):
    k, val = "", None
    for f, _, v in _fields(buf):
        if f == 1:
            k = _s(v)
        elif f == 2:
            if value_kind == "str":
                val = _s(v)
            elif value_kind == "f64":
                val = _f64(v)
            elif value_kind == "bytes":
                val = base64.b64encode(v).decode()
            elif value_kind == "anyvalue":
                val = _any_value(v)
    if val is None:
        val = {"str": "", "f64": 0.0, "bytes": "", "anyvalue": None}[value_kind]
    return k, val


def _array_value(buf: bytes):
    t, out = 0, {"type": 0}
    for f, _, v in _fields(buf):
        if f == 1:
            t = v
        elif f == 2:
            out["value"] = _s(v)
        elif f == 3:
            out["value"] = bool(v)
        elif f == 4:
            out["value"] = _i64(v)
        elif f == 5:
            out["value"] = _f64(v)
    out["type"] = ["string", "bool", "int", "double"][t] if t < 4 else t
    out.setdefault("value", {"string": "", "bool": False, "int": 0, "double": 0.0}.get(out["type"]))
    return out


def _any_value(buf: bytes):
    t, out = 0, {}
    for f, _, v in _fields(buf):
        if f == 1:
            t = v
        elif f == 2:
            out["value"] = _s(v)
        elif f == 3:
            out["value"] = bool(v)
        elif f == 4:
            out["value"] = _i64(v)
        elif f == 5:
            out["value"] = _f64(v)
        elif f == 6:
            out["value"] = [_array_value(x) for ff, _, x in _fields(v) if ff == 1]
    out["type"] = ["string", "bool", "int", "double", "array"][t] if t < 5 else t
    out.setdefault("value", {"string": "", "bool": False, "int": 0, "double": 0.0, "array": []}.get(out["type"]))
    return out


def _hex64(v: int) -> str:
    return f"{v & U64:016x}"


def _span_link(buf: bytes):
    d = {"trace_id": "0", "trace_id_high": "0", "span_id": "0", "attributes": {}, "tracestate": "", "flags": 0}
    for f, _, v in _fields(buf):
        if f == 1:
            d["trace_id"] = str(v)
        elif f == 2:
            d["trace_id_high"] = str(v)
        elif f == 3:
            d["span_id"] = str(v)
        elif f == 4:
            k, val = _map_entry(v, "str")
            d["attributes"][k] = val
        elif f == 5:
            d["tracestate"] = _s(v)
        elif f == 6:
            d["flags"] = v
    d["trace_id_hex"] = _hex64(int(d["trace_id_high"])) + _hex64(int(d["trace_id"]))
    d["span_id_hex"] = _hex64(int(d["span_id"]))
    return d


def _span_event(buf: bytes):
    d = {"time_unix_nano": "0", "name": "", "attributes": {}}
    for f, wt, v in _fields(buf):
        if f == 1:
            d["time_unix_nano"] = str(struct.unpack("<Q", v)[0] if wt == 1 else v)
        elif f == 2:
            d["name"] = _s(v)
        elif f == 3:
            k, val = _map_entry(v, "anyvalue")
            d["attributes"][k] = val
    return d


def _span(buf: bytes):
    d = {
        "service": "", "name": "", "resource": "", "trace_id": "0", "span_id": "0", "parent_id": "0",
        "start": "0", "duration": 0, "error": 0, "meta": {}, "metrics": {}, "type": "",
        "meta_struct": {}, "span_links": [], "span_events": [],
    }
    for f, _, v in _fields(buf):
        if f == 1:
            d["service"] = _s(v)
        elif f == 2:
            d["name"] = _s(v)
        elif f == 3:
            d["resource"] = _s(v)
        elif f == 4:
            d["trace_id"] = str(v)
        elif f == 5:
            d["span_id"] = str(v)
        elif f == 6:
            d["parent_id"] = str(v)
        elif f == 7:
            d["start"] = str(_i64(v))
        elif f == 8:
            d["duration"] = _i64(v)
        elif f == 9:
            d["error"] = _i32(v)
        elif f == 10:
            k, val = _map_entry(v, "str")
            d["meta"][k] = val
        elif f == 11:
            k, val = _map_entry(v, "f64")
            d["metrics"][k] = val
        elif f == 12:
            d["type"] = _s(v)
        elif f == 13:
            k, val = _map_entry(v, "bytes")
            d["meta_struct"][k] = val
        elif f == 14:
            d["span_links"].append(_span_link(v))
        elif f == 15:
            d["span_events"].append(_span_event(v))
    low = int(d["trace_id"])
    high = None
    tid = d["meta"].get("_dd.p.tid", "")
    otel = d["meta"].get("otel.trace_id", "")
    if len(tid) == 16:
        high = tid.lower()
    elif len(otel) == 32:
        high = otel[:16].lower()
    d["trace_id_high"] = high
    d["trace_id_hex"] = (high or "0" * 16) + _hex64(low)
    d["span_id_hex"] = _hex64(int(d["span_id"]))
    d["parent_id_hex"] = _hex64(int(d["parent_id"]))
    return d


def _chunk(buf: bytes):
    d = {"priority": 0, "origin": "", "spans": [], "tags": {}, "dropped_trace": False}
    for f, _, v in _fields(buf):
        if f == 1:
            d["priority"] = _i32(v)
        elif f == 2:
            d["origin"] = _s(v)
        elif f == 3:
            d["spans"].append(_span(v))
        elif f == 4:
            k, val = _map_entry(v, "str")
            d["tags"][k] = val
        elif f == 5:
            d["dropped_trace"] = bool(v)
    return d


_TP_STR = {1: "container_id", 2: "language_name", 3: "language_version", 4: "tracer_version",
           5: "runtime_id", 8: "env", 9: "hostname", 10: "app_version"}


def _tracer_payload(buf: bytes):
    d = {name: "" for name in _TP_STR.values()}
    d.update({"chunks": [], "tags": {}})
    for f, _, v in _fields(buf):
        if f in _TP_STR:
            d[_TP_STR[f]] = _s(v)
        elif f == 6:
            d["chunks"].append(_chunk(v))
        elif f == 7:
            k, val = _map_entry(v, "str")
            d["tags"][k] = val
    return d


# --------------------------------------------------------------------------- "idx" (string table) format
# pkg/proto/datadog/trace/idx/{span,tracer_payload}.proto: every string is an index into
# TracerPayload.strings; the full 128-bit trace id is TraceChunk.traceID (16 bytes, big-endian).
# Agent 7.85.4 forwards spans received from ddtrace on the native intake in this format (AgentPayload
# field 11), OTLP spans in the classic format (field 5). Both are normalized to the same span shape.
def _idx_any(buf: bytes, st: list[str]):
    for f, wt, v in _fields(buf):
        if f == 1:
            return ("str", st[v] if v < len(st) else "")
        if f == 2:
            return ("bool", bool(v))
        if f == 3:
            return ("num", _f64(v))
        if f == 4:
            return ("num", float(_i64(v)))
        if f == 5:
            return ("bytes", base64.b64encode(v).decode())
        if f == 6:
            return ("json", [_idx_any(x, st)[1] for ff, _, x in _fields(v) if ff == 1])
        if f == 7:
            out = {}
            for ff, _, kv in _fields(v):
                if ff == 1:
                    k, val = "", None
                    for f2, _, v2 in _fields(kv):
                        if f2 == 1:
                            k = st[v2] if v2 < len(st) else ""
                        elif f2 == 2:
                            val = _idx_any(v2, st)[1]
                    out[k] = val
            return ("json", out)
    return ("str", "")


def _idx_attr_entry(buf: bytes, st: list[str]):
    k, val = "", ("str", "")
    for f, _, v in _fields(buf):
        if f == 1:
            k = st[v] if v < len(st) else ""
        elif f == 2:
            val = _idx_any(v, st)
    return k, val


def _idx_put(d: dict, k: str, val) -> None:
    kind, v = val
    if kind == "num":
        d["metrics"][k] = v
    elif kind == "bytes":
        d["meta_struct"][k] = v
    elif kind == "bool":
        d["meta"][k] = "true" if v else "false"
    elif kind == "json":
        d["meta"][k] = json.dumps(v, separators=(",", ":"))
    else:
        d["meta"][k] = v


_IDX_KINDS = {0: "", 1: "internal", 2: "server", 3: "client", 4: "producer", 5: "consumer"}


def _idx_span(buf: bytes, st: list[str], trace_hex: str):
    ref = lambda i: st[i] if i < len(st) else ""  # noqa: E731
    d = {"service": "", "name": "", "resource": "", "trace_id": str(int(trace_hex[16:], 16)), "span_id": "0",
         "parent_id": "0", "start": "0", "duration": 0, "error": 0, "meta": {}, "metrics": {}, "type": "",
         "meta_struct": {}, "span_links": [], "span_events": [], "env": "", "version": "", "component": "", "kind": ""}
    for f, wt, v in _fields(buf):
        if f == 1:
            d["service"] = ref(v)
        elif f == 2:
            d["name"] = ref(v)
        elif f == 3:
            d["resource"] = ref(v)
        elif f == 4:
            d["span_id"] = str(struct.unpack("<Q", v)[0] if wt == 1 else v)
        elif f == 5:
            d["parent_id"] = str(v)
        elif f == 6:
            d["start"] = str(struct.unpack("<Q", v)[0] if wt == 1 else v)
        elif f == 7:
            d["duration"] = v
        elif f == 8:
            d["error"] = int(bool(v))
        elif f == 9:
            k, val = _idx_attr_entry(v, st)
            _idx_put(d, k, val)
        elif f == 10:
            d["type"] = ref(v)
        elif f == 11:
            lk = {"trace_id_hex": "", "span_id": "0", "attributes": {}, "tracestate": "", "flags": 0}
            for f2, wt2, v2 in _fields(v):
                if f2 == 1:
                    lk["trace_id_hex"] = v2.hex().rjust(32, "0")
                elif f2 == 2:
                    lk["span_id"] = str(struct.unpack("<Q", v2)[0] if wt2 == 1 else v2)
                elif f2 == 3:
                    k, val = _idx_attr_entry(v2, st)
                    lk["attributes"][k] = val[1]
                elif f2 == 4:
                    lk["tracestate"] = ref(v2)
                elif f2 == 5:
                    lk["flags"] = v2
            lk["span_id_hex"] = _hex64(int(lk["span_id"]))
            d["span_links"].append(lk)
        elif f == 12:
            ev = {"time_unix_nano": "0", "name": "", "attributes": {}}
            for f2, wt2, v2 in _fields(v):
                if f2 == 1:
                    ev["time_unix_nano"] = str(struct.unpack("<Q", v2)[0] if wt2 == 1 else v2)
                elif f2 == 2:
                    ev["name"] = ref(v2)
                elif f2 == 3:
                    k, val = _idx_attr_entry(v2, st)
                    ev["attributes"][k] = val[1]
            d["span_events"].append(ev)
        elif f == 13:
            d["env"] = ref(v)
        elif f == 14:
            d["version"] = ref(v)
        elif f == 15:
            d["component"] = ref(v)
        elif f == 16:
            d["kind"] = _IDX_KINDS.get(v, str(v))
    # mirror the classic-format conventions so tests can treat both formats alike
    for k in ("env", "version", "component"):
        if d[k] and k not in d["meta"]:
            d["meta"][k] = d[k]
    if d["kind"] and "span.kind" not in d["meta"]:
        d["meta"]["span.kind"] = d["kind"]
    high = trace_hex[:16]
    d["trace_id_high"] = high if int(high, 16) else None
    d["trace_id_hex"] = trace_hex
    d["span_id_hex"] = _hex64(int(d["span_id"]))
    d["parent_id_hex"] = _hex64(int(d["parent_id"]))
    d["format"] = "idx"
    return d


def _idx_chunk(buf: bytes, st: list[str]):
    d = {"priority": 0, "origin": "", "tags": {}, "dropped_trace": False, "sampling_mechanism": 0}
    trace_hex = "0" * 32
    span_bufs = []
    for f, _, v in _fields(buf):
        if f == 1:
            d["priority"] = _i32(v)
        elif f == 2:
            d["origin"] = st[v] if v < len(st) else ""
        elif f == 3:
            k, val = _idx_attr_entry(v, st)
            d["tags"][k] = val[1] if isinstance(val[1], str) else json.dumps(val[1])
        elif f == 4:
            span_bufs.append(v)
        elif f == 5:
            d["dropped_trace"] = bool(v)
        elif f == 6:
            trace_hex = v.hex().rjust(32, "0")
        elif f == 7:
            d["sampling_mechanism"] = v
    d["spans"] = [_idx_span(b, st, trace_hex) for b in span_bufs]
    return d


_IDX_TP_REF = {2: "container_id", 3: "language_name", 4: "language_version", 5: "tracer_version",
               6: "runtime_id", 7: "env", 8: "hostname", 9: "app_version"}


def _idx_tracer_payload(buf: bytes):
    fields = list(_fields(buf))
    st = [_s(v) for f, _, v in fields if f == 1]
    d = {name: "" for name in _IDX_TP_REF.values()}
    d.update({"chunks": [], "tags": {}, "format": "idx"})
    for f, _, v in fields:
        if f in _IDX_TP_REF:
            d[_IDX_TP_REF[f]] = st[v] if v < len(st) else ""
        elif f == 10:
            k, val = _idx_attr_entry(v, st)
            d["tags"][k] = val[1] if isinstance(val[1], str) else json.dumps(val[1])
        elif f == 11:
            d["chunks"].append(_idx_chunk(v, st))
    return d


def decode_agent_payload(buf: bytes):
    d = {"host_name": "", "env": "", "tracer_payloads": [], "tags": {}, "agent_version": "",
         "target_tps": 0.0, "error_tps": 0.0, "rare_sampler_enabled": False, "idx_tracer_payloads": 0,
         "undecodable_idx_payloads": 0}
    for f, _, v in _fields(buf):
        if f == 1:
            d["host_name"] = _s(v)
        elif f == 2:
            d["env"] = _s(v)
        elif f == 5:
            d["tracer_payloads"].append(_tracer_payload(v))
        elif f == 6:
            k, val = _map_entry(v, "str")
            d["tags"][k] = val
        elif f == 7:
            d["agent_version"] = _s(v)
        elif f == 8:
            d["target_tps"] = _f64(v)
        elif f == 9:
            d["error_tps"] = _f64(v)
        elif f == 10:
            d["rare_sampler_enabled"] = bool(v)
        elif f == 11:
            # string-table ("idx") format: decoded into the same shape, marked "format": "idx"
            d["idx_tracer_payloads"] += 1
            try:
                d["tracer_payloads"].append(_idx_tracer_payload(v))
            except (ValueError, IndexError, struct.error):
                d["undecodable_idx_payloads"] += 1
    return d


# --------------------------------------------------------------------------- fakeintake access
def _get(path: str, method: str = "GET") -> bytes:
    req = urllib.request.Request(FAKEINTAKE_URL + path, method=method)
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.read()


def _inflate(data: bytes, encoding: str) -> bytes:
    enc = (encoding or "").lower()
    if enc == "zstd":
        if zstd is None:
            raise RuntimeError("zstd payload but this Python has no compression.zstd")
        return zstd.decompress(data)
    if enc == "gzip":
        return gzip.decompress(data)
    if enc == "deflate":
        return zlib.decompress(data)
    return data


def load_payloads():
    raw = json.loads(_get("/fakeintake/payloads?endpoint=" + urllib.parse.quote(TRACES_ROUTE, safe="")) or b"{}")
    out = []
    for p in raw.get("payloads") or []:
        body = _inflate(base64.b64decode(p.get("data") or ""), p.get("encoding", ""))
        ap = decode_agent_payload(body)
        ap["received_at"] = p.get("timestamp", "")
        ap["content_encoding"] = p.get("encoding", "")
        out.append(ap)
    return out


def flat_spans(payloads):
    for ap in payloads:
        agent = {"hostname": ap["host_name"], "env": ap["env"], "agent_version": ap["agent_version"], "tags": ap["tags"],
                 "received_at": ap["received_at"]}
        for tp in ap["tracer_payloads"]:
            tracer = {k: tp[k] for k in tp if k != "chunks"}
            for ch in tp["chunks"]:
                chunk = {k: ch[k] for k in ch if k != "spans"}
                for sp in ch["spans"]:
                    yield dict(sp, chunk=chunk, tracer=tracer, agent=agent)


def _id_matcher(value: str, field: str, decimal: bool):
    v = value.strip().lower()
    try:
        if decimal:  # *_dec: decimal (Datadog style); a trace id above 2^64 also pins the high half
            n = int(v, 10)
            return lambda s: int(s[field]) == (n & U64) and (n >> 64 == 0 or (s.get("trace_id_high") or "") == _hex64(n >> 64))
        v = v.removeprefix("0x")
        n = int(v, 16)
    except ValueError:
        raise ValueError(f"malformed id {value!r} for {field}{'_dec' if decimal else ''}") from None
    if field == "trace_id" and len(v) > 16:  # full 128-bit id; spans without a known high half match on the low half
        v = v.rjust(32, "0")
        return lambda s: s["trace_id_hex"] == v or (s["trace_id_high"] is None and s["trace_id_hex"][16:] == v[16:])
    return lambda s: int(s[field]) == n


def select(spans, query: dict[str, list[str]]):
    preds = []
    for key, values in query.items():
        val = values[-1]
        if key in ("trace_id", "span_id", "parent_id"):
            preds.append(_id_matcher(val, key, False))
        elif key in ("trace_id_dec", "span_id_dec", "parent_id_dec"):
            preds.append(_id_matcher(val, key[:-4], True))
        elif key in ("service", "name", "resource", "type"):
            preds.append(lambda s, k=key, v=val: s[k] == v)
        elif key == "env":
            preds.append(lambda s, v=val: s["meta"].get("env") == v)
        elif key.startswith("meta."):
            preds.append(lambda s, k=key[5:], v=val: s["meta"].get(k) == v)
        else:
            raise ValueError(f"unknown filter {key!r}")
    return [s for s in spans if all(p(s) for p in preds)]


# --------------------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "trace-reader/1"

    def log_message(self, fmt, *args):  # quiet
        pass

    def _send(self, status: int, doc) -> None:
        body = json.dumps(doc, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        url = urllib.parse.urlsplit(self.path)
        try:
            if url.path == "/health":
                self._send(200, {"status": "ok"})
            elif url.path == "/payloads":
                self._send(200, {"payloads": load_payloads()})
            elif url.path == "/spans":
                payloads = load_payloads()
                spans = select(list(flat_spans(payloads)), urllib.parse.parse_qs(url.query))
                spans.sort(key=lambda s: (int(s["start"]), s["span_id_hex"]))
                self._send(200, {"spans": spans, "payloads": len(payloads),
                                 "idx_tracer_payloads": sum(p["idx_tracer_payloads"] for p in payloads),
                                 "undecodable_idx_payloads": sum(p["undecodable_idx_payloads"] for p in payloads)})
            else:
                self._send(404, {"error": "not found"})
        except ValueError as exc:
            self._send(400, {"error": str(exc)})
        except (urllib.error.URLError, OSError) as exc:
            self._send(502, {"error": f"fakeintake: {exc}"})

    def do_POST(self):  # noqa: N802
        if urllib.parse.urlsplit(self.path).path == "/flush":
            try:
                _get("/fakeintake/flushPayloads")
                self._send(200, {"flushed": True})
            except (urllib.error.URLError, OSError) as exc:
                self._send(502, {"error": f"fakeintake: {exc}"})
        else:
            self._send(404, {"error": "not found"})


def main() -> int:
    if len(sys.argv) > 2 and sys.argv[1] == "decode":  # offline: reader.py decode <fakeintake raw json>
        raw = json.load(open(sys.argv[2]))
        payloads = [decode_agent_payload(_inflate(base64.b64decode(p["data"]), p.get("encoding", "")))
                    for p in raw.get("payloads") or []]
        json.dump(payloads, sys.stdout, indent=1)
        return 0
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
