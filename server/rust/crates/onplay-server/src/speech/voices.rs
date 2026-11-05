//! KorvaTTS voice selection, as in `onplay-dub` (`voices.rs`).

/// Languages KorvaTTS can speak, with their default voice.
const LANGUAGE_VOICES: [(&str, &str); 2] = [("vi", "khanh_vy"), ("en", "khanh_vy")];
/// Live diarized speakers get distinct voices, alternating high/low pitch (measured median
/// F0 ~230-295 Hz vs ~90-125 Hz) so neighbouring speakers stay easy to tell apart.
const SPEAKER_VOICES: [&str; 10] = [
    "khanh_vy", "huu_dat", "phuong_linh", "hoang_nam", "ngoc_huyen", "quang_huy", "quynh_nhu", "gia_bao",
    "bao_kim", "thanh_phong",
];
/// 8 steps: ASR round-trip WER 4.4% vi / 3.1% en at RTF 0.22, versus 6.9% / 0% at
/// RTF 0.63-0.86 for 16 steps, so live speech keeps up with continuous talk.
pub const TOTAL_STEPS: usize = 8;

pub fn speakable(language: &str) -> bool {
    LANGUAGE_VOICES.iter().any(|(code, _)| *code == language)
}

/// Voice for a 1-based speaker label; 0 means not yet identified and keeps the language
/// default.
pub fn voice_for(language: &str, speaker: u32) -> Option<&'static str> {
    if speaker > 0 {
        return Some(SPEAKER_VOICES[(speaker as usize - 1) % SPEAKER_VOICES.len()]);
    }
    LANGUAGE_VOICES.iter().find(|(code, _)| *code == language).map(|(_, voice)| *voice)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rotates_speaker_voices() {
        assert_eq!(voice_for("vi", 0), Some("khanh_vy"));
        assert_eq!(voice_for("en", 2), Some("huu_dat"));
        assert_eq!(voice_for("vi", 11), Some("khanh_vy"));
        assert_eq!(voice_for("fr", 0), None);
        assert!(speakable("en") && !speakable("fr"));
    }
}
