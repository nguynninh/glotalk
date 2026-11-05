//! The router's HTTP contract as the streaming server uses it: `GET /health` and
//! `POST /translate` in standard mode without context.

use std::io::Read;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::corpus::Item;

const TIMEOUT: Duration = Duration::from_secs(120);
const MAX_RESPONSE_BYTES: u64 = 1024 * 1024;

pub struct Client {
    endpoint: String,
    agent: ureq::Agent,
}

/// What the router answered for one item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "lowercase")]
pub enum Outcome {
    Ok { text: String, detected: String, model: String, fallback: bool, cached: bool },
    Failed { code: Option<u16>, error: String },
}

#[derive(Serialize)]
struct Request<'a> {
    text: &'a str,
    source: &'a str,
    target: &'a str,
    mode: &'static str,
}

#[derive(Deserialize)]
struct Response {
    text: String,
    source: String,
    model: String,
    #[serde(default)]
    fallback: bool,
    #[serde(default)]
    cached: bool,
}

impl Client {
    pub fn new(endpoint: &str) -> Self {
        let agent = ureq::AgentBuilder::new().timeout(TIMEOUT).build();
        Self { endpoint: endpoint.trim_end_matches('/').to_owned(), agent }
    }

    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }

    pub fn healthy(&self) -> bool {
        self.agent
            .get(&format!("{}/health", self.endpoint))
            .timeout(Duration::from_secs(2))
            .call()
            .is_ok()
    }

    /// Sends `item` once and returns the wall time from request to parsed reply.
    pub fn translate(&self, item: &Item) -> (Duration, Outcome) {
        let started = Instant::now();
        let outcome = self.send(item);
        (started.elapsed(), outcome)
    }

    fn send(&self, item: &Item) -> Outcome {
        let request = Request { text: &item.text, source: &item.source, target: &item.target, mode: "standard" };
        let body = match serde_json::to_string(&request) {
            Ok(body) => body,
            Err(error) => return failed(None, error.to_string()),
        };
        let response = match self
            .agent
            .post(&format!("{}/translate", self.endpoint))
            .set("Content-Type", "application/json")
            .send_string(&body)
        {
            Ok(response) => response,
            Err(ureq::Error::Status(code, response)) => {
                let detail = read(response).unwrap_or_else(|error| error);
                return failed(Some(code), String::from_utf8_lossy(detail.as_bytes()).into_owned());
            }
            Err(error) => return failed(None, error.to_string()),
        };
        let raw = match read(response) {
            Ok(raw) => raw,
            Err(error) => return failed(None, error),
        };
        match serde_json::from_str::<Response>(&raw) {
            Ok(r) => Outcome::Ok { text: r.text, detected: r.source, model: r.model, fallback: r.fallback, cached: r.cached },
            Err(error) => failed(None, format!("invalid response: {error}")),
        }
    }
}

fn read(response: ureq::Response) -> Result<String, String> {
    let mut raw = String::new();
    response
        .into_reader()
        .take(MAX_RESPONSE_BYTES + 1)
        .read_to_string(&mut raw)
        .map_err(|error| error.to_string())?;
    if raw.len() as u64 > MAX_RESPONSE_BYTES {
        return Err("response too large".into());
    }
    Ok(raw)
}

fn failed(code: Option<u16>, error: String) -> Outcome {
    Outcome::Failed { code, error }
}
