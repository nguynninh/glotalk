//! Streaming transducer model files.

use std::path::{Path, PathBuf};

use onplay_sherpa::online::{Endpoint, RecognizerConfig};

use crate::error::{Error, Result};

#[derive(Debug, Clone, PartialEq)]
pub struct ModelFiles {
    pub encoder: PathBuf,
    pub decoder: PathBuf,
    pub joiner: PathBuf,
    pub tokens: PathBuf,
}

impl ModelFiles {
    /// Fails with `Missing model file: PATH` for the first file that is not a regular file.
    pub fn validate(&self) -> Result<()> {
        for path in [&self.encoder, &self.decoder, &self.joiner, &self.tokens] {
            require_file(path, "Missing model file")?;
        }
        Ok(())
    }

    pub(crate) fn recognizer(&self, threads: i32, endpoint: Option<Endpoint>) -> RecognizerConfig {
        RecognizerConfig {
            encoder: self.encoder.clone(),
            decoder: self.decoder.clone(),
            joiner: self.joiner.clone(),
            tokens: self.tokens.clone(),
            threads,
            endpoint,
        }
    }
}

pub(crate) fn require_file(path: &Path, what: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        Err(Error::invalid(format!("{what}: {}", path.display())))
    }
}

pub(crate) fn check_threads(threads: i32) -> Result<()> {
    if (1..=16).contains(&threads) {
        Ok(())
    } else {
        Err(Error::invalid("threads must be 1..16"))
    }
}
