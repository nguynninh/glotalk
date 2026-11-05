//! Connection events for whoever wants them. The server never logs or formats on its own,
//! and events carry no audio or recognized text.

use std::time::Duration;

use crate::socket::Speakers;

/// What the handshake says about the client. Headers are as sent, unchecked.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Client {
    /// `CF-Connecting-IP` from the Cloudflare tunnel, else the first `X-Forwarded-For`
    /// address, else the TCP peer (`127.0.0.1` behind a local proxy).
    pub address: String,
    pub origin: Option<String>,
    pub user_agent: Option<String>,
}

/// Connection numbers count from 1 in accept order.
#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// The handshake was refused: no `Origin` header, or one not allowed.
    Refused { client: Client },
    /// The handshake succeeded; an [`Event::Ended`] with the same number follows.
    Opened { connection: u64, client: Client },
    /// Options were accepted and recognition is starting. `session` is the stored
    /// transcript id when the session is recorded.
    Started { connection: u64, source: String, target: String, speech: bool, speakers: Speakers, session: Option<String> },
    /// `audio` is the PCM received; `segments` the finals recognized.
    Ended { connection: u64, outcome: Outcome, duration: Duration, audio: Duration, segments: u64 },
}

#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    /// `done` was sent.
    Done,
    /// The client went away (or stopped answering pings) before `done`.
    Closed,
    /// The `error` event sent to the client, such as a refused configuration or a busy engine.
    Error(String),
}

/// Receives events from every connection thread.
pub trait Observer: Send + Sync {
    fn event(&self, event: Event);
}

/// Ignores every event.
pub struct Silent;

impl Observer for Silent {
    fn event(&self, _: Event) {}
}

impl<F: Fn(Event) + Send + Sync> Observer for F {
    fn event(&self, event: Event) {
        self(event);
    }
}
