//! Tester tokens for dub jobs: `name sha256-hex` lines, so the file holds no usable token.
//! `scripts/prod/dub-token.sh` creates them; [`TokenFile`] picks up changes without a restart.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

use sha2::{Digest, Sha256};

pub struct Tokens {
    /// SHA-256 hex of the token -> tester name.
    names: HashMap<String, String>,
}

impl Tokens {
    /// Reads `path`; blank lines and `#` comments are skipped. An error names what is wrong
    /// without echoing the file's contents.
    pub fn load(path: &Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path).map_err(|error| format!("cannot read {}: {error}", path.display()))?;
        let tokens = Self::parse(&text).map_err(|line| format!("{} line {line}: expected `name sha256-hex`", path.display()))?;
        if tokens.names.is_empty() {
            return Err(format!("{} has no tokens", path.display()));
        }
        Ok(tokens)
    }

    /// Fails with the 1-based number of the first malformed line.
    fn parse(text: &str) -> Result<Self, usize> {
        let mut names = HashMap::new();
        for (number, line) in text.lines().enumerate() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut fields = line.split_whitespace();
            let (Some(name), Some(hash), None) = (fields.next(), fields.next(), fields.next()) else { return Err(number + 1) };
            if hash.len() != 64 || !hash.bytes().all(|byte| byte.is_ascii_hexdigit()) {
                return Err(number + 1);
            }
            names.insert(hash.to_ascii_lowercase(), name.to_owned());
        }
        Ok(Self { names })
    }

    /// The tester name for a bearer token.
    pub fn name(&self, token: &str) -> Option<&str> {
        let hash: String = Sha256::digest(token.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect();
        self.names.get(&hash).map(String::as_str)
    }
}

/// The token file, read again when its modification time or size changes, so tokens are
/// added and revoked while the server runs. An edit that does not parse keeps the last
/// tokens that did; removing the file revokes every token.
pub struct TokenFile {
    path: PathBuf,
    loaded: Mutex<Loaded>,
}

struct Loaded {
    stamp: Option<(SystemTime, u64)>,
    tokens: Tokens,
}

impl TokenFile {
    /// Fails like [`Tokens::load`]: dubbing starts only with at least one token.
    pub fn open(path: &Path) -> Result<Self, String> {
        let stamp = stamp(path);
        let tokens = Tokens::load(path)?;
        Ok(Self { path: path.to_owned(), loaded: Mutex::new(Loaded { stamp, tokens }) })
    }

    /// The tester name for a bearer token, from the file as it is now.
    pub fn name(&self, token: &str) -> Option<String> {
        let mut loaded = self.loaded.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let stamp = stamp(&self.path);
        if stamp != loaded.stamp {
            loaded.stamp = stamp;
            match stamp.map(|_| std::fs::read_to_string(&self.path)) {
                None => loaded.tokens = Tokens { names: HashMap::new() },
                Some(Ok(text)) => {
                    if let Ok(tokens) = Tokens::parse(&text) {
                        loaded.tokens = tokens;
                    }
                }
                Some(Err(_)) => {}
            }
        }
        loaded.tokens.name(token).map(str::to_owned)
    }
}

fn stamp(path: &Path) -> Option<(SystemTime, u64)> {
    let metadata = std::fs::metadata(path).ok()?;
    Some((metadata.modified().ok()?, metadata.len()))
}

/// The token of an `Authorization: Bearer <token>` header value.
pub fn bearer(header: &str) -> Option<&str> {
    let (scheme, token) = header.trim().split_once(' ')?;
    let token = token.trim();
    (scheme.eq_ignore_ascii_case("bearer") && !token.is_empty()).then_some(token)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_tokens_by_hash() {
        // SHA-256 of "secret".
        let tokens = Tokens::parse("# testers\nalice 2BB80D537B1DA3E38BD30361AA855686BDE0EACD7162FEF6A25FE97BF527A25B\n\n").unwrap();
        assert_eq!(tokens.name("secret"), Some("alice"));
        assert_eq!(tokens.name("Secret"), None);
        assert_eq!(Tokens::parse("alice abc").err(), Some(1));
        assert_eq!(Tokens::parse("# ok\nbob").err(), Some(2));
    }

    #[test]
    fn follows_edits_to_the_file() {
        let path = std::env::temp_dir().join(format!("onplay-tokens-{}", std::process::id()));
        let line = |name: &str, token: &str| {
            let hash: String = Sha256::digest(token.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect();
            format!("{name} {hash}\n")
        };
        std::fs::write(&path, line("alice", "a") + &line("bob", "b")).unwrap();
        let file = TokenFile::open(&path).unwrap();
        assert_eq!((file.name("a").as_deref(), file.name("b").as_deref()), (Some("alice"), Some("bob")));
        // Revoking bob leaves alice working.
        std::fs::write(&path, line("alice", "a")).unwrap();
        assert_eq!((file.name("a").as_deref(), file.name("b")), (Some("alice"), None));
        // A broken edit keeps the last good tokens.
        std::fs::write(&path, line("alice", "a") + "carol\n").unwrap();
        assert_eq!(file.name("a").as_deref(), Some("alice"));
        std::fs::remove_file(&path).unwrap();
        assert_eq!(file.name("a"), None);
    }

    #[test]
    fn reads_bearer_headers() {
        assert_eq!(bearer("Bearer abc"), Some("abc"));
        assert_eq!(bearer("bearer  abc "), Some("abc"));
        assert_eq!(bearer("Basic abc"), None);
        assert_eq!(bearer("Bearer "), None);
    }
}
