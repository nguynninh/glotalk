//! File locations and environment settings, resolved once at startup.

use std::path::{Path, PathBuf};

use onplay_stt::ModelFiles;

pub const STREAM_ADDRESS: &str = "127.0.0.1:8766";
/// Dub job results over plain HTTP; the tunnel sends `/jobs/` here.
pub const DOWNLOAD_ADDRESS: &str = "127.0.0.1:8767";
/// Origin accepted without configuration, kept from the removed local web UI so existing
/// clients that send it still connect.
const LOCAL_ORIGIN: &str = "http://127.0.0.1:8765";
const STT_MODEL: &str = "models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10";
const SEGMENTATION: &str = "models/speakers/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx";
const EMBEDDING: &str = "models/speakers/embedding.onnx";
const TRANSLATE_URL: &str = "http://127.0.0.1:5002";
/// Each concurrent session may load its own streaming engine (about 340 MB per recognizer
/// configuration in use).
const MAX_SESSIONS: usize = 4;
const DUB_BIN: &str = "build/onplay-dub";
/// Longest video a dub job accepts: 15 minutes.
const DUB_MAX_SECONDS: f64 = 900.0;
const JOB_MAX_GB: u64 = 20;

/// `ONPLAY_CAPTURE_AUDIO`: `0` nothing (default), `1` transcript and audio, `2` transcript
/// only, `3` audio only. A client can still ask for its own transcript with `store: true`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Capture {
    #[default]
    Off,
    All,
    Transcript,
    Audio,
}

impl Capture {
    /// `None` for anything but `0`-`3`.
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim() {
            "0" => Some(Self::Off),
            "1" => Some(Self::All),
            "2" => Some(Self::Transcript),
            "3" => Some(Self::Audio),
            _ => None,
        }
    }

    /// `sessions/<id>.jsonl` for every session.
    pub fn transcript(self) -> bool {
        matches!(self, Self::All | Self::Transcript)
    }

    /// `sessions/<id>.wav` with the received PCM for every session.
    pub fn audio(self) -> bool {
        matches!(self, Self::All | Self::Audio)
    }
}

#[derive(Debug, Clone)]
pub struct Config {
    pub address: String,
    pub stt: ModelFiles,
    pub segmentation: PathBuf,
    pub embedding: PathBuf,
    /// Exact `Origin` header values allowed to open the socket.
    pub origins: Vec<String>,
    pub translate_url: String,
    pub session_dir: PathBuf,
    /// `ONPLAY_CAPTURE_AUDIO`: what to store for every session.
    pub capture: Capture,
    /// `ONPLAY_MAX_SESSIONS`: recognition sessions served at once; more are refused.
    pub max_sessions: usize,
    /// Checkout with `build/` and `models/`, passed to `onplay-dub --root`.
    pub root: PathBuf,
    pub dub_bin: PathBuf,
    pub download_address: String,
    /// `ONPLAY_JOB_DIR`: dub job files, cleared of job directories at startup.
    pub job_dir: PathBuf,
    /// `ONPLAY_DUB_TOKENS`: `name sha256-hex` lines; dubbing is off without it.
    pub dub_tokens: PathBuf,
    /// `ONPLAY_DUB_MAX_SECONDS`: longest video a job accepts.
    pub dub_max_seconds: f64,
    /// `ONPLAY_JOB_MAX_GB`: new jobs are refused while `job_dir` holds more.
    pub job_max_bytes: u64,
    /// `ONPLAY_DUB_LOCAL_LINKS=1` accepts links to local hosts; for tests only.
    pub dub_local_links: bool,
}

/// A positive session count; `None` otherwise.
pub fn parse_max_sessions(value: &str) -> Option<usize> {
    value.trim().parse().ok().filter(|count| *count > 0)
}

impl Config {
    /// Paths under the project `root`, overridden by `ONPLAY_PUBLIC_ORIGIN`,
    /// `ONPLAY_TRANSLATE_URL`, `ONPLAY_SESSION_DIR`, `ONPLAY_CAPTURE_AUDIO` and
    /// `ONPLAY_MAX_SESSIONS`, plus the dub job settings documented on each field.
    pub fn from_env(root: &Path) -> Self {
        let env = |name: &str| std::env::var(name).ok();
        let model = root.join(STT_MODEL);
        let mut origins = vec![LOCAL_ORIGIN.to_owned()];
        origins.extend(env("ONPLAY_PUBLIC_ORIGIN").unwrap_or_default().split(',').filter(|o| !o.is_empty()).map(str::to_owned));
        Self {
            address: STREAM_ADDRESS.to_owned(),
            stt: ModelFiles {
                encoder: model.join("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx"),
                decoder: model.join("decoder-epoch-75-avg-11-chunk-16-left-128.onnx"),
                joiner: model.join("joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx"),
                tokens: model.join("tokens.txt"),
            },
            segmentation: root.join(SEGMENTATION),
            embedding: root.join(EMBEDDING),
            origins,
            translate_url: env("ONPLAY_TRANSLATE_URL").unwrap_or_else(|| TRANSLATE_URL.to_owned()),
            session_dir: env("ONPLAY_SESSION_DIR").map_or_else(|| root.join("sessions"), PathBuf::from),
            capture: env("ONPLAY_CAPTURE_AUDIO").as_deref().and_then(Capture::parse).unwrap_or_default(),
            max_sessions: env("ONPLAY_MAX_SESSIONS").as_deref().and_then(parse_max_sessions).unwrap_or(MAX_SESSIONS),
            root: root.to_owned(),
            dub_bin: root.join(DUB_BIN),
            download_address: DOWNLOAD_ADDRESS.to_owned(),
            job_dir: env("ONPLAY_JOB_DIR").map_or_else(|| root.join("jobs"), PathBuf::from),
            dub_tokens: env("ONPLAY_DUB_TOKENS").map_or_else(|| root.join("dub-tokens"), PathBuf::from),
            dub_max_seconds: env("ONPLAY_DUB_MAX_SECONDS").and_then(|value| value.trim().parse().ok()).filter(|seconds: &f64| *seconds > 0.0).unwrap_or(DUB_MAX_SECONDS),
            job_max_bytes: env("ONPLAY_JOB_MAX_GB").and_then(|value| value.trim().parse().ok()).unwrap_or(JOB_MAX_GB) << 30,
            dub_local_links: env("ONPLAY_DUB_LOCAL_LINKS").is_some_and(|value| value == "1"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_capture_modes() {
        let modes: Vec<_> = ["0", "1", "2", "3", " 2\n"].iter().map(|value| Capture::parse(value).unwrap()).collect();
        assert_eq!(modes, [Capture::Off, Capture::All, Capture::Transcript, Capture::Audio, Capture::Transcript]);
        assert_eq!(Capture::parse("yes"), None);
        assert!(Capture::All.transcript() && Capture::All.audio());
        assert!(Capture::Transcript.transcript() && !Capture::Transcript.audio());
        assert!(!Capture::Audio.transcript() && Capture::Audio.audio());
        assert!(!Capture::Off.transcript() && !Capture::Off.audio());
    }

    #[test]
    fn parses_max_sessions() {
        assert_eq!(parse_max_sessions(" 3\n"), Some(3));
        assert_eq!(parse_max_sessions("0"), None);
        assert_eq!(parse_max_sessions("many"), None);
    }
}
