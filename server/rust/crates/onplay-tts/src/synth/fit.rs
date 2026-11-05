//! Speed selection that fits speech into a time budget before any audio is generated.

/// Speed at which chunks with `raw` predicted durations (seconds at speed 1) plus `gaps`
/// seconds of inter-chunk silence fit in `max_duration`. Never slower than `speed`, never
/// faster than `max_speed`; the result may still overrun when even `max_speed` is too slow.
pub(super) fn fitted_speed(raw: &[f32], gaps: f32, speed: f32, max_duration: Option<f32>, max_speed: f32) -> f32 {
    let Some(max_duration) = max_duration else {
        return speed;
    };
    let speech: f32 = raw.iter().sum();
    let budget = max_duration - gaps;
    if budget <= 0.0 {
        return max_speed;
    }
    (speech / budget).clamp(speed, max_speed.max(speed))
}

/// A predicted duration (seconds at speed 1) is a runaway above twice this many seconds.
const PLAUSIBLE_BASE_S: f32 = 0.6;
const PLAUSIBLE_PER_CHAR_S: f32 = 0.1;

/// KorvaTTS predicts 4.5-7 s for short English text ("Yes." 7.2 s, "Hello." 6.9 s, same
/// in Python), and fills it with invented speech. Such runaways fall back to the plausible
/// duration; ordinary predictions, including all Vietnamese ones measured, stay untouched.
pub(super) fn runaway_capped(raw: f32, chars: usize) -> f32 {
    let plausible = PLAUSIBLE_BASE_S + PLAUSIBLE_PER_CHAR_S * chars as f32;
    if raw > 2.0 * plausible {
        plausible
    } else {
        raw
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_speed_when_it_fits() {
        assert_eq!(fitted_speed(&[2.0], 0.0, 1.05, Some(3.0), 2.0), 1.05);
        assert_eq!(fitted_speed(&[9.0], 0.0, 1.05, None, 2.0), 1.05);
    }

    #[test]
    fn caps_only_runaway_durations() {
        assert!((runaway_capped(6.9, 6) - 1.2).abs() < 1e-6);
        assert_eq!(runaway_capped(3.7, 28), 3.7);
        assert_eq!(runaway_capped(1.5, 9), 1.5);
    }

    #[test]
    fn speeds_up_to_fit_and_caps() {
        assert!((fitted_speed(&[3.0, 3.0], 0.3, 1.05, Some(4.3), 2.0) - 1.5).abs() < 1e-6);
        assert_eq!(fitted_speed(&[10.0], 0.0, 1.05, Some(1.0), 2.0), 2.0);
        assert_eq!(fitted_speed(&[1.0], 0.5, 1.05, Some(0.4), 2.0), 2.0);
    }
}
