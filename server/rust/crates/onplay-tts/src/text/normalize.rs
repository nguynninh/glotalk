//! `normalize_text`: clean raw text and wrap it in the encoder's language tags.

use unicode_normalization::UnicodeNormalization;

use super::lang::Language;
use super::whitespace::{collapse, strip};

const CHAR_REPLACEMENTS: [(char, &str); 17] = [
    ('–', "-"), ('‑', "-"), ('—', "-"), ('_', " "),
    ('“', "\""), ('”', "\""), ('‘', "'"), ('’', "'"), ('´', "'"), ('`', "'"),
    ('[', " "), (']', " "), ('|', " "), ('/', " "), ('#', " "), ('→', " "), ('←', " "),
];
const REMOVED: [char; 5] = ['♥', '☆', '♡', '©', '\\'];
/// Applied in order, like the Python dict iteration.
const EXPR_REPLACEMENTS: [(&str, &str); 3] =
    [("@", " at "), ("e.g.,", "for example, "), ("i.e.,", "that is, ")];
const TIGHT_PUNCT: [char; 7] = [',', '.', '!', '?', ';', ':', '\''];
const TRAILING_PUNCT: [char; 18] =
    ['.', '!', '?', ';', ':', ',', '\'', '"', ')', ']', '}', '…', '。', '」', '』', '】', '〉', '》'];
const TRAILING_PUNCT_EXTRA: [char; 2] = ['›', '»'];

pub fn normalize_text(text: &str, language: Language) -> String {
    let mut text: String = text.nfkd().filter(|c| !is_emoji(*c)).collect();
    text = text
        .chars()
        .filter(|c| !REMOVED.contains(c))
        .fold(String::with_capacity(text.len()), |mut out, c| {
            match CHAR_REPLACEMENTS.iter().find(|(from, _)| *from == c) {
                Some((_, to)) => out.push_str(to),
                None => out.push(c),
            }
            out
        });
    for (from, to) in EXPR_REPLACEMENTS {
        text = text.replace(from, to);
    }
    text = tighten_punctuation(&text);
    for quote in ['"', '\'', '`'] {
        text = collapse_runs(&text, quote);
    }
    let mut text = strip(&collapse(&text)).to_owned();
    if text.chars().last().is_some_and(|c| !is_trailing_punct(c)) {
        text.push('.');
    }
    let tag = language.tag();
    format!("<{tag}>{text}</{tag}>")
}

/// The emoji and symbol ranges of `_EMOJI_RE`.
fn is_emoji(c: char) -> bool {
    matches!(c,
        '\u{1f600}'..='\u{1f64f}' | '\u{1f300}'..='\u{1f5ff}' | '\u{1f680}'..='\u{1f6ff}'
        | '\u{1f700}'..='\u{1f77f}' | '\u{1f780}'..='\u{1f7ff}' | '\u{1f800}'..='\u{1f8ff}'
        | '\u{1f900}'..='\u{1f9ff}' | '\u{1fa00}'..='\u{1fa6f}' | '\u{1fa70}'..='\u{1faff}'
        | '\u{2600}'..='\u{26ff}' | '\u{2700}'..='\u{27bf}' | '\u{1f1e6}'..='\u{1f1ff}')
}

/// `re.sub(r" ([,.!?;:'])", r"\1", text)`: drop one space before punctuation.
fn tighten_punctuation(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if c == ' ' && chars.peek().is_some_and(|next| TIGHT_PUNCT.contains(next)) {
            continue;
        }
        out.push(c);
    }
    out
}

/// `while dup in text: text = text.replace(dup, dup[0])` collapses each run to one char.
fn collapse_runs(text: &str, quote: char) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        if !(c == quote && out.ends_with(quote)) {
            out.push(c);
        }
    }
    out
}

fn is_trailing_punct(c: char) -> bool {
    TRAILING_PUNCT.contains(&c) || TRAILING_PUNCT_EXTRA.contains(&c)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vi(text: &str) -> String {
        normalize_text(text, Language::parse("vi").unwrap())
    }

    #[test]
    fn decomposes_and_wraps() {
        assert_eq!(vi("Xin chào"), "<vi>Xin cha\u{300}o.</vi>");
    }

    #[test]
    fn replaces_and_tightens() {
        assert_eq!(vi("a — b , “c”  😀 x@y"), "<vi>a - b, \"c\" x at y.</vi>");
        assert_eq!(vi("ok '' \"\"\" ''"), "<vi>ok' \"'</vi>");
        assert_eq!(vi("e.g., this"), "<vi>for example, this.</vi>");
    }

    #[test]
    fn keeps_trailing_punctuation_and_empty_text() {
        assert_eq!(vi("Xong!"), "<vi>Xong!</vi>");
        assert_eq!(vi("  "), "<vi></vi>");
    }
}
