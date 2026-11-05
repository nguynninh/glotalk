use std::path::PathBuf;

pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Setup(String),
    #[error("invalid argument: {0}")]
    InvalidArgument(String),
    #[error("cannot access {path}: {source}")]
    Io { path: PathBuf, source: std::io::Error },
    #[error("{program} failed: {detail}")]
    Command { program: String, detail: String },
    #[error("invalid media: {0}")]
    Media(String),
    #[error("translation failed: {0}")]
    Translation(String),
    #[error("speech failed: {0}")]
    Speech(#[from] onplay_tts::Error),
    #[error("no speech recognized")]
    NoSpeech,
}

impl Error {
    pub(crate) fn io(path: impl Into<PathBuf>) -> impl FnOnce(std::io::Error) -> Self {
        let path = path.into();
        move |source| Self::Io { path, source }
    }
}
