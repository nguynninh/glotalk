//! A private temporary directory removed on drop, also after errors.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{Error, Result};

pub struct WorkDir {
    path: PathBuf,
}

impl WorkDir {
    pub fn create() -> Result<Self> {
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |time| time.as_nanos());
        let path = std::env::temp_dir().join(format!("onplay-dub-{}-{nanos}", std::process::id()));
        std::fs::create_dir(&path).map_err(Error::io(&path))?;
        Ok(Self { path })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for WorkDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}
