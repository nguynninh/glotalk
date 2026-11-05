//! Runs the corpus through a freshly started router `runs` times, one request at a time as
//! the streaming server sends live segments, and writes `run-N.jsonl`, `router-N.log` and
//! `summary.json` into the output folder.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use crate::client::Client;
use crate::corpus::Item;
use crate::error::{Error, Result};
use crate::events::{Event, Observer};
use crate::memory;
use crate::report::{Record, RunSummary, Summary};
use crate::router::Router;

const MEMORY_INTERVAL: Duration = Duration::from_millis(100);

pub struct Options {
    pub label: String,
    /// Starts the router; run in `root`.
    pub command: Vec<String>,
    pub root: PathBuf,
    pub endpoint: String,
    pub runs: usize,
    pub out: PathBuf,
    pub startup_timeout: Duration,
}

pub fn run(corpus: &[Item], options: &Options, observer: &dyn Observer) -> Result<Summary> {
    if corpus.is_empty() || options.runs == 0 {
        return Err(Error::InvalidArgument("need at least one item and one run".into()));
    }
    fs::create_dir_all(&options.out).map_err(Error::io(&options.out))?;
    let client = Client::new(&options.endpoint);
    let mut runs = Vec::new();
    let mut records = Vec::new();
    for run in 1..=options.runs {
        observer.event(Event::Starting { run });
        let log = options.out.join(format!("router-{run}.log"));
        let (router, startup) = Router::start(&options.command, &options.root, &log, &client, options.startup_timeout)?;
        observer.event(Event::Ready { run, startup });
        let idle = memory::tree_rss(router.pid()).unwrap_or(0);
        let sampler = Sampler::start(router.pid());
        let mut current = Vec::with_capacity(corpus.len());
        for (index, item) in corpus.iter().enumerate() {
            let (elapsed, outcome) = client.translate(item);
            current.push(Record { index, item: item.clone(), latency_ms: millis(elapsed), outcome });
            observer.event(Event::Translated { run, done: index + 1, total: corpus.len() });
        }
        let peak = sampler.stop().max(idle);
        drop(router);
        observer.event(Event::Stopped { run });
        write_records(&options.out.join(format!("run-{run}.jsonl")), &current)?;
        runs.push(RunSummary::new(run, millis(startup), idle, peak, &current));
        records.push(current);
    }
    let summary = Summary::new(&options.label, &options.command, corpus.len(), runs, &records);
    let path = options.out.join("summary.json");
    let json = serde_json::to_string_pretty(&summary).map_err(Error::data(&path))?;
    fs::write(&path, json + "\n").map_err(Error::io(&path))?;
    Ok(summary)
}

/// Polls the router's process tree for its peak memory while requests run.
struct Sampler {
    stop: Arc<AtomicBool>,
    peak: Arc<AtomicU64>,
    thread: std::thread::JoinHandle<()>,
}

impl Sampler {
    fn start(pid: u32) -> Self {
        let (stop, peak) = (Arc::new(AtomicBool::new(false)), Arc::new(AtomicU64::new(0)));
        let thread = {
            let (stop, peak) = (stop.clone(), peak.clone());
            std::thread::spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    if let Some(bytes) = memory::tree_rss(pid) {
                        peak.fetch_max(bytes, Ordering::Relaxed);
                    }
                    std::thread::sleep(MEMORY_INTERVAL);
                }
            })
        };
        Self { stop, peak, thread }
    }

    fn stop(self) -> u64 {
        self.stop.store(true, Ordering::Relaxed);
        let _ = self.thread.join();
        self.peak.load(Ordering::Relaxed)
    }
}

fn write_records(path: &Path, records: &[Record]) -> Result<()> {
    let mut file = fs::File::create(path).map_err(Error::io(path))?;
    for record in records {
        let line = serde_json::to_string(record).map_err(Error::data(path))?;
        writeln!(file, "{line}").map_err(Error::io(path))?;
    }
    Ok(())
}

fn millis(duration: Duration) -> f64 {
    (duration.as_secs_f64() * 1e5).round() / 100.0
}
