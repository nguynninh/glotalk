//! Protocol progress from the `onplay-dub --progress` event lines.

use serde_json::{json, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    Downloading,
    Recognizing,
    Speaking,
    Muxing,
}

impl Stage {
    fn name(self) -> &'static str {
        match self {
            Self::Downloading => "downloading",
            Self::Recognizing => "recognizing",
            Self::Speaking => "speaking",
            Self::Muxing => "muxing",
        }
    }
}

/// Recognition, translation and speech overlap; the stage is the earliest unfinished one.
#[derive(Debug, Clone, Default)]
pub struct Progress {
    stage: Option<Stage>,
    duration: f64,
    recognized: f64,
    /// Segments queued for speech, known once recognition is complete.
    speech: Option<usize>,
    spoken: usize,
}

impl Progress {
    /// Applies one event line; returns whether the stage changed.
    pub fn apply(&mut self, event: &Value) -> bool {
        let number = |key: &str| event.get(key).and_then(Value::as_f64).unwrap_or(0.0);
        let before = self.stage;
        match event.get("event").and_then(Value::as_str) {
            Some("downloading") => self.stage = Some(Stage::Downloading),
            Some("recognizing") => {
                self.stage = Some(Stage::Recognizing);
                self.duration = number("duration");
            }
            Some("recognized") => self.recognized = self.recognized.max(number("end")),
            Some("transcribed") => {
                let speech = number("speech") as usize;
                self.speech = Some(speech);
                if speech > 0 {
                    self.stage = Some(Stage::Speaking);
                }
            }
            Some("spoken") => self.spoken += 1,
            Some("muxing") => self.stage = Some(Stage::Muxing),
            _ => {}
        }
        self.stage != before
    }

    /// The `progress` message fields, without `job_id` and `version`; `None` before the
    /// first event.
    pub fn message(&self) -> Option<Value> {
        let stage = self.stage?;
        let mut message = json!({"type": "progress", "stage": stage.name()});
        let counts = match stage {
            Stage::Recognizing => Some((self.recognized.min(self.duration).round() as u64, self.duration.round() as u64)),
            Stage::Speaking => self.speech.map(|total| (self.spoken.min(total) as u64, total as u64)),
            Stage::Downloading | Stage::Muxing => None,
        };
        if let Some((done, total)) = counts {
            message["done"] = done.into();
            message["total"] = total.into();
        }
        Some(message)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn apply(progress: &mut Progress, line: Value) -> Option<Value> {
        progress.apply(&line);
        progress.message()
    }

    #[test]
    fn follows_overlapping_stages() {
        let mut progress = Progress::default();
        assert_eq!(progress.message(), None);
        assert_eq!(apply(&mut progress, json!({"event": "downloading"})), Some(json!({"type": "progress", "stage": "downloading"})));
        apply(&mut progress, json!({"event": "recognizing", "offset": 0.0, "duration": 746.4}));
        apply(&mut progress, json!({"event": "recognized", "index": 0, "end": 20.2}));
        apply(&mut progress, json!({"event": "spoken", "index": 0}));
        let message = apply(&mut progress, json!({"event": "recognized", "index": 1, "end": 11.9}));
        assert_eq!(message, Some(json!({"type": "progress", "stage": "recognizing", "done": 20, "total": 746})));
        let message = apply(&mut progress, json!({"event": "transcribed", "segments": 8, "speech": 6}));
        assert_eq!(message, Some(json!({"type": "progress", "stage": "speaking", "done": 1, "total": 6})));
        assert_eq!(apply(&mut progress, json!({"event": "muxing"})), Some(json!({"type": "progress", "stage": "muxing"})));
    }

    #[test]
    fn skips_speaking_without_speech() {
        let mut progress = Progress::default();
        apply(&mut progress, json!({"event": "recognizing", "duration": 30.0}));
        assert!(!progress.apply(&json!({"event": "transcribed", "segments": 4, "speech": 0})));
        assert_eq!(progress.message().unwrap()["stage"], "recognizing");
    }
}
