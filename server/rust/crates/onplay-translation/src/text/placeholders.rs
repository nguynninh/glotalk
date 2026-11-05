//! Literal placeholders (`{{name}}`, `${name}`, `{name}`, `%s`, `%1$d`) are kept verbatim and
//! the text around them is translated separately.

use std::sync::LazyLock;

use regex::Regex;

static PLACEHOLDERS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\{\{[^{}\n]+\}\}|\$\{[^{}\n]+\}|\{[A-Za-z_][\w.]*\}|%(?:\d+\$)?[sdif]").expect("valid pattern"));

/// A piece of a line from Python `re.split('(' + PLACEHOLDERS + ')', line)`.
pub(crate) struct Piece<'a> {
    pub text: &'a str,
    pub placeholder: bool,
}

/// Text and placeholder pieces in order, starting and ending with a (possibly empty) text piece.
pub(crate) fn split(line: &str) -> Vec<Piece<'_>> {
    let mut pieces = Vec::new();
    let mut last = 0;
    for found in PLACEHOLDERS.find_iter(line) {
        pieces.push(Piece { text: &line[last..found.start()], placeholder: false });
        pieces.push(Piece { text: found.as_str(), placeholder: true });
        last = found.end();
    }
    pieces.push(Piece { text: &line[last..], placeholder: false });
    pieces
}

/// The placeholders in `text` as a multiset (Python `Counter(PLACEHOLDERS.findall(text))`).
pub(crate) fn multiset(text: &str) -> Vec<&str> {
    let mut found: Vec<&str> = PLACEHOLDERS.find_iter(text).map(|m| m.as_str()).collect();
    found.sort_unstable();
    found
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_like_python() {
        let pieces: Vec<_> = split("Hi {name}, %1$s left ${x}{{y}}").into_iter().map(|p| (p.text, p.placeholder)).collect();
        assert_eq!(
            pieces,
            [("Hi ", false), ("{name}", true), (", ", false), ("%1$s", true), (" left ", false), ("${x}", true), ("", false), ("{{y}}", true), ("", false)]
        );
        assert_eq!(multiset("%s a %d %s"), ["%d", "%s", "%s"]);
    }
}
