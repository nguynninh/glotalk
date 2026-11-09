//! Offline video translation into subtitles and dubbed speech.
//!
//! video -> [`fetch`] -> [`media`] 16 kHz audio -> [`recognize`] diarized segments, streamed
//!       -> [`translate`] per segment -> [`speech`] parallel TTS -> [`timeline`] dub track
//!       -> [`srt`] subtitles -> [`media`] mux -> optionally [`hls`] packaging
//!
//! [`pipeline`] overlaps the stages: each segment is translated and queued for speech as soon
//! as `onplay-speakers` prints it, while recognition continues.

pub mod error;
pub mod events;
pub mod fetch;
pub mod hls;
pub mod media;
pub mod pipeline;
pub mod project;
pub mod recognize;
pub mod segment;
pub mod speech;
pub mod srt;
pub mod timeline;
pub mod translate;
pub mod voices;
pub mod workdir;

mod command;

pub use error::{Error, Result};
pub use events::{Event, Observer, Silent};
pub use pipeline::{run, Options, Outputs};
pub use project::Project;
