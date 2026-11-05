//! KorvaTTS voice selection, mirroring `ui/voices.py`.

/// Languages KorvaTTS can speak, with their default voice.
const LANGUAGE_VOICES: [(&str, &str); 2] = [("vi", "khanh_vy"), ("en", "khanh_vy")];
/// Diarized speakers get distinct voices, alternating high/low pitch so neighbouring speakers
/// stay easy to tell apart.
const SPEAKER_VOICES: [&str; 10] = [
    "khanh_vy", "huu_dat", "phuong_linh", "hoang_nam", "ngoc_huyen", "quang_huy", "quynh_nhu", "gia_bao",
    "bao_kim", "thanh_phong",
];
/// Flow-matching steps used for dubbing; see the measurements in `ui/voices.py`.
pub const TOTAL_STEPS: usize = 8;
pub const SPEED: f32 = 1.05;

pub fn speakable(language: &str) -> bool {
    LANGUAGE_VOICES.iter().any(|(code, _)| *code == language)
}

pub fn speakable_languages() -> impl Iterator<Item = &'static str> {
    LANGUAGE_VOICES.iter().map(|(code, _)| *code)
}

/// Voice for a 1-based speaker label; 0 means unidentified and keeps the language default.
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
    fn matches_python_voice_rotation() {
        assert_eq!(voice_for("vi", 0), Some("khanh_vy"));
        assert_eq!(voice_for("vi", 2), Some("huu_dat"));
        assert_eq!(voice_for("en", 11), Some("khanh_vy"));
        assert_eq!(voice_for("fr", 0), None);
        assert!(speakable("en") && !speakable("fr"));
    }
}
