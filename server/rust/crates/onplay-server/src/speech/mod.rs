//! In-process KorvaTTS returning bounded PCM16 WAV.
//!
//! One synthesizer, loaded once and shared behind a mutex: sessions run one at a time and
//! speak one sentence at a time, so a single model copy is enough.

pub mod voices;

use std::collections::HashMap;
use std::sync::Mutex;

use onplay_tts::audio::encode_pcm16_wav;
use onplay_tts::text::Language;
use onplay_tts::{Assets, Device, GaussianNoise, SynthesisOptions, Synthesizer, VoiceStyle};

use crate::languages::is_target;

const MAX_TEXT_CHARS: usize = 3000;
const MAX_WAV_BYTES: usize = 32 * 1024 * 1024;

/// Messages are sent to clients in `audio_error` events.
#[derive(Debug, thiserror::Error)]
pub enum SpeechError {
    /// Rejected request (Python `ValueError`).
    #[error("{0}")]
    Invalid(&'static str),
    #[error("{0}")]
    Unavailable(&'static str),
}

/// A synthesized sentence.
pub struct Spoken {
    pub wav: Vec<u8>,
    pub seconds: f64,
}

pub struct Speech {
    state: Mutex<State>,
}

struct State {
    synthesizer: Option<Synthesizer>,
    voices: HashMap<&'static str, VoiceStyle>,
    noise: GaussianNoise,
}

impl Default for Speech {
    fn default() -> Self {
        Self::new()
    }
}

impl Speech {
    pub fn new() -> Self {
        Self { state: Mutex::new(State { synthesizer: None, voices: HashMap::new(), noise: GaussianNoise::new(None) }) }
    }

    /// Loads the models from the Hugging Face cache unless already loaded. Needs
    /// `onplay_tts::init_runtime` first.
    pub fn load(&self) -> Result<(), String> {
        let mut state = self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        state.load()
    }

    /// `speaker` is the live 1-based label (0 or `None`: language default voice);
    /// `speed` is 1..=2.
    pub fn synthesize(&self, text: &str, language: &str, speaker: Option<u32>, speed: f64) -> Result<Spoken, SpeechError> {
        if text.trim().is_empty() || text.chars().count() > MAX_TEXT_CHARS || text.contains('\0') {
            return Err(SpeechError::Invalid("Expected non-empty text, maximum 3000 characters"));
        }
        if !is_target(language) {
            return Err(SpeechError::Invalid("Unsupported speech language"));
        }
        let Some(voice) = voices::voice_for(language, speaker.unwrap_or(0)).filter(|_| voices::speakable(language)) else {
            return Err(SpeechError::Unavailable("KorvaTTS currently supports Vietnamese and English speech only"));
        };
        if !(1.0..=2.0).contains(&speed) {
            return Err(SpeechError::Invalid("Speed must be between 1 and 2"));
        }
        let mut state = self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        state.speak(text, language, voice, speed as f32).ok_or(SpeechError::Unavailable("KorvaTTS speech synthesis unavailable"))?
    }
}

impl State {
    fn load(&mut self) -> Result<(), String> {
        if self.synthesizer.is_none() {
            let assets = Assets::from_huggingface_cache().map_err(|error| error.to_string())?;
            self.synthesizer = Some(Synthesizer::load(&assets, Device::Cpu, None).map_err(|error| error.to_string())?);
            self.voices.clear();
        }
        Ok(())
    }

    /// `None` when the engine cannot load or synthesize.
    fn speak(&mut self, text: &str, language: &str, voice: &'static str, speed: f32) -> Option<Result<Spoken, SpeechError>> {
        self.load().ok()?;
        let synthesizer = self.synthesizer.as_mut()?;
        if !self.voices.contains_key(voice) {
            let assets = Assets::from_huggingface_cache().ok()?;
            self.voices.insert(voice, VoiceStyle::load(&assets.voice(voice).ok()?).ok()?);
        }
        let options =
            SynthesisOptions { total_steps: voices::TOTAL_STEPS, speed, cap_runaway_duration: true, ..SynthesisOptions::default() };
        let language = Language::parse(language).ok()?;
        let samples = synthesizer.synthesize(text, language, &self.voices[voice], options, &mut self.noise).ok()?;
        let rate = synthesizer.sample_rate();
        let wav = encode_pcm16_wav(&samples, rate).ok()?;
        if wav.len() > MAX_WAV_BYTES {
            return Some(Err(SpeechError::Unavailable("Generated audio exceeds 32 MiB")));
        }
        Some(Ok(Spoken { wav, seconds: samples.len() as f64 / f64::from(rate) }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_before_loading_models() {
        let speech = Speech::new();
        let error = |text: &str, language: &str, speed: f64| speech.synthesize(text, language, None, speed).err().unwrap().to_string();
        assert_eq!(error(" ", "vi", 1.05), "Expected non-empty text, maximum 3000 characters");
        assert_eq!(error("a\0", "vi", 1.05), "Expected non-empty text, maximum 3000 characters");
        assert_eq!(error("hi", "xx", 1.05), "Unsupported speech language");
        assert_eq!(error("hi", "fr", 1.05), "KorvaTTS currently supports Vietnamese and English speech only");
        assert_eq!(error("hi", "vi", 2.5), "Speed must be between 1 and 2");
    }
}
