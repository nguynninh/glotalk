//! Number rounding that keeps event values identical to the Python server.

/// `float("%g" % value)`: 6 significant digits. The Python server parsed metrics and
/// speaker times from CLI output printed this way.
pub fn significant6(value: f64) -> f64 {
    if !value.is_finite() || value == 0.0 {
        return value;
    }
    format!("{value:.5e}").parse().unwrap_or(value)
}

/// `round(value, 2)` for millisecond timings and speech speed.
pub fn round2(value: f64) -> f64 {
    (value * 100.0).round() / 100.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounds_like_python() {
        assert_eq!(significant6(2.477_843), 2.47784);
        assert_eq!(significant6(123_456.7), 123_457.0);
        assert_eq!(significant6(0.0), 0.0);
        assert_eq!(round2(1.234_9), 1.23);
        assert_eq!(round2(1.05 + 0.15 * 2.0), 1.35);
    }
}
