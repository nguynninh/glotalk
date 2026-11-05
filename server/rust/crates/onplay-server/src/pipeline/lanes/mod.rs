//! Worker threads that translate and speak while recognition continues.
//!
//! - [`live`]: finals (and end-of-input speaker segments), fast lane, then speech jobs
//! - [`provisional`]: the newest partial, only while the fast lane is idle
//! - [`refinement`]: finals again with previous sentences as context (`context` mode)
//! - [`speech`]: TTS paced to the client's playback
//!
//! Lanes poll the cancellation flag and are not joined: an in-flight router request may
//! outlive the connection, and anything it would send is dropped.

mod live;
mod provisional;
mod refinement;
mod speech;

use std::sync::Arc;
use std::time::Duration;

use super::state::Shared;
use crate::translation::Mode;

/// How long a lane waits for work before re-checking cancellation.
const POLL: Duration = Duration::from_millis(200);
/// Keep this many previous sentences as translation context.
const CONTEXT: usize = 5;

pub fn start(shared: &Arc<Shared>) {
    let spawn = |lane: fn(&Shared)| {
        let shared = shared.clone();
        std::thread::spawn(move || lane(&shared));
    };
    if shared.speech {
        spawn(speech::run);
    }
    if shared.translating() {
        spawn(live::run);
        spawn(provisional::run);
        if shared.mode == Mode::Context {
            spawn(refinement::run);
        }
    }
}

/// The last [`CONTEXT`] sentences.
fn recent(history: &[String]) -> &[String] {
    &history[history.len().saturating_sub(CONTEXT)..]
}

fn elapsed_ms(since: std::time::Instant) -> f64 {
    since.elapsed().as_secs_f64() * 1000.0
}
