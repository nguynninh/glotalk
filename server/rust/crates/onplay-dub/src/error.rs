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
    /// Stable failure code for clients of a dub job (`docs/video-dubbing-design.md`).
    pub fn code(&self) -> &'static str {
        match self {
            Self::Command { program, .. } if program == "yt-dlp" => "download_failed",
            Self::NoSpeech => "no_speech",
            Self::Translation(_) => "translation_failed",
            Self::Speech(_) => "speech_failed",
            _ => "internal",
        }
    }

    pub(crate) fn io(path: impl Into<PathBuf>) -> impl FnOnce(std::io::Error) -> Self {
        let path = path.into();
        move |source| Self::Io { path, source }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_failures_for_clients() {
        let command = |program: &str| Error::Command { program: program.into(), detail: String::new() };
        assert_eq!(command("yt-dlp").code(), "download_failed");
        assert_eq!(command("ffmpeg").code(), "internal");
        assert_eq!(Error::NoSpeech.code(), "no_speech");
        assert_eq!(Error::Translation("x".into()).code(), "translation_failed");
    }
}
