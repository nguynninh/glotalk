//! Streaming recognition with endpointing and optional live speaker labels (`src/stt.cpp`).

use std::path::PathBuf;

use onplay_sherpa::online::{Endpoint, Recognizer, Stream};
use onplay_sherpa::speaker::{EmbeddingExtractor, EmbeddingManager};

use crate::error::{Error, Result};
use crate::model::{check_threads, require_file, ModelFiles};
use crate::{FLUSH_SAMPLES, SAMPLE_RATE};

/// Most samples accepted per call: one second.
pub const MAX_CHUNK: usize = SAMPLE_RATE;
/// Live speaker labels use the most recent four seconds.
const SPEAKER_WINDOW: usize = 4 * SAMPLE_RATE;
const SPEAKER_MIN: usize = SAMPLE_RATE;
/// A partial result re-identifies the speaker at most every two seconds.
const SPEAKER_INTERVAL: usize = 2 * SAMPLE_RATE;
const SPEAKER_MATCH: f32 = 0.5;

#[derive(Debug, Clone, PartialEq)]
pub struct EngineConfig {
    pub model: ModelFiles,
    pub threads: i32,
    /// Enables provisional live speaker labels.
    pub speaker_model: Option<PathBuf>,
    pub low_latency: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Transcript {
    pub text: String,
    pub is_final: bool,
    /// Provisional session-local label; 0 means unknown or disabled.
    pub speaker: u32,
}

/// Loaded models; create one [`Session`] per recording.
pub struct Engine {
    recognizer: Recognizer,
    extractor: Option<EmbeddingExtractor>,
}

impl Engine {
    pub fn load(config: &EngineConfig) -> Result<Self> {
        check_threads(config.threads).map_err(|_| Error::invalid("Listener required; threads must be 1..16"))?;
        config.model.validate()?;
        let extractor = match &config.speaker_model {
            Some(path) => {
                require_file(path, "Missing speaker model file")?;
                Some(EmbeddingExtractor::new(path, config.threads)?)
            }
            None => None,
        };
        let endpoint = Endpoint {
            rule1_min_trailing_silence: 2.4,
            // Low latency finalizes at short pauses between sentences; spoken and displayed
            // translations cannot be revised, so cutting mid-clause costs more than waiting.
            rule2_min_trailing_silence: if config.low_latency { 0.45 } else { 1.2 },
            // Bound translation wait during continuous speech, at the cost of sentence context.
            rule3_min_utterance_length: if config.low_latency { 12.0 } else { 20.0 },
        };
        let recognizer = Recognizer::new(&config.model.recognizer(config.threads, Some(endpoint)))?;
        Ok(Self { recognizer, extractor })
    }

    pub fn session<L: FnMut(&Transcript)>(&self, listener: L) -> Result<Session<'_, L>> {
        let speakers = match &self.extractor {
            Some(extractor) => Some(Speakers::new(extractor)?),
            None => None,
        };
        Ok(Session {
            stream: self.recognizer.create_stream()?,
            speakers,
            listener,
            previous: String::new(),
            finished: false,
        })
    }
}

/// One recording. Synchronous: the listener runs inside `accept_audio` and `finish`.
pub struct Session<'e, L: FnMut(&Transcript)> {
    stream: Stream<'e>,
    speakers: Option<Speakers<'e>>,
    listener: L,
    previous: String,
    finished: bool,
}

impl<L: FnMut(&Transcript)> Session<'_, L> {
    /// Accepts at most one second of finite samples in [-1, 1].
    pub fn accept_audio(&mut self, samples: &[f32]) -> Result<()> {
        if self.finished {
            return Err(Error::Finished);
        }
        if samples.len() > MAX_CHUNK {
            return Err(Error::invalid("Audio chunk exceeds one second"));
        }
        if samples.iter().any(|sample| !sample.is_finite() || !(-1.0..=1.0).contains(sample)) {
            return Err(Error::invalid("PCM samples must be finite and within [-1, 1]"));
        }
        if samples.is_empty() {
            return Ok(());
        }
        if let Some(speakers) = &mut self.speakers {
            speakers.push(samples);
        }
        self.stream.accept_waveform(samples)?;
        self.decode(false)
    }

    /// Flushes and emits the final transcript. Idempotent; the session accepts no more audio.
    pub fn finish(&mut self) -> Result<()> {
        if self.finished {
            return Ok(());
        }
        self.finished = true;
        // Flush encoder lookahead without counting synthetic silence as input duration.
        self.stream.accept_waveform(&[0.0; FLUSH_SAMPLES])?;
        self.stream.input_finished();
        self.decode(true)
    }

    fn decode(&mut self, is_final: bool) -> Result<()> {
        self.stream.decode();
        let text = self.stream.text()?;
        let endpoint = !is_final && self.stream.is_endpoint();
        if !text.is_empty() && (is_final || endpoint || text != self.previous) {
            let speaker = match &mut self.speakers {
                Some(speakers) => speakers.identify(is_final || endpoint)?,
                None => 0,
            };
            self.previous.clone_from(&text);
            (self.listener)(&Transcript { text, is_final: is_final || endpoint, speaker });
        }
        if endpoint {
            self.stream.reset();
            self.previous.clear();
            if let Some(speakers) = &mut self.speakers {
                speakers.reset_turn();
            }
        }
        Ok(())
    }
}

/// Live speaker labels: one label for the recent four seconds.
/// Shortcut kept from C++: use segmentation for overlapping or rapid turns.
struct Speakers<'e> {
    extractor: &'e EmbeddingExtractor,
    registry: EmbeddingManager,
    audio: Vec<f32>,
    since: usize,
    current: u32,
    count: u32,
}

impl<'e> Speakers<'e> {
    fn new(extractor: &'e EmbeddingExtractor) -> Result<Self> {
        Ok(Self { extractor, registry: EmbeddingManager::new(extractor.dim())?, audio: Vec::new(), since: 0, current: 0, count: 0 })
    }

    fn push(&mut self, samples: &[f32]) {
        self.audio.extend_from_slice(samples);
        if self.audio.len() > SPEAKER_WINDOW {
            self.audio.drain(..self.audio.len() - SPEAKER_WINDOW);
        }
        self.since += samples.len();
    }

    fn identify(&mut self, is_final: bool) -> Result<u32> {
        if self.audio.len() < SPEAKER_MIN || (!is_final && self.since < SPEAKER_INTERVAL) {
            return Ok(self.current);
        }
        let Some(embedding) = self.extractor.embed(&self.audio)? else {
            return Ok(self.current);
        };
        self.current = match self.registry.search(&embedding, SPEAKER_MATCH) {
            Some(name) => name.parse().map_err(|_| Error::runtime("Invalid speaker label"))?,
            None => {
                self.count += 1;
                if !self.registry.add(&self.count.to_string(), &embedding)? {
                    return Err(Error::runtime("Cannot register speaker"));
                }
                self.count
            }
        };
        self.since = 0;
        Ok(self.current)
    }

    fn reset_turn(&mut self) {
        self.audio.clear();
        self.since = 0;
        self.current = 0;
    }
}
