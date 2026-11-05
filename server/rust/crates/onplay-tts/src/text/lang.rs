use crate::error::{Error, Result};

/// Language tags accepted by the text encoder (`AVAILABLE_LANGS`); "na" is language-agnostic.
const LANGUAGES: [&str; 32] = [
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
    "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
];

/// A validated language tag.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Language(&'static str);

impl Language {
    pub fn parse(tag: &str) -> Result<Self> {
        LANGUAGES
            .iter()
            .find(|known| **known == tag)
            .map(|known| Self(known))
            .ok_or_else(|| Error::InvalidArgument(format!("unsupported language '{tag}'")))
    }

    pub fn tag(self) -> &'static str {
        self.0
    }
}

/// Maximum chunk length in code points; Korean and Japanese are denser per character.
pub fn chunk_limit(language: Language) -> usize {
    if matches!(language.tag(), "ko" | "ja") {
        120
    } else {
        300
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_known_tags_only() {
        assert_eq!(Language::parse("vi").unwrap().tag(), "vi");
        assert!(Language::parse("zh").is_err());
        assert_eq!(chunk_limit(Language::parse("ja").unwrap()), 120);
    }
}
