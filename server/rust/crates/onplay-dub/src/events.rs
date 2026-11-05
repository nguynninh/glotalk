//! Pipeline events for whoever wants them. The library never logs or formats on its own.

/// Something the pipeline just did. Indices are segment positions in output order.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Event {
    Downloading,
    /// Recognition of the piece starting at `offset` seconds began.
    Recognizing { offset: f64, duration: f64 },
    Recognized { index: usize },
    Translated { index: usize },
    /// Recognition and translation are complete; speech may still be running.
    Transcribed { segments: usize },
    Spoken { index: usize },
    Muxing,
}

/// Receives events, possibly from several speech threads at once.
pub trait Observer: Sync {
    fn event(&self, event: Event);
}

/// Ignores every event.
pub struct Silent;

impl Observer for Silent {
    fn event(&self, _: Event) {}
}

impl<F: Fn(Event) + Sync> Observer for F {
    fn event(&self, event: Event) {
        self(event);
    }
}
