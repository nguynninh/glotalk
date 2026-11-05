//! Bounded FIFO with Python `queue.Queue` semantics: `unfinished` counts items until
//! their consumer calls `task_done`, so the session can wait for in-flight work.

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex, MutexGuard};
use std::time::Duration;

pub struct WorkQueue<T> {
    state: Mutex<State<T>>,
    ready: Condvar,
    capacity: usize,
}

struct State<T> {
    items: VecDeque<T>,
    unfinished: usize,
}

impl<T> WorkQueue<T> {
    pub fn new(capacity: usize) -> Self {
        Self { state: Mutex::new(State { items: VecDeque::new(), unfinished: 0 }), ready: Condvar::new(), capacity }
    }

    /// Never blocks; gives the item back when full.
    pub fn put(&self, item: T) -> Result<(), T> {
        let mut state = self.lock();
        if state.items.len() >= self.capacity {
            return Err(item);
        }
        state.items.push_back(item);
        state.unfinished += 1;
        self.ready.notify_one();
        Ok(())
    }

    /// Waits up to `timeout` for the oldest item.
    pub fn get(&self, timeout: Duration) -> Option<T> {
        let state = self.lock();
        let (mut state, _) = self.ready.wait_timeout_while(state, timeout, |state| state.items.is_empty()).unwrap_or_else(|poisoned| poisoned.into_inner());
        state.items.pop_front()
    }

    /// Removes the oldest waiting item as if it had been taken and finished.
    pub fn drop_oldest(&self) -> Option<T> {
        let mut state = self.lock();
        let item = state.items.pop_front()?;
        state.unfinished -= 1;
        Some(item)
    }

    pub fn task_done(&self) {
        let mut state = self.lock();
        state.unfinished = state.unfinished.saturating_sub(1);
    }

    /// Items queued or taken but not yet finished.
    pub fn unfinished(&self) -> usize {
        self.lock().unfinished
    }

    fn lock(&self) -> MutexGuard<'_, State<T>> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tracks_unfinished_work_and_capacity() {
        let queue = WorkQueue::new(2);
        assert!(queue.put(1).is_ok() && queue.put(2).is_ok());
        assert_eq!(queue.put(3), Err(3));
        assert_eq!(queue.drop_oldest(), Some(1));
        assert_eq!(queue.get(Duration::ZERO), Some(2));
        assert_eq!(queue.unfinished(), 1);
        queue.task_done();
        assert_eq!(queue.unfinished(), 0);
        assert_eq!(queue.get(Duration::from_millis(1)), None);
    }
}
