//! Text preparation must match Python korvatts 0.1.3 exactly; the fixture was produced
//! from that package (the generator script has since been removed).

use std::collections::BTreeMap;

use onplay_tts::text::{chunk_text, normalize_text, Language};
use serde::Deserialize;

#[derive(Deserialize)]
struct Fixture {
    cases: Vec<Case>,
}

#[derive(Deserialize)]
struct Case {
    text: String,
    normalized: BTreeMap<String, String>,
    chunks: BTreeMap<String, Vec<String>>,
}

fn fixture() -> Fixture {
    serde_json::from_str(include_str!("fixtures/text_parity.json")).expect("valid fixture")
}

#[test]
fn normalization_matches_python() {
    for case in fixture().cases {
        for (lang, expected) in &case.normalized {
            let actual = normalize_text(&case.text, Language::parse(lang).unwrap());
            assert_eq!(&actual, expected, "text {:?} lang {lang}", case.text);
        }
    }
}

#[test]
fn chunking_matches_python() {
    for case in fixture().cases {
        for (limit, expected) in &case.chunks {
            let actual = chunk_text(&case.text, limit.parse().unwrap());
            assert_eq!(&actual, expected, "text {:?} limit {limit}", case.text);
        }
    }
}
