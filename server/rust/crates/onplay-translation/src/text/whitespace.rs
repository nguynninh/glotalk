//! Python `str` whitespace handling (same rules as `onplay-tts`'s text module).

/// Python `str.isspace()`, which `\s`, `split()` and `strip()` use on `str`.
///
/// It equals Unicode White_Space plus the information separators U+001C..=U+001F.
pub(crate) fn is_space(c: char) -> bool {
    c.is_whitespace() || ('\u{1c}'..='\u{1f}').contains(&c)
}

/// Python `str.strip()`.
pub(crate) fn strip(text: &str) -> &str {
    text.trim_matches(is_space)
}

/// Python `str.lstrip()`.
pub(crate) fn lstrip(text: &str) -> &str {
    text.trim_start_matches(is_space)
}

/// Python `' '.join(text.split())`.
pub(crate) fn join_words(text: &str) -> String {
    text.split(is_space).filter(|word| !word.is_empty()).collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_python_isspace() {
        assert!(is_space('\u{1f}') && is_space('\u{a0}') && is_space('\u{3000}'));
        assert!(!is_space('\u{200b}'));
        assert_eq!(strip("\u{1c} x \n"), "x");
        assert_eq!(lstrip(" \tx "), "x ");
        assert_eq!(join_words(" a \t\u{a0}b  c\n"), "a b c");
    }
}
