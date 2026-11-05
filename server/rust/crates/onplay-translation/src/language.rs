//! Languages the router accepts (`LANGUAGES` in the Python router).

pub(crate) struct Language {
    /// ISO 639-1 code used by the HTTP API and route names.
    pub code: &'static str,
    /// NLLB language token.
    pub nllb: &'static str,
    /// English name used in the Qwen prompt.
    pub name: &'static str,
}

const fn language(code: &'static str, nllb: &'static str, name: &'static str) -> Language {
    Language { code, nllb, name }
}

pub(crate) const LANGUAGES: [Language; 12] = [
    language("en", "eng_Latn", "English"),
    language("vi", "vie_Latn", "Vietnamese"),
    language("zh", "zho_Hans", "Chinese"),
    language("ja", "jpn_Jpan", "Japanese"),
    language("ko", "kor_Hang", "Korean"),
    language("th", "tha_Thai", "Thai"),
    language("id", "ind_Latn", "Indonesian"),
    language("ru", "rus_Cyrl", "Russian"),
    language("ar", "arb_Arab", "Arabic"),
    language("fr", "fra_Latn", "French"),
    language("de", "deu_Latn", "German"),
    language("es", "spa_Latn", "Spanish"),
];

pub(crate) fn find(code: &str) -> Option<&'static Language> {
    LANGUAGES.iter().find(|language| language.code == code)
}
