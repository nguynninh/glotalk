//! Waveform post-processing and output.

mod tail;
mod wav;

pub use tail::clean_tail;
pub use wav::{encode_pcm16_wav, write_pcm16_wav};
