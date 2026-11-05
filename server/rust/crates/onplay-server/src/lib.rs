//! Streaming speech server: one WebSocket (`ws://127.0.0.1:8766`), the protocol of the
//! former Python server (see `docs/streaming-audio.md`).
//!
//! client PCM16 -> [`socket`] -> [`pipeline`] recognition on `onplay-stt` (in process)
//!      -> partial/final events -> [`translation`] router lanes -> [`speech`] KorvaTTS
//!      -> events and WAV frames back over the same socket
//!
//! The translation router stays a separate HTTP service (`./scripts/dev/translation.sh`).
//! Up to `ONPLAY_MAX_SESSIONS` recognition sessions run at once ([`EngineSlots`]), each on
//! its own recognition worker; opt-in transcripts are appended by [`sessions`].

pub mod config;
pub mod engine_slots;
pub mod events;
pub mod languages;
pub mod models;
pub mod numbers;
pub mod pipeline;
pub mod sessions;
pub mod socket;
pub mod speech;
pub mod trace;
pub mod translation;

use std::net::TcpListener;
use std::sync::Arc;

pub use config::Config;
pub use engine_slots::EngineSlots;
pub use events::{Event, Observer, Outcome, Silent};

use pipeline::RecognitionWorker;
use sessions::ContextPasses;
use speech::Speech;
use translation::Translator;

/// Process-wide services shared by every connection.
pub struct Services {
    pub config: Config,
    pub translator: Arc<Translator>,
    pub speech: Arc<Speech>,
    pub slots: EngineSlots,
    pub context_passes: ContextPasses,
    /// One worker per slot; each owns its loaded Sherpa models and runs the recognition of
    /// the session holding that slot.
    pub recognition: Vec<RecognitionWorker>,
    /// Receives connection events.
    pub observer: Arc<dyn Observer>,
}

impl Services {
    /// Starts the background workers. Recognition and speech models load on first use,
    /// or earlier through [`Services::preload`] and [`Speech::load`].
    pub fn start(config: Config, observer: impl Observer + 'static) -> Self {
        let translator = Arc::new(Translator::new(&config.translate_url));
        let slots = EngineSlots::new(config.max_sessions);
        let context_passes = ContextPasses::start(translator.clone(), slots.clone());
        let recognition = (0..slots.count()).map(|_| RecognitionWorker::start()).collect();
        Self { config, translator, speech: Arc::new(Speech::new()), slots, context_passes, recognition, observer: Arc::new(observer) }
    }

    /// Loads the streaming engine for `threads` and `low_latency` on the first worker in the
    /// background, so a matching session on a free server starts without the model load.
    /// Sessions with other options (thread count, live speaker labels), or on another
    /// worker while the first is busy, still load their engine once.
    pub fn preload(&self, threads: i32, low_latency: bool) {
        let engine = onplay_stt::EngineConfig { model: self.config.stt.clone(), threads, speaker_model: None, low_latency };
        // A load error is reported again, to the client, by the first session that needs it.
        drop(self.recognition[0].run(move |models| drop(models.engine(&engine))));
    }
}

/// Opens the WebSocket port. Bind before [`Services::start`] so a busy port fails before any
/// model work starts in the background.
pub fn bind(config: &Config) -> std::io::Result<TcpListener> {
    TcpListener::bind(&config.address)
}

/// Accepts connections on `listener` (from [`bind`]) until it fails.
pub fn serve(services: Services, listener: TcpListener) -> std::io::Result<()> {
    socket::serve(Arc::new(services), listener)
}
