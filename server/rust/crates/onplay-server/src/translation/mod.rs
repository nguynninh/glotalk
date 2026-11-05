//! Bounded client for the local translation router; no model code in the server.

mod client;
mod retry;

pub use client::Translator;
pub use retry::wait_busy;

use serde_json::{Map, Value};

/// The router's JSON result (`text`, `source`, `target`, `model`, `latency_ms`, ...),
/// validated and forwarded to clients with extra timing fields.
pub type Translation = Map<String, Value>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Standard,
    /// Uses up to five previous sentences as context.
    Context,
}

impl Mode {
    pub fn parse(name: &str) -> Option<Self> {
        match name {
            "standard" => Some(Self::Standard),
            "context" => Some(Self::Context),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Standard => "standard",
            Self::Context => "context",
        }
    }
}

/// Messages are sent to clients in `translation_error` events.
#[derive(Debug, thiserror::Error)]
pub enum TranslationError {
    /// Rejected before sending (Python `ValueError`).
    #[error("{0}")]
    Invalid(&'static str),
    /// HTTP 409: the router is translating for someone else.
    #[error("Router dịch đang bận; thử lại sau.")]
    Busy,
    #[error("{0}")]
    Failed(&'static str),
}

impl TranslationError {
    pub fn is_busy(&self) -> bool {
        matches!(self, Self::Busy)
    }
}
