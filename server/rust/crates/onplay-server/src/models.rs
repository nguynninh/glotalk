//! Model checks done before a session relies on optional speaker models.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

use sha2::{Digest, Sha256};

/// SHA-256 of the speaker embedding model that `./scripts/setup/speakers.sh` installs.
const EMBEDDING_SHA256: &str = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11";

/// The last verified file and its size and modification time; hashing 28 MB per session
/// is avoidable while the file is unchanged.
static VERIFIED: Mutex<Option<(PathBuf, u64, SystemTime, bool)>> = Mutex::new(None);

/// True when the embedding model exists and is the verified download.
pub fn embedding_is_valid(path: &Path) -> bool {
    let Ok(stamp) = std::fs::metadata(path).and_then(|meta| Ok((meta.len(), meta.modified()?))) else { return false };
    let mut verified = VERIFIED.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some((cached, len, modified, valid)) = verified.as_ref() {
        if cached == path && (*len, *modified) == stamp {
            return *valid;
        }
    }
    let valid = hash_matches(path);
    *verified = Some((path.to_owned(), stamp.0, stamp.1, valid));
    valid
}

fn hash_matches(path: &Path) -> bool {
    let Ok(mut file) = std::fs::File::open(path) else { return false };
    let mut digest = Sha256::new();
    let mut block = vec![0u8; 1024 * 1024];
    loop {
        match file.read(&mut block) {
            Ok(0) => break,
            Ok(read) => digest.update(&block[..read]),
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return false,
        }
    }
    let hex: String = digest.finalize().iter().map(|byte| format!("{byte:02x}")).collect();
    hex == EMBEDDING_SHA256
}
