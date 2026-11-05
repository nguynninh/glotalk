//! Parallel in-process speech synthesis.
//!
//! Each worker thread owns one [`Synthesizer`] (ONNX sessions run through `&mut self`), so
//! N workers load the models N times, like N separate processes, without IPC or temp files.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::Mutex;

use onplay_tts::text::Language;
use onplay_tts::{Assets, Device, GaussianNoise, SynthesisOptions, Synthesizer, VoiceStyle};

use crate::error::{Error, Result};
use crate::events::{Event, Observer};
use crate::voices;

/// Mono samples at the synthesizer's sample rate.
pub type Clip = Vec<f32>;

#[derive(Debug, Clone)]
pub struct Job {
    pub index: usize,
    pub text: String,
    pub voice: &'static str,
    /// Speech may be sped up (to at most 2x) to fit this many seconds.
    pub max_seconds: f32,
}

pub struct PoolConfig<'a> {
    pub assets: &'a Assets,
    pub language: Language,
    pub workers: usize,
    pub threads: usize,
}

/// Starts the workers, lets `produce` queue jobs while they run, then waits for every job.
/// Returns what `produce` returned, the clips by job index and the output sample rate.
pub fn run<R>(
    config: &PoolConfig,
    observer: &dyn Observer,
    produce: impl FnOnce(&mut dyn FnMut(Job)) -> Result<R>,
) -> Result<(R, Vec<Option<Clip>>, u32)> {
    let synthesizers = load(config)?;
    let sample_rate = synthesizers[0].sample_rate();
    let (job_tx, job_rx) = mpsc::channel::<Job>();
    let (clip_tx, clip_rx) = mpsc::channel::<(usize, Result<Clip>)>();
    let jobs = Mutex::new(job_rx);
    let cancelled = AtomicBool::new(false);

    let produced = std::thread::scope(|scope| {
        for mut synthesizer in synthesizers {
            let (jobs, cancelled, clip_tx) = (&jobs, &cancelled, clip_tx.clone());
            scope.spawn(move || {
                let mut voices = std::collections::HashMap::new();
                let mut noise = GaussianNoise::new(None);
                loop {
                    let job = match jobs.lock().map(|jobs| jobs.recv()) {
                        Ok(Ok(job)) => job,
                        _ => break,
                    };
                    if cancelled.load(Ordering::Relaxed) {
                        continue;
                    }
                    let index = job.index;
                    let clip = speak(&mut synthesizer, config, &mut voices, &mut noise, job);
                    if clip.is_err() {
                        cancelled.store(true, Ordering::Relaxed);
                    } else {
                        observer.event(Event::Spoken { index });
                    }
                    if clip_tx.send((index, clip)).is_err() {
                        break;
                    }
                }
            });
        }
        drop(clip_tx);
        let mut queued = 0;
        let produced = produce(&mut |job| {
            queued = queued.max(job.index + 1);
            // Workers only stop receiving after the sender is dropped, so this cannot fail.
            let _ = job_tx.send(job);
        });
        if produced.is_err() {
            cancelled.store(true, Ordering::Relaxed);
        }
        drop(job_tx);
        produced.map(|value| (value, queued))
    });
    let (value, queued) = produced?;

    let mut clips: Vec<Option<Clip>> = vec![None; queued];
    for (index, clip) in clip_rx {
        clips[index] = Some(clip?);
    }
    Ok((value, clips, sample_rate))
}

/// Loads one synthesizer per worker, in parallel.
fn load(config: &PoolConfig) -> Result<Vec<Synthesizer>> {
    if config.workers == 0 {
        return Err(Error::InvalidArgument("at least one speech worker is required".into()));
    }
    std::thread::scope(|scope| {
        let handles: Vec<_> = (0..config.workers)
            .map(|_| scope.spawn(|| Synthesizer::load(config.assets, Device::Cpu, Some(config.threads))))
            .collect();
        handles
            .into_iter()
            .map(|handle| handle.join().expect("synthesizer loading panicked").map_err(Error::from))
            .collect()
    })
}

fn speak(
    synthesizer: &mut Synthesizer,
    config: &PoolConfig,
    voices: &mut std::collections::HashMap<&'static str, VoiceStyle>,
    noise: &mut GaussianNoise,
    job: Job,
) -> Result<Clip> {
    if !voices.contains_key(job.voice) {
        let style = VoiceStyle::load(&config.assets.voice(job.voice)?)?;
        voices.insert(job.voice, style);
    }
    let options = SynthesisOptions {
        total_steps: voices::TOTAL_STEPS,
        speed: voices::SPEED,
        max_duration: Some(job.max_seconds),
        ..SynthesisOptions::default()
    };
    Ok(synthesizer.synthesize(&job.text, config.language, &voices[job.voice], options, noise)?)
}
