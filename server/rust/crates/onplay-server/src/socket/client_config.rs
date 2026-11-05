//! The first client message: session options, validated like the former Python server.

use serde_json::{Map, Value};

use crate::languages::{is_source, is_target};
use crate::translation::Mode;

const INVALID: &str = "Cấu hình không hợp lệ.";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioEncoding {
    Base64,
    Binary,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Speakers {
    Off,
    /// Realtime labels and voices only.
    Live,
    /// Realtime labels plus diarization of the whole recording at the end.
    Full,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ClientConfig {
    /// Empty: recognition only.
    pub target: String,
    pub source: String,
    pub mode: Mode,
    pub speech: bool,
    pub audio_encoding: AudioEncoding,
    pub speakers: Speakers,
    pub store: bool,
    pub threads: i32,
    pub chunk_ms: usize,
}

impl ClientConfig {
    /// Error messages are sent to the client in an `error` event.
    pub fn parse(text: &str) -> Result<Self, &'static str> {
        let Ok(Value::Object(config)) = serde_json::from_str::<Value>(text) else { return Err(INVALID) };
        let string = |key: &str, default: &'static str| match config.get(key) {
            None => Some(default.to_owned()),
            Some(Value::String(value)) => Some(value.clone()),
            Some(_) => None,
        };
        let target = string("target_language", "").filter(|target| target.is_empty() || is_target(target)).ok_or("Ngôn ngữ dịch không hợp lệ.")?;
        let source = string("source_language", "auto").filter(|source| is_source(source));
        let mode = string("translation_mode", "standard").and_then(|mode| Mode::parse(&mode));
        let (Some(source), Some(mode)) = (source, mode) else { return Err("Cấu hình dịch không hợp lệ.") };
        let speech = flag(&config, "speech").filter(|speech| !speech || !target.is_empty());
        let speech = speech.ok_or("speech requires a target_language and a boolean value.")?;
        let audio_encoding = match config.get("audio_encoding").map(Value::as_str) {
            None | Some(Some("base64")) => AudioEncoding::Base64,
            Some(Some("binary")) => AudioEncoding::Binary,
            Some(_) => return Err("audio_encoding must be \"base64\" or \"binary\"."),
        };
        let speakers = match config.get("speakers") {
            None | Some(Value::Bool(false)) => Speakers::Off,
            Some(Value::Bool(true)) => Speakers::Full,
            Some(Value::String(value)) if value == "live" => Speakers::Live,
            Some(_) => return Err("Cấu hình người nói không hợp lệ."),
        };
        let store = flag(&config, "store").ok_or("store must be a boolean value.")?;
        let threads = integer(config.get("threads"), 2).filter(|threads| (1..=16).contains(threads)).ok_or(INVALID)?;
        let chunk_ms = integer(config.get("chunk"), 100).filter(|chunk| (10..=1000).contains(chunk)).ok_or(INVALID)?;
        Ok(Self { target, source, mode, speech, audio_encoding, speakers, store, threads: threads as i32, chunk_ms: chunk_ms as usize })
    }
}

/// A boolean option, `false` when absent.
fn flag(config: &Map<String, Value>, key: &str) -> Option<bool> {
    match config.get(key) {
        None => Some(false),
        Some(value) => value.as_bool(),
    }
}

/// Python `int(value)`: integers, truncated floats, booleans and integer strings.
fn integer(value: Option<&Value>, default: i64) -> Option<i64> {
    match value {
        None => Some(default),
        Some(Value::Number(number)) => number.as_i64().or_else(|| number.as_f64().filter(|n| n.is_finite()).map(|n| n.trunc() as i64)),
        Some(Value::Bool(value)) => Some(i64::from(*value)),
        Some(Value::String(text)) => text.trim().parse().ok(),
        Some(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn applies_defaults() {
        let config = ClientConfig::parse("{}").unwrap();
        assert_eq!(config.target, "");
        assert_eq!((config.source.as_str(), config.mode), ("auto", Mode::Standard));
        assert_eq!((config.speech, config.audio_encoding, config.speakers, config.store), (false, AudioEncoding::Base64, Speakers::Off, false));
        assert_eq!((config.threads, config.chunk_ms), (2, 100));
    }

    #[test]
    fn reads_options_like_python() {
        let config = ClientConfig::parse(
            r#"{"target_language":"vi","source_language":"en","translation_mode":"context","speech":true,
                "audio_encoding":"binary","speakers":"live","store":true,"threads":"4","chunk":160.9}"#,
        )
        .unwrap();
        assert_eq!((config.target.as_str(), config.source.as_str(), config.mode), ("vi", "en", Mode::Context));
        assert_eq!((config.speech, config.audio_encoding, config.speakers, config.store), (true, AudioEncoding::Binary, Speakers::Live, true));
        assert_eq!((config.threads, config.chunk_ms), (4, 160));
        assert_eq!(ClientConfig::parse(r#"{"speakers":true}"#).unwrap().speakers, Speakers::Full);
    }

    #[test]
    fn rejects_invalid_options() {
        for (config, message) in [
            ("[]", INVALID),
            (r#"{"target_language":"xx"}"#, "Ngôn ngữ dịch không hợp lệ."),
            (r#"{"translation_mode":"fast"}"#, "Cấu hình dịch không hợp lệ."),
            (r#"{"speech":true}"#, "speech requires a target_language and a boolean value."),
            (r#"{"audio_encoding":"raw"}"#, "audio_encoding must be \"base64\" or \"binary\"."),
            (r#"{"speakers":"yes"}"#, "Cấu hình người nói không hợp lệ."),
            (r#"{"store":1}"#, "store must be a boolean value."),
            (r#"{"threads":17}"#, INVALID),
            (r#"{"chunk":5}"#, INVALID),
        ] {
            assert_eq!(ClientConfig::parse(config), Err(message), "{config}");
        }
    }
}
