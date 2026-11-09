//! Connection events for whoever wants them. The server never logs or formats on its own,
//! and events carry no audio or recognized text.
//!
//! [`Event::Frame`] and [`Event::Stage`] are detail events, built only for an observer whose
//! [`Observer::detailed`] is true.

use std::time::Duration;

use serde_json::Value;

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
    /// Options were accepted and recognition is starting on engine slot `slot` (from 0);
    /// `active` sessions of `capacity` now hold a slot, this one included. `session` is the
    /// stored transcript id when the session is recorded.
    Started {
        connection: u64,
        source: String,
        target: String,
        speech: bool,
        speakers: Speakers,
        session: Option<String>,
        slot: usize,
        active: usize,
        capacity: usize,
    },
    /// `audio` is the PCM received; `segments` the finals recognized.
    Ended { connection: u64, outcome: Outcome, duration: Duration, audio: Duration, segments: u64 },
    /// Detail: one WebSocket frame, `at` after the connection opened. `fields` is the JSON
    /// message (or a binary frame's JSON header) with recognized and translated text
    /// replaced by its length and audio data removed; `None` for PCM and control frames.
    Frame { connection: u64, at: Duration, direction: Direction, kind: FrameKind, bytes: usize, fields: Option<Value> },
    /// Detail: a pipeline step of the session finished, `at` after the connection opened.
    Stage { connection: u64, at: Duration, stage: Stage, took: Duration },
    /// A dub job changed; `owner` is the tester name from the token file. Links, titles and
    /// transcripts are never included.
    Job { job: String, owner: String, change: JobChange },
}

#[derive(Debug, Clone, PartialEq)]
pub enum JobChange {
    /// `duration` is the video's length in seconds.
    Queued { duration: f64, target: String },
    Started,
    /// `state` is `done`, `failed` or `cancelled`; `code` the failure code.
    Ended { state: &'static str, code: Option<&'static str>, took: Duration },
    /// Deleted by the app, or `expired` after the retention period.
    Removed { expired: bool },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    /// From the client.
    In,
    /// To the client.
    Out,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameKind {
    Text,
    Binary,
    Ping,
    Pong,
    Close,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    /// The streaming engine was found loaded, or loaded, and a recognition session opened.
    Engine,
    /// The recognition job waited for the worker, which was busy (a preload).
    Queued,
    /// Remaining PCM decoded and the recognizer flushed after `end`.
    Finish,
    /// End-of-input speaker diarization.
    Diarization,
    /// Waiting for translation and speech lanes before `done`.
    Drain,
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

/// Receives events from every connection and recognition thread.
pub trait Observer: Send + Sync {
    fn event(&self, event: Event);

    /// Whether to build and send detail events ([`Event::Frame`], [`Event::Stage`]).
    fn detailed(&self) -> bool {
        false
    }
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
