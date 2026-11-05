use unicode_normalization::UnicodeNormalization;

use super::is_control;
use super::whitespace::{is_space, strip};
use crate::error::{Error, Result};

pub(crate) const MAX_TEXT_CHARS: usize = 10_000;
pub(crate) const TEXT_ERROR: &str = "Text must be a string of at most 10000 characters";

/// NFC, `\n` line endings, runs of other whitespace collapsed to one space, lines and the
/// whole text stripped; rejects empty text and control characters other than `\n`.
pub(crate) fn normalize(text: &str) -> Result<String> {
    if text.chars().count() > MAX_TEXT_CHARS {
        return Err(Error::invalid(TEXT_ERROR));
    }
    let text: String = text.nfc().collect();
    let text = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<String> = text.split('\n').map(|line| strip(&collapse(line)).to_owned()).collect();
    let text = strip(&lines.join("\n")).to_owned();
    if text.is_empty() || text.chars().any(|c| is_control(c) && c != '\n') {
        return Err(Error::invalid("Empty text or unsupported control characters"));
    }
    Ok(text)
}

/// Python `re.sub(r'[^\S\n]+', ' ', line)` on a line that holds no `\n`.
fn collapse(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut in_space = false;
    for c in line.chars() {
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
    fn normalizes_like_python() {
        assert_eq!(normalize(" a \t b\r\n\r\n  c\u{a0} \r").unwrap(), "a b\n\nc");
        assert_eq!(normalize("e\u{301}").unwrap(), "\u{e9}");
        assert!(normalize(" \n ").is_err());
        assert!(normalize("a\u{7}b").is_err());
        assert!(normalize(&"a".repeat(MAX_TEXT_CHARS + 1)).is_err());
        assert!(normalize(&"\u{e9}".repeat(MAX_TEXT_CHARS)).is_ok());
    }
}
