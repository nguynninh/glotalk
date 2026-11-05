//! Replays recorded noise so output can be compared sample by sample with Python KorvaTTS.

use std::path::Path;

use onplay_tts::NoiseSource;

pub struct NoiseFile {
    values: Vec<f32>,
    position: usize,
}

impl NoiseFile {
    /// Reads raw float32 little-endian values.
    pub fn open(path: &Path) -> Result<Self, String> {
        let bytes = std::fs::read(path).map_err(|error| format!("{}: {error}", path.display()))?;
        if bytes.len() % 4 != 0 {
            return Err(format!("{}: length is not a multiple of 4", path.display()));
        }
        let values = bytes.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
        Ok(Self { values, position: 0 })
    }

    pub fn remaining(&self) -> usize {
        self.values.len() - self.position
    }
}

impl NoiseSource for NoiseFile {
    /// Panics when the file holds fewer values than the pipeline requests, because silently
    /// padding would break the reproducibility the file exists for.
    fn fill(&mut self, out: &mut [f32]) {
        let end = self.position + out.len();
        assert!(end <= self.values.len(), "noise file has {} values, {} needed", self.remaining(), out.len());
        out.copy_from_slice(&self.values[self.position..end]);
        self.position = end;
    }
}
