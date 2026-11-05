//! Context-mode refinement of live finals with the previous sentences as context.

use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use serde_json::{json, Map, Value};

use super::{elapsed_ms, recent, POLL};
use crate::numbers::round2;
use crate::pipeline::segment::Segment;
use crate::pipeline::state::Shared;
use crate::translation::{wait_busy, Mode, TranslationError};

/// Refinement older than this is skipped; the segment keeps its fast translation.
const MAX_LAG: Duration = Duration::from_secs(15);

pub fn run(shared: &Shared) {
    let mut history = Vec::new();
    while !shared.cancelled() {
        let Some((segment, queued_at)) = shared.refinements.get(POLL) else { continue };
        refine(shared, &mut history, &segment, queued_at);
        shared.refinements.task_done();
    }
}

fn refine(shared: &Shared, history: &mut Vec<String>, segment: &Segment, queued_at: Instant) {
    let context = recent(history).to_vec();
    history.push(segment.text.clone());
    if queued_at.elapsed() > MAX_LAG {
        shared.context_skipped.fetch_add(1, Ordering::Relaxed);
        return;
    }
    let queue_ms = elapsed_ms(queued_at);
    let result = wait_busy(shared.outbox.cancelled_flag(), TranslationError::is_busy, || {
        shared.translator.translate(&segment.text, &shared.target, &shared.source, Mode::Context, &context)
    });
    match result {
        Ok(mut result) => {
            result.insert("queue_ms".into(), round2(queue_ms).into());
            result.insert("total_ms".into(), round2(elapsed_ms(queued_at)).into());
            if shared.cancelled() {
                return;
            }
            let mut event = Map::new();
            event.insert("type".into(), "translation".into());
            event.insert("id".into(), segment.id.into());
            event.insert("revision".into(), "context".into());
            segment.add_labels(&mut event, false);
            event.insert("source".into(), json!({"text": segment.text, "language": result["source"]}));
            result.insert("language".into(), shared.target.clone().into());
            event.insert("translation".into(), Value::Object(result));
            shared.emit(&Value::Object(event));
        }
        Err(_) => {
            // The segment keeps its fast translation; later segments are still refined.
            if shared.context_failed.fetch_add(1, Ordering::Relaxed) == 0 && !shared.cancelled() {
                shared.warn("Có đoạn không hiệu chỉnh được theo ngữ cảnh; đoạn đó giữ bản dịch nhanh.");
            }
        }
    }
}
