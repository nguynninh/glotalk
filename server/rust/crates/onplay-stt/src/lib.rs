//! Speech recognition on Sherpa ONNX, ported from the C++ engine (`src/stt.cpp`,
//! `src/speakers.cpp`, `src/recognize.cpp`, `include/onplay/wave_reader.hpp`).
//!
//! Audio is mono float PCM at 16 kHz with finite values in [-1, 1]. Everything is
//! synchronous and single-threaded: listeners run inside [`Session::accept_audio`] and
//! [`Session::finish`] and cannot re-enter the session.

pub mod diarize;
pub mod error;
pub mod model;
pub mod pcm;
pub mod streaming;
pub mod utterance;
pub mod wave;

pub use error::{Error, Result};
pub use model::ModelFiles;
pub use streaming::{Engine, EngineConfig, Session, Transcript};
pub use wave::WaveReader;

pub const SAMPLE_RATE: usize = 16_000;
/// Silence appended before `input_finished` to flush the encoder lookahead (0.8 s).
pub(crate) const FLUSH_SAMPLES: usize = 12_800;
