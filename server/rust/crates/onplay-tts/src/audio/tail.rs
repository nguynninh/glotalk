//! `clean_tail`, mirroring `korvatts/audio_post.py`.
//!
//! Trimming at the predicted duration can leave an abrupt cut or a detached vocoder noise
//! burst after the final pause. Remove the burst, if any, and fade the tail to zero.

const FRAME_S: f64 = 0.02;
const SEARCH_S: f64 = 0.6;
const GAP_RMS: f64 = 0.01;
const MIN_GAP_S: f64 = 0.05;
const MAX_BURST_S: f64 = 0.35;
const BURST_PEAK: f32 = 0.4;
const SILENT_PEAK: f32 = 0.05;
const KEEP_PAUSE_S: f64 = 0.05;
const FADE_S: f64 = 0.03;

pub fn clean_tail(mut wav: Vec<f32>, sample_rate: u32) -> Vec<f32> {
    let rate = f64::from(sample_rate);
    if let Some(cut) = detached_burst_start(&wav, rate) {
        wav.truncate(cut + seconds(KEEP_PAUSE_S, rate));
    }
    fade_out(&mut wav, seconds(FADE_S, rate));
    wav
}

fn seconds(duration: f64, rate: f64) -> usize {
    (duration * rate) as usize
}

/// Start of the trailing pause that separates speech from a short loud burst.
fn detached_burst_start(wav: &[f32], rate: f64) -> Option<usize> {
    let frame = seconds(FRAME_S, rate);
    let min_gap = MIN_GAP_S * rate;
    let start = wav.len().saturating_sub(seconds(SEARCH_S, rate));
    let (mut gap_start, mut run, mut best) = (0, 0, None);
    // Python: range(start, len(wav) - frame, frame); the last partial frame is skipped.
    for i in (start..wav.len().saturating_sub(frame)).step_by(frame.max(1)) {
        let block = &wav[i..i + frame];
        let rms = (block.iter().map(|v| f64::from(*v).powi(2)).sum::<f64>() / frame as f64).sqrt();
        if rms < GAP_RMS {
            if run == 0 {
                gap_start = i;
            }
            run += frame;
        } else {
            if run as f64 >= min_gap {
                best = Some(gap_start);
            }
            run = 0;
        }
    }
    if run as f64 >= min_gap {
        best = Some(gap_start);
    }
    let best = best?;
    let after = wav.get(best + seconds(MIN_GAP_S, rate)..).unwrap_or_default();
    let peak = after.iter().fold(0.0f32, |peak, v| peak.max(v.abs()));
    if peak < SILENT_PEAK {
        return Some(best); // only silence remains: trim it
    }
    // A burst is short and abnormally loud; anything long or moderate is real speech.
    (after.len() <= seconds(MAX_BURST_S, rate) && peak >= BURST_PEAK).then_some(best)
}

/// Linear fade to zero over the last `n` samples, like `np.linspace(1, 0, n)`.
fn fade_out(wav: &mut [f32], n: usize) {
    let n = n.min(wav.len());
    let offset = wav.len() - n;
    for (k, value) in wav[offset..].iter_mut().enumerate() {
        let gain = if n > 1 { 1.0 - k as f64 / (n - 1) as f64 } else { 1.0 };
        *value *= gain as f32;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: u32 = 1000;

    #[test]
    fn removes_detached_burst() {
        let mut wav = vec![0.2f32; 600];
        wav.extend(vec![0.0; 100]);
        wav.extend(vec![0.9; 100]);
        let cleaned = clean_tail(wav, RATE);
        assert_eq!(cleaned.len(), 600 + 50);
        assert_eq!(*cleaned.last().unwrap(), 0.0);
    }

    #[test]
    fn keeps_trailing_speech() {
        let mut wav = vec![0.2f32; 600];
        wav.extend(vec![0.0; 100]);
        wav.extend(vec![0.2; 400]);
        assert_eq!(clean_tail(wav, RATE).len(), 1100);
    }
}
