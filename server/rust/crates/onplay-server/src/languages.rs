//! Languages the translation router accepts.

pub const TARGETS: [&str; 12] = ["vi", "en", "zh", "ja", "ko", "th", "id", "ru", "ar", "fr", "de", "es"];

pub fn is_target(code: &str) -> bool {
    TARGETS.contains(&code)
}

/// A target language or `auto` (router-side detection).
pub fn is_source(code: &str) -> bool {
    code == "auto" || is_target(code)
}
