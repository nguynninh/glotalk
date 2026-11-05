//! Latency distribution in milliseconds.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Latency {
    pub count: usize,
    pub mean_ms: f64,
    pub p50_ms: f64,
    pub p95_ms: f64,
    pub p99_ms: f64,
    pub max_ms: f64,
}

impl Latency {
    /// Nearest-rank percentiles; `None` without samples.
    pub fn of(samples: &[f64]) -> Option<Self> {
        if samples.is_empty() {
            return None;
        }
        let mut sorted = samples.to_vec();
        sorted.sort_by(f64::total_cmp);
        let rank = |p: f64| sorted[((p * sorted.len() as f64).ceil() as usize).clamp(1, sorted.len()) - 1];
        Some(Self {
            count: sorted.len(),
            mean_ms: sorted.iter().sum::<f64>() / sorted.len() as f64,
            p50_ms: rank(0.50),
            p95_ms: rank(0.95),
            p99_ms: rank(0.99),
            max_ms: sorted[sorted.len() - 1],
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nearest_rank_percentiles() {
        let samples: Vec<f64> = (1..=100).rev().map(f64::from).collect();
        let latency = Latency::of(&samples).unwrap();
        assert_eq!((latency.p50_ms, latency.p95_ms, latency.p99_ms, latency.max_ms), (50.0, 95.0, 99.0, 100.0));
        assert_eq!((latency.count, latency.mean_ms), (100, 50.5));
        assert_eq!(Latency::of(&[7.0]).unwrap().p99_ms, 7.0);
        assert!(Latency::of(&[]).is_none());
    }
}
