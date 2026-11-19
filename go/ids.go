package datadogapm

// Datadog <-> OpenTelemetry id conversion (API.md §3, DECISIONS.md #3).
//
// Datadog stores a trace id as the low 64 bits in unsigned decimal plus, for
// 128-bit ids, the high 64 bits as 16 lower-case hex characters (_dd.p.tid).
// OpenTelemetry uses 32 hex characters. Span ids are unsigned 64-bit decimals
// in Datadog and 16 hex characters in OpenTelemetry.

import (
	"math/big"
	"strconv"
	"strings"
)

const zeroHigh = "0000000000000000"

// DatadogTraceID is a trace id split the way Datadog stores it.
type DatadogTraceID struct {
	// Low64Decimal is the low 64 bits as an unsigned decimal string
	// (x-datadog-trace-id, the Agent's trace_id).
	Low64Decimal string
	// High64Hex is the high 64 bits as 16 lower-case hex characters (_dd.p.tid);
	// all zeros for a 64-bit id.
	High64Hex string
	// Is128Bit is true when the high 64 bits are not zero (the id needs _dd.p.tid).
	Is128Bit bool
}

func invalidID(what string) error { return ValidationError("datadog id", what) }

func isHex(s string) bool {
	for _, c := range s {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func allZeros(s string) bool { return strings.Trim(s, "0") == "" }

func hexTraceID(traceIDHex string) (string, error) {
	h := strings.ToLower(strings.TrimSpace(traceIDHex))
	if h == "" || len(h) > 32 || !isHex(h) {
		return "", invalidID("trace id must be 1 to 32 hex characters")
	}
	h = strings.Repeat("0", 32-len(h)) + h
	if allZeros(h) {
		return "", invalidID("trace id must not be all zeros")
	}
	return h, nil
}

func parseU64(decimal, what string) (uint64, error) {
	t := strings.TrimSpace(decimal)
	if !isDigits(t) {
		return 0, invalidID(what + " must be an unsigned decimal")
	}
	v, err := strconv.ParseUint(t, 10, 64)
	if err != nil {
		return 0, invalidID(what + " exceeds 2^64-1")
	}
	return v, nil
}

func hex16(v uint64) string {
	s := strconv.FormatUint(v, 16)
	return strings.Repeat("0", 16-len(s)) + s
}

// ToDatadogTraceID splits an OpenTelemetry trace id (up to 32 hex characters,
// left-padded) into Datadog's form. Invalid or all-zero ids are VALIDATION errors.
//
//	ToDatadogTraceID("a3ce929d0e0e47364bf92f3577b34da6")
//	// {Low64Decimal: "5474458728733560230", High64Hex: "a3ce929d0e0e4736", Is128Bit: true}
func ToDatadogTraceID(traceIDHex string) (DatadogTraceID, error) {
	h, err := hexTraceID(traceIDHex)
	if err != nil {
		return DatadogTraceID{}, err
	}
	low, _ := strconv.ParseUint(h[16:], 16, 64)
	high := h[:16]
	return DatadogTraceID{Low64Decimal: strconv.FormatUint(low, 10), High64Hex: high, Is128Bit: high != zeroHigh}, nil
}

// FromDatadogTraceID joins a Datadog trace id (low 64 bits in decimal, optional
// _dd.p.tid high half in hex) into a 32-character OpenTelemetry trace id. An
// empty high64Hex means a zero high half.
//
//	FromDatadogTraceID("1", "ffffffffffffffff") // "ffffffffffffffff0000000000000001"
//	FromDatadogTraceID("42", "")                // "0000000000000000000000000000002a"
func FromDatadogTraceID(low64Decimal, high64Hex string) (string, error) {
	low, err := parseU64(low64Decimal, "trace id")
	if err != nil {
		return "", err
	}
	high := zeroHigh
	if high64Hex != "" {
		high = strings.ToLower(strings.TrimSpace(high64Hex))
		if len(high) != 16 || !isHex(high) {
			return "", invalidID("high 64 bits must be 16 hex characters")
		}
	}
	id := high + hex16(low)
	if allZeros(id) {
		return "", invalidID("trace id must not be all zeros")
	}
	return id, nil
}

// ToDatadogSpanID converts an OpenTelemetry span id (up to 16 hex characters) to
// Datadog's unsigned decimal.
func ToDatadogSpanID(spanIDHex string) (string, error) {
	h := strings.ToLower(strings.TrimSpace(spanIDHex))
	if h == "" || len(h) > 16 || !isHex(h) {
		return "", invalidID("span id must be 1 to 16 hex characters")
	}
	v, _ := strconv.ParseUint(h, 16, 64)
	return strconv.FormatUint(v, 10), nil
}

// FromDatadogSpanID converts a Datadog unsigned decimal span id to 16 lower-case
// hex characters.
func FromDatadogSpanID(decimal string) (string, error) {
	v, err := parseU64(decimal, "span id")
	if err != nil {
		return "", err
	}
	return hex16(v), nil
}

// DatadogTraceIDString returns the trace id in the format Datadog's log
// correlation (dd.trace_id) and its search API use: 32 lower-case hex
// characters for a 128-bit id, the low 64 bits in decimal for a 64-bit id.
func DatadogTraceIDString(traceIDHex string) (string, error) {
	id, err := ToDatadogTraceID(traceIDHex)
	if err != nil {
		return "", err
	}
	if id.Is128Bit {
		return hexTraceID(traceIDHex)
	}
	return id.Low64Decimal, nil
}

// NormalizeDatadogTraceID normalizes any trace id Datadog returns to 32
// lower-case hex characters: a 32-character value is hex, a shorter all-digit
// value (at most 20 digits) is the 64-bit decimal form, anything else is hex.
func NormalizeDatadogTraceID(value string) (string, error) {
	t := strings.ToLower(strings.TrimSpace(value))
	if len(t) == 32 && isHex(t) {
		return t, nil
	}
	if isDigits(t) && len(t) <= 20 {
		return FromDatadogTraceID(t, "")
	}
	return hexTraceID(t)
}

// NormalizeDatadogSpanID normalizes a Datadog decimal span id to 16 hex
// characters; it returns "" for an empty or zero id.
func NormalizeDatadogSpanID(value string) (string, error) {
	t := strings.TrimSpace(value)
	if t == "" || (isDigits(t) && allZeros(t)) {
		return "", nil
	}
	return FromDatadogSpanID(t)
}

// TraceIDForPath returns the id to put in a GET /api/v2/trace/{id} path.
// Datadog reads an all-digit id as decimal, so a hex id without a letter is
// sent as its full decimal value; otherwise lower-case hex.
func TraceIDForPath(traceIDHex string) (string, error) {
	h, err := hexTraceID(traceIDHex)
	if err != nil {
		return "", err
	}
	if strings.ContainsAny(h, "abcdef") {
		return h, nil
	}
	v, _ := new(big.Int).SetString(h, 16)
	return v.String(), nil
}
