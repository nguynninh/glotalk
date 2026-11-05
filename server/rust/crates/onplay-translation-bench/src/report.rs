//! Per-item records and the summary of all runs, both stored as JSON in the output folder.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::client::Outcome;
use crate::corpus::Item;
use crate::stats::Latency;

/// One item's result in one run (a line of `run-N.jsonl`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Record {
    pub index: usize,
    #[serde(flatten)]
    pub item: Item,
    pub latency_ms: f64,
    pub outcome: Outcome,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RunSummary {
    pub run: usize,
    pub startup_ms: f64,
    /// Memory of the router's process tree once healthy, before any request.
    pub idle_rss_mb: f64,
    pub peak_rss_mb: f64,
    pub latency: Option<Latency>,
    pub failures: usize,
    pub fallbacks: usize,
    /// Must stay 0: every item is unique and the router starts empty.
    pub cached: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Summary {
    pub label: String,
    pub command: Vec<String>,
    pub items: usize,
    pub runs: Vec<RunSummary>,
    /// Successful requests of every run.
    pub latency: Option<Latency>,
    pub latency_by_model: BTreeMap<String, Latency>,
    pub requests_by_model: BTreeMap<String, usize>,
    /// Items whose outcome was not the same in every run.
    pub unstable: Vec<usize>,
}

impl RunSummary {
    pub fn new(run: usize, startup_ms: f64, idle_rss: u64, peak_rss: u64, records: &[Record]) -> Self {
        let ok = || records.iter().filter_map(|record| match &record.outcome {
            Outcome::Ok { fallback, cached, .. } => Some((record.latency_ms, *fallback, *cached)),
            Outcome::Failed { .. } => None,
        });
        Self {
            run,
            startup_ms,
            idle_rss_mb: megabytes(idle_rss),
            peak_rss_mb: megabytes(peak_rss),
            latency: Latency::of(&ok().map(|(ms, ..)| ms).collect::<Vec<_>>()),
            failures: records.len() - ok().count(),
            fallbacks: ok().filter(|(_, fallback, _)| *fallback).count(),
            cached: ok().filter(|(.., cached)| *cached).count(),
        }
    }
}

impl Summary {
    pub fn new(label: &str, command: &[String], items: usize, runs: Vec<RunSummary>, records: &[Vec<Record>]) -> Self {
        let mut all = Vec::new();
        let mut by_model: BTreeMap<String, Vec<f64>> = BTreeMap::new();
        for record in records.iter().flatten() {
            if let Outcome::Ok { model, .. } = &record.outcome {
                all.push(record.latency_ms);
                by_model.entry(model.clone()).or_default().push(record.latency_ms);
            }
        }
        let unstable = (0..items)
            .filter(|&index| records.iter().any(|run| run.get(index).map(|r| &r.outcome) != records[0].get(index).map(|r| &r.outcome)))
            .collect();
        Self {
            label: label.to_owned(),
            command: command.to_vec(),
            items,
            runs,
            latency: Latency::of(&all),
            requests_by_model: by_model.iter().map(|(model, samples)| (model.clone(), samples.len() / records.len().max(1))).collect(),
            latency_by_model: by_model.iter().filter_map(|(model, samples)| Some((model.clone(), Latency::of(samples)?))).collect(),
            unstable,
        }
    }
}

fn megabytes(bytes: u64) -> f64 {
    (bytes as f64 / (1024.0 * 1024.0) * 10.0).round() / 10.0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::corpus::Origin;

    fn record(index: usize, ms: f64, outcome: Outcome) -> Record {
        let item = Item { text: format!("t{index}"), source: "auto".into(), target: "vi".into(), origin: Origin::Final };
        Record { index, item, latency_ms: ms, outcome }
    }

    fn ok(text: &str, model: &str, fallback: bool) -> Outcome {
        Outcome::Ok { text: text.into(), detected: "zh".into(), model: model.into(), fallback, cached: false }
    }

    #[test]
    fn summarizes_runs_and_finds_unstable_items() {
        let failed = Outcome::Failed { code: Some(503), error: "x".into() };
        let first = vec![record(0, 10.0, ok("a", "opus", false)), record(1, 30.0, ok("b", "nllb", true)), record(2, 5.0, failed)];
        let mut second = first.clone();
        second[1].outcome = ok("c", "nllb", true);
        let run = RunSummary::new(1, 900.0, 3 << 20, 5 << 20, &first);
        assert_eq!((run.failures, run.fallbacks, run.cached, run.idle_rss_mb, run.peak_rss_mb), (1, 1, 0, 3.0, 5.0));
        assert_eq!(run.latency.unwrap().count, 2);
        let summary = Summary::new("python", &[], 3, vec![run], &[first, second]);
        assert_eq!(summary.unstable, [1]);
        assert_eq!(summary.requests_by_model["opus"], 1);
        assert_eq!(summary.latency_by_model["nllb"].count, 2);
    }
}
