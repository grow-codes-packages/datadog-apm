package datadogapm

// Datadog span formats → the SPEC §5 trace model (API.md §2.1, §4).
//
// Two Datadog shapes are mapped:
//   - a trace span as the Agent forwards it and as GET /api/v2/trace/{id}
//     returns it (meta, metrics, nanosecond start/startTime, decimal ids;
//     camelCase or snake_case keys);
//   - a span event of the spans search API (attributes.trace_id,
//     start_timestamp, …).
//
// For spans that came in over OTLP the Datadog resource is the OpenTelemetry
// span name and the Datadog name is the operation (Internal, server.request,
// http.server.request, …), so the normalized Name is the resource; the Datadog
// operation is kept as attribute operation_name.

import (
	"bytes"
	"encoding/json"
	"math"
	"math/big"
	"strconv"
	"strings"
	"time"
)

// RawDatadogSpan is a raw span object (any Datadog span shape) decoded from JSON.
// Decode with json.Decoder.UseNumber (or DecodeJSONPreservingNumbers) so ids
// above 2^53 stay exact.
type RawDatadogSpan = map[string]any

// DecodeJSONPreservingNumbers decodes JSON like json.Unmarshal but keeps every
// number as a json.Number, so ids and nanosecond timestamps above 2^53 (the
// trace-by-id API returns them as JSON numbers) stay exact.
func DecodeJSONPreservingNumbers(data []byte, out any) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	return dec.Decode(out)
}

// DatadogKind converts a Datadog span.kind (or OpenTelemetry kind name) to a
// SpanKind; unknown values are SpanKindUnspecified.
func DatadogKind(value any) SpanKind {
	s, ok := value.(string)
	if !ok {
		return SpanKindUnspecified
	}
	switch k := SpanKind(strings.ToLower(s)); k {
	case SpanKindInternal, SpanKindServer, SpanKindClient, SpanKindProducer, SpanKindConsumer:
		return k
	}
	return SpanKindUnspecified
}

// first returns the first value of m under keys that is present and not nil.
func first(m map[string]any, keys ...string) any {
	for _, k := range keys {
		if v, ok := m[k]; ok && v != nil {
			return v
		}
	}
	return nil
}

// strOf renders strings and numbers as text; "" for anything else or an empty string.
func strOf(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case json.Number:
		return x.String()
	case float64:
		if x == math.Trunc(x) && math.Abs(x) < 1e21 {
			return strconv.FormatFloat(x, 'f', -1, 64)
		}
		return strconv.FormatFloat(x, 'g', -1, 64)
	case int:
		return strconv.Itoa(x)
	case int64:
		return strconv.FormatInt(x, 10)
	case uint64:
		return strconv.FormatUint(x, 10)
	case *big.Int:
		if x != nil {
			return x.String()
		}
	}
	return ""
}

func recordOf(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	return map[string]any{}
}

// int64Of parses an integer (number or decimal string); ok is false otherwise.
func int64Of(v any) (int64, bool) {
	switch x := v.(type) {
	case float64:
		if math.IsNaN(x) || math.IsInf(x, 0) {
			return 0, false
		}
		return int64(x), true
	case int:
		return int64(x), true
	case int64:
		return x, true
	case json.Number:
		if n, err := x.Int64(); err == nil {
			return n, true
		}
		if f, err := x.Float64(); err == nil && strings.ContainsAny(x.String(), ".eE") {
			return int64(f), true
		}
	case string:
		if n, err := strconv.ParseInt(strings.TrimSpace(x), 10, 64); err == nil {
			return n, true
		}
	}
	return 0, false
}

// numberOf converts a JSON number to int64 (integral) or float64.
func numberOf(v any) (any, bool) {
	switch x := v.(type) {
	case float64:
		return x, true
	case int:
		return int64(x), true
	case int64:
		return x, true
	case json.Number:
		if n, err := x.Int64(); err == nil {
			return n, true
		}
		if f, err := x.Float64(); err == nil {
			return f, true
		}
	}
	return nil, false
}

// attrValue converts decoded JSON to an attribute value (nil is dropped).
func attrValue(v any) (any, bool) {
	switch x := v.(type) {
	case nil:
		return nil, false
	case string, bool:
		return x, true
	case []any:
		out := make([]any, 0, len(x))
		for _, it := range x {
			if a, ok := attrValue(it); ok {
				out = append(out, a)
			}
		}
		return out, true
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, it := range x {
			if a, ok := attrValue(it); ok {
				out[k] = a
			}
		}
		return out, true
	}
	if n, ok := numberOf(v); ok {
		return n, true
	}
	if s := strOf(v); s != "" {
		return s, true
	}
	return nil, false
}

func attrsOf(v any) map[string]any {
	out := map[string]any{}
	for k, it := range recordOf(v) {
		if a, ok := attrValue(it); ok {
			out[k] = a
		}
	}
	return out
}

func parseJSONArray(v any) []map[string]any {
	text, ok := v.(string)
	if !ok || text == "" {
		return nil
	}
	var items []any
	if DecodeJSONPreservingNumbers([]byte(text), &items) != nil {
		return nil
	}
	out := make([]map[string]any, len(items))
	for i, it := range items {
		out[i] = recordOf(it)
	}
	return out
}

func nanosToMicros(ns int64) int64 { return ns / 1000 }

// nativeAttr turns a native Datadog span event attribute ({type, string_value, …}) into a plain value.
func nativeAttr(v any) any {
	m := recordOf(v)
	for _, k := range []string{"string_value", "int_value", "double_value", "bool_value"} {
		if x, ok := m[k]; ok {
			return x
		}
	}
	if arr, ok := m["array_value"]; ok {
		values, _ := recordOf(arr)["values"].([]any)
		out := make([]any, len(values))
		for i, it := range values {
			out[i] = nativeAttr(it)
		}
		return out
	}
	return v
}

func eventsOf(meta map[string]any, raw RawDatadogSpan) []SpanEvent {
	var out []SpanEvent
	for _, e := range parseJSONArray(meta["events"]) {
		ns, _ := int64Of(e["time_unix_nano"])
		out = append(out, SpanEvent{Name: strOf(e["name"]), TimeMicros: nanosToMicros(ns), Attributes: attrsOf(e["attributes"])})
	}
	native, _ := first(raw, "span_events", "spanEvents").([]any)
	for _, item := range native {
		e := recordOf(item)
		ns, _ := int64Of(first(e, "time_unix_nano", "timeUnixNano"))
		a := map[string]any{}
		for k, v := range recordOf(e["attributes"]) {
			a[k] = nativeAttr(v)
		}
		out = append(out, SpanEvent{Name: strOf(e["name"]), TimeMicros: nanosToMicros(ns), Attributes: attrsOf(a)})
	}
	return out
}

func isHex16(s string) bool { return len(s) == 16 && isHex(s) }

func linkTraceID(l map[string]any) (string, bool) {
	id := strOf(first(l, "trace_id", "traceId", "traceID"))
	if id == "" {
		return "", false
	}
	high := strOf(first(l, "trace_id_high", "traceIdHigh"))
	if high != "" && isDigits(id) {
		highHex := high
		if !isHex16(high) {
			n, ok := new(big.Int).SetString(high, 10)
			if !ok {
				return "", false
			}
			highHex = n.Text(16)
			if len(highHex) < 16 {
				highHex = strings.Repeat("0", 16-len(highHex)) + highHex
			}
		}
		out, err := FromDatadogTraceID(id, highHex)
		return out, err == nil
	}
	if isHex16(id) {
		return zeroHigh + id, true
	}
	out, err := NormalizeDatadogTraceID(id)
	return out, err == nil
}

func linkSpanID(l map[string]any) (string, bool) {
	id := strOf(first(l, "span_id", "spanId", "spanID"))
	if id == "" {
		return "", false
	}
	if isHex16(id) && strings.ContainsAny(id, "abcdef") {
		return id, true
	}
	out, err := NormalizeDatadogSpanID(id)
	return out, err == nil && out != ""
}

func linksOf(meta map[string]any, raw RawDatadogSpan) []SpanLink {
	items := parseJSONArray(meta["_dd.span_links"])
	native, _ := first(raw, "span_links", "spanLinks").([]any)
	for _, it := range native {
		items = append(items, recordOf(it))
	}
	var out []SpanLink
	for _, l := range items {
		traceID, ok1 := linkTraceID(l)
		spanID, ok2 := linkSpanID(l)
		if ok1 && ok2 {
			out = append(out, SpanLink{TraceID: traceID, SpanID: spanID, Attributes: attrsOf(l["attributes"])})
		}
	}
	return out
}

// numericTruthy mirrors JavaScript's Number(v) !== 0 for flags such as "error".
func numericTruthy(v any) bool {
	switch x := v.(type) {
	case nil:
		return false
	case bool:
		return x
	case string:
		t := strings.TrimSpace(x)
		if t == "" {
			return false
		}
		f, err := strconv.ParseFloat(t, 64)
		return err != nil || f != 0
	case map[string]any, []any:
		return false
	}
	if n, ok := numberOf(v); ok {
		switch y := n.(type) {
		case int64:
			return y != 0
		case float64:
			return y != 0
		}
	}
	return false
}

// DatadogTraceSpanToSpan maps a Datadog trace span — the Agent's format
// (trace_id, span_id, parent_id, start, duration, meta, metrics) or the
// trace-by-id API format (traceID, traceIDFull, spanID, parentID, startTime,
// endTime, duration) — to a SPEC Span.
//
// The full trace id comes from traceIDFull / trace_id_hex / meta otel.trace_id,
// else the decimal low 64 bits plus meta _dd.p.tid. Name = Datadog resource
// (the OTel span name for OTLP spans), Kind = meta span.kind, status = error
// flag (message from error.message, error.msg or otel.status_description) or
// meta otel.status_code. Events come from meta events and span_events, links
// from meta _dd.span_links and span_links. Attributes are the remaining meta
// (strings) and metrics (numbers), plus operation_name, resource_name and
// span.type. Invalid ids are VALIDATION errors.
func DatadogTraceSpanToSpan(raw RawDatadogSpan) (Span, error) {
	meta := recordOf(raw["meta"])
	metrics := recordOf(raw["metrics"])
	var traceID string
	var err error
	if full := strOf(first(raw, "traceIDFull", "trace_id_hex")); full != "" || strOf(meta["otel.trace_id"]) != "" {
		if full == "" {
			full = strOf(meta["otel.trace_id"])
		}
		if len(full) < 32 {
			full = strings.Repeat("0", 32-len(full)) + full
		}
		traceID, err = NormalizeDatadogTraceID(full)
	} else {
		low := strOf(first(raw, "traceID", "trace_id", "traceId"))
		if low == "" {
			low = "0"
		}
		high := first(meta, "_dd.p.tid")
		if high == nil {
			high = raw["trace_id_high"]
		}
		traceID, err = FromDatadogTraceID(low, strOf(high))
	}
	if err != nil {
		return Span{}, err
	}
	spanRaw := strOf(first(raw, "spanID", "span_id", "spanId"))
	if spanRaw == "" {
		spanRaw = "0"
	}
	spanID, err := requireSpanID(spanRaw)
	if err != nil {
		return Span{}, err
	}
	parentID, err := NormalizeDatadogSpanID(strOf(first(raw, "parentID", "parent_id", "parentId")))
	if err != nil {
		return Span{}, err
	}
	startNs, _ := int64Of(first(raw, "startTime", "start"))
	endNs, hasEnd := int64Of(first(raw, "endTime", "end"))
	durationNs, hasDuration := int64Of(raw["duration"])
	if !hasDuration && hasEnd {
		durationNs = endNs - startNs
	}
	if !hasEnd {
		endNs = startNs + durationNs
	}
	ddName := strOf(raw["name"])
	resource := strOf(first(raw, "resource", "resource_name"))
	spanType := strOf(raw["type"])

	attributes := map[string]any{}
	for k, v := range meta {
		if s, ok := v.(string); ok && k != "events" && k != "_dd.span_links" {
			attributes[k] = s
		}
	}
	for k, v := range metrics {
		if n, ok := numberOf(v); ok {
			attributes[k] = n
		} else if s, ok := v.(string); ok && s != "" {
			if f, err := strconv.ParseFloat(s, 64); err == nil && !math.IsInf(f, 0) && !math.IsNaN(f) {
				attributes[k] = f
			}
		}
	}
	if ddName != "" {
		attributes["operation_name"] = ddName
	}
	if resource != "" {
		attributes["resource_name"] = resource
	}
	if spanType != "" {
		attributes["span.type"] = spanType
	}

	status := SpanStatus{Code: SpanStatusUnset}
	if numericTruthy(raw["error"]) {
		status = SpanStatus{Code: SpanStatusError, Message: strOf(first(meta, "error.message", "error.msg", "otel.status_description"))}
	} else if strings.EqualFold(strOf(meta["otel.status_code"]), "ok") {
		status.Code = SpanStatusOK
	}
	name := resource
	if name == "" {
		name = ddName
	}
	return Span{
		TraceID:        traceID,
		SpanID:         spanID,
		ParentSpanID:   parentID,
		Name:           name,
		Kind:           DatadogKind(meta["span.kind"]),
		ServiceName:    strOf(raw["service"]),
		StartMicros:    nanosToMicros(startNs),
		EndMicros:      nanosToMicros(endNs),
		DurationMicros: nanosToMicros(durationNs),
		Status:         status,
		Attributes:     attributes,
		Events:         eventsOf(meta, raw),
		Links:          linksOf(meta, raw),
	}, nil
}

func flatten(prefix string, v any, out map[string]any) {
	if m, ok := v.(map[string]any); ok {
		for k, it := range m {
			key := k
			if prefix != "" {
				key = prefix + "." + k
			}
			flatten(key, it, out)
		}
		return
	}
	if a, ok := attrValue(v); ok && prefix != "" {
		out[prefix] = a
	}
}

// isoToMicros parses an ISO-8601 timestamp to Unix microseconds.
func isoToMicros(v any) (int64, bool) {
	s, ok := v.(string)
	if !ok || s == "" {
		return 0, false
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05.999999999Z0700"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t.UnixMicro(), true
		}
	}
	return 0, false
}

// DatadogSearchSpanToSpan maps one span of the spans search API (data[i] of
// POST /api/v2/spans/events/search, or its attributes) to a SPEC Span. Nested
// span attributes (attributes, custom) are flattened to dotted keys; tags
// (key:value) are added as attributes. Name = resource_name, kind from
// span.kind, status error when status is "error" or error is set; duration from
// the duration attribute (nanoseconds) when present, else end − start.
func DatadogSearchSpanToSpan(item RawDatadogSpan) (Span, error) {
	a := item
	if inner, ok := item["attributes"]; ok && item["type"] == "spans" {
		a = recordOf(inner)
	}
	attributes := map[string]any{}
	tags, _ := a["tags"].([]any)
	for _, t := range tags {
		if s, ok := t.(string); ok {
			if k, v, found := strings.Cut(s, ":"); found && k != "" {
				attributes[k] = v
			}
		}
	}
	flatten("", a["custom"], attributes)
	flatten("", a["attributes"], attributes)
	for _, key := range []string{"env", "host", "type", "resource_hash", "ingestion_reason"} {
		if v := strOf(a[key]); v != "" {
			if key == "type" {
				attributes["span.type"] = v
			} else {
				attributes[key] = v
			}
		}
	}
	opName := strOf(first(a, "operation_name"))
	if opName == "" {
		opName = strOf(attributes["operation_name"])
	}
	if opName != "" {
		attributes["operation_name"] = opName
	}
	resource := strOf(first(a, "resource_name"))
	if resource == "" {
		resource = strOf(attributes["resource_name"])
	}
	if resource != "" {
		attributes["resource_name"] = resource
	}
	startMicros, _ := isoToMicros(a["start_timestamp"])
	endMicros, hasEnd := isoToMicros(a["end_timestamp"])
	var durationMicros int64
	if d, ok := int64Of(attributes["duration"]); ok {
		durationMicros = nanosToMicros(d)
	} else if hasEnd {
		durationMicros = endMicros - startMicros
	}
	if !hasEnd {
		endMicros = startMicros + durationMicros
	}
	statusWord := strings.ToLower(strOf(first(a, "status")))
	if statusWord == "" {
		statusWord = strings.ToLower(strOf(attributes["status"]))
	}
	status := SpanStatus{Code: SpanStatusUnset}
	if statusWord == "error" || numericTruthy(attributes["error"]) {
		status = SpanStatus{Code: SpanStatusError, Message: strOf(first(attributes, "error.message", "error.msg"))}
	} else if strings.EqualFold(strOf(attributes["otel.status_code"]), "ok") {
		status.Code = SpanStatusOK
	}
	traceRaw := strOf(a["trace_id"])
	if traceRaw == "" {
		traceRaw = "0"
	}
	traceID, err := NormalizeDatadogTraceID(traceRaw)
	if err != nil {
		return Span{}, err
	}
	spanRaw := strOf(a["span_id"])
	if spanRaw == "" {
		spanRaw = "0"
	}
	spanID, err := requireSpanID(spanRaw)
	if err != nil {
		return Span{}, err
	}
	parentID, err := NormalizeDatadogSpanID(strOf(a["parent_id"]))
	if err != nil {
		return Span{}, err
	}
	name := resource
	if name == "" {
		name = opName
	}
	return Span{
		TraceID:        traceID,
		SpanID:         spanID,
		ParentSpanID:   parentID,
		Name:           name,
		Kind:           DatadogKind(attributes["span.kind"]),
		ServiceName:    strOf(a["service"]),
		StartMicros:    startMicros,
		EndMicros:      endMicros,
		DurationMicros: durationMicros,
		Status:         status,
		Attributes:     attributes,
		Events:         []SpanEvent{},
		Links:          []SpanLink{},
	}, nil
}

// requireSpanID normalizes a span's own id; a missing or zero id is a
// VALIDATION error (same rule in every language).
func requireSpanID(raw string) (string, error) {
	id, err := NormalizeDatadogSpanID(raw)
	if err != nil {
		return "", err
	}
	if id == "" {
		return "", ValidationError("datadog span", "span id is missing or zero")
	}
	return id, nil
}
