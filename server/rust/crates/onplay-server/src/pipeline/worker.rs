//! A recognition worker: one long-lived thread that owns its loaded Sherpa models and
//! runs the recognition of the sessions holding its engine slot, one at a time.
//!
//! Loading the streaming model takes about a second and the speaker models several
//! seconds, against a fraction of a second of decoding for a short clip, so models stay
//! loaded between sessions. Sherpa handles are neither `Send` nor `Sync` and never leave
//! this thread, so concurrent sessions run on separate workers with separate models.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::mpsc::{self, Receiver, Sender};

use onplay_stt::diarize::{SpeakerModels, SpeakerTranscriber};
use onplay_stt::{Engine, EngineConfig};

/// Streaming engines kept loaded: one per recognizer configuration in recent use
/// (low latency and live speaker labels change the native recognizer). Each holds the
/// ~340 MB encoder.
const ENGINES: usize = 2;

type Job = Box<dyn FnOnce(&mut Models) + Send>;

pub struct RecognitionWorker {
    jobs: Sender<Job>,
}

impl RecognitionWorker {
    pub fn start() -> Self {
        let (jobs, queue) = mpsc::channel::<Job>();
        std::thread::spawn(move || {
            let mut models = Models::default();
            for job in queue {
                // A panicking session must not take the worker down; its models may be
                // in an unknown state, so drop them.
                if catch_unwind(AssertUnwindSafe(|| job(&mut models))).is_err() {
                    models = Models::default();
                }
            }
        });
        Self { jobs }
    }

    /// Runs `job` on the worker; the receiver disconnects when it has finished.
    pub fn run(&self, job: impl FnOnce(&mut Models) + Send + 'static) -> Receiver<()> {
        let (done, finished) = mpsc::channel::<()>();
        let _ = self.jobs.send(Box::new(move |models| {
            job(models);
            drop(done);
        }));
        finished
    }
}

#[derive(Default)]
pub struct Models {
    /// Most recently used last.
    engines: Vec<(EngineConfig, Engine)>,
    speakers: Option<(SpeakerModels, i32, SpeakerTranscriber)>,
}

impl Models {
    /// The streaming engine for `config`, loading it (and evicting the least recently
    /// used one) when needed.
    pub fn engine(&mut self, config: &EngineConfig) -> onplay_stt::Result<&Engine> {
        match self.engines.iter().position(|(loaded, _)| loaded == config) {
            Some(index) => {
                let entry = self.engines.remove(index);
                self.engines.push(entry);
            }
            None => {
                if self.engines.len() >= ENGINES {
                    // Free the old encoder before loading the new one.
                    self.engines.remove(0);
                }
                self.engines.push((config.clone(), Engine::load(config)?));
            }
        }
        Ok(&self.engines.last().expect("just inserted").1)
    }

    /// The end-of-input speaker transcriber, kept for the next session with the same
    /// models and thread count.
    pub fn speakers(&mut self, models: &SpeakerModels, threads: i32) -> onplay_stt::Result<&mut SpeakerTranscriber> {
        let cached = matches!(&self.speakers, Some((loaded, loaded_threads, _)) if loaded == models && *loaded_threads == threads);
        if !cached {
            self.speakers = None;
            self.speakers = Some((models.clone(), threads, SpeakerTranscriber::load(models, threads)?));
        }
        Ok(&mut self.speakers.as_mut().expect("just loaded").2)
    }
}
