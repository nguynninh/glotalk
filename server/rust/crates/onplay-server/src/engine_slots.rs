//! A fixed number of concurrent recognition sessions, one recognition worker per slot;
//! the background context pass yields while any session runs.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

#[derive(Clone)]
pub struct EngineSlots(Arc<[AtomicBool]>);

/// Frees its slot when dropped.
pub struct EngineSlot {
    slots: Arc<[AtomicBool]>,
    index: usize,
}

impl EngineSlots {
    /// `count` slots, at least one.
    pub fn new(count: usize) -> Self {
        Self((0..count.max(1)).map(|_| AtomicBool::new(false)).collect())
    }

    pub fn count(&self) -> usize {
        self.0.len()
    }

    /// Never waits: a session beyond the limit is refused instead of queued. Takes the
    /// lowest free slot, so the preloaded first worker serves sessions when it can.
    pub fn try_acquire(&self) -> Option<EngineSlot> {
        let index = self.0.iter().position(|slot| slot.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).is_ok())?;
        Some(EngineSlot { slots: self.0.clone(), index })
    }

    pub fn any_busy(&self) -> bool {
        self.active() > 0
    }

    /// Slots held right now.
    pub fn active(&self) -> usize {
        self.0.iter().filter(|slot| slot.load(Ordering::Acquire)).count()
    }
}

impl EngineSlot {
    pub fn index(&self) -> usize {
        self.index
    }
}

impl Drop for EngineSlot {
    fn drop(&mut self) {
        self.slots[self.index].store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_beyond_the_limit_until_a_slot_is_released() {
        let slots = EngineSlots::new(2);
        let first = slots.try_acquire().unwrap();
        let second = slots.try_acquire().unwrap();
        assert_eq!((first.index(), second.index()), (0, 1));
        assert!(slots.any_busy() && slots.active() == 2 && slots.try_acquire().is_none());
        drop(first);
        assert_eq!(slots.try_acquire().unwrap().index(), 0);
        drop(second);
        assert!(!slots.any_busy());
        assert_eq!(EngineSlots::new(0).count(), 1);
    }
}
