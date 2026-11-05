//! The subset of `onnx/tts.json` the inference pipeline needs.

use std::path::Path;

use serde::Deserialize;

use super::read_json;
use crate::error::{Error, Result};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ModelConfig {
    /// Output sample rate of the vocoder (`ae.sample_rate`).
    pub sample_rate: u32,
    /// Waveform samples per latent frame before compression (`ae.base_chunk_size`).
    pub base_chunk_size: usize,
    /// Latent frames merged into one estimator frame (`ttl.chunk_compress_factor`).
    pub chunk_compress_factor: usize,
    /// Latent channels per uncompressed frame (`ttl.latent_dim`).
    pub latent_dim: usize,
}

impl ModelConfig {
    pub fn load(path: &Path) -> Result<Self> {
        let raw: Raw = read_json(path)?;
        let config = Self {
            sample_rate: raw.ae.sample_rate,
            base_chunk_size: raw.ae.base_chunk_size,
            chunk_compress_factor: raw.ttl.chunk_compress_factor,
            latent_dim: raw.ttl.latent_dim,
        };
        if config.sample_rate == 0
            || config.base_chunk_size == 0
            || config.chunk_compress_factor == 0
            || config.latent_dim == 0
        {
            return Err(Error::InvalidAsset { path: path.into(), reason: "zero dimension".into() });
        }
        Ok(config)
    }

    /// Waveform samples covered by one estimator latent frame.
    pub fn samples_per_latent(&self) -> usize {
        self.base_chunk_size * self.chunk_compress_factor
    }

    /// Channels of the compressed latent fed to the vector estimator.
    pub fn compressed_latent_dim(&self) -> usize {
        self.latent_dim * self.chunk_compress_factor
    }
}

#[derive(Deserialize)]
struct Raw {
    ae: RawAe,
    ttl: RawTtl,
}

#[derive(Deserialize)]
struct RawAe {
    sample_rate: u32,
    base_chunk_size: usize,
}

#[derive(Deserialize)]
struct RawTtl {
    latent_dim: usize,
    chunk_compress_factor: usize,
}
