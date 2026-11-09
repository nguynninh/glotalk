//! The whole translation run, with recognition, translation and speech overlapped.

use std::path::{Path, PathBuf};

use onplay_tts::audio::write_pcm16_wav;
use onplay_tts::text::Language;
use onplay_tts::Assets;

use crate::command;
use crate::error::{Error, Result};
use crate::events::{Event, Observer};
use crate::fetch::{fetch, Input};
use crate::media::{self, Dub};
use crate::project::Project;
use crate::recognize::recognize;
use crate::segment::Segment;
use crate::speech::{self, Job, PoolConfig};
use crate::srt;
use crate::timeline;
use crate::translate::{sentence_case, Translator, TARGETS};
use crate::voices;
use crate::workdir::WorkDir;

#[derive(Debug, Clone)]
pub struct Options {
    /// Video file or http(s) link.
    pub input: String,
    pub target: String,
    /// Output MP4; default `<input stem>.<target>.mp4` next to the input.
    pub output: Option<PathBuf>,
    /// Keep the original audio and only add subtitles.
    pub subtitles_only: bool,
    /// Original audio volume while dubbed speech plays, 0..=1.
    pub background: f64,
    /// Recognition threads. Measured on an Apple M4 (4 performance + 6 efficiency cores):
    /// 4 is fastest; more threads spill onto efficiency cores.
    pub threads: usize,
    /// Parallel synthesizers and threads each; 3 x 2 gave the best M4 throughput alongside
    /// recognition (1x4, 2x2, 2x3, 3x1 and 4x2 were slower).
    pub tts_workers: usize,
    pub tts_threads: usize,
    pub translation_endpoint: String,
}

impl Options {
    pub fn new(input: impl Into<String>, target: impl Into<String>) -> Self {
        Self {
            input: input.into(),
            target: target.into(),
            output: None,
            subtitles_only: false,
            background: 0.15,
            threads: 4,
            tts_workers: 3,
            tts_threads: 2,
            translation_endpoint: crate::translate::DEFAULT_ENDPOINT.into(),
        }
    }

    fn validate(&self) -> Result<()> {
        let invalid = |message: String| Err(Error::InvalidArgument(message));
        if !TARGETS.contains(&self.target.as_str()) {
            return invalid(format!("unsupported target '{}'; choose one of {}", self.target, TARGETS.join(", ")));
        }
        if !self.subtitles_only && !voices::speakable(&self.target) {
            let languages: Vec<_> = voices::speakable_languages().collect();
            return invalid(format!("speech supports {} only; use subtitles-only for {}", languages.join(", "), self.target));
        }
        if !(0.0..=1.0).contains(&self.background) {
            return invalid("background must be 0-1".into());
        }
        if !(1..=16).contains(&self.threads) || !(1..=16).contains(&self.tts_threads) || !(1..=8).contains(&self.tts_workers) {
            return invalid("threads and tts threads must be 1-16, tts workers 1-8".into());
        }
        Ok(())
    }
}

/// Files written by a run.
#[derive(Debug, Clone)]
pub struct Outputs {
    pub video: PathBuf,
    pub translated_srt: PathBuf,
    pub original_srt: PathBuf,
    /// The source language detected for the most segments.
    pub source_language: Option<String>,
}

pub fn run(project: &Project, options: &Options, observer: &dyn Observer) -> Result<Outputs> {
    options.validate()?;
    if !command::available("ffmpeg", "-version") {
        return Err(Error::Setup("ffmpeg is required: brew install ffmpeg".into()));
    }
    let translator = Translator::new(&options.translation_endpoint);
    translator.check()?;
    let speech = if options.subtitles_only {
        None
    } else {
        onplay_tts::init_runtime(&project.ort_dylib())?;
        Some((Assets::from_huggingface_cache()?, Language::parse(&options.target)?))
    };

    let work = WorkDir::create()?;
    let input = fetch(&options.input, work.path(), observer)?;
    let outputs = output_paths(&input, options)?;
    let audio = work.path().join("audio.wav");
    let duration = media::extract_audio(&input.video, &audio)?;

    let transcribe = |queue: &mut dyn FnMut(Job)| {
        transcribe(project, options, &translator, &audio, duration, work.path(), observer, queue)
    };
    let ((segments, source_language), clips, rate) = match &speech {
        Some((assets, language)) => {
            let config = PoolConfig { assets, language: *language, workers: options.tts_workers, threads: options.tts_threads };
            speech::run(&config, observer, transcribe)?
        }
        None => (transcribe(&mut |_| {})?, Vec::new(), 0),
    };
    if segments.is_empty() {
        return Err(Error::NoSpeech);
    }

    srt::write(&segments, srt::Text::Original, &outputs.original_srt)?;
    srt::write(&segments, srt::Text::Translation, &outputs.translated_srt)?;
    let track_path = work.path().join("dub.wav");
    let track = clips.iter().any(Option::is_some).then(|| {
        let mut clips = clips;
        clips.resize(segments.len(), None);
        timeline::place(&segments, &clips, duration, rate)
    });
    if let Some(track) = &track {
        write_pcm16_wav(&track_path, &track.samples, rate)?;
    }
    observer.event(Event::Muxing);
    let dub = track.as_ref().map(|track| Dub { track: &track_path, spans: &track.spans, background: options.background });
    media::mux(&input.video, dub, &outputs.translated_srt, &options.target, &outputs.video, work.path())?;
    Ok(Outputs { source_language, ..outputs })
}

/// Recognizes and translates every segment; each one is queued for speech once the next
/// segment's start (or the end of the video) bounds its slot.
#[allow(clippy::too_many_arguments)]
fn transcribe(
    project: &Project,
    options: &Options,
    translator: &Translator,
    audio: &Path,
    duration: f64,
    work: &Path,
    observer: &dyn Observer,
    queue: &mut dyn FnMut(Job),
) -> Result<(Vec<Segment>, Option<String>)> {
    let mut segments: Vec<Segment> = Vec::new();
    let mut languages: Vec<String> = Vec::new();
    let mut speech = 0;
    let mut enqueue = |segments: &[Segment], index: usize, following: f64| {
        let segment = &segments[index];
        if options.subtitles_only || !segment.dub {
            return;
        }
        speech += 1;
        let voice = voices::voice_for(&options.target, segment.speaker).expect("target validated as speakable");
        // Translated speech may borrow the pause before the next segment but not overlap it.
        let max_seconds = (segment.end.max(following) - segment.start) as f32;
        queue(Job { index, text: segment.translation.clone(), voice, max_seconds });
    };
    recognize(project, audio, duration, options.threads, work, observer, |mut segment| {
        let index = segments.len();
        observer.event(Event::Recognized { index, end: segment.end });
        let translation = translator.translate(&sentence_case(&segment.text), &options.target)?;
        segment.dub = translation.source != options.target;
        languages.push(translation.source);
        segment.translation = translation.text;
        observer.event(Event::Translated { index });
        let start = segment.start;
        segments.push(segment);
        if index > 0 {
            enqueue(&segments, index - 1, start);
        }
        Ok(())
    })?;
    if let Some(last) = segments.len().checked_sub(1) {
        enqueue(&segments, last, duration);
    }
    observer.event(Event::Transcribed { segments: segments.len(), speech });
    Ok((segments, most_common(languages)))
}

/// The language detected for the most segments; the first one seen on a tie.
fn most_common(languages: Vec<String>) -> Option<String> {
    let mut counts: Vec<(String, usize)> = Vec::new();
    for language in languages {
        match counts.iter_mut().find(|(seen, _)| *seen == language) {
            Some((_, count)) => *count += 1,
            None => counts.push((language, 1)),
        }
    }
    let best = counts.iter().map(|(_, count)| *count).max()?;
    counts.into_iter().find(|(_, count)| *count == best).map(|(language, _)| language)
}

/// `<stem>.<target>.mp4`, `<stem>.<target>.srt` and `<stem>.original.srt`; a downloaded
/// video is named `video` in the current directory.
fn output_paths(input: &Input, options: &Options) -> Result<Outputs> {
    let stem = match (&options.output, input.downloaded) {
        (Some(output), _) => output.with_extension(""),
        (None, true) => std::env::current_dir().map_err(Error::io("."))?.join("video"),
        (None, false) => input.video.with_extension(""),
    };
    let name = stem.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_else(|| "video".into());
    let video = options.output.clone().unwrap_or_else(|| stem.with_file_name(format!("{name}.{}.mp4", options.target)));
    Ok(Outputs {
        translated_srt: video.with_file_name(format!("{name}.{}.srt", options.target)),
        original_srt: video.with_file_name(format!("{name}.original.srt")),
        video,
        source_language: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn paths(video: &str, downloaded: bool, output: Option<&str>) -> Outputs {
        let mut options = Options::new(video, "vi");
        options.output = output.map(PathBuf::from);
        output_paths(&Input { video: video.into(), downloaded }, &options).unwrap()
    }

    #[test]
    fn names_outputs_next_to_input() {
        let outputs = paths("/v/talk.mp4", false, None);
        assert_eq!(outputs.video, Path::new("/v/talk.vi.mp4"));
        assert_eq!(outputs.translated_srt, Path::new("/v/talk.vi.srt"));
        assert_eq!(outputs.original_srt, Path::new("/v/talk.original.srt"));
        let outputs = paths("/v/talk.mp4", false, Some("/o/out.mp4"));
        assert_eq!(outputs.video, Path::new("/o/out.mp4"));
        assert_eq!(outputs.translated_srt, Path::new("/o/out.vi.srt"));
    }

    #[test]
    fn picks_the_most_common_language() {
        let languages = |list: &[&str]| list.iter().map(|language| (*language).to_owned()).collect();
        assert_eq!(most_common(languages(&["en", "zh", "zh", "en", "zh"])).as_deref(), Some("zh"));
        assert_eq!(most_common(languages(&["en", "zh"])).as_deref(), Some("en"));
        assert_eq!(most_common(Vec::new()), None);
    }

    #[test]
    fn validates_options() {
        assert!(Options::new("a.mp4", "vi").validate().is_ok());
        assert!(Options::new("a.mp4", "fr").validate().is_err());
        let mut options = Options::new("a.mp4", "fr");
        options.subtitles_only = true;
        assert!(options.validate().is_ok());
        options.tts_workers = 0;
        assert!(options.validate().is_err());
    }
}
