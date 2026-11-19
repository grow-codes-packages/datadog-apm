//! Datadog <-> OpenTelemetry id conversion.
//!
//! Datadog stores a trace id as the low 64 bits in unsigned decimal plus, for 128-bit ids, the
//! high 64 bits as 16 lower-case hex characters (`_dd.p.tid`). OpenTelemetry uses 32 hex
//! characters. Span ids are unsigned 64-bit decimals in Datadog and 16 hex characters in
//! OpenTelemetry.

use serde::Serialize;

use crate::core::{Error, Result};

const ZERO_HIGH: &str = "0000000000000000";

/// A trace id split the way Datadog stores it.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DatadogTraceId {
    /// Low 64 bits as an unsigned decimal string (`x-datadog-trace-id`, the Agent's `trace_id`).
    pub low64_decimal: String,
    /// High 64 bits as 16 lower-case hex characters (`_dd.p.tid`); all zeros for a 64-bit id.
    pub high64_hex: String,
    /// `true` when the high 64 bits are not zero (the id needs `_dd.p.tid`).
    #[serde(rename = "is128Bit")]
    pub is_128_bit: bool,
}

fn invalid(what: &str) -> Error {
    Error::validation(format!("datadog id: {what}"))
}

pub(crate) fn is_hex(s: &str) -> bool {
    s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

pub(crate) fn is_digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

pub(crate) fn is_hex16(s: &str) -> bool {
    s.len() == 16 && is_hex(s)
}

/// Parses and left-pads an OpenTelemetry trace id to 32 lower-case hex characters.
fn hex_trace_id(trace_id_hex: &str) -> Result<u128> {
    let h = trace_id_hex.trim().to_ascii_lowercase();
    if h.is_empty() || h.len() > 32 || !is_hex(&h) {
        return Err(invalid("trace id must be 1 to 32 hex characters"));
    }
    let v = u128::from_str_radix(&h, 16).map_err(|_| invalid("trace id is not hex"))?;
    if v == 0 {
        return Err(invalid("trace id must not be all zeros"));
    }
    Ok(v)
}

fn parse_u64(decimal: &str, what: &str) -> Result<u64> {
    let t = decimal.trim();
    if !is_digits(t) {
        return Err(invalid(&format!("{what} must be an unsigned decimal")));
    }
    t.parse::<u64>()
        .map_err(|_| invalid(&format!("{what} exceeds 2^64-1")))
}

/// Splits an OpenTelemetry trace id (up to 32 hex characters, left-padded) into Datadog's form.
/// Invalid or all-zero ids are `VALIDATION` errors.
///
/// ```
/// let id = grow_datadog_apm::to_datadog_trace_id("a3ce929d0e0e47364bf92f3577b34da6").unwrap();
/// assert_eq!(id.low64_decimal, "5474458728733560230");
/// assert_eq!(id.high64_hex, "a3ce929d0e0e4736");
/// assert!(id.is_128_bit);
/// ```
pub fn to_datadog_trace_id(trace_id_hex: &str) -> Result<DatadogTraceId> {
    let v = hex_trace_id(trace_id_hex)?;
    let high = (v >> 64) as u64;
    Ok(DatadogTraceId {
        low64_decimal: (v as u64).to_string(),
        high64_hex: format!("{high:016x}"),
        is_128_bit: high != 0,
    })
}

/// Joins a Datadog trace id (low 64 bits in decimal, optional `_dd.p.tid` high half in hex)
/// into a 32-character OpenTelemetry trace id. `None` (or an empty string) means a zero high
/// half.
///
/// ```
/// use grow_datadog_apm::from_datadog_trace_id;
/// assert_eq!(from_datadog_trace_id("1", Some("ffffffffffffffff")).unwrap(), "ffffffffffffffff0000000000000001");
/// assert_eq!(from_datadog_trace_id("42", None).unwrap(), "0000000000000000000000000000002a");
/// ```
pub fn from_datadog_trace_id(low64_decimal: &str, high64_hex: Option<&str>) -> Result<String> {
    let low = parse_u64(low64_decimal, "trace id")?;
    let high = match high64_hex.map(|h| h.trim().to_ascii_lowercase()) {
        Some(h) if !h.is_empty() => {
            if !is_hex16(&h) {
                return Err(invalid("high 64 bits must be 16 hex characters"));
            }
            h
        }
        _ => ZERO_HIGH.to_owned(),
    };
    let id = format!("{high}{low:016x}");
    if id.bytes().all(|b| b == b'0') {
        return Err(invalid("trace id must not be all zeros"));
    }
    Ok(id)
}

/// Converts an OpenTelemetry span id (up to 16 hex characters) to Datadog's unsigned decimal.
pub fn to_datadog_span_id(span_id_hex: &str) -> Result<String> {
    let h = span_id_hex.trim().to_ascii_lowercase();
    if h.is_empty() || h.len() > 16 || !is_hex(&h) {
        return Err(invalid("span id must be 1 to 16 hex characters"));
    }
    let v = u64::from_str_radix(&h, 16).map_err(|_| invalid("span id is not hex"))?;
    Ok(v.to_string())
}

/// Converts a Datadog unsigned decimal span id to 16 lower-case hex characters.
pub fn from_datadog_span_id(decimal: &str) -> Result<String> {
    Ok(format!("{:016x}", parse_u64(decimal, "span id")?))
}

/// The trace id in the format Datadog's log correlation (`dd.trace_id`) and its search API use:
/// 32 lower-case hex characters for a 128-bit id, the low 64 bits in decimal for a 64-bit id.
pub fn datadog_trace_id_string(trace_id_hex: &str) -> Result<String> {
    let v = hex_trace_id(trace_id_hex)?;
    if (v >> 64) != 0 {
        Ok(format!("{v:032x}"))
    } else {
        Ok((v as u64).to_string())
    }
}

/// Normalizes any trace id Datadog returns to 32 lower-case hex characters: a 32-character value
/// is hex, a shorter all-digit value (at most 20 digits) is the 64-bit decimal form, anything
/// else is hex.
pub fn normalize_datadog_trace_id(value: &str) -> Result<String> {
    let t = value.trim().to_ascii_lowercase();
    if t.len() == 32 && is_hex(&t) {
        hex_trace_id(&t)?;
        return Ok(t);
    }
    if is_digits(&t) && t.len() <= 20 {
        return from_datadog_trace_id(&t, None);
    }
    Ok(format!("{:032x}", hex_trace_id(&t)?))
}

/// Normalizes a Datadog decimal span id to 16 hex characters; `None` for an empty or zero id.
pub fn normalize_datadog_span_id(value: &str) -> Result<Option<String>> {
    let t = value.trim();
    if t.is_empty() || (is_digits(t) && t.bytes().all(|b| b == b'0')) {
        return Ok(None);
    }
    from_datadog_span_id(t).map(Some)
}

/// The id to put in a `GET /api/v2/trace/{id}` path. Datadog reads an all-digit id as decimal,
/// so a hex id without a letter is sent as its full decimal value; otherwise lower-case hex.
///
/// ```
/// use grow_datadog_apm::trace_id_for_path;
/// assert_eq!(trace_id_for_path("00000000000000000020000000000001").unwrap(), "9007199254740993");
/// assert_eq!(trace_id_for_path("abc").unwrap(), "00000000000000000000000000000abc");
/// ```
pub fn trace_id_for_path(trace_id_hex: &str) -> Result<String> {
    let v = hex_trace_id(trace_id_hex)?;
    let h = format!("{v:032x}");
    if h.bytes().any(|b| b.is_ascii_alphabetic()) {
        Ok(h)
    } else {
        Ok(v.to_string())
    }
}
