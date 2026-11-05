//! Local translation router, replacing the removed Python `ui/translation_engine.py` with the
//! same HTTP contract, routes, models, limits, cache and metrics.
//!
//! Live translation runs OPUS-MT and NLLB through CTranslate2, the native library and INT8
//! models the Python router used; context translation runs Qwen3 with candle (Metal when
//! available). Models load on first use; [`Engine::warmup`] loads the CTranslate2 ones and
//! the language detector so startup cost is not charged to the first segment.
//!
//! The engine is synchronous and shared between request threads. Each mode has one worker
//! lane, so a slow context request cannot block live translation, and each model runs one
//! request at a time.

mod cache;
mod config;
mod detect;
mod engine;
mod error;
mod language;
mod metrics;
mod models;
mod request;
mod text;

pub use config::Config;
pub use engine::{Engine, Translation};
pub use error::{Error, Result};
pub use metrics::Metrics;
