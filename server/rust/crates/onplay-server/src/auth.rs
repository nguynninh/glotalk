//! Editor sign-in over HTTP on `127.0.0.1:8767`, beside the job downloads:
//!
//! - `POST /auth/login` with `{"user","password"}` -> `{"token","user","expiresAt"}`;
//! - `GET /auth/me` with `Authorization: Bearer <token>` -> `{"user"}`;
//! - `POST /auth/logout` with the same header revokes the token.
//!
//! Accounts are `name argon2-PHC` lines in `ONPLAY_AUTH_USERS` (default `auth-users`),
//! read on every login so accounts change without a restart; `scripts/prod/auth-user.sh`
//! adds them. Sessions live in memory, keyed by the token's SHA-256, and end on restart.
//! A name that fails [`MAX_FAILURES`] times in a row is refused for [`LOCKOUT`].
//! The token only says who signed in; dub jobs keep their tester tokens.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime};

use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use sha2::{Digest, Sha256};

const SESSION_TTL: Duration = Duration::from_secs(30 * 24 * 3600);
const MAX_FAILURES: u32 = 5;
const LOCKOUT: Duration = Duration::from_secs(10 * 60);

#[derive(Debug, PartialEq)]
pub enum LoginError {
    Invalid,
    TooManyAttempts,
}

pub struct Auth {
    users: PathBuf,
    /// SHA-256 hex of the token -> session.
    sessions: Mutex<HashMap<String, Session>>,
    /// Lowercased name -> consecutive failures and the last one.
    failures: Mutex<HashMap<String, (u32, Instant)>>,
}

struct Session {
    user: String,
    expires: SystemTime,
}

impl Auth {
    pub fn new(users: &Path) -> Self {
        Self { users: users.to_owned(), sessions: Mutex::default(), failures: Mutex::default() }
    }

    /// The number of accounts in the file, or why it cannot be read.
    pub fn accounts(&self) -> Result<usize, String> {
        let text = std::fs::read_to_string(&self.users).map_err(|error| format!("cannot read {}: {error}", self.users.display()))?;
        Ok(parse(&text).len())
    }

    /// A new token and its expiry for a correct name and password.
    pub fn login(&self, user: &str, password: &str) -> Result<(String, SystemTime), LoginError> {
        let key = user.trim().to_lowercase();
        {
            let mut failures = lock(&self.failures);
            if let Some(&(count, last)) = failures.get(&key) {
                if count >= MAX_FAILURES && last.elapsed() < LOCKOUT {
                    return Err(LoginError::TooManyAttempts);
                }
                if last.elapsed() >= LOCKOUT {
                    failures.remove(&key);
                }
            }
        }
        let stored = std::fs::read_to_string(&self.users).ok().and_then(|text| parse(&text).into_iter().find(|(name, _)| name.to_lowercase() == key));
        // An unknown name still costs one hash, so timing does not tell which names exist.
        let phc = stored.as_ref().map_or_else(|| dummy_hash().as_str(), |(_, phc)| phc.as_str());
        let verified = PasswordHash::new(phc).is_ok_and(|hash| Argon2::default().verify_password(password.as_bytes(), &hash).is_ok());
        let Some((name, _)) = stored.filter(|_| verified) else {
            let mut failures = lock(&self.failures);
            let entry = failures.entry(key).or_insert((0, Instant::now()));
            *entry = (entry.0 + 1, Instant::now());
            return Err(LoginError::Invalid);
        };
        lock(&self.failures).remove(&key);
        let token = URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>());
        let expires = SystemTime::now() + SESSION_TTL;
        let mut sessions = lock(&self.sessions);
        let now = SystemTime::now();
        sessions.retain(|_, session| session.expires > now);
        sessions.insert(digest(&token), Session { user: name, expires });
        Ok((token, expires))
    }

    /// The account name of a live token.
    pub fn user(&self, token: &str) -> Option<String> {
        let sessions = lock(&self.sessions);
        sessions.get(&digest(token)).filter(|session| session.expires > SystemTime::now()).map(|session| session.user.clone())
    }

    pub fn logout(&self, token: &str) {
        lock(&self.sessions).remove(&digest(token));
    }
}

/// An Argon2id PHC string for `password` with a random salt, as stored in the accounts file.
pub fn hash_password(password: &str) -> String {
    let salt = SaltString::encode_b64(&rand::random::<[u8; 16]>()).expect("16-byte salt");
    Argon2::default().hash_password(password.as_bytes(), &salt).expect("Argon2 with default parameters").to_string()
}

fn dummy_hash() -> &'static String {
    static HASH: OnceLock<String> = OnceLock::new();
    HASH.get_or_init(|| hash_password("unused"))
}

/// `name phc` lines; blank lines, `#` comments and malformed lines are skipped.
fn parse(text: &str) -> Vec<(String, String)> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            match (fields.next(), fields.next(), fields.next()) {
                (Some(name), Some(phc), None) if phc.starts_with("$argon2") => Some((name.to_owned(), phc.to_owned())),
                _ => None,
            }
        })
        .collect()
}

fn digest(token: &str) -> String {
    Sha256::digest(token.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect()
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn auth_with(lines: &str) -> (Auth, PathBuf) {
        let path = std::env::temp_dir().join(format!("onplay-auth-{}-{}", std::process::id(), rand::random::<u32>()));
        std::fs::write(&path, lines).unwrap();
        (Auth::new(&path), path)
    }

    #[test]
    fn logs_in_and_out() {
        let (auth, path) = auth_with(&format!("# accounts\nvtvlive {}\nbroken line here\n", hash_password("12345678")));
        assert_eq!(auth.accounts(), Ok(1));
        assert_eq!(auth.login("vtvlive", "wrong"), Err(LoginError::Invalid));
        assert_eq!(auth.login("nobody", "12345678"), Err(LoginError::Invalid));
        let (token, expires) = auth.login(" VTVLive ", "12345678").unwrap();
        assert!(expires > SystemTime::now());
        assert_eq!(auth.user(&token).as_deref(), Some("vtvlive"));
        assert_eq!(auth.user("other"), None);
        auth.logout(&token);
        assert_eq!(auth.user(&token), None);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn locks_out_after_repeated_failures() {
        let (auth, path) = auth_with(&format!("vtvlive {}\n", hash_password("12345678")));
        for _ in 0..MAX_FAILURES {
            assert_eq!(auth.login("vtvlive", "wrong"), Err(LoginError::Invalid));
        }
        // Even the right password waits out the lockout.
        assert_eq!(auth.login("vtvlive", "12345678"), Err(LoginError::TooManyAttempts));
        std::fs::remove_file(path).unwrap();
    }
}
