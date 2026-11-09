//! Đặc trưng log-mel (fbank) kiểu Kaldi, đầu vào của model CAM++ (3D-Speaker).
//!
//! Cùng tham số sherpa-onnx dùng cho model này: khung 25 ms, bước 10 ms, `snip_edges`, không dither,
//! bỏ DC, pre-emphasis 0.97, cửa sổ Povey, FFT 512, 80 dải mel 20 Hz–8 kHz, rồi trừ trung bình theo
//! thời gian. Phép trừ trung bình làm thang biên độ mẫu (±1 hay ±32768) không còn ảnh hưởng.
use rustfft::num_complex::Complex32;
use rustfft::FftPlanner;
use std::sync::Arc;

pub const BINS: usize = 80;
const RATE: f32 = 16_000.0;
const FRAME: usize = 400;
const SHIFT: usize = 160;
const FFT: usize = 512;
const PREEMPH: f32 = 0.97;

pub struct Fbank {
    fft: Arc<dyn rustfft::Fft<f32>>,
    window: Vec<f32>,
    /// Mỗi dải mel: (bin FFT đầu tiên, trọng số các bin liên tiếp).
    filters: Vec<(usize, Vec<f32>)>,
}

fn mel(hz: f32) -> f32 {
    1127.0 * (1.0 + hz / 700.0).ln()
}

impl Default for Fbank {
    fn default() -> Self {
        Self::new()
    }
}

impl Fbank {
    pub fn new() -> Self {
        let fft = FftPlanner::new().plan_fft_forward(FFT);
        let window = (0..FRAME)
            .map(|i| (0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / (FRAME - 1) as f32).cos()).powf(0.85))
            .collect();
        let (lo, hi) = (mel(20.0), mel(RATE / 2.0));
        let delta = (hi - lo) / (BINS + 1) as f32;
        let filters = (0..BINS)
            .map(|b| {
                let (left, center, right) = (lo + b as f32 * delta, lo + (b + 1) as f32 * delta, lo + (b + 2) as f32 * delta);
                let weights: Vec<(usize, f32)> = (0..FFT / 2)
                    .filter_map(|k| {
                        let m = mel(k as f32 * RATE / FFT as f32);
                        let w = if m > left && m <= center {
                            (m - left) / (center - left)
                        } else if m > center && m < right {
                            (right - m) / (right - center)
                        } else {
                            return None;
                        };
                        Some((k, w))
                    })
                    .collect();
                let first = weights.first().map_or(0, |w| w.0);
                (first, weights.into_iter().map(|w| w.1).collect())
            })
            .collect();
        Self { fft, window, filters }
    }

    /// Số khung của `n` mẫu (`snip_edges`: bỏ phần cuối không đủ một khung).
    pub fn frames(n: usize) -> usize {
        if n < FRAME { 0 } else { 1 + (n - FRAME) / SHIFT }
    }

    /// Ma trận `frames × BINS` (theo hàng), đã trừ trung bình từng dải.
    pub fn compute(&self, samples: &[f32]) -> Vec<f32> {
        let frames = Self::frames(samples.len());
        let mut out = vec![0.0f32; frames * BINS];
        let mut buf = vec![Complex32::default(); FFT];
        let mut frame = [0.0f32; FRAME];
        for f in 0..frames {
            frame.copy_from_slice(&samples[f * SHIFT..f * SHIFT + FRAME]);
            let mean = frame.iter().sum::<f32>() / FRAME as f32;
            frame.iter_mut().for_each(|x| *x -= mean);
            for i in (1..FRAME).rev() {
                frame[i] -= PREEMPH * frame[i - 1];
            }
            frame[0] -= PREEMPH * frame[0];
            for (i, c) in buf.iter_mut().enumerate() {
                *c = Complex32::new(if i < FRAME { frame[i] * self.window[i] } else { 0.0 }, 0.0);
            }
            self.fft.process(&mut buf);
            let row = &mut out[f * BINS..(f + 1) * BINS];
            for (b, (first, weights)) in self.filters.iter().enumerate() {
                let energy: f32 = weights.iter().enumerate().map(|(i, w)| w * buf[first + i].norm_sqr()).sum();
                row[b] = energy.max(f32::EPSILON).ln();
            }
        }
        if frames > 0 {
            for b in 0..BINS {
                let mean = (0..frames).map(|f| out[f * BINS + b]).sum::<f32>() / frames as f32;
                (0..frames).for_each(|f| out[f * BINS + b] -= mean);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_and_mean_normalized() {
        assert_eq!(Fbank::frames(399), 0);
        assert_eq!(Fbank::frames(16_000), 98);
        let samples: Vec<f32> = (0..16_000).map(|i| (2.0 * std::f32::consts::PI * 440.0 * i as f32 / RATE).sin() * 0.5).collect();
        let fb = Fbank::new();
        let feats = fb.compute(&samples);
        assert_eq!(feats.len(), 98 * BINS);
        let col_mean = (0..98).map(|f| feats[f * BINS + 10]).sum::<f32>() / 98.0;
        assert!(col_mean.abs() < 1e-3);
        assert!(fb.filters.iter().all(|(_, w)| !w.is_empty()), "dải mel rỗng");
    }
}
