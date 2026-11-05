//! Code point to token id mapping from `unicode_indexer.json`.

use std::path::Path;

use super::lang::Language;
use super::normalize::normalize_text;
use crate::assets::read_json;
use crate::error::{Error, Result};

pub struct UnicodeIndexer {
    /// Token id per BMP code point; negative marks code points outside the vocabulary.
    table: Vec<i64>,
}

impl UnicodeIndexer {
    pub fn load(path: &Path) -> Result<Self> {
        let table: Vec<i64> = read_json(path)?;
        if table.is_empty() {
            return Err(Error::InvalidAsset { path: path.into(), reason: "empty indexer".into() });
        }
        Ok(Self { table })
    }

    /// Normalized token ids for one utterance. Unknown code points are dropped, because a -1
    /// id would wrap to the last embedding row in the ONNX gather.
    pub fn encode(&self, text: &str, language: Language) -> Vec<i64> {
        normalize_text(text, language)
            .chars()
            .filter_map(|c| self.table.get(c as usize).copied().filter(|id| *id >= 0))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn drops_unknown_code_points() {
        let mut table = vec![-1; 128];
        for (id, c) in "<vi>a./".chars().enumerate() {
            table[c as usize] = id as i64;
        }
        let indexer = UnicodeIndexer { table };
        // 'b' has no id and is dropped; normalization appended the final '.'.
        assert_eq!(indexer.encode("ab", Language::parse("vi").unwrap()), [0, 1, 2, 3, 4, 5, 0, 6, 1, 2, 3]);
    }
}
