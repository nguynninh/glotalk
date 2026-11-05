use std::path::PathBuf;

pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("missing asset file: {0}")]
    MissingAsset(PathBuf),
    #[error("cannot read {path}: {source}")]
    Io { path: PathBuf, source: std::io::Error },
    #[error("invalid asset {path}: {reason}")]
    InvalidAsset { path: PathBuf, reason: String },
    #[error("unknown voice '{name}'; available: {available}")]
    UnknownVoice { name: String, available: String },
    #[error("invalid argument: {0}")]
    InvalidArgument(String),
    #[error("ONNX Runtime: {0}")]
    Runtime(#[from] ort::Error),
    #[error("unexpected model output: {0}")]
    ModelOutput(String),
}
