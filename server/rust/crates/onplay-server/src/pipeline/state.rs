//! State shared by the recognition thread and the translation and speech lanes.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use onplay_stt::Transcript;
use serde_json::{json, Value};

use super::queue::WorkQueue;
use super::segment::Segment;
use crate::sessions::Recorder;
use crate::socket::{AudioEncoding, ClientConfig, Outbox};
use crate::speech::Speech;
use crate::trace::Trace;
use crate::translation::{Mode, Translator};

/// Translations waiting before the session gives up on keeping up.
const TRANSLATION_QUEUE: usize = 64;
const REFINEMENT_QUEUE: usize = 64;
const SPEECH_QUEUE: usize = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Revision {
    /// A live final, translated on the fast lane.
    Live,
    /// An end-of-input speaker segment.
    Refined,
}

impl Revision {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Live => "live",
            Self::Refined => "refined",
        }
    }
}

pub struct TranslationJob {
    pub segment: Segment,
    pub revision: Revision,
    pub queued_at: Instant,
}

pub struct SpeechJob {
    pub id: u64,
    pub text: String,
    pub speaker: Option<u32>,
    /// When the source sentence was queued for translation.
    pub spoken_at: Instant,
}

pub struct Shared {
    pub outbox: Outbox,
    /// Empty: no translation.
    pub target: String,
    pub source: String,
    pub mode: Mode,
    pub speech: bool,
    pub audio_encoding: AudioEncoding,
    pub translator: Arc<Translator>,
    pub speech_engine: Arc<Speech>,
    pub recorder: Option<Arc<Recorder>>,
    pub trace: Trace,
    /// Id the next final will receive.
    pub segment_id: AtomicU64,
    /// PCM bytes received from the client so far.
    pub received: AtomicU64,
    pub finals: Mutex<Vec<Segment>>,
    pub translations: WorkQueue<TranslationJob>,
    pub translation_failed: AtomicBool,
    /// Newest partial of the open segment; older hypotheses are never worth translating.
    provisional: Mutex<Option<Segment>>,
    provisional_ready: Condvar,
    pub refinements: WorkQueue<(Segment, Instant)>,
    pub speech_jobs: WorkQueue<SpeechJob>,
    pub speech_failed: AtomicBool,
    pub speech_skipped: AtomicU64,
    pub context_skipped: AtomicU64,
    pub context_failed: AtomicU64,
    /// Set when `done` was sent, so the session is not recorded as cancelled.
    pub completed: AtomicBool,
}

impl Shared {
    pub fn new(
        outbox: Outbox,
        config: &ClientConfig,
        translator: Arc<Translator>,
        speech_engine: Arc<Speech>,
        recorder: Option<Arc<Recorder>>,
        trace: Trace,
    ) -> Self {
        Self {
            outbox,
            target: config.target.clone(),
            source: config.source.clone(),
            mode: config.mode,
            speech: config.speech,
            audio_encoding: config.audio_encoding,
            translator,
            speech_engine,
            recorder,
            trace,
            segment_id: AtomicU64::new(0),
            received: AtomicU64::new(0),
            finals: Mutex::new(Vec::new()),
            translations: WorkQueue::new(TRANSLATION_QUEUE),
            translation_failed: AtomicBool::new(false),
            provisional: Mutex::new(None),
            provisional_ready: Condvar::new(),
            refinements: WorkQueue::new(REFINEMENT_QUEUE),
            speech_jobs: WorkQueue::new(SPEECH_QUEUE),
            speech_failed: AtomicBool::new(false),
            speech_skipped: AtomicU64::new(0),
            context_skipped: AtomicU64::new(0),
            context_failed: AtomicU64::new(0),
            completed: AtomicBool::new(false),
        }
    }

    pub fn translating(&self) -> bool {
        !self.target.is_empty()
    }

    pub fn cancelled(&self) -> bool {
        self.outbox.is_cancelled()
    }

    /// Sends an event; a closed connection cancels the session instead of failing here.
    pub fn emit(&self, event: &Value) {
        let _ = self.outbox.emit(event);
    }

    pub fn warn(&self, text: &str) {
        self.emit(&json!({"type": "warning", "text": text}));
    }

    /// Handles one recognizer result on the recognition thread.
    pub fn on_transcript(&self, transcript: &Transcript, labelled: bool) {
        let text = transcript.text.trim();
        if text.is_empty() {
            return;
        }
        let speaker = labelled.then_some(transcript.speaker);
        if transcript.is_final {
            let id = self.segment_id.fetch_add(1, Ordering::SeqCst);
            self.on_final(Segment { id, text: text.to_owned(), speaker, span: None });
        } else {
            // Partials carry the id their final will receive.
            let segment = Segment { id: self.segment_id.load(Ordering::SeqCst), text: text.to_owned(), speaker, span: None };
            self.emit(&segment.event("partial"));
            self.offer(segment);
        }
    }

    fn on_final(&self, segment: Segment) {
        self.emit(&segment.event("final"));
        if let Some(recorder) = &self.recorder {
            // Audio position when the final arrived, for segmentation calibration.
            let mut record = segment.event("final");
            record["received_s"] = (self.received.load(Ordering::Relaxed) as f64 / 32_000.0).into();
            recorder.write(&record);
            self.finals.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).push(segment.clone());
        }
        self.enqueue(segment.clone(), Revision::Live);
        self.refine(&segment);
    }

    /// Queues a segment on the translation lane; a full queue stops translation.
    pub fn enqueue(&self, segment: Segment, revision: Revision) {
        if !self.translating() || !segment.has_text() || self.translation_failed.load(Ordering::Relaxed) {
            return;
        }
        if self.translations.put(TranslationJob { segment, revision, queued_at: Instant::now() }).is_err() {
            self.translation_failed.store(true, Ordering::Relaxed);
            self.emit(&json!({"type": "translation_error", "text": "Dịch không theo kịp audio (64 đoạn đang chờ). Hãy dịch lại bản chữ sau khi kết thúc."}));
        }
    }

    /// Replaces the partial waiting for a provisional translation.
    fn offer(&self, segment: Segment) {
        if !self.translating() || !segment.has_text() || self.translation_failed.load(Ordering::Relaxed) {
            return;
        }
        *self.provisional.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(segment);
        self.provisional_ready.notify_one();
    }

    /// Waits up to `timeout` for a partial to translate.
    pub fn take_provisional(&self, timeout: Duration) -> Option<Segment> {
        let slot = self.provisional.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let (mut slot, _) = self
            .provisional_ready
            .wait_timeout_while(slot, timeout, |slot| slot.is_none() && !self.cancelled())
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        slot.take()
    }

    /// Puts a partial back unless a newer one arrived meanwhile.
    pub fn return_provisional(&self, segment: Segment) {
        self.provisional.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).get_or_insert(segment);
    }

    fn refine(&self, segment: &Segment) {
        if self.mode != Mode::Context || !self.translating() || !segment.has_text() {
            return;
        }
        // A full refinement queue only costs context quality; the fast translation stays.
        let _ = self.refinements.put((segment.clone(), Instant::now()));
    }

    pub fn skip_speech(&self, id: u64, reason: &str) {
        self.speech_skipped.fetch_add(1, Ordering::Relaxed);
        self.emit(&json!({"type": "audio_skipped", "id": id, "reason": reason}));
    }

    /// Lanes still have queued or in-flight work.
    pub fn busy(&self) -> bool {
        self.translations.unfinished() > 0 || self.refinements.unfinished() > 0 || self.speech_jobs.unfinished() > 0
    }
}
