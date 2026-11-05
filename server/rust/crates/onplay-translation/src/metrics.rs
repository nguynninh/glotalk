//! Counters served by `GET /metrics`, with the Python router's keys and order.

use serde::ser::{Serialize, SerializeMap, Serializer};

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Metrics {
    pub requests: u64,
    pub cache_hits: u64,
    pub errors: u64,
    pub fallbacks: u64,
    /// Sum of request latencies, each rounded to 0.01 ms.
    pub latency_ms_total: f64,
    /// Successful translations per model, in first-use order.
    pub by_model: Vec<(String, u64)>,
    pub placeholder_failures: u64,
    pub over_500_ms: u64,
}

impl Metrics {
    pub(crate) fn count_model(&mut self, name: &str) {
        match self.by_model.iter_mut().find(|(model, _)| model == name) {
            Some((_, count)) => *count += 1,
            None => self.by_model.push((name.to_owned(), 1)),
        }
    }
}

struct ByModel<'a>(&'a [(String, u64)]);

impl Serialize for ByModel<'_> {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(self.0.len()))?;
        for (model, count) in self.0 {
            map.serialize_entry(model, count)?;
        }
        map.end()
    }
}

impl Serialize for Metrics {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(8))?;
        map.serialize_entry("requests", &self.requests)?;
        map.serialize_entry("cache_hits", &self.cache_hits)?;
        map.serialize_entry("errors", &self.errors)?;
        map.serialize_entry("fallbacks", &self.fallbacks)?;
        map.serialize_entry("latency_ms_total", &self.latency_ms_total)?;
        map.serialize_entry("by_model", &ByModel(&self.by_model))?;
        map.serialize_entry("placeholder_failures", &self.placeholder_failures)?;
        map.serialize_entry("over_500_ms", &self.over_500_ms)?;
        map.end()
    }
}
