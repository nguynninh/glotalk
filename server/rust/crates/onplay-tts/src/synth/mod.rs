//! The synthesis pipeline, mirroring `korvatts/tts.py`.

mod fit;
mod latent;
mod noise;

pub use noise::{GaussianNoise, NoiseSource};

use crate::assets::{Assets, ModelConfig};
use crate::audio::clean_tail;
use crate::error::{Error, Result};
use crate::runtime::{Device, Graphs};
use crate::text::{chunk_limit, chunk_text, Language, UnicodeIndexer};
use crate::voice::VoiceStyle;

pub const MAX_TOTAL_STEPS: usize = 32;
pub const SPEED_RANGE: (f32, f32) = (0.5, 2.0);

#[derive(Debug, Clone, Copy)]
pub struct SynthesisOptions {
    /// Flow-matching iterations, 1..=32. KorvaTTS defaults to 32 (best quality).
    pub total_steps: usize,
    /// Divides the predicted duration; 0.5..=2.0.
    pub speed: f32,
    /// Silence inserted between text chunks, in seconds.
    pub chunk_silence: f32,
    /// Raise the speed (up to the maximum) so the predicted speech fits this many seconds.
    pub max_duration: Option<f32>,
    /// Shorten predicted durations over twice `0.6 s + 0.1 s` per character, which
    /// KorvaTTS fills with invented speech. Off by default, which matches Python KorvaTTS.
    pub cap_runaway_duration: bool,
}

impl Default for SynthesisOptions {
    fn default() -> Self {
        Self { total_steps: MAX_TOTAL_STEPS, speed: 1.05, chunk_silence: 0.3, max_duration: None, cap_runaway_duration: false }
    }
}

impl SynthesisOptions {
    fn validate(&self) -> Result<()> {
        if !(1..=MAX_TOTAL_STEPS).contains(&self.total_steps) {
            return Err(Error::InvalidArgument(format!("total_steps must be 1..={MAX_TOTAL_STEPS}")));
        }
        if !(SPEED_RANGE.0..=SPEED_RANGE.1).contains(&self.speed) {
            return Err(Error::InvalidArgument(format!("speed must be {}..={}", SPEED_RANGE.0, SPEED_RANGE.1)));
        }
        if !(self.chunk_silence >= 0.0 && self.chunk_silence.is_finite()) {
            return Err(Error::InvalidArgument("chunk_silence must be >= 0".into()));
        }
        if self.max_duration.is_some_and(|max| !(max > 0.0 && max.is_finite())) {
            return Err(Error::InvalidArgument("max_duration must be > 0".into()));
        }
        Ok(())
    }
}

/// Loaded graphs and text tables; reuse it for many utterances. Not shareable across
/// threads while synthesizing, because ONNX sessions run through `&mut self`.
pub struct Synthesizer {
    graphs: Graphs,
    indexer: UnicodeIndexer,
    config: ModelConfig,
}

impl Synthesizer {
    pub fn load(assets: &Assets, device: Device, threads: Option<usize>) -> Result<Self> {
        Ok(Self {
            graphs: Graphs::load(assets, device, threads)?,
            indexer: UnicodeIndexer::load(&assets.unicode_indexer())?,
            config: assets.config()?,
        })
    }

    pub fn sample_rate(&self) -> u32 {
        self.config.sample_rate
    }

    /// Mono float waveform at [`Self::sample_rate`] for arbitrarily long text.
    pub fn synthesize(
        &mut self,
        text: &str,
        language: Language,
        voice: &VoiceStyle,
        options: SynthesisOptions,
        noise: &mut dyn NoiseSource,
    ) -> Result<Vec<f32>> {
        options.validate()?;
        if text.trim().is_empty() {
            return Err(Error::InvalidArgument("text is empty".into()));
        }
        // Predict every chunk's duration first (cheap), so the speed can be chosen once.
        let mut chunks = Vec::new();
        for chunk in chunk_text(text, chunk_limit(language)) {
            let ids = self.indexer.encode(&chunk, language);
            let mask = latent::full_mask(ids.len());
            let mut raw_duration = self.graphs.duration(&ids, &mask, voice)?;
            if options.cap_runaway_duration {
                raw_duration = fit::runaway_capped(raw_duration, chunk.chars().count());
            }
            chunks.push(Chunk { ids, mask, raw_duration });
        }
        let gaps = options.chunk_silence * chunks.len().saturating_sub(1) as f32;
        let raw: Vec<f32> = chunks.iter().map(|chunk| chunk.raw_duration).collect();
        let speed = fit::fitted_speed(&raw, gaps, options.speed, options.max_duration, SPEED_RANGE.1);

        let silence = (options.chunk_silence * self.config.sample_rate as f32) as usize;
        let mut wav = Vec::new();
        for (index, chunk) in chunks.iter().enumerate() {
            if index > 0 {
                wav.resize(wav.len() + silence, 0.0);
            }
            wav.extend(self.infer(chunk, voice, options.total_steps, speed, noise)?);
        }
        Ok(wav)
    }

    fn infer(
        &mut self,
        chunk: &Chunk,
        voice: &VoiceStyle,
        total_steps: usize,
        speed: f32,
        noise: &mut dyn NoiseSource,
    ) -> Result<Vec<f32>> {
        let duration = chunk.raw_duration / speed;
        let text_emb = self.graphs.encode_text(&chunk.ids, &chunk.mask, voice)?;
        let mut latent = latent::noisy_latent(&self.config, duration, noise);
        for step in 0..total_steps {
            latent.data = self.graphs.estimate(&latent, &text_emb, voice, &chunk.mask, step, total_steps)?;
        }
        let mut wav = self.graphs.vocode(&latent)?;
        // Python: wav[0, : int(dur * sample_rate)] with dur as a Python float.
        let keep = (f64::from(duration) * f64::from(self.config.sample_rate)) as usize;
        wav.truncate(keep);
        Ok(clean_tail(wav, self.config.sample_rate))
    }
}

/// One text chunk ready for inference.
struct Chunk {
    ids: Vec<i64>,
    mask: Vec<f32>,
    /// Duration predictor output in seconds, before the speed factor.
    raw_duration: f32,
}
