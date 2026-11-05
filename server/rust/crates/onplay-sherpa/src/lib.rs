//! Safe wrappers over the parts of the Sherpa ONNX C API that onplay-stt uses.
//!
//! Every native handle is owned by exactly one Rust value and released in `Drop`, on success
//! and on every error path. Streams borrow the recognizer or extractor that created them, so
//! they cannot outlive it. No type is `Send` or `Sync`: the C++ engine documented a
//! single-worker-thread contract and the C API makes no stronger guarantee.

mod error;
mod ffi_string;

pub mod diarization;
pub mod online;
pub mod speaker;

pub use error::{Error, Result};

/// Sample rate of every model used here; streams are fed mono float PCM at this rate.
pub const SAMPLE_RATE: i32 = 16_000;
