//! Dub job results over plain HTTP on `127.0.0.1:8767`, behind the tunnel's `/jobs/` rule:
//!
//! - `GET|HEAD /jobs/<id>/<subtitles.srt|original.srt>` with `Authorization: Bearer <token>`;
//! - `GET|HEAD /jobs/<id>/hls/<key>/<file>` without a header, the key being the access check.
//!
//! Files never change once a job is done, so every response carries a strong `ETag`,
//! `Last-Modified` and a private `Cache-Control` up to the job's expiry, and single byte
//! ranges (with `If-Range`) let background downloads resume. Requests are not logged.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::MetadataExt;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use tiny_http::{Header, Method, Request, Response, StatusCode};

use crate::jobs::tokens::bearer;
use crate::jobs::{Jobs, NotServed, Served};
use crate::time::{http_date, seconds_until};

/// Answers requests until the listener fails, one thread per request so a slow download
/// does not hold up the others.
pub fn serve(jobs: &Arc<Jobs>, server: tiny_http::Server) {
    for request in server.incoming_requests() {
        let jobs = jobs.clone();
        std::thread::spawn(move || respond(&jobs, request));
    }
}

fn respond(jobs: &Jobs, request: Request) {
    let header = |name: &'static str| request.headers().iter().find(|header| header.field.equiv(name)).map(|header| header.value.as_str().to_owned());
    if !matches!(request.method(), Method::Get | Method::Head) {
        let _ = request.respond(error(405, "method_not_allowed"));
        return;
    }
    let path = request.url().split(['?', '#']).next().unwrap_or_default().to_owned();
    let parts: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let located = match parts.as_slice() {
        ["jobs", id, name] => {
            let authorization = header("Authorization");
            jobs.file(id, name, authorization.as_deref().and_then(bearer))
        }
        ["jobs", id, "hls", key, name] => jobs.hls_file(id, key, name),
        _ => Err(NotServed::NotFound),
    };
    let response = match located {
        Ok(served) => file(&served, header("Range").as_deref(), header("If-Range").as_deref()),
        Err(NotServed::Unauthorized) => Err(error(401, "invalid_token")),
        Err(NotServed::NotFound) => Err(error(404, "job_not_found")),
    };
    let _ = match response {
        Ok(response) => request.respond(response),
        Err(response) => request.respond(response),
    };
}

type Body = Response<Box<dyn Read + Send>>;
type Refused = Response<std::io::Cursor<Vec<u8>>>;

fn file(served: &Served, range: Option<&str>, if_range: Option<&str>) -> Result<Body, Refused> {
    let not_found = || error(404, "job_not_found");
    let mut file = File::open(&served.path).map_err(|_| not_found())?;
    let metadata = file.metadata().map_err(|_| not_found())?;
    if !metadata.is_file() {
        return Err(not_found());
    }
    let size = metadata.len();
    let modified = metadata.modified().unwrap_or(UNIX_EPOCH);
    let etag = format!("\"{:x}-{:x}-{:x}\"", metadata.ino(), size, modified.duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos());
    let last_modified = http_date(modified);
    let max_age = seconds_until(served.expires, SystemTime::now());
    let mut headers = vec![
        header("Content-Type", content_type(&served.path.to_string_lossy())),
        header("Accept-Ranges", "bytes"),
        header("ETag", &etag),
        header("Last-Modified", &last_modified),
        header("Cache-Control", &format!("private, max-age={max_age}")),
    ];
    let fresh = if_range.map_or(true, |validator| validator == etag || validator == last_modified);
    let (status, start, length) = match plan(range.filter(|_| fresh), size) {
        Plan::Full => (200, 0, size),
        Plan::Partial(start, end) => {
            headers.push(header("Content-Range", &format!("bytes {start}-{end}/{size}")));
            (206, start, end - start + 1)
        }
        Plan::Unsatisfiable => {
            let mut refused = error(416, "range_not_satisfiable");
            refused.add_header(header("Content-Range", &format!("bytes */{size}")));
            return Err(refused);
        }
    };
    file.seek(SeekFrom::Start(start)).map_err(|_| not_found())?;
    let body: Box<dyn Read + Send> = Box::new(file.take(length));
    Ok(Response::new(StatusCode(status), headers, body, Some(length as usize), None))
}

#[derive(Debug, PartialEq)]
enum Plan {
    Full,
    /// Inclusive byte range.
    Partial(u64, u64),
    Unsatisfiable,
}

/// One `bytes=` range of a `size`-byte file; several ranges or a malformed header get the
/// whole file, as RFC 9110 allows.
fn plan(range: Option<&str>, size: u64) -> Plan {
    let Some(spec) = range.and_then(|range| range.trim().strip_prefix("bytes=")) else { return Plan::Full };
    if spec.contains(',') {
        return Plan::Full;
    }
    let Some((first, last)) = spec.split_once('-') else { return Plan::Full };
    let (first, last) = (first.trim(), last.trim());
    let parsed = |text: &str| text.parse::<u64>().ok();
    let (start, end) = match (first.is_empty(), last.is_empty()) {
        // Suffix: the last N bytes.
        (true, false) => match parsed(last) {
            Some(0) => return Plan::Unsatisfiable,
            Some(count) => (size.saturating_sub(count), size.saturating_sub(1)),
            None => return Plan::Full,
        },
        (false, _) => match (parsed(first), if last.is_empty() { Some(u64::MAX) } else { parsed(last) }) {
            (Some(start), Some(end)) if start <= end => (start, end.min(size.saturating_sub(1))),
            _ => return Plan::Full,
        },
        (true, true) => return Plan::Full,
    };
    if size == 0 || start >= size {
        return Plan::Unsatisfiable;
    }
    Plan::Partial(start, end)
}

fn content_type(path: &str) -> &'static str {
    match path.rsplit('.').next().unwrap_or_default() {
        "m3u8" => "application/vnd.apple.mpegurl",
        "m4s" | "mp4" => "video/mp4",
        "vtt" => "text/vtt; charset=utf-8",
        "srt" => "application/x-subrip; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("ASCII header")
}

fn error(status: u16, code: &str) -> Refused {
    Response::from_string(format!("{{\"code\":\"{code}\"}}"))
        .with_status_code(StatusCode(status))
        .with_header(header("Content-Type", "application/json"))
        .with_header(header("Cache-Control", "no-store"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plans_byte_ranges() {
        assert_eq!(plan(None, 100), Plan::Full);
        assert_eq!(plan(Some("bytes=0-9"), 100), Plan::Partial(0, 9));
        assert_eq!(plan(Some("bytes=90-"), 100), Plan::Partial(90, 99));
        assert_eq!(plan(Some("bytes=90-500"), 100), Plan::Partial(90, 99));
        assert_eq!(plan(Some("bytes=-10"), 100), Plan::Partial(90, 99));
        assert_eq!(plan(Some("bytes=-500"), 100), Plan::Partial(0, 99));
        assert_eq!(plan(Some("bytes=100-"), 100), Plan::Unsatisfiable);
        assert_eq!(plan(Some("bytes=-0"), 100), Plan::Unsatisfiable);
        assert_eq!(plan(Some("bytes=0-1,5-6"), 100), Plan::Full);
        assert_eq!(plan(Some("bytes=9-0"), 100), Plan::Full);
        assert_eq!(plan(Some("items=0-1"), 100), Plan::Full);
    }

    #[test]
    fn names_content_types() {
        assert_eq!(content_type("/j/hls/k/master.m3u8"), "application/vnd.apple.mpegurl");
        assert_eq!(content_type("/j/hls/k/seg00001.m4s"), "video/mp4");
        assert_eq!(content_type("/j/subtitles.srt"), "application/x-subrip; charset=utf-8");
    }
}
