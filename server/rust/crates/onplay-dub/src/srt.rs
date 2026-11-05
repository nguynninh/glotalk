//! SubRip output for original and translated text.

use std::path::Path;

use crate::error::{Error, Result};
use crate::segment::Segment;

/// Which text of each segment to write.
#[derive(Debug, Clone, Copy)]
pub enum Text {
    Original,
    Translation,
}

pub fn write(segments: &[Segment], text: Text, path: &Path) -> Result<()> {
    std::fs::write(path, render(segments, text)).map_err(Error::io(path))
}

fn render(segments: &[Segment], text: Text) -> String {
    let blocks: Vec<String> = segments
        .iter()
        .enumerate()
        .map(|(index, segment)| {
            let body = match text {
                Text::Original => &segment.text,
                Text::Translation => &segment.translation,
            };
            format!("{}\n{} --> {}\n{body}\n", index + 1, stamp(segment.start), stamp(segment.end))
        })
        .collect();
    blocks.join("\n")
}

fn stamp(seconds: f64) -> String {
    // Python round() rounds halves to even.
    let millis = (seconds * 1000.0).round_ties_even() as u64;
    format!("{:02}:{:02}:{:02},{:03}", millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_like_python() {
        let segment = |start, end, text: &str, translation: &str| Segment {
            start,
            end,
            speaker: 1,
            text: text.into(),
            translation: translation.into(),
            dub: true,
        };
        let segments = [segment(0.031, 4.064, "a", "A"), segment(3725.5, 3726.0625 - 0.0615, "b", "B"), segment(1.0625, 2.0, "c", "C")];
        assert_eq!(
            render(&segments, Text::Translation),
            "1\n00:00:00,031 --> 00:00:04,064\nA\n\n2\n01:02:05,500 --> 01:02:06,001\nB\n\n3\n00:00:01,062 --> 00:00:02,000\nC\n"
        );
    }
}
