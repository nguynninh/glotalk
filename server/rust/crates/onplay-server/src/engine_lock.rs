//! One recognition session at a time; the background context pass yields to it.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

#[derive(Clone, Default)]
pub struct EngineLock(Arc<AtomicBool>);

/// Releases the lock when dropped.
pub struct EngineGuard(Arc<AtomicBool>);

impl EngineLock {
    /// Never waits: a second session is refused instead of queued.
    pub fn try_acquire(&self) -> Option<EngineGuard> {
        self.0.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).ok().map(|_| EngineGuard(self.0.clone()))
    }

    pub fn is_locked(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

impl Drop for EngineGuard {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_a_second_holder_until_released() {
        let lock = EngineLock::default();
        let guard = lock.try_acquire().unwrap();
        assert!(lock.is_locked() && lock.try_acquire().is_none());
        drop(guard);
        assert!(!lock.is_locked() && lock.try_acquire().is_some());
    }
}
