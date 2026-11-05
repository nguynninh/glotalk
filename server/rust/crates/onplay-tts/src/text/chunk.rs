//! `chunk_text`: sentence-aligned chunks of at most `max_len` code points.

use super::whitespace::{is_space, strip};

/// A split after `.`/`!`/`?` is suppressed when the text before the whitespace ends with one
/// of these (`_SENTENCE_SPLIT_RE` negative lookbehinds).
const ABBREVIATIONS: [&str; 18] = [
    "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "Sr.", "Jr.", "Ph.D.", "etc.", "e.g.", "i.e.", "vs.",
    "Inc.", "Ltd.", "Co.", "Corp.", "St.", "Ave.",
];
const ABBREVIATIONS_EXTRA: [&str; 1] = ["Blvd."];

pub fn chunk_text(text: &str, max_len: usize) -> Vec<String> {
    let mut chunks = Vec::new();
    for paragraph in paragraphs(strip(text)) {
        let mut current = String::new();
        for sentence in sentences(paragraph) {
            for piece in split_long_sentence(sentence, max_len) {
                // Python: len(current) + len(piece) + 1 <= max_len
                if len(&current) + len(&piece) < max_len {
                    if !current.is_empty() {
                        current.push(' ');
                    }
                    current.push_str(&piece);
                } else {
                    if !current.is_empty() {
                        chunks.push(strip(&current).to_owned());
                    }
                    current = piece;
                }
            }
        }
        if !current.is_empty() {
            chunks.push(strip(&current).to_owned());
        }
    }
    chunks
}

fn len(text: &str) -> usize {
    text.chars().count()
}

/// `re.split(r"\n\s*\n+", ...)`, stripped and without empty parts: a whitespace run containing
/// at least two newlines separates paragraphs.
fn paragraphs(text: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut start = 0;
    let mut run: Option<(usize, usize)> = None; // (run start, newlines in run)
    for (index, c) in text.char_indices() {
        if is_space(c) {
            let (run_start, newlines) = run.unwrap_or((index, 0));
            run = Some((run_start, newlines + usize::from(c == '\n')));
        } else {
            if let Some((run_start, newlines)) = run.take() {
                if newlines >= 2 {
                    parts.push(&text[start..run_start]);
                    start = index;
                }
            }
        }
    }
    parts.push(&text[start..]);
    parts.into_iter().map(strip).filter(|part| !part.is_empty()).collect()
}

/// `_SENTENCE_SPLIT_RE.split(paragraph)`: split on a whitespace run that follows `.`, `!` or
/// `?`, unless the text before it ends with an abbreviation or a single capital initial.
fn sentences(paragraph: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut start = 0;
    let mut previous: Option<char> = None;
    let mut skip_until = 0;
    for (index, c) in paragraph.char_indices() {
        if index >= skip_until
            && is_space(c)
            && previous.is_some_and(|p| matches!(p, '.' | '!' | '?'))
            && !ends_with_abbreviation(&paragraph[..index])
        {
            let end = paragraph[index..]
                .char_indices()
                .find(|(_, c)| !is_space(*c))
                .map_or(paragraph.len(), |(offset, _)| index + offset);
            parts.push(&paragraph[start..index]);
            start = end;
            skip_until = end;
        }
        previous = Some(c);
    }
    parts.push(&paragraph[start..]);
    parts
}

fn ends_with_abbreviation(before: &str) -> bool {
    if ABBREVIATIONS.iter().chain(&ABBREVIATIONS_EXTRA).any(|abbreviation| before.ends_with(abbreviation)) {
        return true;
    }
    // `(?<!\b[A-Z]\.)`: an ASCII capital, preceded by a non-word character or the start.
    let mut tail = before.chars().rev();
    match (tail.next(), tail.next(), tail.next()) {
        (Some('.'), Some(initial), before_initial) if initial.is_ascii_uppercase() => {
            !before_initial.is_some_and(|c| c.is_alphanumeric() || c == '_')
        }
        _ => false,
    }
}

fn split_long_sentence(sentence: &str, max_len: usize) -> Vec<String> {
    if len(sentence) <= max_len {
        return vec![sentence.to_owned()];
    }
    let mut pieces: Vec<String> = Vec::new();
    let mut current = String::new();
    for word in sentence.split(is_space).filter(|word| !word.is_empty()) {
        if !current.is_empty() && len(&current) + len(word) + 1 > max_len {
            pieces.push(std::mem::take(&mut current));
            current.push_str(word);
        } else {
            if !current.is_empty() {
                current.push(' ');
            }
            current.push_str(word);
        }
    }
    if !current.is_empty() {
        pieces.push(current);
    }
    // A single "word" longer than max_len (e.g. a URL) is hard-cut.
    pieces
        .iter()
        .flat_map(|piece| {
            let chars: Vec<char> = piece.chars().collect();
            chars.chunks(max_len).map(|part| part.iter().collect()).collect::<Vec<String>>()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_sentences_but_not_abbreviations() {
        assert_eq!(sentences("Hi Mr. Smith. Bye!  Ok? J. Doe"), ["Hi Mr. Smith.", "Bye!", "Ok?", "J. Doe"]);
        assert_eq!(sentences("A.B. end. x"), ["A.B. end.", "x"]);
    }

    #[test]
    fn splits_paragraphs_on_two_newlines() {
        assert_eq!(paragraphs(" a\nb \n \n c\n\n\nd "), ["a\nb", "c", "d"]);
    }

    #[test]
    fn packs_and_hard_cuts() {
        assert_eq!(chunk_text("Một. Hai. Ba.", 9), ["Một. Hai.", "Ba."]);
        assert_eq!(chunk_text("abcdefghij", 4), ["abcd", "efgh", "ij"]);
    }
}
