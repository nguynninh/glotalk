//! A recognized sentence and its JSON fields.

use serde_json::{json, Map, Value};

#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    /// Live finals count from 0; a partial carries the id its final will receive.
    /// End-of-input speaker segments are numbered separately, also from 0.
    pub id: u64,
    pub text: String,
    /// Live labels may be 0 (not yet identified); diarized labels start at 1.
    pub speaker: Option<u32>,
    /// Start and end in seconds, for diarized segments only.
    pub span: Option<(f64, f64)>,
}

impl Segment {
    /// `{"type", "id", "text", "speaker"?}` for `partial` and `final` events.
    pub fn event(&self, kind: &str) -> Value {
        let mut event = json!({"type": kind, "id": self.id, "text": self.text});
        if let Some(speaker) = self.speaker {
            event["speaker"] = speaker.into();
        }
        event
    }

    /// Adds `speaker` and, when `with_span`, `start` and `end` when known.
    pub fn add_labels(&self, map: &mut Map<String, Value>, with_span: bool) {
        if let Some(speaker) = self.speaker {
            map.insert("speaker".into(), speaker.into());
        }
        if let (true, Some((start, end))) = (with_span, self.span) {
            map.insert("start".into(), start.into());
            map.insert("end".into(), end.into());
        }
    }

    pub fn has_text(&self) -> bool {
        !self.text.trim().is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_optional_fields_only_when_known() {
        let plain = Segment { id: 3, text: "xin chào".into(), speaker: None, span: None };
        assert_eq!(plain.event("final"), json!({"type": "final", "id": 3, "text": "xin chào"}));
        let labelled = Segment { speaker: Some(2), span: Some((0.5, 1.25)), ..plain };
        let mut map = Map::new();
        labelled.add_labels(&mut map, true);
        assert_eq!(Value::Object(map), json!({"speaker": 2, "start": 0.5, "end": 1.25}));
    }
}
