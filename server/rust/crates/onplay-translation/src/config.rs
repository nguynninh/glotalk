//! `config/translation.yaml`, validated with the Python router's rules.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Deserializer};

use crate::error::{Error, Result};
use crate::language;

pub const CONFIG_FILE: &str = "config/translation.yaml";
const MAX_CACHE_ENTRIES: usize = 4096;
const MAX_CONTEXT_SEGMENTS: usize = 5;

#[derive(Debug, Clone)]
pub struct Config {
    pub default_model: String,
    /// `None` when the key is absent; `Some(None)` when it is `null`.
    pub fallback_model: Option<Option<String>>,
    pub routes: HashMap<String, Route>,
    pub context: Context,
    pub cache_entries: usize,
    /// Registered models in file order, with paths resolved against the project root.
    pub models: Vec<(String, ModelSpec)>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Route {
    #[serde(default)]
    pub primary: Option<String>,
    #[serde(default, deserialize_with = "present")]
    pub fallback: Option<Option<String>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Context {
    pub enabled: bool,
    pub model: String,
    #[serde(default, deserialize_with = "present")]
    pub fallback: Option<Option<String>>,
    pub max_segments: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Backend {
    /// OPUS-MT (Argos) CTranslate2 packages, one directory per `xx_yy` pair.
    Ct2Opus,
    /// NLLB converted to CTranslate2, with the Hugging Face tokenizer.
    Ct2Nllb,
    /// Qwen3 causal LM (safetensors) for context translation.
    Qwen,
    /// NLLB on PyTorch. Accepted in the config for compatibility, but not runnable here:
    /// use `ct2_nllb`.
    Nllb,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ModelSpec {
    pub backend: Backend,
    pub path: PathBuf,
    #[serde(default)]
    pub tokenizer: Option<PathBuf>,
}

#[derive(Deserialize)]
struct File {
    translation: Raw,
}

#[derive(Deserialize)]
struct Raw {
    default_model: String,
    #[serde(default, deserialize_with = "present")]
    fallback_model: Option<Option<String>>,
    routes: HashMap<String, Route>,
    context: Context,
    cache_entries: usize,
    models: serde_yaml::Mapping,
}

/// Distinguishes an absent key (`None`, via `#[serde(default)]`) from `null` (`Some(None)`).
fn present<'de, D: Deserializer<'de>>(deserializer: D) -> std::result::Result<Option<Option<String>>, D::Error> {
    Option::deserialize(deserializer).map(Some)
}

impl Config {
    /// Reads `config/translation.yaml` under `root`.
    pub fn load(root: &Path) -> Result<Self> {
        let path = root.join(CONFIG_FILE);
        let text = std::fs::read_to_string(&path).map_err(|error| Error::Config(format!("{}: {error}", path.display())))?;
        Self::parse(&text, root)
    }

    pub fn parse(text: &str, root: &Path) -> Result<Self> {
        let raw: File = serde_yaml::from_str(text).map_err(|error| Error::Config(error.to_string()))?;
        let raw = raw.translation;
        let mut models = Vec::new();
        for (name, spec) in raw.models {
            let name = name.as_str().ok_or_else(|| Error::Config("model names must be strings".into()))?.to_owned();
            let mut spec: ModelSpec = serde_yaml::from_value(spec).map_err(|error| Error::Config(format!("model {name}: {error}")))?;
            spec.path = root.join(&spec.path);
            spec.tokenizer = spec.tokenizer.map(|tokenizer| root.join(tokenizer));
            models.push((name, spec));
        }
        let config = Self {
            default_model: raw.default_model,
            fallback_model: raw.fallback_model,
            routes: raw.routes,
            context: raw.context,
            cache_entries: raw.cache_entries,
            models,
        };
        config.validate()?;
        Ok(config)
    }

    pub fn model(&self, name: &str) -> Option<&ModelSpec> {
        self.models.iter().find(|(registered, _)| registered == name).map(|(_, spec)| spec)
    }

    fn validate(&self) -> Result<()> {
        let flat = |name: &Option<Option<String>>| name.clone().flatten();
        let mut chosen = vec![Some(self.default_model.clone()), flat(&self.fallback_model)];
        if self.context.enabled {
            chosen.extend([Some(self.context.model.clone()), flat(&self.context.fallback)]);
        }
        for route in self.routes.values() {
            chosen.extend([route.primary.clone(), flat(&route.fallback)]);
        }
        if chosen.iter().flatten().any(|name| self.model(name).is_none()) {
            return Err(Error::Config("A route references an unregistered model".into()));
        }
        if !(1..=MAX_CONTEXT_SEGMENTS).contains(&self.context.max_segments) || self.cache_entries > MAX_CACHE_ENTRIES {
            return Err(Error::Config("Invalid context/cache limit".into()));
        }
        let valid_pair = |pair: &str| pair.split_once('-').is_some_and(|(a, b)| language::find(a).is_some() && language::find(b).is_some());
        if !self.routes.keys().all(|pair| valid_pair(pair)) {
            return Err(Error::Config("Unsupported route language pair".into()));
        }
        if self.models.iter().any(|(_, spec)| spec.backend == Backend::Ct2Nllb && spec.tokenizer.is_none()) {
            return Err(Error::Config("ct2_nllb models need a tokenizer path".into()));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CONFIG: &str = include_str!("../../../../config/translation.yaml");

    #[test]
    fn reads_the_project_config() {
        let config = Config::parse(CONFIG, Path::new("/root")).unwrap();
        let names: Vec<_> = config.models.iter().map(|(name, _)| name.as_str()).collect();
        assert_eq!(names, ["nllb-fast", "opus-fast", "nllb-200", "qwen"]);
        assert_eq!(config.fallback_model, Some(None));
        assert_eq!(config.routes["zh-vi"].fallback, Some(Some("nllb-fast".into())));
        assert_eq!(config.model("opus-fast").unwrap().path, Path::new("/root/models/translation/opus"));
    }

    #[test]
    fn rejects_like_python() {
        let broken = |from: &str, to: &str| Config::parse(&CONFIG.replace(from, to), Path::new("/")).unwrap_err().to_string();
        assert!(broken("primary: opus-fast", "primary: missing").contains("unregistered"));
        assert!(broken("max_segments: 5", "max_segments: 6").contains("limit"));
        assert!(broken("en-vi:", "en-xx:").contains("pair"));
        assert!(broken("backend: qwen", "backend: other").contains("unknown variant"));
    }
}
