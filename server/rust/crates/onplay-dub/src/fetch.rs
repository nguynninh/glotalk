//! Input resolution: a local file, or an http(s) link downloaded with yt-dlp.

use std::path::{Path, PathBuf};

use crate::command;
use crate::error::{Error, Result};
use crate::events::{Event, Observer};

/// Where the input video lives and whether it was downloaded into the work directory.
#[derive(Debug, Clone)]
pub struct Input {
    pub video: PathBuf,
    pub downloaded: bool,
}

pub fn fetch(source: &str, work: &Path, observer: &dyn Observer) -> Result<Input> {
    if !source.starts_with("http://") && !source.starts_with("https://") {
        let video = PathBuf::from(source);
        if !video.is_file() {
            return Err(Error::InvalidArgument(format!("input not found: {source}")));
        }
        return Ok(Input { video, downloaded: false });
    }
    if !command::available("yt-dlp", "--version") {
        return Err(Error::Setup("yt-dlp is required for links: brew install yt-dlp".into()));
    }
    observer.event(Event::Downloading);
    let template = work.join("source.%(ext)s");
    // Prefer H.264 so the copied video stream plays everywhere; AV1 does not in QuickTime.
    command::run("yt-dlp", [
        "-f", "bv*[vcodec^=avc1]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b",
        "--merge-output-format", "mp4", "--no-playlist", "--quiet", "--no-warnings",
        "-o", &template.to_string_lossy(), source,
    ])?;
    let video = std::fs::read_dir(work)
        .map_err(Error::io(work))?
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .find(|path| path.file_stem().is_some_and(|stem| stem == "source"))
        .ok_or_else(|| Error::Command { program: "yt-dlp".into(), detail: "no file downloaded".into() })?;
    Ok(Input { video, downloaded: true })
}
