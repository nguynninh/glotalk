//! `POST /translate` body validation, with the Python router's checks, order and messages.

use serde_json::Value;

use crate::error::{Error, Result};
use crate::language::{self, Language};
use crate::text::normalize;

const MAX_CONTEXT: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum Mode {
    /// Live translation on the route's models.
    Standard,
    /// Translation with previous segments as context, on the context model.
    Context,
}

impl Mode {
    pub fn lane(self) -> usize {
        self as usize
    }
}

pub(crate) struct Request {
    pub text: String,
    /// `None` for `auto`.
    pub source: Option<&'static Language>,
    pub target: &'static Language,
    pub mode: Mode,
    /// The last `max_segments` normalized context segments.
    pub context: Vec<String>,
}

impl Request {
    pub fn parse(data: &Value, max_segments: usize) -> Result<Self> {
        let data = data.as_object().ok_or_else(|| Error::invalid("Expected a JSON object"))?;
        let text = normalize_value(data.get("text"))?;
        let source = match data.get("source") {
            None => Some(None),
            Some(Value::String(code)) if code == "auto" => Some(None),
            Some(Value::String(code)) => language::find(code).map(Some),
            Some(_) => None,
        };
        let target = data.get("target").and_then(Value::as_str).and_then(language::find);
        let (Some(source), Some(target)) = (source, target) else {
            return Err(Error::invalid("Unsupported source or target language"));
        };
        let mode = match data.get("mode").map(Value::as_str) {
            None | Some(Some("standard")) => Mode::Standard,
            Some(Some("context")) => Mode::Context,
            Some(_) => return Err(Error::invalid("Mode must be standard or context")),
        };
        let context = match data.get("context") {
            None => Vec::new(),
            Some(Value::Array(items)) if items.len() <= MAX_CONTEXT => items.iter().map(|item| normalize_value(Some(item))).collect::<Result<_>>()?,
            Some(_) => return Err(Error::invalid("Context must contain at most five previous text segments")),
        };
        let context = context[context.len().saturating_sub(max_segments)..].to_vec();
        Ok(Self { text, source, target, mode, context })
    }
}

fn normalize_value(value: Option<&Value>) -> Result<String> {
    match value {
        Some(Value::String(text)) => normalize(text),
        _ => Err(Error::invalid(crate::text::TEXT_ERROR)),
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn error(data: Value) -> String {
        Request::parse(&data, 5).err().unwrap().to_string()
    }

    #[test]
    fn validates_like_python() {
        let request = Request::parse(&json!({"text": " a ", "target": "vi", "context": ["1", "2", "3"]}), 2).unwrap();
        assert_eq!((request.text.as_str(), request.source.is_none(), request.target.code), ("a", true, "vi"));
        assert_eq!((request.mode, request.context), (Mode::Standard, vec!["2".to_owned(), "3".to_owned()]));
        assert_eq!(error(json!([])), "Expected a JSON object");
        assert_eq!(error(json!({"text": 1, "target": "vi"})), "Text must be a string of at most 10000 characters");
        assert_eq!(error(json!({"text": "a", "target": "xx"})), "Unsupported source or target language");
        assert_eq!(error(json!({"text": "a", "source": null, "target": "vi"})), "Unsupported source or target language");
        assert_eq!(error(json!({"text": "a", "target": "vi", "mode": "fast"})), "Mode must be standard or context");
        assert_eq!(error(json!({"text": "a", "target": "vi", "context": vec!["1"; 6]})), "Context must contain at most five previous text segments");
    }
}
