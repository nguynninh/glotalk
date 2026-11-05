//! Benchmark input built from recorded sessions (`sessions/*.jsonl` from the streaming
//! server). The corpus holds recognized speech, so it belongs in ignored `build/`.

use std::collections::HashSet;
use std::fs;
use std::io::Write;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

/// One request as the streaming server would send it.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Item {
    pub text: String,
    /// `auto` for recognized speech, as the server sends it.
    pub source: String,
    pub target: String,
    pub origin: Origin,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Origin {
    /// A final STT segment, sent with the session's source and target.
    Final,
    /// A recorded Vietnamese translation sent back out, to cover vi -> other routes.
    Derived,
}

#[derive(Deserialize)]
struct Line {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    text: String,
    #[serde(default)]
    source: String,
    #[serde(default)]
    target: String,
    #[serde(default)]
    model: String,
}

/// Unique items from every `*.jsonl` in `sessions`, in file-name then event order. Recorded
/// Vietnamese translations are added once per `derive_targets` language.
pub fn extract(sessions: &Path, derive_targets: &[String]) -> Result<Vec<Item>> {
    let mut paths: Vec<_> = fs::read_dir(sessions)
        .map_err(Error::io(sessions))?
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| path.extension().is_some_and(|ext| ext == "jsonl"))
        .collect();
    paths.sort();
    let mut seen = HashSet::new();
    let mut items = Vec::new();
    for path in paths {
        let raw = fs::read_to_string(&path).map_err(Error::io(&path))?;
        let mut session = None;
        for line in raw.lines().filter(|line| !line.trim().is_empty()) {
            let line: Line = serde_json::from_str(line).map_err(Error::data(&path))?;
            let text = line.text.trim();
            let mut candidates = Vec::new();
            match line.kind.as_str() {
                "session" => session = Some((line.source, line.target)),
                "final" if !text.is_empty() => {
                    let (source, target) = session.clone().ok_or_else(|| Error::Data {
                        path: path.clone(),
                        detail: "final segment before the session header".into(),
                    })?;
                    candidates.push((source, target, Origin::Final));
                }
                "translation"
                    if !text.is_empty()
                        && line.model != "identity"
                        && session.as_ref().is_some_and(|(_, target)| target == "vi") =>
                {
                    candidates.extend(derive_targets.iter().map(|target| ("vi".into(), target.clone(), Origin::Derived)));
                }
                _ => {}
            }
            for (source, target, origin) in candidates {
                let item = Item { text: text.to_owned(), source, target, origin };
                if seen.insert((item.text.clone(), item.source.clone(), item.target.clone())) {
                    items.push(item);
                }
            }
        }
    }
    Ok(items)
}

pub fn write(path: &Path, items: &[Item]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(Error::io(parent))?;
    }
    let mut file = fs::File::create(path).map_err(Error::io(path))?;
    for item in items {
        let line = serde_json::to_string(item).map_err(Error::data(path))?;
        writeln!(file, "{line}").map_err(Error::io(path))?;
    }
    Ok(())
}

pub fn read(path: &Path) -> Result<Vec<Item>> {
    read_lines(path)
}

pub(crate) fn read_lines<T: serde::de::DeserializeOwned>(path: &Path) -> Result<Vec<T>> {
    let raw = fs::read_to_string(path).map_err(Error::io(path))?;
    raw.lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str(line).map_err(Error::data(path)))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_unique_finals_and_derived_items() {
        let dir = std::env::temp_dir().join(format!("onplay-bench-corpus-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        fs::write(
            dir.join("a.jsonl"),
            concat!(
                r#"{"type": "session", "id": "a", "source": "auto", "target": "vi"}"#, "\n",
                r#"{"type": "final", "id": 0, "text": " 是 ", "speaker": 0}"#, "\n",
                r#"{"type": "translation", "revision": "live", "id": 0, "text": "Phải.", "model": "opus-fast"}"#, "\n",
                r#"{"type": "final", "id": 1, "text": "是"}"#, "\n",
                r#"{"type": "final", "id": 2, "text": "H"}"#, "\n",
                r#"{"type": "translation", "id": 2, "text": "H", "model": "identity"}"#, "\n",
                r#"{"type": "status", "value": "stopped"}"#, "\n",
            ),
        )
        .unwrap();
        fs::write(dir.join("ignored.wav"), b"").unwrap();
        let items = extract(&dir, &["en".into(), "zh".into()]).unwrap();
        let summary: Vec<_> =
            items.iter().map(|i| (i.text.as_str(), i.source.as_str(), i.target.as_str(), i.origin)).collect();
        assert_eq!(
            summary,
            [
                ("是", "auto", "vi", Origin::Final),
                ("Phải.", "vi", "en", Origin::Derived),
                ("Phải.", "vi", "zh", Origin::Derived),
                ("H", "auto", "vi", Origin::Final),
            ]
        );
        let path = dir.join("corpus.jsonl");
        write(&path, &items).unwrap();
        assert_eq!(read(&path).unwrap(), items);
        fs::remove_dir_all(dir).unwrap();
    }
}
