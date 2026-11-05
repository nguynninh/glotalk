//! Streaming transducer recognition (`SherpaOnnxOnlineRecognizer`, `SherpaOnnxOnlineStream`).

use std::path::PathBuf;

use onplay_sherpa_sys as sys;

use crate::error::{Error, Result};
use crate::ffi_string;
use crate::SAMPLE_RATE;

/// Endpoint rules; `None` in [`RecognizerConfig::endpoint`] disables endpointing.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Endpoint {
    pub rule1_min_trailing_silence: f32,
    pub rule2_min_trailing_silence: f32,
    pub rule3_min_utterance_length: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RecognizerConfig {
    pub encoder: PathBuf,
    pub decoder: PathBuf,
    pub joiner: PathBuf,
    pub tokens: PathBuf,
    pub threads: i32,
    pub endpoint: Option<Endpoint>,
}

/// A loaded transducer model with greedy search on the CPU, 80-dim features at 16 kHz.
pub struct Recognizer {
    raw: *const sys::SherpaOnnxOnlineRecognizer,
}

impl Recognizer {
    pub fn new(config: &RecognizerConfig) -> Result<Self> {
        let encoder = ffi_string::path(&config.encoder)?;
        let decoder = ffi_string::path(&config.decoder)?;
        let joiner = ffi_string::path(&config.joiner)?;
        let tokens = ffi_string::path(&config.tokens)?;
        // Zeroed like `SherpaOnnxOnlineRecognizerConfig c{}` in C++; unset fields keep defaults.
        let mut raw = sys::SherpaOnnxOnlineRecognizerConfig::default();
        raw.feat_config.sample_rate = SAMPLE_RATE;
        raw.feat_config.feature_dim = 80;
        raw.model_config.transducer.encoder = encoder.as_ptr();
        raw.model_config.transducer.decoder = decoder.as_ptr();
        raw.model_config.transducer.joiner = joiner.as_ptr();
        raw.model_config.tokens = tokens.as_ptr();
        raw.model_config.num_threads = config.threads;
        raw.model_config.provider = ffi_string::literal(c"cpu");
        raw.decoding_method = ffi_string::literal(c"greedy_search");
        if let Some(endpoint) = config.endpoint {
            raw.enable_endpoint = 1;
            raw.rule1_min_trailing_silence = endpoint.rule1_min_trailing_silence;
            raw.rule2_min_trailing_silence = endpoint.rule2_min_trailing_silence;
            raw.rule3_min_utterance_length = endpoint.rule3_min_utterance_length;
        }
        // SAFETY: `raw` and the CStrings it points into are alive for the call; Sherpa copies
        // the configuration before returning.
        let recognizer = unsafe { sys::SherpaOnnxCreateOnlineRecognizer(&raw) };
        if recognizer.is_null() {
            return Err(Error::Create("Cannot initialize streaming model"));
        }
        Ok(Self { raw: recognizer })
    }

    pub fn create_stream(&self) -> Result<Stream<'_>> {
        // SAFETY: `self.raw` is a live recognizer.
        let stream = unsafe { sys::SherpaOnnxCreateOnlineStream(self.raw) };
        if stream.is_null() {
            return Err(Error::Create("Cannot create streaming session"));
        }
        Ok(Stream { raw: stream, recognizer: self })
    }
}

impl Drop for Recognizer {
    fn drop(&mut self) {
        // SAFETY: owned, non-NULL, destroyed once; streams borrow `self` so none outlive it.
        unsafe { sys::SherpaOnnxDestroyOnlineRecognizer(self.raw) };
    }
}

/// One recognition session on a [`Recognizer`].
pub struct Stream<'r> {
    raw: *const sys::SherpaOnnxOnlineStream,
    recognizer: &'r Recognizer,
}

impl Stream<'_> {
    pub fn accept_waveform(&mut self, samples: &[f32]) -> Result<()> {
        let count = ffi_string::sample_count(samples)?;
        // SAFETY: live stream; `samples` is valid for `count` floats and copied by Sherpa.
        unsafe { sys::SherpaOnnxOnlineStreamAcceptWaveform(self.raw, SAMPLE_RATE, samples.as_ptr(), count) };
        Ok(())
    }

    pub fn input_finished(&mut self) {
        // SAFETY: live stream.
        unsafe { sys::SherpaOnnxOnlineStreamInputFinished(self.raw) };
    }

    /// Decodes every frame that is ready.
    pub fn decode(&mut self) {
        // SAFETY: live stream and the recognizer that created it.
        while unsafe { sys::SherpaOnnxIsOnlineStreamReady(self.recognizer.raw, self.raw) } != 0 {
            // SAFETY: as above.
            unsafe { sys::SherpaOnnxDecodeOnlineStream(self.recognizer.raw, self.raw) };
        }
    }

    /// Text recognized so far in the current utterance.
    pub fn text(&self) -> Result<String> {
        // SAFETY: live stream and its recognizer.
        let result = unsafe { sys::SherpaOnnxGetOnlineStreamResult(self.recognizer.raw, self.raw) };
        if result.is_null() {
            return Err(Error::Create("Cannot read recognition result"));
        }
        // SAFETY: `result` is non-NULL and owned here until destroyed below; `text` is NULL or
        // a NUL-terminated string inside it.
        let text = unsafe { ffi_string::copy((*result).text) };
        // SAFETY: created above, destroyed once.
        unsafe { sys::SherpaOnnxDestroyOnlineRecognizerResult(result) };
        Ok(text)
    }

    pub fn is_endpoint(&self) -> bool {
        // SAFETY: live stream and its recognizer.
        unsafe { sys::SherpaOnnxOnlineStreamIsEndpoint(self.recognizer.raw, self.raw) != 0 }
    }

    /// Starts a new utterance after an endpoint.
    pub fn reset(&mut self) {
        // SAFETY: live stream and its recognizer.
        unsafe { sys::SherpaOnnxOnlineStreamReset(self.recognizer.raw, self.raw) };
    }
}

impl Drop for Stream<'_> {
    fn drop(&mut self) {
        // SAFETY: owned, non-NULL, destroyed once while the recognizer is still borrowed.
        unsafe { sys::SherpaOnnxDestroyOnlineStream(self.raw) };
    }
}

/// A stream handle created by another object (the speaker extractor), destroyed on drop.
/// Callers keep it inside a scope that also borrows its creator.
pub(crate) struct RawStream {
    pub(crate) raw: *const sys::SherpaOnnxOnlineStream,
}

impl Drop for RawStream {
    fn drop(&mut self) {
        // SAFETY: owned, non-NULL, destroyed once while its creator is still borrowed.
        unsafe { sys::SherpaOnnxDestroyOnlineStream(self.raw) };
    }
}
