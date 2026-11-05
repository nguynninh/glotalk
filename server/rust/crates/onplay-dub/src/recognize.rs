//! Diarized recognition with `onplay-speakers`, streamed segment by segment.
//!
//! `onplay-speakers` refuses audio over 600 s, so long videos are cut into pieces; speaker
//! labels are only consistent inside one piece. It diarizes a whole piece first, then prints
//! `SPEAKER start end label text` per segment as each is recognized.

use std::io::{BufRead, BufReader, Read};
use std::path::Path;
use std::process::{Command, Stdio};

use crate::error::{Error, Result};
use crate::events::{Event, Observer};
use crate::media;
use crate::project::Project;
use crate::segment::Segment;

pub const PIECE_SECONDS: u64 = 590;

/// Calls `on_segment` for every recognized segment, in order, while recognition continues.
pub fn recognize(
    project: &Project,
    audio: &Path,
    duration: f64,
    threads: usize,
    work: &Path,
    observer: &dyn Observer,
    mut on_segment: impl FnMut(Segment) -> Result<()>,
) -> Result<()> {
    for (index, offset) in (0..=duration as u64).step_by(PIECE_SECONDS as usize).enumerate() {
        if duration - (offset as f64) < 0.5 {
            break;
        }
        let piece = work.join(format!("piece-{index}.wav"));
        media::cut(audio, offset, PIECE_SECONDS, &piece)?;
        observer.event(Event::Recognizing { offset: offset as f64, duration });
        recognize_piece(project, &piece, offset as f64, threads, &mut on_segment)?;
    }
    Ok(())
}

fn recognize_piece(
    project: &Project,
    piece: &Path,
    offset: f64,
    threads: usize,
    on_segment: &mut impl FnMut(Segment) -> Result<()>,
) -> Result<()> {
    let failed = |detail: String| Error::Command { program: "onplay-speakers".into(), detail };
    let mut child = Command::new(project.speakers_bin())
        .args(project.speaker_args())
        .arg(piece)
        .arg(threads.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| failed(error.to_string()))?;
    let stdout = child.stdout.take().expect("stdout is piped");
    let mut stderr = child.stderr.take().expect("stderr is piped");
    // Drain stderr concurrently so the child never blocks on a full pipe.
    let errors = std::thread::spawn(move || {
        let mut text = String::new();
        let _ = stderr.read_to_string(&mut text);
        text
    });
    let mut result = Ok(());
    for line in BufReader::new(stdout).lines() {
        match line {
            Ok(line) => {
                if let Some(segment) = parse_line(&line, offset) {
                    result = on_segment(segment);
                }
            }
            Err(error) => result = Err(failed(error.to_string())),
        }
        if result.is_err() {
            let _ = child.kill();
            break;
        }
    }
    let status = child.wait().map_err(|error| failed(error.to_string()))?;
    let errors = errors.join().unwrap_or_default();
    result?;
    if !status.success() {
        return Err(failed(format!("{status} {}", errors.trim())));
    }
    Ok(())
}

fn parse_line(line: &str, offset: f64) -> Option<Segment> {
    let mut parts = line.splitn(5, ' ');
    if parts.next()? != "SPEAKER" {
        return None;
    }
    let start: f64 = parts.next()?.parse().ok()?;
    let end: f64 = parts.next()?.parse().ok()?;
    let speaker: u32 = parts.next()?.parse().ok()?;
    let text = parts.next()?.trim();
    if text.is_empty() {
        return None;
    }
    Some(Segment {
        start: offset + start,
        end: offset + end,
        speaker,
        text: text.to_owned(),
        translation: String::new(),
        dub: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_speaker_lines_with_offset() {
        let segment = parse_line("SPEAKER 1.5 3.25 2 hello there world", 590.0).unwrap();
        assert_eq!((segment.start, segment.end, segment.speaker), (591.5, 593.25, 2));
        assert_eq!(segment.text, "hello there world");
        assert!(parse_line("SPEAKER 1 2 1 ", 0.0).is_none());
        assert!(parse_line("SPEAKER x 2 1 hi", 0.0).is_none());
        assert!(parse_line("Error: boom", 0.0).is_none());
    }
}
