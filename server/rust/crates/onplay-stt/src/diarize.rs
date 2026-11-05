//! Offline diarization followed by per-turn recognition (`src/speakers.cpp`).

use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Condvar, Mutex};
use std::thread;
use std::path::PathBuf;

use onplay_sherpa::diarization::{DiarizationConfig, Diarizer};

use crate::error::{Error, Result};
use crate::model::{check_threads, require_file, ModelFiles};
use crate::utterance::UtteranceRecognizer;
use crate::SAMPLE_RATE;

/// Shortcut kept from C++: cap the offline pass at 10 minutes; use windowed diarization for
/// longer recordings.
pub const MAX_SAMPLES: u64 = 600 * SAMPLE_RATE as u64;

/// Extra recognizers that share per-turn recognition with the calling thread.
const HELPERS: usize = 3;

#[derive(Debug, Clone, PartialEq)]
pub struct SpeakerModels {
    pub stt: ModelFiles,
    pub segmentation: PathBuf,
    pub embedding: PathBuf,
}

/// A recognized speaker turn in seconds.
#[derive(Debug, Clone, PartialEq)]
pub struct Turn {
    pub start: f32,
    pub end: f32,
    /// 1-based, in order of first appearance.
    pub speaker: u32,
    pub text: String,
}

pub struct SpeakerTranscriber {
    diarizer: Diarizer,
    recognizer: UtteranceRecognizer,
    stt: ModelFiles,
    helpers: usize,
    helper_threads: i32,
}

impl SpeakerTranscriber {
    pub fn load(models: &SpeakerModels, threads: i32) -> Result<Self> {
        check_threads(threads)?;
        for path in [&models.stt.encoder, &models.stt.decoder, &models.stt.joiner, &models.stt.tokens, &models.segmentation, &models.embedding] {
            require_file(path, "Missing model file")?;
        }
        let diarizer = Diarizer::new(&DiarizationConfig {
            segmentation: models.segmentation.clone(),
            embedding: models.embedding.clone(),
            threads,
            num_clusters: -1,
            threshold: 0.5,
            min_duration_on: 0.3,
            min_duration_off: 0.5,
        })?;
        // Load the STT model once; each speaker turn gets its own stream.
        let recognizer = UtteranceRecognizer::load(&models.stt, threads)?;
        if diarizer.sample_rate() != SAMPLE_RATE as i32 {
            return Err(Error::runtime("Speaker model must accept 16000 Hz"));
        }
        let helpers = std::env::var("H").ok().and_then(|v| v.parse().ok()).unwrap_or(HELPERS);
        let helper_threads = std::env::var("HT").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
        Ok(Self { diarizer, recognizer, stt: models.stt.clone(), helpers, helper_threads })
    }

    /// Sets how many helper recognizers later calls load. Each loads the STT model again
    /// per call, which only pays off for long recordings with many turns.
    pub fn set_helpers(&mut self, helpers: usize) {
        self.helpers = helpers;
    }

    /// Calls `on_turn` for each turn in start order, as soon as it is recognized.
    ///
    /// Turns are recognized one stream each, as before, but spread over this thread and
    /// helper recognizers that load while diarization runs. Each recognizer is created,
    /// used and dropped on one thread, so text is the same as sequential recognition.
    pub fn transcribe(&self, samples: &[f32], mut on_turn: impl FnMut(Turn) -> Result<()>) -> Result<()> {
        if samples.len() as u64 > MAX_SAMPLES {
            return Err(Error::invalid("Speaker preview supports at most 10 minutes"));
        }
        let stop = AtomicBool::new(false);
        let queued = Gate::default();
        let (job_sender, jobs) = mpsc::channel::<(usize, &[f32])>();
        let jobs = Mutex::new(jobs);
        let (result_sender, results) = mpsc::channel::<Result<(usize, String)>>();
        let (stt, helper_threads) = (&self.stt, self.helper_threads);
        thread::scope(|scope| {
            for _ in 0..self.helpers {
                let (jobs, stop, queued, results) = (&jobs, &stop, &queued, result_sender.clone());
                scope.spawn(move || {
                    let recognizer = match UtteranceRecognizer::load(stt, helper_threads) {
                        Ok(recognizer) => recognizer,
                        Err(error) => return drop(results.send(Err(error))),
                    };
                    // Take jobs only once every turn is queued, and never block while holding
                    // the queue lock: the calling thread takes jobs from the same queue and
                    // would wait on that lock forever.
                    queued.wait();
                    while !stop.load(Ordering::Relaxed) {
                        let Ok((index, audio)) = jobs.lock().expect("job queue").try_recv() else { break };
                        if results.send(recognizer.transcribe(audio).map(|text| (index, text))).is_err() {
                            break;
                        }
                    }
                });
            }
            drop(result_sender);
            let outcome = self.emit(samples, &job_sender, &queued, &jobs, &results, &mut on_turn);
            stop.store(true, Ordering::Relaxed);
            queued.open();
            drop(job_sender);
            outcome
        })
    }

    /// Diarizes, queues every turn, then recognizes queued turns here too while emitting
    /// finished ones in order.
    fn emit<'a>(
        &self,
        samples: &'a [f32],
        job_sender: &mpsc::Sender<(usize, &'a [f32])>,
        queued: &Gate,
        jobs: &Mutex<mpsc::Receiver<(usize, &'a [f32])>>,
        results: &mpsc::Receiver<Result<(usize, String)>>,
        on_turn: &mut impl FnMut(Turn) -> Result<()>,
    ) -> Result<()> {
        let turns = self.diarizer.process(samples)?;
        for (index, turn) in turns.iter().enumerate() {
            if !turn.start.is_finite() || !turn.end.is_finite() || turn.start < 0.0 || turn.end <= turn.start || turn.speaker < 0 {
                return Err(Error::runtime("Invalid speaker segment"));
            }
            let first = samples.len().min((turn.start * SAMPLE_RATE as f32) as usize);
            let last = samples.len().min((turn.end * SAMPLE_RATE as f32).ceil() as usize);
            job_sender.send((index, &samples[first..last])).expect("receiver held by this call");
        }
        queued.open();
        let mut labels: HashMap<i32, u32> = HashMap::new();
        let mut texts: BTreeMap<usize, String> = BTreeMap::new();
        for (index, turn) in turns.iter().enumerate() {
            let text = loop {
                if let Some(text) = texts.remove(&index) {
                    break text;
                }
                // Recognize a queued turn here when one is left, otherwise wait for a helper.
                let job = jobs.lock().expect("job queue").try_recv().ok();
                let (done, text) = match job {
                    Some((done, audio)) => (done, self.recognizer.transcribe(audio)?),
                    None => results.recv().expect("an unfinished turn is queued or with a helper")?,
                };
                texts.insert(done, text);
            };
            let next = labels.len() as u32 + 1;
            let speaker = *labels.entry(turn.speaker).or_insert(next);
            on_turn(Turn { start: turn.start, end: turn.end, speaker, text })?;
        }
        Ok(())
    }
}

/// Opens once; waiters block until then.
#[derive(Default)]
struct Gate {
    open: Mutex<bool>,
    opened: Condvar,
}

impl Gate {
    fn open(&self) {
        *self.open.lock().expect("gate") = true;
        self.opened.notify_all();
    }

    fn wait(&self) {
        let open = self.open.lock().expect("gate");
        drop(self.opened.wait_while(open, |open| !*open).expect("gate"));
    }
}
