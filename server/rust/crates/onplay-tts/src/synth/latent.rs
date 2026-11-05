//! Masks and the initial noisy latent, mirroring `_sample_noisy_latent` and `length_to_mask`.

use super::noise::NoiseSource;
use crate::assets::ModelConfig;
use crate::tensor::Latent;

/// A (1, 1, len) mask of ones.
pub(super) fn full_mask(len: usize) -> Vec<f32> {
    vec![1.0; len]
}

/// Noise shaped for `duration` seconds: one latent frame per `samples_per_latent` samples.
pub(super) fn noisy_latent(config: &ModelConfig, duration: f32, noise: &mut dyn NoiseSource) -> Latent {
    // Python: (duration * sample_rate).astype(int64) on a float32 array.
    let samples = (duration * config.sample_rate as f32) as i64;
    let chunk = config.samples_per_latent() as i64;
    let frames = usize::try_from((samples + chunk - 1).div_euclid(chunk)).unwrap_or(0);
    let channels = config.compressed_latent_dim();
    let mut data = vec![0.0; channels * frames];
    noise.fill(&mut data);
    // A single utterance fills its own mask, so masking keeps every value.
    Latent { channels, frames, data, mask: full_mask(frames) }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Ones;
    impl NoiseSource for Ones {
        fn fill(&mut self, out: &mut [f32]) {
            out.fill(1.0);
        }
    }

    #[test]
    fn rounds_frames_up() {
        let config = ModelConfig { sample_rate: 44100, base_chunk_size: 512, chunk_compress_factor: 6, latent_dim: 24 };
        let latent = noisy_latent(&config, 1.0, &mut Ones);
        assert_eq!((latent.channels, latent.frames), (144, 15)); // ceil(44100 / 3072)
        assert_eq!(latent.data.len(), 144 * 15);
    }
}
