//! Minimal ISO-8601 formatting and parsing (UTC) for the query API, without a date crate.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (
        if m <= 2 {
            yoe + era * 400 + 1
        } else {
            yoe + era * 400
        },
        m,
        d,
    )
}

/// Formats a time as ISO-8601 UTC with milliseconds, e.g. `2026-11-19T10:00:00.000Z`. Times
/// before 1970 are clamped to the epoch.
pub(crate) fn format_iso8601(t: SystemTime) -> String {
    let d = t.duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO);
    let secs = d.as_secs() as i64;
    let (y, m, day) = civil_from_days(secs.div_euclid(86_400));
    let rem = secs.rem_euclid(86_400);
    format!(
        "{y:04}-{m:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60,
        d.subsec_millis()
    )
}

fn num(s: &str) -> Option<i64> {
    (!s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
        .then(|| s.parse().ok())
        .flatten()
}

/// Parses an ISO-8601 date-time (`2026-11-19T10:00:00Z`, fractional seconds and `±hh:mm`
/// offsets allowed; no offset means UTC) to Unix microseconds.
pub(crate) fn parse_iso8601_micros(s: &str) -> Option<i64> {
    let s = s.trim();
    if s.len() < 19 || !s.is_char_boundary(10) || !s.is_char_boundary(19) {
        return None;
    }
    let (date, rest) = s.split_at(10);
    let sep = rest.as_bytes()[0];
    if sep != b'T' && sep != b't' && sep != b' ' {
        return None;
    }
    let dp: Vec<&str> = date.split('-').collect();
    if dp.len() != 3 || dp[0].len() != 4 || dp[1].len() != 2 || dp[2].len() != 2 {
        return None;
    }
    let (y, mo, d) = (num(dp[0])?, num(dp[1])?, num(dp[2])?);
    let time = &rest[1..9];
    let tp: Vec<&str> = time.split(':').collect();
    if tp.len() != 3 || tp.iter().any(|p| p.len() != 2) {
        return None;
    }
    let (h, mi, se) = (num(tp[0])?, num(tp[1])?, num(tp[2])?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || se > 60 {
        return None;
    }
    let mut tail = &rest[9..];
    let mut micros = 0i64;
    if let Some(frac) = tail.strip_prefix('.') {
        let n = frac.bytes().take_while(u8::is_ascii_digit).count();
        if n == 0 {
            return None;
        }
        let digits = &frac[..n];
        let padded = format!("{:0<6}", &digits[..n.min(6)]);
        micros = padded.parse().ok()?;
        tail = &frac[n..];
    }
    let offset_secs = match tail {
        "" | "Z" | "z" => 0,
        _ => {
            let sign = match tail.as_bytes()[0] {
                b'+' => 1,
                b'-' => -1,
                _ => return None,
            };
            let o = tail[1..].replace(':', "");
            if o.len() != 4 {
                return None;
            }
            sign * (num(&o[..2])? * 3600 + num(&o[2..])? * 60)
        }
    };
    let secs = days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + se - offset_secs;
    Some(secs * 1_000_000 + micros)
}
