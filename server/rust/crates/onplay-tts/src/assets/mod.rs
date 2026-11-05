//! Location and validation of the KorvaTTS asset directory.
//!
//! The layout matches the `dogenthq/KorvaTTS` Hugging Face snapshot that the Python package
//! downloads: `onnx/*.onnx`, `onnx/tts.json`, `onnx/unicode_indexer.json`, `voice_styles/*.json`.
//! Files are only read; nothing in the directory is created or changed.

mod config;
mod read;

use std::path::{Path, PathBuf};

pub use config::ModelConfig;
pub(crate) use read::read_json;

use crate::error::{Error, Result};

pub const DURATION_PREDICTOR: &str = "duration_predictor.onnx";
pub const TEXT_ENCODER: &str = "text_encoder.onnx";
pub const VECTOR_ESTIMATOR: &str = "vector_estimator.onnx";
pub const VOCODER: &str = "vocoder.onnx";
const CONFIG: &str = "tts.json";
const UNICODE_INDEXER: &str = "unicode_indexer.json";
const REQUIRED: [&str; 6] =
    [DURATION_PREDICTOR, TEXT_ENCODER, VECTOR_ESTIMATOR, VOCODER, CONFIG, UNICODE_INDEXER];

/// A validated asset directory.
#[derive(Debug, Clone)]
pub struct Assets {
    root: PathBuf,
}

impl Assets {
    /// Validates that every required file exists under `root`.
    pub fn open(root: impl Into<PathBuf>) -> Result<Self> {
        let assets = Self { root: root.into() };
        for name in REQUIRED {
            let path = assets.onnx(name);
            if !path.is_file() {
                return Err(Error::MissingAsset(path));
            }
        }
        if !assets.root.join("voice_styles").is_dir() {
            return Err(Error::MissingAsset(assets.root.join("voice_styles")));
        }
        Ok(assets)
    }

    /// The newest snapshot in the Hugging Face cache, as downloaded by `korvatts`.
    pub fn from_huggingface_cache() -> Result<Self> {
        let home = std::env::var_os("HF_HOME")
            .map(|home| PathBuf::from(home).join("hub"))
            .or_else(|| std::env::var_os("HOME").map(|home| Path::new(&home).join(".cache/huggingface/hub")))
            .ok_or_else(|| Error::InvalidArgument("HOME is not set".into()))?;
        let snapshots = home.join("models--dogenthq--KorvaTTS/snapshots");
        let newest = std::fs::read_dir(&snapshots)
            .map_err(|source| Error::Io { path: snapshots.clone(), source })?
            .filter_map(|entry| entry.ok())
            .filter(|entry| entry.path().is_dir())
            .max_by_key(|entry| entry.metadata().and_then(|m| m.modified()).ok())
            .ok_or_else(|| Error::MissingAsset(snapshots.clone()))?;
        Self::open(newest.path())
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn onnx(&self, name: &str) -> PathBuf {
        self.root.join("onnx").join(name)
    }

    pub fn config(&self) -> Result<ModelConfig> {
        ModelConfig::load(&self.onnx(CONFIG))
    }

    pub fn unicode_indexer(&self) -> PathBuf {
        self.onnx(UNICODE_INDEXER)
    }

    /// Voice names (file stems under `voice_styles/`), sorted.
    pub fn voices(&self) -> Result<Vec<String>> {
        let directory = self.root.join("voice_styles");
        let mut names: Vec<String> = std::fs::read_dir(&directory)
            .map_err(|source| Error::Io { path: directory.clone(), source })?
            .filter_map(|entry| entry.ok())
            .map(|entry| entry.path())
            .filter(|path| path.extension().is_some_and(|ext| ext == "json"))
            .filter_map(|path| path.file_stem().and_then(|stem| stem.to_str()).map(str::to_owned))
            .collect();
        names.sort();
        Ok(names)
    }

    /// Path of a voice style; rejects names that are not listed voices.
    pub fn voice(&self, name: &str) -> Result<PathBuf> {
        let voices = self.voices()?;
        if !voices.iter().any(|voice| voice == name) {
            return Err(Error::UnknownVoice { name: name.into(), available: voices.join(", ") });
        }
        Ok(self.root.join("voice_styles").join(format!("{name}.json")))
    }
}
