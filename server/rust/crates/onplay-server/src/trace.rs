//! A connection's handle for detail events: cheap to check, built only when observed.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{Map, Value};

use crate::events::{Direction, Event, FrameKind, Observer, Stage};

/// Messages whose top-level `text` is a server-written notice, not recognized speech.
const NOTICES: [&str; 5] = ["status", "warning", "error", "translation_error", "audio_error"];
/// Dub job strings that identify the user's video or carry an HLS path secret.
const PRIVATE: [&str; 4] = ["url", "title", "thumbnail_url", "path"];

#[derive(Clone)]
pub struct Trace {
    observer: Arc<dyn Observer>,
    connection: u64,
    opened: Instant,
    detailed: bool,
}

impl Trace {
    pub fn new(observer: Arc<dyn Observer>, connection: u64, opened: Instant) -> Self {
        let detailed = observer.detailed();
        Self { observer, connection, opened, detailed }
    }

    /// Reports a frame; `json` is its JSON text, parsed only when detail is observed.
    pub fn frame(&self, direction: Direction, kind: FrameKind, bytes: usize, json: Option<&str>) {
        if self.detailed {
            let fields = json.and_then(|json| serde_json::from_str(json).ok()).map(redact);
            self.observer.event(Event::Frame { connection: self.connection, at: self.opened.elapsed(), direction, kind, bytes, fields });
        }
    }

    pub fn stage(&self, stage: Stage, took: Duration) {
        if self.detailed {
            self.observer.event(Event::Stage { connection: self.connection, at: self.opened.elapsed(), stage, took });
        }
    }
}

/// Replaces every `text` with `text_chars` (except a notice's own text), drops base64
/// audio `data`, and replaces dub links, titles and paths with their length, so detail logs
/// carry no speech, transcript, translation or video identity.
pub fn redact(message: Value) -> Value {
    let notice = message.get("type").and_then(Value::as_str).is_some_and(|kind| NOTICES.contains(&kind));
    match message {
        Value::Object(fields) => Value::Object(redact_fields(fields, notice)),
        other => redact_nested(other),
    }
}

fn redact_fields(fields: Map<String, Value>, keep_text: bool) -> Map<String, Value> {
    let mut redacted = Map::new();
    for (key, value) in fields {
        match (key.as_str(), value) {
            ("text", Value::String(text)) if !keep_text => {
                redacted.insert("text_chars".into(), text.chars().count().into());
            }
            ("data", Value::String(data)) => {
                redacted.insert("data_chars".into(), data.len().into());
            }
            (private, Value::String(value)) if PRIVATE.contains(&private) => {
                redacted.insert(format!("{private}_chars"), value.chars().count().into());
            }
            (_, value) => {
                redacted.insert(key, if keep_text { value } else { redact_nested(value) });
            }
        }
    }
    redacted
}

fn redact_nested(value: Value) -> Value {
    match value {
        Value::Object(fields) => Value::Object(redact_fields(fields, false)),
        Value::Array(items) => Value::Array(items.into_iter().map(redact_nested).collect()),
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn hides_speech_and_audio_but_keeps_notices() {
        let translation = json!({"type": "translation", "id": 2, "source": {"text": "xin chào", "language": "vi"}, "translation": {"text": "hello", "total_ms": 41.5}});
        assert_eq!(redact(translation), json!({"type": "translation", "id": 2, "source": {"text_chars": 8, "language": "vi"}, "translation": {"text_chars": 5, "total_ms": 41.5}}));
        let speakers = json!({"type": "speakers", "segments": [{"speaker": 1, "text": "abc"}]});
        assert_eq!(redact(speakers), json!({"type": "speakers", "segments": [{"speaker": 1, "text_chars": 3}]}));
        assert_eq!(redact(json!({"type": "audio", "text": "hi", "data": "UklGRg=="})), json!({"type": "audio", "text_chars": 2, "data_chars": 8}));
        assert_eq!(redact(json!({"type": "error", "text": "Máy chủ đầy."})), json!({"type": "error", "text": "Máy chủ đầy."}));
        let job = json!({"type": "job", "title": "Talk", "playback": {"path": "/jobs/j/hls/k/master.m3u8"}});
        assert_eq!(redact(job), json!({"type": "job", "title_chars": 4, "playback": {"path_chars": 25}}));
        assert_eq!(redact(json!({"type": "dub", "url": "https://x.y"})), json!({"type": "dub", "url_chars": 11}));
    }
}
