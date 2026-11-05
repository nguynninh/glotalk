//! Source of the Gaussian noise the flow-matching loop starts from.

use rand::rngs::StdRng;
use rand::SeedableRng;
use rand_distr::{Distribution, StandardNormal};

/// Fills the initial latent. A trait so callers can replay recorded noise for
/// reproducibility checks against the Python implementation.
pub trait NoiseSource {
    fn fill(&mut self, out: &mut [f32]);
}

/// Standard normal noise, optionally seeded. It does not reproduce numpy's generator.
pub struct GaussianNoise {
    rng: StdRng,
}

impl GaussianNoise {
    pub fn new(seed: Option<u64>) -> Self {
        let rng = match seed {
            Some(seed) => StdRng::seed_from_u64(seed),
            None => StdRng::from_entropy(),
        };
        Self { rng }
    }
}

impl NoiseSource for GaussianNoise {
    fn fill(&mut self, out: &mut [f32]) {
        for value in out {
            *value = StandardNormal.sample(&mut self.rng);
        }
    }
}
