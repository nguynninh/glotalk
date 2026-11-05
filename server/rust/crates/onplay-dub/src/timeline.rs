//! Laying speech clips onto one dub track.

use crate::segment::Segment;
use crate::speech::Clip;

/// The dub track and the intervals where dubbed speech plays.
pub struct Track {
    pub samples: Vec<f32>,
    pub spans: Vec<(f64, f64)>,
}

/// Places clips in segment order at their segment start; a clip that still overruns its slot
/// delays the next one instead of overlapping it.
pub fn place(segments: &[Segment], clips: &[Option<Clip>], duration: f64, rate: u32) -> Track {
    let rate_f = f64::from(rate);
    let mut samples = vec![0.0f32; ((duration + 5.0) * rate_f) as usize];
    let mut spans = Vec::new();
    let mut cursor = 0.0f64;
    for (segment, clip) in segments.iter().zip(clips) {
        let Some(clip) = clip else { continue };
        let start = segment.start.max(cursor);
        let first = (start * rate_f) as usize;
        if first + clip.len() > samples.len() {
            samples.resize(first + clip.len(), 0.0);
        }
        for (out, value) in samples[first..first + clip.len()].iter_mut().zip(clip) {
            *out += value;
        }
        cursor = start + clip.len() as f64 / rate_f;
        spans.push((start, cursor));
    }
    Track { samples, spans }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn segment(start: f64) -> Segment {
        Segment { start, end: start + 1.0, speaker: 1, text: String::new(), translation: String::new(), dub: true }
    }

    #[test]
    fn delays_overrunning_clips_and_skips_missing() {
        let segments = [segment(0.0), segment(1.0), segment(5.0)];
        let clips = [Some(vec![1.0; 15]), None, Some(vec![0.5; 5])];
        let track = place(&segments, &clips, 6.0, 10);
        assert_eq!(track.spans, [(0.0, 1.5), (5.0, 5.5)]);
        assert_eq!(track.samples.len(), 110);
        assert_eq!((track.samples[14], track.samples[15], track.samples[50]), (1.0, 0.0, 0.5));
    }

    #[test]
    fn pushes_clip_after_previous_overrun() {
        let segments = [segment(0.0), segment(1.0)];
        let clips = [Some(vec![1.0; 20]), Some(vec![1.0; 10])];
        assert_eq!(place(&segments, &clips, 2.0, 10).spans, [(0.0, 2.0), (2.0, 3.0)]);
    }
}
