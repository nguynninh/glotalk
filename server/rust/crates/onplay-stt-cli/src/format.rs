//! Number formatting identical to C++ `std::ostream << double` defaults (`%g`, precision 6),
//! which the Python callers parse.

/// `printf("%g", value)`: 6 significant digits, trailing zeros removed, scientific notation
/// when the exponent is below -4 or at least 6.
pub fn g(value: f64) -> String {
    if value == 0.0 {
        return if value.is_sign_negative() { "-0".into() } else { "0".into() };
    }
    if !value.is_finite() {
        return if value.is_nan() { "nan".into() } else if value > 0.0 { "inf".into() } else { "-inf".into() };
    }
    // Round to 6 significant digits first; the rounded exponent picks the notation.
    let scientific = format!("{value:.5e}");
    let (mantissa, exponent) = scientific.split_once('e').expect("exponent present");
    let exponent: i32 = exponent.parse().expect("integer exponent");
    if !(-4..6).contains(&exponent) {
        let mantissa = trim(mantissa);
        let sign = if exponent < 0 { '-' } else { '+' };
        return format!("{mantissa}e{sign}{:02}", exponent.abs());
    }
    let decimals = (5 - exponent).max(0) as usize;
    trim(&format!("{value:.decimals$}")).to_owned()
}

fn trim(number: &str) -> &str {
    if number.contains('.') {
        number.trim_end_matches('0').trim_end_matches('.')
    } else {
        number
    }
}

#[cfg(test)]
mod tests {
    use super::g;

    #[test]
    fn matches_printf_g() {
        // Expected values from C printf("%g") / std::cout defaults.
        for (value, expected) in [
            (0.149094f32 as f64, "0.149094"),
            (2.477_843_f64, "2.47784"),
            (6.865_34, "6.86534"),
            (229.9_f64, "229.9"),
            (1.0, "1"),
            (100.0, "100"),
            (123_456.7, "123457"),
            (999_999.5, "1e+06"),
            (1_234_567.0, "1.23457e+06"),
            (0.000_123_456, "0.000123456"),
            (0.000_012_345_6, "1.23456e-05"),
            (-0.5, "-0.5"),
            (0.0, "0"),
            (0.15, "0.15"),
            (16.67, "16.67"),
        ] {
            assert_eq!(g(value), expected, "{value}");
        }
    }
}
