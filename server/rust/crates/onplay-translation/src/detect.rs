//! Source language detection with Lingua, limited to the router's languages.

use lingua::{Language, LanguageDetector, LanguageDetectorBuilder};

const LANGUAGES: [(Language, &str); 12] = [
    (Language::English, "en"),
    (Language::Vietnamese, "vi"),
    (Language::Chinese, "zh"),
    (Language::Japanese, "ja"),
    (Language::Korean, "ko"),
    (Language::Thai, "th"),
    (Language::Indonesian, "id"),
    (Language::Russian, "ru"),
    (Language::Arabic, "ar"),
    (Language::French, "fr"),
    (Language::German, "de"),
    (Language::Spanish, "es"),
];

pub(crate) struct Detector(LanguageDetector);

impl Detector {
    pub fn new() -> Self {
        let languages = LANGUAGES.map(|(language, _)| language);
        Self(LanguageDetectorBuilder::from_languages(&languages).with_preloaded_language_models().build())
    }

    /// The ISO 639-1 code of `text`'s language, or `None` when Lingua cannot decide.
    pub fn detect(&self, text: &str) -> Option<&'static str> {
        let detected = self.0.detect_language_of(text)?;
        LANGUAGES.iter().find(|(language, _)| *language == detected).map(|(_, code)| *code)
    }
}
