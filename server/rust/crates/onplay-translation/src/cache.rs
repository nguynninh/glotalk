//! Least-recently-used result cache (the Python router's `OrderedDict`).

use std::collections::VecDeque;

/// Oldest entry first. At most 4096 entries, so linear lookup is enough.
pub(crate) struct Cache<K, V> {
    capacity: usize,
    entries: VecDeque<(K, V)>,
}

impl<K: PartialEq, V: Clone> Cache<K, V> {
    pub fn new(capacity: usize) -> Self {
        Self { capacity, entries: VecDeque::new() }
    }

    /// The cached value, marked as most recently used.
    pub fn get(&mut self, key: &K) -> Option<V> {
        let index = self.entries.iter().position(|(stored, _)| stored == key)?;
        let entry = self.entries.remove(index)?;
        let value = entry.1.clone();
        self.entries.push_back(entry);
        Some(value)
    }

    /// Stores `value`; an existing key keeps its position, as `OrderedDict` assignment does.
    pub fn insert(&mut self, key: K, value: V) {
        if self.capacity == 0 {
            return;
        }
        match self.entries.iter_mut().find(|(stored, _)| *stored == key) {
            Some(entry) => entry.1 = value,
            None => self.entries.push_back((key, value)),
        }
        while self.entries.len() > self.capacity {
            self.entries.pop_front();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evicts_least_recently_used() {
        let mut cache = Cache::new(2);
        cache.insert(1, "a");
        cache.insert(2, "b");
        assert_eq!(cache.get(&1), Some("a"));
        cache.insert(3, "c");
        assert_eq!(cache.get(&2), None);
        assert_eq!(cache.get(&1), Some("a"));
        let mut disabled = Cache::new(0);
        disabled.insert(1, "a");
        assert_eq!(disabled.get(&1), None);
    }
}
