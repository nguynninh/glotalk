//! Streaming recognition of the client's PCM on the recognition worker,
//! replacing the `onplay-stt -` child process: same chunking, events and metrics.

use std::sync::mpsc::Receiver;
use std::time::{Duration, Instant};

use onplay_stt::pcm::pcm16_to_f32;
use onplay_stt::{EngineConfig, Session, Transcript, SAMPLE_RATE};
use serde_json::json;

use super::state::Shared;
use super::worker::Models;
use super::Input;
use crate::numbers::significant6;

/// Timings in seconds, as the CLI printed them on stderr.
#[derive(Debug, Clone, Copy)]
pub struct Metrics {
    pub model_load_s: f64,
    pub audio_s: f64,
    pub processing_s: f64,
    pub wall_s: f64,
}

pub struct Finished {
    pub metrics: Metrics,
    /// PCM kept for end-of-input diarization.
    pub recording: Option<Vec<u8>>,
}

/// `Ok(None)` when the client went away before ending its input. Errors are the
/// engine's messages.
pub fn run(models: &mut Models, shared: &Shared, config: &EngineConfig, chunk_ms: usize, input: &Receiver<Input>) -> Result<Option<Finished>, String> {
    // Near zero when the worker already holds an engine for this configuration.
    let load = Instant::now();
    let engine = models.engine(config).map_err(|error| error.to_string())?;
    let labelled = config.speaker_model.is_some();
    let mut session = engine.session(|transcript: &Transcript| shared.on_transcript(transcript, labelled)).map_err(|error| error.to_string())?;
    let model_load_s = load.elapsed().as_secs_f64();
    let mut ready = json!({"type": "ready"});
    if let Some(recorder) = &shared.recorder {
        ready["session_id"] = recorder.id().into();
    }
    shared.emit(&ready);

    let start = Instant::now();
    let chunk_bytes = chunk_ms * SAMPLE_RATE / 1000 * 2;
    let mut feeder = Feeder { samples: vec![0.0; chunk_bytes / 2], compute: Duration::ZERO, position: 0, reported: 0 };
    let mut pending: Vec<u8> = Vec::new();
    loop {
        if shared.cancelled() {
            return Ok(None);
        }
        match input.recv() {
            Ok(Input::Pcm(bytes)) => {
                pending.extend_from_slice(&bytes);
                // The CLI read fixed chunks from its pipe; decoding per chunk keeps the
                // same partial results.
                let full = pending.len() - pending.len() % chunk_bytes;
                for chunk in pending[..full].chunks(chunk_bytes) {
                    feeder.feed(shared, &mut session, chunk)?;
                }
                pending.drain(..full);
            }
            Ok(Input::End { recording }) => {
                if !pending.is_empty() {
                    feeder.feed(shared, &mut session, &pending)?;
                }
                let tick = Instant::now();
                session.finish().map_err(|error| error.to_string())?;
                feeder.compute += tick.elapsed();
                let metrics = Metrics {
                    model_load_s,
                    audio_s: feeder.position as f64 / SAMPLE_RATE as f64,
                    processing_s: feeder.compute.as_secs_f64(),
                    wall_s: start.elapsed().as_secs_f64(),
                };
                return Ok(Some(Finished { metrics, recording }));
            }
            Err(_) => return Ok(None),
        }
    }
}

struct Feeder {
    samples: Vec<f32>,
    /// Time spent inside the recognizer.
    compute: Duration,
    /// Samples accepted so far.
    position: u64,
    /// Position of the last `progress` event.
    reported: u64,
}

impl Feeder {
    fn feed<L: FnMut(&Transcript)>(&mut self, shared: &Shared, session: &mut Session<'_, L>, bytes: &[u8]) -> Result<(), String> {
        let count = pcm16_to_f32(bytes, &mut self.samples);
        let tick = Instant::now();
        session.accept_audio(&self.samples[..count]).map_err(|error| error.to_string())?;
        self.compute += tick.elapsed();
        self.position += count as u64;
        if self.position - self.reported >= SAMPLE_RATE as u64 {
            shared.emit(&json!({"type": "progress", "seconds": significant6(self.position as f64 / SAMPLE_RATE as f64)}));
            self.reported = self.position;
        }
        Ok(())
    }
}
