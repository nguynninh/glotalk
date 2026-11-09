//! UTC timestamps for logs, job messages and HTTP headers, without a date library.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

const WEEKDAYS: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

struct Parts {
    days: i64,
    year: i64,
    month: u32,
    day: u32,
    seconds: u64,
    millis: u32,
}

fn parts(time: SystemTime) -> Parts {
    let since = time.duration_since(UNIX_EPOCH).unwrap_or_default();
    let days = (since.as_secs() / 86_400) as i64;
    let (year, month, day) = civil(days);
    Parts { days, year, month, day, seconds: since.as_secs() % 86_400, millis: since.subsec_millis() }
}

/// `2026-11-05T10:40:45.870Z`.
pub fn iso8601_millis(time: SystemTime) -> String {
    let Parts { year, month, day, seconds, millis, .. } = parts(time);
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{millis:03}Z", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/// `2026-11-05T10:40:45Z`.
pub fn iso8601(time: SystemTime) -> String {
    let Parts { year, month, day, seconds, .. } = parts(time);
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/// `Thu, 05 Nov 2026 10:40:45 GMT` (RFC 9110 `IMF-fixdate`).
pub fn http_date(time: SystemTime) -> String {
    let Parts { days, year, month, day, seconds, .. } = parts(time);
    let weekday = WEEKDAYS[days.rem_euclid(7) as usize];
    let month = MONTHS[month as usize - 1];
    format!("{weekday}, {day:02} {month} {year:04} {:02}:{:02}:{:02} GMT", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/// Whole seconds from `now` until `time`; 0 when it has passed.
pub fn seconds_until(time: SystemTime, now: SystemTime) -> u64 {
    time.duration_since(now).unwrap_or(Duration::ZERO).as_secs()
}

/// Proleptic Gregorian date of `days` since 1970-01-01 (Howard Hinnant's `civil_from_days`).
fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(month <= 2), month, day)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_utc_timestamps() {
        assert_eq!(iso8601_millis(UNIX_EPOCH), "1970-01-01T00:00:00.000Z");
        let moment = UNIX_EPOCH + Duration::from_millis(1_793_875_245_870);
        assert_eq!(iso8601_millis(moment), "2026-11-05T10:40:45.870Z");
        assert_eq!(iso8601(moment), "2026-11-05T10:40:45Z");
        assert_eq!(http_date(moment), "Thu, 05 Nov 2026 10:40:45 GMT");
        assert_eq!(http_date(UNIX_EPOCH), "Thu, 01 Jan 1970 00:00:00 GMT");
        assert_eq!(civil(11_016), (2000, 2, 29));
        assert_eq!(seconds_until(moment, UNIX_EPOCH), 1_793_875_245);
        assert_eq!(seconds_until(UNIX_EPOCH, moment), 0);
    }
}
