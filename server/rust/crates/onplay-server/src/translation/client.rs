//! `POST {ONPLAY_TRANSLATE_URL}/translate` with bounded request and response checks.

use std::io::Read;
use std::time::Duration;

use serde_json::{json, Value};

use super::{Mode, Translation, TranslationError};
use crate::languages::{is_source, is_target};

const MAX_TEXT_CHARS: usize = 10_000;
const MAX_CONTEXT: usize = 5;
const MAX_REQUEST_BYTES: usize = 131_072;
const MAX_RESPONSE_BYTES: u64 = 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(60);
const UNAVAILABLE: &str = "Không dịch được. Kiểm tra router local: ./scripts/dev/translation.sh và model đã tải.";

pub struct Translator {
    endpoint: String,
    agent: ureq::Agent,
}

impl Translator {
    pub fn new(endpoint: &str) -> Self {
        Self { endpoint: endpoint.trim_end_matches('/').to_owned(), agent: ureq::AgentBuilder::new().timeout(TIMEOUT).build() }
    }

    pub fn translate(&self, text: &str, target: &str, source: &str, mode: Mode, context: &[String]) -> Result<Translation, TranslationError> {
        if !usable(text) {
            return Err(TranslationError::Invalid("Invalid text"));
        }
        if !is_target(target) {
            return Err(TranslationError::Invalid("Invalid target"));
        }
        if !is_source(source) {
            return Err(TranslationError::Invalid("Invalid source or translation mode"));
        }
        if context.len() > MAX_CONTEXT || !context.iter().all(|sentence| usable(sentence)) {
            return Err(TranslationError::Invalid("Invalid context"));
        }
        if !has_http_host(&self.endpoint) {
            return Err(TranslationError::Failed("ONPLAY_TRANSLATE_URL phải trỏ tới translation router HTTP/HTTPS."));
        }
        let body = json!({"text": text, "source": source, "target": target, "mode": mode.as_str(), "context": context}).to_string();
        if body.len() > MAX_REQUEST_BYTES {
            return Err(TranslationError::Invalid("Translation request too large"));
        }
        let response = match self.agent.post(&format!("{}/translate", self.endpoint)).set("Content-Type", "application/json").send_string(&body) {
            Ok(response) => response,
            Err(ureq::Error::Status(409, _)) => return Err(TranslationError::Busy),
            Err(_) => return Err(TranslationError::Failed(UNAVAILABLE)),
        };
        let mut raw = Vec::new();
        response.into_reader().take(MAX_RESPONSE_BYTES + 1).read_to_end(&mut raw).map_err(|_| TranslationError::Failed(UNAVAILABLE))?;
        if raw.len() as u64 > MAX_RESPONSE_BYTES {
            return Err(TranslationError::Failed(UNAVAILABLE));
        }
        match serde_json::from_slice(&raw) {
            Ok(Value::Object(result)) if valid_result(&result, target) => Ok(result),
            _ => Err(TranslationError::Failed(UNAVAILABLE)),
        }
    }
}

/// Non-blank text of at most 10,000 characters.
fn usable(text: &str) -> bool {
    !text.trim().is_empty() && text.chars().count() <= MAX_TEXT_CHARS
}

fn has_http_host(endpoint: &str) -> bool {
    let rest = endpoint.strip_prefix("http://").or_else(|| endpoint.strip_prefix("https://"));
    rest.and_then(|rest| rest.split(['/', ':', '?', '#']).next()).is_some_and(|host| !host.is_empty())
}

fn valid_result(result: &Translation, target: &str) -> bool {
    let text = |key: &str| result.get(key).and_then(Value::as_str);
    text("text").is_some_and(|text| !text.trim().is_empty())
        && text("source").is_some_and(is_target)
        && text("target") == Some(target)
        && text("model").is_some_and(|model| !model.is_empty())
        && result.get("latency_ms").and_then(Value::as_f64).is_some_and(|ms| ms.is_finite() && ms >= 0.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_bad_requests_before_sending() {
        let translator = Translator::new("http://127.0.0.1:9");
        let invalid = |result: Result<Translation, TranslationError>| matches!(result, Err(TranslationError::Invalid(_)));
        assert!(invalid(translator.translate(" ", "vi", "auto", Mode::Standard, &[])));
        assert!(invalid(translator.translate("hi", "xx", "auto", Mode::Standard, &[])));
        assert!(invalid(translator.translate("hi", "vi", "xx", Mode::Standard, &[])));
        assert!(invalid(translator.translate("hi", "vi", "auto", Mode::Context, &vec!["a".to_owned(); 6])));
    }

    #[test]
    fn checks_endpoint_and_result() {
        assert!(has_http_host("http://127.0.0.1:5002") && has_http_host("https://example.com"));
        assert!(!has_http_host("ftp://host") && !has_http_host("http://"));
        let result = |value: Value| value.as_object().unwrap().clone();
        let good = result(json!({"text": "Xin chào", "source": "en", "target": "vi", "model": "m", "latency_ms": 3.5}));
        assert!(valid_result(&good, "vi") && !valid_result(&good, "en"));
        assert!(!valid_result(&result(json!({"text": " ", "source": "en", "target": "vi", "model": "m", "latency_ms": 1})), "vi"));
    }
}
