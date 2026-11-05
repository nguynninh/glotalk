use std::path::Path;

use serde::de::DeserializeOwned;

use crate::error::{Error, Result};

pub(crate) fn read_json<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let bytes = std::fs::read(path).map_err(|source| Error::Io { path: path.into(), source })?;
    serde_json::from_slice(&bytes)
        .map_err(|error| Error::InvalidAsset { path: path.into(), reason: error.to_string() })
}
