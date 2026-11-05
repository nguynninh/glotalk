//! Translates the newest partial while the fast lane is idle; results are disposable.

use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use super::{elapsed_ms, POLL};
use crate::numbers::round2;
use crate::pipeline::state::Shared;
use crate::translation::{Mode, TranslationError};

const BUSY_RETRY: Duration = Duration::from_millis(50);

pub fn run(shared: &Shared) {
    let mut translated: Option<(u64, String)> = None;
    while !shared.cancelled() {
        let Some(segment) = shared.take_provisional(POLL) else { continue };
        // Finals share the fast lane and take priority over a hypothesis.
        let current = shared.segment_id.load(Ordering::SeqCst);
        if segment.id < current
            || shared.translations.unfinished() > 0
            || translated.as_ref().is_some_and(|(id, text)| *id == segment.id && *text == segment.text)
            || shared.translation_failed.load(Ordering::Relaxed)
        {
            continue;
        }
        let started = Instant::now();
        match shared.translator.translate(&segment.text, &shared.target, &shared.source, Mode::Standard, &[]) {
            Ok(mut result) => {
                translated = Some((segment.id, segment.text.clone()));
                result.insert("total_ms".into(), round2(elapsed_ms(started)).into());
                if segment.id >= shared.segment_id.load(Ordering::SeqCst) && !shared.cancelled() {
                    let source = json!({"text": segment.text, "language": result["source"]});
                    result.insert("language".into(), shared.target.clone().into());
                    shared.emit(&json!({
                        "type": "translation", "id": segment.id, "revision": "provisional",
                        "source": source, "translation": Value::Object(result),
                    }));
                }
            }
            Err(TranslationError::Busy) => {
                shared.return_provisional(segment);
                std::thread::sleep(BUSY_RETRY);
            }
            // Provisional text is disposable; the final path reports provider failures.
            Err(_) => {}
        }
    }
}
