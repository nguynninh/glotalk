//! Compares a candidate router's results with a baseline's, both written by [`crate::run`] on
//! the same corpus. Outputs are compared from each folder's first run; `Summary::unstable`
//! says whether that run is representative.

use std::fs;
use std::path::Path;

use serde::Serialize;

use crate::client::Outcome;
use crate::corpus::{self, Item};
use crate::error::{Error, Result};
use crate::report::{Record, Summary};

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Difference {
    pub index: usize,
    #[serde(flatten)]
    pub item: Item,
    pub baseline: Outcome,
    pub candidate: Outcome,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Comparison {
    pub baseline: Summary,
    pub candidate: Summary,
    pub items: usize,
    pub differences: Vec<Difference>,
}

impl Comparison {
    pub fn load(baseline: &Path, candidate: &Path) -> Result<Self> {
        let (base_summary, base) = load(baseline)?;
        let (cand_summary, cand) = load(candidate)?;
        if base.len() != cand.len() || base.iter().zip(&cand).any(|(a, b)| a.item != b.item) {
            return Err(Error::InvalidArgument("the two results were not made from the same corpus".into()));
        }
        let differences = base
            .iter()
            .zip(&cand)
            .filter(|(a, b)| !same_output(&a.outcome, &b.outcome))
            .map(|(a, b)| Difference { index: a.index, item: a.item.clone(), baseline: a.outcome.clone(), candidate: b.outcome.clone() })
            .collect();
        Ok(Self { baseline: base_summary, candidate: cand_summary, items: base.len(), differences })
    }

    pub fn identical(&self) -> usize {
        self.items - self.differences.len()
    }

    pub fn write_differences(&self, path: &Path) -> Result<()> {
        let lines: Result<Vec<_>> =
            self.differences.iter().map(|d| serde_json::to_string(d).map_err(Error::data(path))).collect();
        let body: String = lines?.into_iter().map(|line| line + "\n").collect();
        fs::write(path, body).map_err(Error::io(path))
    }
}

/// Same text, detected language and model, or both failed. Error wording and the cache
/// flag are implementation details.
fn same_output(a: &Outcome, b: &Outcome) -> bool {
    match (a, b) {
        (
            Outcome::Ok { text: t1, detected: d1, model: m1, fallback: f1, .. },
            Outcome::Ok { text: t2, detected: d2, model: m2, fallback: f2, .. },
        ) => (t1, d1, m1, f1) == (t2, d2, m2, f2),
        (Outcome::Failed { .. }, Outcome::Failed { .. }) => true,
        _ => false,
    }
}

fn load(dir: &Path) -> Result<(Summary, Vec<Record>)> {
    let path = dir.join("summary.json");
    let raw = fs::read_to_string(&path).map_err(Error::io(&path))?;
    let summary = serde_json::from_str(&raw).map_err(Error::data(&path))?;
    Ok((summary, corpus::read_lines(&dir.join("run-1.jsonl"))?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_outputs_not_error_wording_or_cache() {
        let ok = |text: &str, cached| Outcome::Ok {
            text: text.into(),
            detected: "zh".into(),
            model: "opus-fast".into(),
            fallback: false,
            cached,
        };
        let failed = |error: &str| Outcome::Failed { code: Some(503), error: error.into() };
        assert!(same_output(&ok("a", false), &ok("a", true)));
        assert!(same_output(&failed("x"), &failed("y")));
        assert!(!same_output(&ok("a", false), &ok("b", false)));
        assert!(!same_output(&ok("a", false), &failed("x")));
    }
}
