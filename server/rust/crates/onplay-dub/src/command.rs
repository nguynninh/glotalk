//! Running external tools (ffmpeg, yt-dlp) with their errors captured.

use std::ffi::OsStr;
use std::process::{Command, Stdio};

use crate::error::{Error, Result};

/// Runs `program` to completion; stderr becomes the error detail on failure.
pub(crate) fn run<I, S>(program: &str, args: I) -> Result<()>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    let output = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .output()
        .map_err(|error| Error::Command { program: program.into(), detail: error.to_string() })?;
    if !output.status.success() {
        let detail = String::from_utf8_lossy(&output.stderr).trim().to_owned();
        return Err(Error::Command { program: program.into(), detail: format!("{} {detail}", output.status) });
    }
    Ok(())
}

/// Whether `program` starts from PATH; `version_flag` is its harmless version option
/// (ffmpeg uses `-version`, yt-dlp `--version`).
pub(crate) fn available(program: &str, version_flag: &str) -> bool {
    Command::new(program)
        .arg(version_flag)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}
