//! Whole-utterance recognition on a fresh stream (`src/recognize.cpp`, per-turn STT in
//! `src/speakers.cpp`): no endpointing, flush silence, decode everything.

use onplay_sherpa::online::Recognizer;

use crate::error::Result;
use crate::model::{check_threads, ModelFiles};
use crate::FLUSH_SAMPLES;

/// A model loaded once; each call gets its own stream.
pub struct UtteranceRecognizer {
    recognizer: Recognizer,
}

impl UtteranceRecognizer {
    pub fn load(model: &ModelFiles, threads: i32) -> Result<Self> {
        check_threads(threads)?;
        model.validate()?;
        Ok(Self { recognizer: Recognizer::new(&model.recognizer(threads, None))? })
    }

    pub fn transcribe(&self, samples: &[f32]) -> Result<String> {
        let mut stream = self.recognizer.create_stream()?;
        stream.accept_waveform(samples)?;
        stream.accept_waveform(&[0.0; FLUSH_SAMPLES])?;
        stream.input_finished();
        stream.decode();
        Ok(stream.text()?)
    }
}
