//! Client for the local translation router (`./scripts/dev/translation.sh`, port 5002).
//!
//! Mirrors `ui/translation.py`: POST /translate with standard mode and no context, bounded
//! response, retry while the router reports busy (409).

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

pub const DEFAULT_ENDPOINT: &str = "http://127.0.0.1:5002";
/// Languages the router accepts (`TARGETS` in `ui/translation.py`).
pub const TARGETS: [&str; 12] = ["vi", "en", "zh", "ja", "ko", "th", "id", "ru", "ar", "fr", "de", "es"];
const MAX_TEXT_CHARS: usize = 10_000;
const MAX_RESPONSE_BYTES: u64 = 1024 * 1024;
const BUSY_RETRIES: usize = 60;
const BUSY_WAIT: Duration = Duration::from_millis(500);

pub struct Translator {
    endpoint: String,
    agent: ureq::Agent,
}

/// A translated text and the source language the router detected.
#[derive(Debug, Clone, PartialEq)]
pub struct Translation {
    pub text: String,
    pub source: String,
}

#[derive(Serialize)]
struct Request<'a> {
    text: &'a str,
    source: &'static str,
    target: &'a str,
    mode: &'static str,
    context: [&'a str; 0],
}

#[derive(Deserialize)]
struct Response {
    text: String,
    source: String,
    target: String,
}

impl Translator {
    pub fn new(endpoint: impl Into<String>) -> Self {
        let agent = ureq::AgentBuilder::new().timeout(Duration::from_secs(60)).build();
        Self { endpoint: endpoint.into().trim_end_matches('/').to_owned(), agent }
    }

    /// Fails unless the router answers its health check.
    pub fn check(&self) -> Result<()> {
        self.agent
            .get(&format!("{}/health", self.endpoint))
            .timeout(Duration::from_secs(3))
            .call()
            .map(drop)
            .map_err(|_| Error::Setup("translation router is not running: start ./scripts/dev/translation.sh".into()))
    }

    pub fn translate(&self, text: &str, target: &str) -> Result<Translation> {
        if text.trim().is_empty() || text.chars().count() > MAX_TEXT_CHARS || !TARGETS.contains(&target) {
            return Err(Error::InvalidArgument("translation needs 1-10000 characters and a supported target".into()));
        }
        let body = serde_json::to_string(&Request { text, source: "auto", target, mode: "standard", context: [] })
            .map_err(|error| Error::Translation(error.to_string()))?;
        for _ in 0..BUSY_RETRIES {
            match self.agent.post(&format!("{}/translate", self.endpoint)).set("Content-Type", "application/json").send_string(&body) {
                Ok(response) => return parse(response, target),
                Err(ureq::Error::Status(409, _)) => std::thread::sleep(BUSY_WAIT),
                Err(error) => return Err(Error::Translation(error.to_string())),
            }
        }
        Err(Error::Translation("router stayed busy".into()))
    }
}

fn parse(response: ureq::Response, target: &str) -> Result<Translation> {
    use std::io::Read;
    let mut raw = Vec::new();
    response
        .into_reader()
        .take(MAX_RESPONSE_BYTES + 1)
        .read_to_end(&mut raw)
        .map_err(|error| Error::Translation(error.to_string()))?;
    if raw.len() as u64 > MAX_RESPONSE_BYTES {
        return Err(Error::Translation("response too large".into()));
    }
    let response: Response = serde_json::from_slice(&raw).map_err(|error| Error::Translation(error.to_string()))?;
    if response.text.trim().is_empty() || response.target != target || !TARGETS.contains(&response.source.as_str()) {
        return Err(Error::Translation("invalid router response".into()));
    }
    Ok(Translation { text: response.text, source: response.source })
}

/// The STT model emits Latin-script languages in capitals, which the translation models
/// garble; Python `str.capitalize()` when the text is `str.isupper()`.
pub fn sentence_case(text: &str) -> String {
    let has_upper = text.chars().any(char::is_uppercase);
    if !has_upper || text.chars().any(char::is_lowercase) {
        return text.to_owned();
    }
    let mut chars = text.chars();
    let first = chars.next().map(|c| c.to_uppercase().collect::<String>()).unwrap_or_default();
    first + &chars.as_str().to_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sentence_cases_only_all_capital_text() {
        assert_eq!(sentence_case("WHAT TOOLS DID YOU USE"), "What tools did you use");
        assert_eq!(sentence_case("ĐỪNG HỎI"), "Đừng hỏi");
        assert_eq!(sentence_case("đừng hỏi đất nước"), "đừng hỏi đất nước");
        assert_eq!(sentence_case("Mixed Case"), "Mixed Case");
        assert_eq!(sentence_case("123 !"), "123 !");
    }

    #[test]
    fn rejects_bad_requests_before_sending() {
        let translator = Translator::new("http://127.0.0.1:9");
        assert!(matches!(translator.translate(" ", "vi"), Err(Error::InvalidArgument(_))));
        assert!(matches!(translator.translate("hi", "xx"), Err(Error::InvalidArgument(_))));
    }
}
