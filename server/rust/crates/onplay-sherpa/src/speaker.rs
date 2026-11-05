//! Speaker embeddings and their nearest-match registry, used for live speaker labels.

use std::path::Path;

use onplay_sherpa_sys as sys;

use crate::error::{Error, Result};
use crate::ffi_string;
use crate::online::RawStream;
use crate::SAMPLE_RATE;

pub struct EmbeddingExtractor {
    raw: *const sys::SherpaOnnxSpeakerEmbeddingExtractor,
}

impl EmbeddingExtractor {
    pub fn new(model: &Path, threads: i32) -> Result<Self> {
        let model = ffi_string::path(model)?;
        let config = sys::SherpaOnnxSpeakerEmbeddingExtractorConfig {
            model: model.as_ptr(),
            num_threads: threads,
            provider: ffi_string::literal(c"cpu"),
            ..Default::default()
        };
        // SAFETY: `config` and `model` are alive for the call; Sherpa copies them.
        let raw = unsafe { sys::SherpaOnnxCreateSpeakerEmbeddingExtractor(&config) };
        if raw.is_null() {
            return Err(Error::Create("Cannot load speaker model"));
        }
        Ok(Self { raw })
    }

    pub fn dim(&self) -> i32 {
        // SAFETY: live extractor.
        unsafe { sys::SherpaOnnxSpeakerEmbeddingExtractorDim(self.raw) }
    }

    /// Embedding of a finished recording, or `None` when it is too short to be ready.
    pub fn embed(&self, samples: &[f32]) -> Result<Option<Vec<f32>>> {
        let count = ffi_string::sample_count(samples)?;
        // SAFETY: live extractor.
        let raw = unsafe { sys::SherpaOnnxSpeakerEmbeddingExtractorCreateStream(self.raw) };
        if raw.is_null() {
            return Err(Error::Create("Cannot create speaker stream"));
        }
        let stream = RawStream { raw };
        // SAFETY: live stream; `samples` is valid for `count` floats and copied by Sherpa.
        unsafe {
            sys::SherpaOnnxOnlineStreamAcceptWaveform(stream.raw, SAMPLE_RATE, samples.as_ptr(), count);
            sys::SherpaOnnxOnlineStreamInputFinished(stream.raw);
        }
        // SAFETY: live extractor and the stream it created.
        if unsafe { sys::SherpaOnnxSpeakerEmbeddingExtractorIsReady(self.raw, stream.raw) } == 0 {
            return Ok(None);
        }
        // SAFETY: as above; the returned buffer holds `dim()` floats owned by the caller.
        let embedding = unsafe { sys::SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(self.raw, stream.raw) };
        if embedding.is_null() {
            return Err(Error::Create("Cannot compute speaker embedding"));
        }
        let dim = usize::try_from(self.dim()).unwrap_or(0);
        // SAFETY: non-NULL buffer of `dim` floats, copied before it is destroyed once.
        let values = unsafe { std::slice::from_raw_parts(embedding, dim) }.to_vec();
        // SAFETY: returned by ComputeEmbedding above.
        unsafe { sys::SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding(embedding) };
        Ok(Some(values))
    }
}

impl Drop for EmbeddingExtractor {
    fn drop(&mut self) {
        // SAFETY: owned, non-NULL, destroyed once; streams never outlive `embed`.
        unsafe { sys::SherpaOnnxDestroySpeakerEmbeddingExtractor(self.raw) };
    }
}

/// Registered speakers searched by cosine similarity.
pub struct EmbeddingManager {
    raw: *const sys::SherpaOnnxSpeakerEmbeddingManager,
    dim: usize,
}

impl EmbeddingManager {
    pub fn new(dim: i32) -> Result<Self> {
        // SAFETY: plain value argument.
        let raw = unsafe { sys::SherpaOnnxCreateSpeakerEmbeddingManager(dim) };
        if raw.is_null() {
            return Err(Error::Create("Cannot create speaker manager"));
        }
        Ok(Self { raw, dim: usize::try_from(dim).unwrap_or(0) })
    }

    /// Name of the best match above `threshold`.
    pub fn search(&self, embedding: &[f32], threshold: f32) -> Option<String> {
        if embedding.len() != self.dim {
            return None;
        }
        // SAFETY: live manager; `embedding` holds `dim` floats.
        let name = unsafe { sys::SherpaOnnxSpeakerEmbeddingManagerSearch(self.raw, embedding.as_ptr(), threshold) };
        if name.is_null() {
            return None;
        }
        // SAFETY: non-NULL NUL-terminated string owned by the caller until freed once.
        let text = unsafe { ffi_string::copy(name) };
        // SAFETY: returned by Search above.
        unsafe { sys::SherpaOnnxSpeakerEmbeddingManagerFreeSearch(name) };
        Some(text)
    }

    pub fn add(&mut self, name: &str, embedding: &[f32]) -> Result<bool> {
        if embedding.len() != self.dim {
            return Ok(false);
        }
        let name = ffi_string::text(name)?;
        // SAFETY: live manager; `name` and `embedding` are valid for the call and copied.
        Ok(unsafe { sys::SherpaOnnxSpeakerEmbeddingManagerAdd(self.raw, name.as_ptr(), embedding.as_ptr()) } != 0)
    }
}

impl Drop for EmbeddingManager {
    fn drop(&mut self) {
        // SAFETY: owned, non-NULL, destroyed once.
        unsafe { sys::SherpaOnnxDestroySpeakerEmbeddingManager(self.raw) };
    }
}
