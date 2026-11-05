//! Fast-lane translation of live finals and end-of-input speaker segments; queues speech.

use std::sync::atomic::Ordering;

use serde_json::{json, Map, Value};

use super::{elapsed_ms, recent, POLL};
use crate::numbers::round2;
use crate::pipeline::state::{Revision, Shared, SpeechJob, TranslationJob};
use crate::translation::{wait_busy, Mode, TranslationError};

/// A translation within this many milliseconds of the final keeps up with live speech.
const BUDGET_MS: f64 = 500.0;

pub fn run(shared: &Shared) {
    let mut live_history = Vec::new();
    let mut refined_history = Vec::new();
    while !shared.cancelled() {
        let Some(job) = shared.translations.get(POLL) else { continue };
        let history = match job.revision {
            Revision::Live => &mut live_history,
            Revision::Refined => &mut refined_history,
        };
        translate(shared, history, &job);
        shared.translations.task_done();
    }
}

fn translate(shared: &Shared, history: &mut Vec<String>, job: &TranslationJob) {
    if shared.translation_failed.load(Ordering::Relaxed) {
        return;
    }
    let queue_ms = elapsed_ms(job.queued_at);
    // Live finals use the fast lane; context mode refines them separately.
    let mode = if job.revision == Revision::Live { Mode::Standard } else { shared.mode };
    let context = if mode == Mode::Context { recent(history) } else { &[] };
    let segment = &job.segment;
    let result = wait_busy(shared.outbox.cancelled_flag(), TranslationError::is_busy, || {
        shared.translator.translate(&segment.text, &shared.target, &shared.source, mode, context)
    });
    let mut result = match result {
        Ok(result) => result,
        Err(error) => {
            shared.translation_failed.store(true, Ordering::Relaxed);
            if let (Some(recorder), Revision::Live) = (&shared.recorder, job.revision) {
                recorder.write(&json!({"type": "translation_error", "revision": "live", "id": segment.id}));
            }
            if !shared.cancelled() {
                shared.emit(&json!({"type": "translation_error", "text": error.to_string()}));
            }
            return;
        }
    };
    let total_ms = round2(elapsed_ms(job.queued_at));
    result.insert("queue_ms".into(), round2(queue_ms).into());
    result.insert("total_ms".into(), total_ms.into());
    result.insert("within_budget".into(), (total_ms <= BUDGET_MS).into());
    history.push(segment.text.clone());
    let overflow = history.len().saturating_sub(5);
    history.drain(..overflow);
    if shared.cancelled() {
        return;
    }
    let text = result["text"].as_str().unwrap_or_default().to_owned();
    if let (Some(recorder), Revision::Live) = (&shared.recorder, job.revision) {
        recorder.write(&json!({
            "type": "translation", "revision": "live", "id": segment.id, "text": text,
            "model": result["model"], "fallback": result.get("fallback").cloned().unwrap_or(Value::Bool(false)),
        }));
    }
    shared.emit(&event(shared, job, result));
    if shared.speech && job.revision == Revision::Live && !shared.speech_failed.load(Ordering::Relaxed) {
        queue_speech(shared, SpeechJob { id: segment.id, text, speaker: segment.speaker, spoken_at: job.queued_at });
    }
}

/// `{"type": "translation", "id", "revision", "speaker"?, "start"?, "end"?, "source", "translation"}`.
fn event(shared: &Shared, job: &TranslationJob, mut result: Map<String, Value>) -> Value {
    let segment = &job.segment;
    let mut event = Map::new();
    event.insert("type".into(), "translation".into());
    event.insert("id".into(), segment.id.into());
    event.insert("revision".into(), job.revision.as_str().into());
    segment.add_labels(&mut event, true);
    event.insert("source".into(), json!({"text": segment.text, "language": result["source"]}));
    result.insert("language".into(), shared.target.clone().into());
    event.insert("translation".into(), Value::Object(result));
    Value::Object(event)
}

/// A full speech queue drops its oldest sentence; only this lane adds jobs.
fn queue_speech(shared: &Shared, job: SpeechJob) {
    if let Err(job) = shared.speech_jobs.put(job) {
        if let Some(dropped) = shared.speech_jobs.drop_oldest() {
            shared.skip_speech(dropped.id, "queue");
        }
        let _ = shared.speech_jobs.put(job);
    }
}
