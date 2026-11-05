/// Python `str.isspace()`, which `\s`, `split()` and `strip()` use on `str`.
///
/// It equals Unicode White_Space plus the information separators U+001C..=U+001F.
pub(super) fn is_space(c: char) -> bool {
    c.is_whitespace() || ('\u{1c}'..='\u{1f}').contains(&c)
}

/// Python `str.strip()`.
pub(super) fn strip(text: &str) -> &str {
    text.trim_matches(is_space)
}

/// Python `re.sub(r"\s+", " ", text)`.
pub(super) fn collapse(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut in_space = false;
    for c in text.chars() {
        if is_space(c) {
            if !in_space {
                out.push(' ');
            }
            in_space = true;
        } else {
            out.push(c);
            in_space = false;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_python_isspace() {
        assert!(is_space('\u{1f}') && is_space('\u{a0}') && is_space('\u{3000}'));
        assert!(!is_space('\u{200b}'));
        assert_eq!(collapse("a \t\n b\u{a0}c"), "a b c");
        assert_eq!(strip("\u{1c} x \n"), "x");
    }
}
