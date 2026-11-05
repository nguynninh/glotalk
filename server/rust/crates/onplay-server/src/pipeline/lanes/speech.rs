//! Speech that keeps up with the source.
//!
//! Clients play segment WAVs back to back, so the lane tracks when the client will be
//! free, speeds speech up while it is behind and skips segments that would start too long
//! after they were spoken.

use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use base64::Engine as _;
use serde_json::json;

use super::{elapsed_ms, POLL};
use crate::numbers::round2;
use crate::pipeline::state::{Shared, SpeechJob};
use crate::socket::AudioEncoding;

const MAX_LAG_S: f64 = 4.0;
const SPEED: f64 = 1.05;
const MAX_SPEED: f64 = 1.5;
const SPEED_PER_S: f64 = 0.15;

pub fn run(shared: &Shared) {
    // Estimated moment the client finishes playing every WAV already sent.
    let mut playback_free = Instant::now();
    while !shared.cancelled() {
        let Some(job) = shared.speech_jobs.get(POLL) else { continue };
        if !shared.cancelled() && !shared.speech_failed.load(Ordering::Relaxed) {
            speak(shared, &mut playback_free, &job);
        }
        shared.speech_jobs.task_done();
    }
}

fn speak(shared: &Shared, playback_free: &mut Instant, job: &SpeechJob) {
    let now = Instant::now();
    let backlog = playback_free.saturating_duration_since(now).as_secs_f64();
    if (now - job.spoken_at).as_secs_f64() + backlog > MAX_LAG_S {
        shared.skip_speech(job.id, "late");
        return;
    }
    let speed = round2(MAX_SPEED.min(SPEED + SPEED_PER_S * backlog));
    let started = Instant::now();
    let spoken = match shared.speech_engine.synthesize(&job.text, &shared.target, job.speaker, speed) {
        Ok(spoken) => spoken,
        Err(error) => {
            shared.speech_failed.store(true, Ordering::Relaxed);
            if !shared.cancelled() {
                shared.emit(&json!({"type": "audio_error", "text": error.to_string()}));
            }
            return;
        }
    };
    let tts_ms = round2(elapsed_ms(started));
    if shared.cancelled() {
        return;
    }
    *playback_free = (*playback_free).max(Instant::now()) + Duration::from_secs_f64(spoken.seconds);
    // text is the exact sentence spoken, for subtitles shown during playback.
    let mut event = json!({
        "type": "audio", "id": job.id, "revision": "live", "text": job.text, "language": shared.target,
        "content_type": "audio/wav", "tts_ms": tts_ms, "speed": speed,
    });
    match shared.audio_encoding {
        AudioEncoding::Binary => {
            event["encoding"] = "binary".into();
            let _ = shared.outbox.emit_with_payload(&event, &spoken.wav);
        }
        AudioEncoding::Base64 => {
            event["encoding"] = "base64".into();
            event["data"] = base64::engine::general_purpose::STANDARD.encode(&spoken.wav).into();
            shared.emit(&event);
        }
    }
}
