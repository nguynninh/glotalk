//! Benchmark events for whoever wants them. The library never logs or formats on its own.

use std::time::Duration;

/// Something the benchmark just did. `run` counts from 1.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Event {
    Starting { run: usize },
    Ready { run: usize, startup: Duration },
    Translated { run: usize, done: usize, total: usize },
    Stopped { run: usize },
}

pub trait Observer {
    fn event(&self, event: Event);
}

/// Ignores every event.
pub struct Silent;

impl Observer for Silent {
    fn event(&self, _: Event) {}
}

impl<F: Fn(Event)> Observer for F {
    fn event(&self, event: Event) {
        self(event);
    }
}
