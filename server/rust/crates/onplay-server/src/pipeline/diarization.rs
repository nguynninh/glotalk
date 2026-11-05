//! End-of-input diarization (`speakers: true`): segment the whole recording by speaker,
//! recognize each turn, then translate the segments again as `refined`.

use std::time::Instant;

use onplay_stt::diarize::SpeakerModels;
use onplay_stt::pcm::pcm16_to_f32;
use serde_json::{json, Value};

use super::segment::Segment;
use super::state::{Revision, Shared};
use super::worker::Models;
use crate::numbers::significant6;

/// Helper recognizers load the STT model again on every call (about a second each), so
/// they only pay off on long recordings: on an M4 they cost 1.7 s on a 4 s clip, gained
/// nothing on 57 s and saved 4.7 s of 26 s on 285 s. Shorter recordings use the cached
/// recognizer alone.
const HELPERS: usize = 3;
const HELPERS_FROM_SAMPLES: usize = 120 * 16_000;

/// Returns the seconds spent.
pub fn run(loaded: &mut Models, shared: &Shared, models: &SpeakerModels, threads: i32, recording: &[u8]) -> f64 {
    let started = Instant::now();
    shared.emit(&json!({"type": "status", "text": "Đang phân biệt người nói · bản chữ gốc được giữ để so sánh…"}));
    let mut samples = vec![0.0f32; recording.len() / 2];
    pcm16_to_f32(recording, &mut samples);
    let mut segments: Vec<Segment> = Vec::new();
    let outcome = loaded.speakers(models, threads).and_then(|transcriber| {
        transcriber.set_helpers(if samples.len() >= HELPERS_FROM_SAMPLES { HELPERS } else { 0 });
        transcriber.transcribe(&samples, |turn| {
            if shared.cancelled() {
                return Err(onplay_stt::Error::Runtime("cancelled".into()));
            }
            segments.push(Segment {
                id: segments.len() as u64,
                text: turn.text,
                speaker: Some(turn.speaker),
                // Times went through `%g` text in the Python bridge.
                span: Some((significant6(f64::from(turn.start)), significant6(f64::from(turn.end)))),
            });
            shared.emit(&json!({"type": "status", "text": format!("Đang phân biệt người nói · đã xử lý {} đoạn…", segments.len())}));
            Ok(())
        })
    });
    if outcome.is_err() {
        shared.warn("Phân biệt người nói thất bại. Đã giữ bản chữ gốc.");
    } else if segments.is_empty() {
        shared.warn("Không tìm thấy đoạn giọng nói đủ rõ. Đã giữ bản chữ gốc.");
    } else {
        let listed: Vec<Value> = segments
            .iter()
            .map(|segment| {
                let (start, end) = segment.span.unwrap_or_default();
                json!({"start": start, "end": end, "speaker": segment.speaker, "text": segment.text, "id": segment.id})
            })
            .collect();
        shared.emit(&json!({"type": "speakers", "segments": listed}));
        for segment in segments {
            shared.enqueue(segment, Revision::Refined);
        }
    }
    started.elapsed().as_secs_f64()
}
