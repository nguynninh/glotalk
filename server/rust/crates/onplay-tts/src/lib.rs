//! KorvaTTS speech synthesis in Rust.
//!
//! Reads the published KorvaTTS assets (ONNX graphs, `tts.json`, `unicode_indexer.json`,
//! voice styles) without modifying them and reproduces the Python `korvatts` 0.1.3 pipeline:
//!
//! text -> [`text`] -> duration predictor -> text encoder
//!      -> flow-matching loop over the vector estimator -> vocoder -> [`audio`]
//!
//! The shipped graphs fix the batch dimension to 1, so synthesis is single-utterance.
//!
//! The pipeline logic is ported from `korvatts` 0.1.3 (Apache-2.0, dogenthq); keep its
//! LICENSE and NOTICE with any redistribution of this crate.

pub mod assets;
pub mod audio;
pub mod error;
pub mod runtime;
pub mod synth;
pub mod tensor;
pub mod text;
pub mod voice;

pub use assets::Assets;
pub use error::{Error, Result};
pub use runtime::{init_runtime, Device};
pub use synth::{GaussianNoise, NoiseSource, SynthesisOptions, Synthesizer};
pub use voice::VoiceStyle;
