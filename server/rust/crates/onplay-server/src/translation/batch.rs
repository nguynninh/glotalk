//! Subtitle lines translated in order, for the editor's `POST /translate` ([`crate::downloads`]).
//!
//! Each line is sent in `context` mode with up to five previous source lines, the request's
//! `context` first, so a batch reads like one passage. A failed line does not stop the rest.

use std::sync::atomic::AtomicBool;

use serde_json::{json, Value};

use super::{wait_busy, Mode, Translation, TranslationError};
use crate::languages::{is_source, is_target};

/// Most lines in one request; the editor sends longer subtitles in several.
pub const MAX_LINES: usize = 50;
const MAX_CONTEXT: usize = 5;

/// `{"source","target","texts":[..],"context":[..]}` -> `{"translations":[{"text"} | {"error"}]}`,
/// one entry per text in order; a blank text stays blank without a router call.
pub fn translate_batch(
    request: &Value,
    translate: impl Fn(&str, &str, &str, Mode, &[String]) -> Result<Translation, TranslationError>,
) -> Result<Value, &'static str> {
    let field = |name: &str| request.get(name).and_then(Value::as_str).unwrap_or_default();
    let (source, target) = (field("source"), field("target"));
    if !is_target(target) {
        return Err("invalid_target");
    }
    if !is_source(source) {
        return Err("invalid_source");
    }
    let strings = |name: &str| -> Option<Vec<String>> {
        match request.get(name) {
            None | Some(Value::Null) => Some(Vec::new()),
            Some(Value::Array(items)) => items.iter().map(|item| item.as_str().map(str::to_owned)).collect(),
            Some(_) => None,
        }
    };
    let (Some(texts), Some(context)) = (strings("texts"), strings("context")) else {
        return Err("bad_request");
    };
    if texts.is_empty() || texts.len() > MAX_LINES {
        return Err("bad_request");
    }
    let mut history: Vec<String> = context.into_iter().map(|line| line.trim().to_owned()).filter(|line| !line.is_empty()).collect();
    let never_cancelled = AtomicBool::new(false);
    let translations = texts
        .iter()
        .map(|text| {
            let text = text.trim();
            if text.is_empty() {
                return json!({"text": ""});
            }
            let recent = &history[history.len().saturating_sub(MAX_CONTEXT)..];
            let result = wait_busy(&never_cancelled, TranslationError::is_busy, || translate(text, target, source, Mode::Context, recent));
            history.push(text.to_owned());
            match result {
                Ok(result) => json!({"text": result.get("text").cloned().unwrap_or_default()}),
                Err(error) => json!({"error": error.to_string()}),
            }
        })
        .collect::<Vec<_>>();
    Ok(json!({"translations": translations}))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn translates_in_order_with_running_context() {
        let calls = RefCell::new(Vec::new());
        let request = json!({"source": "en", "target": "vi", "texts": ["a", " ", "b", "fail"], "context": ["x", "y", "z", "w", "v", "u"]});
        let out = translate_batch(&request, |text, target, source, mode, context| {
            calls.borrow_mut().push((text.to_owned(), context.to_vec()));
            assert_eq!((target, source, mode), ("vi", "en", Mode::Context));
            if text == "fail" {
                return Err(TranslationError::Failed("down"));
            }
            Ok(json!({"text": text.to_uppercase()}).as_object().unwrap().clone())
        })
        .unwrap();
        assert_eq!(out, json!({"translations": [{"text": "A"}, {"text": ""}, {"text": "B"}, {"error": "down"}]}));
        let calls = calls.into_inner();
        assert_eq!(calls[0].1, ["y", "z", "w", "v", "u"]);
        assert_eq!(calls[1].1, ["z", "w", "v", "u", "a"]);
        assert_eq!(calls[2].1, ["w", "v", "u", "a", "b"]);
    }

    #[test]
    fn rejects_bad_requests() {
        let never = |_: &str, _: &str, _: &str, _: Mode, _: &[String]| -> Result<Translation, TranslationError> { unreachable!() };
        assert_eq!(translate_batch(&json!({"source": "en", "target": "xx", "texts": ["a"]}), never), Err("invalid_target"));
        assert_eq!(translate_batch(&json!({"source": "xx", "target": "vi", "texts": ["a"]}), never), Err("invalid_source"));
        assert_eq!(translate_batch(&json!({"source": "auto", "target": "vi", "texts": []}), never), Err("bad_request"));
        assert_eq!(translate_batch(&json!({"source": "auto", "target": "vi", "texts": [1]}), never), Err("bad_request"));
        let many = vec!["a"; MAX_LINES + 1];
        assert_eq!(translate_batch(&json!({"source": "auto", "target": "vi", "texts": many}), never), Err("bad_request"));
    }

    /// Against a running router: `ONPLAY_TRANSLATE_LIVE=http://127.0.0.1:5002 cargo test -p onplay-server --lib batch_live -- --ignored`.
    #[test]
    #[ignore]
    fn batch_live() {
        let url = std::env::var("ONPLAY_TRANSLATE_LIVE").unwrap_or_else(|_| "http://127.0.0.1:5002".into());
        let translator = super::super::Translator::new(&url);
        let request = json!({"source": "en", "target": "vi", "texts": ["Where are you going?", "", "I'm going home."], "context": ["It is late."]});
        let out = translate_batch(&request, |text, target, source, mode, context| translator.translate(text, target, source, mode, context)).unwrap();
        println!("{out}");
        let lines = out["translations"].as_array().unwrap();
        assert!(lines[0]["text"].as_str().is_some_and(|text| !text.is_empty()), "{out}");
        assert_eq!(lines[1], json!({"text": ""}));
        assert!(lines[2]["text"].as_str().is_some_and(|text| !text.is_empty()), "{out}");
    }
}
