//! Request flow: validate, take the mode's lane, detect the source, pick the route's models,
//! answer from the cache or translate line by line around placeholders, then fall back.

use std::sync::{Mutex, MutexGuard, PoisonError, TryLockError};
use std::time::Instant;

use serde::Serialize;
use serde_json::Value;

use crate::cache::Cache;
use crate::config::Config;
use crate::detect::Detector;
use crate::error::{Error, Result};
use crate::language::{self, Language};
use crate::metrics::Metrics;
use crate::models::{Job, ModelError, ModelResult, Models};
use crate::request::{Mode, Request};
use crate::text::whitespace::{join_words, lstrip, strip};
use crate::text::{is_control, placeholders};

/// Latency budget reported per response.
const BUDGET_MS: f64 = 500.0;
const IDENTITY: &str = "identity";
/// Output prefixes that mean the context model repeated its prompt.
const ECHOES: [&str; 3] = ["{\"previous_segments\"", "Text to translate:", "Previous context"];

/// A `POST /translate` response.
#[derive(Debug, Clone, Serialize)]
pub struct Translation {
    pub text: String,
    pub source: &'static str,
    pub target: &'static str,
    pub model: String,
    pub fallback: bool,
    pub cached: bool,
    pub latency_ms: f64,
    pub within_budget: bool,
}

#[derive(PartialEq)]
struct Key {
    text: String,
    source: &'static str,
    target: &'static str,
    primary: String,
    fallback: Option<String>,
    mode: Mode,
    context: Vec<String>,
}

pub struct Engine {
    config: Config,
    models: Models,
    detector: Mutex<Option<Detector>>,
    cache: Mutex<Cache<Key, Translation>>,
    metrics: Mutex<Metrics>,
    /// One worker lane per [`Mode`].
    lanes: [Mutex<()>; 2],
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

fn rounded_ms(started: Instant) -> f64 {
    (started.elapsed().as_secs_f64() * 100_000.0).round() / 100.0
}

impl Engine {
    pub fn new(config: Config) -> Self {
        Self {
            models: Models::new(&config),
            cache: Mutex::new(Cache::new(config.cache_entries)),
            config,
            detector: Mutex::new(None),
            metrics: Mutex::new(Metrics::default()),
            lanes: [Mutex::new(()), Mutex::new(())],
        }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    pub fn metrics(&self) -> Metrics {
        lock(&self.metrics).clone()
    }

    /// Loads the language detector and the CTranslate2 models before the first request.
    pub fn warmup(&self) -> Result<()> {
        self.detect("Hello, how are you today?")?;
        self.models.warmup().map_err(|ModelError(error)| Error::Failed(format!("model warmup failed: {error}")))
    }

    /// Translates a `POST /translate` JSON body.
    pub fn translate(&self, data: &Value) -> Result<Translation> {
        let request = Request::parse(data, self.config.context.max_segments)?;
        let started = Instant::now();
        let _lane = match self.lanes[request.mode.lane()].try_lock() {
            Ok(guard) => guard,
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
            Err(TryLockError::WouldBlock) => return Err(Error::Busy),
        };
        lock(&self.metrics).requests += 1;
        let result = self.route(&request, started);
        let elapsed = started.elapsed().as_secs_f64() * 1000.0;
        let mut metrics = lock(&self.metrics);
        metrics.errors += u64::from(result.is_err());
        metrics.latency_ms_total += (elapsed * 100.0).round() / 100.0;
        metrics.over_500_ms += u64::from(elapsed > BUDGET_MS);
        result
    }

    fn detect(&self, text: &str) -> Result<&'static str> {
        let mut detector = lock(&self.detector);
        let detector = detector.get_or_insert_with(Detector::new);
        detector.detect(text).ok_or_else(|| Error::invalid("Cannot identify source language; supply source explicitly"))
    }

    fn route(&self, request: &Request, started: Instant) -> Result<Translation> {
        let source = match request.source {
            Some(source) => source,
            None => language::find(self.detect(&request.text)?).expect("detector returns router languages"),
        };
        let target = request.target;
        let route = self.config.routes.get(&format!("{}-{}", source.code, target.code));
        let mut primary = route.and_then(|route| route.primary.clone()).unwrap_or_else(|| self.config.default_model.clone());
        let mut fallback = route.and_then(|route| route.fallback.clone()).unwrap_or_else(|| self.config.fallback_model.clone().flatten());
        if request.mode == Mode::Context {
            let context = &self.config.context;
            if !context.enabled {
                return Err(Error::invalid("Context translation is disabled"));
            }
            primary = context.model.clone();
            fallback = context.fallback.clone().unwrap_or(fallback);
        }
        if source.code == target.code {
            (primary, fallback) = (IDENTITY.to_owned(), None);
        }
        let key = Key {
            text: request.text.clone(),
            source: source.code,
            target: target.code,
            primary: primary.clone(),
            fallback: fallback.clone(),
            mode: request.mode,
            context: request.context.clone(),
        };
        if let Some(mut cached) = lock(&self.cache).get(&key) {
            lock(&self.metrics).cache_hits += 1;
            cached.cached = true;
            cached.latency_ms = rounded_ms(started);
            cached.within_budget = cached.latency_ms <= BUDGET_MS;
            return Ok(cached);
        }
        let mut failed = Vec::new();
        let candidates = std::iter::once(primary.as_str()).chain(fallback.as_deref().filter(|name| *name != primary));
        for name in candidates {
            let context: &[String] = if name == primary { &request.context } else { &[] };
            match self.translate_text(name, &request.text, source, target, context) {
                Ok(text) => {
                    let latency_ms = rounded_ms(started);
                    let result = Translation {
                        text,
                        source: source.code,
                        target: target.code,
                        model: name.to_owned(),
                        fallback: name != primary,
                        cached: false,
                        latency_ms,
                        within_budget: latency_ms <= BUDGET_MS,
                    };
                    let mut metrics = lock(&self.metrics);
                    metrics.fallbacks += u64::from(name != primary);
                    metrics.count_model(name);
                    drop(metrics);
                    lock(&self.cache).insert(key, result.clone());
                    return Ok(result);
                }
                Err(_) => failed.push(name),
            }
        }
        Err(Error::Failed(format!("Translation failed for models: {}. Check models, segment length and placeholders.", failed.join(", "))))
    }

    /// Translates each line separately so blank lines and subtitle boundaries survive, and the
    /// text between placeholders separately so placeholders stay literal.
    fn translate_text(&self, name: &str, text: &str, source: &'static Language, target: &'static Language, context: &[String]) -> ModelResult<String> {
        let mut lines = Vec::new();
        for line in text.split('\n') {
            if line.is_empty() || name == IDENTITY {
                lines.push(line.to_owned());
                continue;
            }
            let mut translated = String::new();
            for piece in placeholders::split(line) {
                if piece.placeholder || strip(piece.text).is_empty() || !piece.text.chars().any(char::is_alphabetic) {
                    translated.push_str(piece.text);
                    continue;
                }
                let value = self.models.run(name, &Job { text: strip(piece.text), source, target, context })?;
                if strip(&value).is_empty() {
                    return Err(ModelError::new("Empty translation"));
                }
                if ECHOES.iter().any(|echo| lstrip(&value).starts_with(echo)) {
                    return Err(ModelError::new("Model echoed the prompt"));
                }
                if piece.text.starts_with(' ') {
                    translated.push(' ');
                }
                translated.push_str(strip(&value));
                if piece.text.ends_with(' ') {
                    translated.push(' ');
                }
            }
            if strip(&translated).is_empty() || translated.chars().any(|c| is_control(c) && c != '\n' && c != '\t') {
                return Err(ModelError::new("Empty translation"));
            }
            if placeholders::multiset(line) != placeholders::multiset(&translated) {
                lock(&self.metrics).placeholder_failures += 1;
                return Err(ModelError::new("Model changed placeholders"));
            }
            lines.push(join_words(&translated));
        }
        Ok(lines.join("\n"))
    }
}
