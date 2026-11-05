//! One streaming session after its options are accepted.
//!
//! The connection thread feeds [`Input`] to the [`RecognitionWorker`], which owns the
//! loaded native models (Sherpa objects stay on the thread that made them) and runs
//! [`recognition`], optional [`diarization`], then waits for the [`lanes`] and sends
//! `done`.

mod diarization;
mod lanes;
mod queue;
mod recognition;
mod segment;
mod state;
mod worker;

pub use lanes::start as start_lanes;
pub use segment::Segment;
pub use state::Shared;
pub use worker::{Models, RecognitionWorker};

use std::sync::atomic::Ordering;
use std::sync::mpsc::Receiver;
use std::time::Duration;

use onplay_stt::diarize::SpeakerModels;
use onplay_stt::EngineConfig;
use serde_json::{json, Map, Value};

use crate::numbers::significant6;
use crate::sessions::{ContextJob, ContextPasses};
use crate::translation::Mode;

/// How often the finished session checks whether the lanes are idle, delaying `done`.
const DRAIN_POLL: Duration = Duration::from_millis(10);

pub enum Input {
    /// PCM16 little-endian mono 16 kHz.
    Pcm(Vec<u8>),
    /// No more audio; the recording is kept when end-of-input diarization is on.
    End { recording: Option<Vec<u8>> },
}

pub struct Setup {
    pub engine: EngineConfig,
    pub chunk_ms: usize,
    /// End-of-input diarization models.
    pub diarize: Option<SpeakerModels>,
}

/// A session's recognition, run on the worker. Always ends by closing the connection.
pub fn run(models: &mut Models, shared: &Shared, setup: &Setup, input: &Receiver<Input>, context_passes: &ContextPasses) {
    match recognition::run(models, shared, &setup.engine, setup.chunk_ms, input) {
        Ok(Some(finished)) => complete(models, shared, setup, finished, context_passes),
        Ok(None) => {}
        Err(message) => {
            if !shared.cancelled() {
                shared.emit(&json!({"type": "error", "text": format!("Engine Rust thất bại: Error: {message}")}));
            }
        }
    }
    shared.outbox.close();
}

fn complete(models: &mut Models, shared: &Shared, setup: &Setup, finished: recognition::Finished, context_passes: &ContextPasses) {
    let metrics = finished.metrics;
    let mut speaker_s = None;
    if let (Some(speaker_models), Some(recording)) = (&setup.diarize, &finished.recording) {
        if !recording.is_empty() && !shared.cancelled() {
            speaker_s = Some(diarization::run(models, shared, speaker_models, setup.engine.threads, recording));
        }
    }
    while shared.busy() && !shared.cancelled() {
        std::thread::sleep(DRAIN_POLL);
    }
    if shared.cancelled() {
        return;
    }
    if let Some(recorder) = &shared.recorder {
        recorder.status("live_done");
        let finals = std::mem::take(&mut *shared.finals.lock().unwrap_or_else(|poisoned| poisoned.into_inner()));
        if shared.translating() && !finals.is_empty() {
            context_passes.schedule(ContextJob { recorder: recorder.clone(), finals, target: shared.target.clone(), source: shared.source.clone() });
        } else if !recorder.failed() {
            recorder.status("done");
        }
        if recorder.failed() {
            shared.warn("Lưu phiên thất bại; bản lưu có thể thiếu dữ liệu.");
        }
    }
    let mut summary = summary(&metrics, speaker_s);
    if shared.speech {
        summary.insert("speech_skipped".into(), shared.speech_skipped.load(Ordering::Relaxed).into());
    }
    if shared.translating() && shared.mode == Mode::Context {
        summary.insert("context_skipped".into(), shared.context_skipped.load(Ordering::Relaxed).into());
        summary.insert("context_failed".into(), shared.context_failed.load(Ordering::Relaxed).into());
    }
    shared.completed.store(true, Ordering::SeqCst);
    shared.emit(&json!({"type": "done", "metrics": Value::Object(summary)}));
}

/// `done` metrics: the CLI's rounded timings, plus diarization time when it ran.
fn summary(metrics: &recognition::Metrics, speaker_s: Option<f64>) -> Map<String, Value> {
    let audio_s = significant6(metrics.audio_s);
    let mut processing_s = significant6(metrics.processing_s);
    let mut wall_s = significant6(metrics.wall_s);
    let mut rtf = significant6(if metrics.audio_s > 0.0 { metrics.processing_s / metrics.audio_s } else { 0.0 });
    let mut summary = Map::new();
    summary.insert("model_load_s".into(), significant6(metrics.model_load_s).into());
    if let Some(speaker_s) = speaker_s {
        processing_s += speaker_s;
        wall_s += speaker_s;
        rtf = if audio_s > 0.0 { processing_s / audio_s } else { 0.0 };
        summary.insert("speaker_s".into(), speaker_s.into());
    }
    summary.insert("audio_s".into(), audio_s.into());
    summary.insert("processing_s".into(), processing_s.into());
    summary.insert("rtf".into(), rtf.into());
    summary.insert("wall_s".into(), wall_s.into());
    summary
}
