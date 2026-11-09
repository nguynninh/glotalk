//! Input resolution: a local file, or an http(s) link downloaded with yt-dlp.

use std::path::{Path, PathBuf};

use serde_json::Value;

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

/// What a link points to, read without downloading the video.
#[derive(Debug, Clone, PartialEq)]
pub struct LinkInfo {
    pub title: Option<String>,
    pub duration: f64,
    pub thumbnail: Option<String>,
}

/// Reads a link's metadata with yt-dlp; for direct media links, which yt-dlp reports
/// without a duration, ffprobe reads it from the stream. Playlists, live streams, links
/// without a known duration and links to local or IP-literal hosts are refused with
/// `InvalidArgument` (`allow_local` lifts the host check, for tests that serve a clip on
/// 127.0.0.1).
pub fn probe(url: &str, allow_local: bool) -> Result<LinkInfo> {
    let host = link_host(url).ok_or_else(|| Error::InvalidArgument("not an http(s) link".into()))?;
    if !allow_local && !is_public_name(host) {
        return Err(Error::InvalidArgument("links to local or IP-literal hosts are not accepted".into()));
    }
    if !command::available("yt-dlp", "--version") {
        return Err(Error::Setup("yt-dlp is required for links: brew install yt-dlp".into()));
    }
    let json = command::output("yt-dlp", [
        "--dump-single-json", "--flat-playlist", "--no-playlist", "--no-warnings", "--socket-timeout", "15", url,
    ])?;
    let mut info: Value = serde_json::from_slice(&json).map_err(|error| Error::Command { program: "yt-dlp".into(), detail: error.to_string() })?;
    if info.get("duration").and_then(Value::as_f64).is_none() {
        if let Some(duration) = stream_duration(url) {
            info["duration"] = duration.into();
        }
    }
    link_info(&info)
}

/// The container duration ffprobe reads from a direct media URL, within 15 s per request.
fn stream_duration(url: &str) -> Option<f64> {
    let output = command::output("ffprobe", [
        "-v", "error", "-rw_timeout", "15000000", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", url,
    ])
    .ok()?;
    String::from_utf8_lossy(&output).trim().parse().ok().filter(|duration: &f64| duration.is_finite())
}

fn link_info(info: &Value) -> Result<LinkInfo> {
    let invalid = |reason: &str| Err(Error::InvalidArgument(reason.into()));
    if info.get("_type").and_then(Value::as_str).is_some_and(|kind| kind != "video") {
        return invalid("a playlist, not one video");
    }
    let live = info.get("is_live").and_then(Value::as_bool) == Some(true)
        || info.get("live_status").and_then(Value::as_str).is_some_and(|status| status == "is_live" || status == "is_upcoming");
    if live {
        return invalid("a live stream");
    }
    let Some(duration) = info.get("duration").and_then(Value::as_f64).filter(|duration| *duration > 0.0) else {
        return invalid("unknown duration");
    };
    let text = |key: &str| info.get(key).and_then(Value::as_str).filter(|value| !value.is_empty()).map(str::to_owned);
    Ok(LinkInfo { title: text("title"), duration, thumbnail: text("thumbnail").filter(|url| link_host(url).is_some()) })
}

/// The host of an http(s) URL without user info; `None` for anything else.
fn link_host(url: &str) -> Option<&str> {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let authority = rest.split(['/', '?', '#']).next()?;
    if authority.contains('@') {
        return None;
    }
    let host = match authority.strip_prefix('[') {
        Some(ipv6) => ipv6.split(']').next()?,
        None => authority.rsplit_once(':').map_or(authority, |(host, _)| host),
    };
    (!host.is_empty()).then_some(host)
}

/// A dotted DNS name that is not local; IP literals are refused so a link cannot reach the
/// server's own loopback services or the local network by address.
fn is_public_name(host: &str) -> bool {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    let ip_literal = host.contains(':') || host.chars().all(|c| c.is_ascii_digit() || c == '.');
    let local = host == "localhost" || [".localhost", ".local", ".internal", ".lan", ".home.arpa"].iter().any(|suffix| host.ends_with(suffix));
    host.contains('.') && !ip_literal && !local
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn accepts_only_public_http_hosts() {
        assert_eq!(link_host("https://www.youtube.com/watch?v=x"), Some("www.youtube.com"));
        assert_eq!(link_host("http://youtu.be:80/x"), Some("youtu.be"));
        assert_eq!(link_host("http://[::1]:8766/"), Some("::1"));
        assert_eq!(link_host("ftp://example.com/a"), None);
        assert_eq!(link_host("https://user@example.com/"), None);
        assert!(is_public_name("www.youtube.com"));
        for host in ["localhost", "127.0.0.1", "10.0.0.1", "::1", "printer.local", "intranet", "a.localhost"] {
            assert!(!is_public_name(host), "{host}");
        }
    }

    #[test]
    fn reads_link_metadata() {
        let info = json!({"_type": "video", "title": "Talk", "duration": 746.4, "thumbnail": "https://i.ytimg.com/vi/x/0.jpg", "live_status": "not_live"});
        assert_eq!(link_info(&info).unwrap(), LinkInfo { title: Some("Talk".into()), duration: 746.4, thumbnail: Some("https://i.ytimg.com/vi/x/0.jpg".into()) });
        assert_eq!(link_info(&json!({"duration": 3})).unwrap().title, None);
        assert!(link_info(&json!({"_type": "playlist", "duration": 3})).is_err());
        assert!(link_info(&json!({"is_live": true, "duration": 3})).is_err());
        assert!(link_info(&json!({"title": "no duration"})).is_err());
    }
}
