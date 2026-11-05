//! Offline speaker diarization (Pyannote segmentation + embedding clustering).

use std::path::PathBuf;

use onplay_sherpa_sys as sys;

use crate::error::{Error, Result};
use crate::ffi_string;

#[derive(Debug, Clone, PartialEq)]
pub struct DiarizationConfig {
    pub segmentation: PathBuf,
    pub embedding: PathBuf,
    pub threads: i32,
    /// Negative: estimate the speaker count from `threshold`.
    pub num_clusters: i32,
    pub threshold: f32,
    pub min_duration_on: f32,
    pub min_duration_off: f32,
}

/// One speaker turn as returned by Sherpa, in seconds.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Turn {
    pub start: f32,
    pub end: f32,
    /// Cluster id; not contiguous or ordered by first appearance.
    pub speaker: i32,
}

pub struct Diarizer {
    raw: *const sys::SherpaOnnxOfflineSpeakerDiarization,
}

impl Diarizer {
    pub fn new(config: &DiarizationConfig) -> Result<Self> {
        let segmentation = ffi_string::path(&config.segmentation)?;
        let embedding = ffi_string::path(&config.embedding)?;
        let mut raw = sys::SherpaOnnxOfflineSpeakerDiarizationConfig::default();
        raw.segmentation.pyannote.model = segmentation.as_ptr();
        raw.segmentation.num_threads = config.threads;
        raw.segmentation.provider = ffi_string::literal(c"cpu");
        raw.embedding.model = embedding.as_ptr();
        raw.embedding.num_threads = config.threads;
        raw.embedding.provider = ffi_string::literal(c"cpu");
        raw.clustering.num_clusters = config.num_clusters;
        raw.clustering.threshold = config.threshold;
        raw.min_duration_on = config.min_duration_on;
        raw.min_duration_off = config.min_duration_off;
        // SAFETY: `raw` and the CStrings it points into are alive for the call and copied.
        let diarizer = unsafe { sys::SherpaOnnxCreateOfflineSpeakerDiarization(&raw) };
        if diarizer.is_null() {
            return Err(Error::Create("Cannot load speaker models"));
        }
        Ok(Self { raw: diarizer })
    }

    pub fn sample_rate(&self) -> i32 {
        // SAFETY: live diarizer.
        unsafe { sys::SherpaOnnxOfflineSpeakerDiarizationGetSampleRate(self.raw) }
    }

    /// Speaker turns sorted by start time.
    pub fn process(&self, samples: &[f32]) -> Result<Vec<Turn>> {
        let count = ffi_string::sample_count(samples)?;
        // SAFETY: live diarizer; `samples` is valid for `count` floats during the call.
        let result = unsafe { sys::SherpaOnnxOfflineSpeakerDiarizationProcess(self.raw, samples.as_ptr(), count) };
        if result.is_null() {
            return Err(Error::Create("Speaker diarization failed"));
        }
        let turns = sorted_turns(result);
        // SAFETY: created above, destroyed once after its segments were copied.
        unsafe { sys::SherpaOnnxOfflineSpeakerDiarizationDestroyResult(result) };
        turns
    }
}

fn sorted_turns(result: *const sys::SherpaOnnxOfflineSpeakerDiarizationResult) -> Result<Vec<Turn>> {
    // SAFETY: `result` is a live, non-NULL diarization result.
    let count = unsafe { sys::SherpaOnnxOfflineSpeakerDiarizationResultGetNumSegments(result) };
    let count = usize::try_from(count).unwrap_or(0);
    if count == 0 {
        return Ok(Vec::new());
    }
    // SAFETY: as above; returns `count` segments owned by the caller.
    let segments = unsafe { sys::SherpaOnnxOfflineSpeakerDiarizationResultSortByStartTime(result) };
    if segments.is_null() {
        return Err(Error::Create("Cannot read speaker segments"));
    }
    // SAFETY: non-NULL array of `count` segments, copied before it is destroyed once.
    let turns = unsafe { std::slice::from_raw_parts(segments, count) }
        .iter()
        .map(|segment| Turn { start: segment.start, end: segment.end, speaker: segment.speaker })
        .collect();
    // SAFETY: returned by SortByStartTime above.
    unsafe { sys::SherpaOnnxOfflineSpeakerDiarizationDestroySegment(segments) };
    Ok(turns)
}

impl Drop for Diarizer {
    fn drop(&mut self) {
        // SAFETY: owned, non-NULL, destroyed once.
        unsafe { sys::SherpaOnnxDestroyOfflineSpeakerDiarization(self.raw) };
    }
}
