//! Dub job results over plain HTTP on `127.0.0.1:8767`, behind the tunnel's `/jobs/` rule:
//!
//! - `GET|HEAD /jobs/<id>/<subtitles.srt|original.srt>` with `Authorization: Bearer <token>`;
//! - `GET|HEAD /jobs/<id>/hls/<key>/<file>` without a header, the key being the access check.
//!
//! The same port answers editor sign-in under `/auth/` ([`crate::auth`]) and, for a signed-in
//! editor, `POST /translate` ([`crate::translation::translate_batch`]): subtitle lines through
//! the translation router, at most 50 per request.
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

use crate::auth::{Auth, LoginError};
use crate::jobs::tokens::bearer;
use crate::jobs::{Jobs, NotServed, Served};
use crate::time::{http_date, seconds_until};
use crate::translation::{translate_batch, Translator};

/// Answers requests until the listener fails, one thread per request so a slow download
/// does not hold up the others.
pub fn serve(jobs: &Arc<Jobs>, auth: &Arc<Auth>, translator: &Arc<Translator>, server: tiny_http::Server) {
    for request in server.incoming_requests() {
        let (jobs, auth, translator) = (jobs.clone(), auth.clone(), translator.clone());
        std::thread::spawn(move || respond(&jobs, &auth, &translator, request));
    }
}

fn respond(jobs: &Jobs, auth: &Auth, translator: &Translator, request: Request) {
    let header = |name: &'static str| request.headers().iter().find(|header| header.field.equiv(name)).map(|header| header.value.as_str().to_owned());
    if request.url().starts_with("/auth/") {
        return respond_auth(auth, request);
    }
    if request.url().split(['?', '#']).next() == Some("/translate") {
        return respond_translate(auth, translator, request);
    }
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

/// Longest sign-in request body read; a name and password fit easily.
const AUTH_BODY_LIMIT: u64 = 4096;

fn respond_auth(auth: &Auth, mut request: Request) {
    let token = request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .and_then(|header| bearer(header.value.as_str()).map(str::to_owned));
    let path = request.url().split(['?', '#']).next().unwrap_or_default().to_owned();
    let response = match (request.method(), path.as_str()) {
        (Method::Post, "/auth/login") => {
            let mut body = String::new();
            let read = request.as_reader().take(AUTH_BODY_LIMIT).read_to_string(&mut body);
            let fields = read.ok().and_then(|_| serde_json::from_str::<serde_json::Value>(&body).ok());
            let field = |name: &str| fields.as_ref().and_then(|value| value.get(name)).and_then(|value| value.as_str()).map(str::to_owned);
            match (field("user"), field("password")) {
                (Some(user), Some(password)) if !user.trim().is_empty() => match auth.login(&user, &password) {
                    Ok((token, expires)) => {
                        let expires_at = expires.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                        json(200, &serde_json::json!({ "token": token, "user": auth.user(&token), "expiresAt": expires_at }).to_string())
                    }
                    Err(LoginError::Invalid) => error(401, "invalid_credentials"),
                    Err(LoginError::TooManyAttempts) => error(429, "too_many_attempts"),
                },
                _ => error(400, "bad_request"),
            }
        }
        (Method::Get, "/auth/me") => match token.as_deref().and_then(|token| auth.user(token)) {
            Some(user) => json(200, &serde_json::json!({ "user": user }).to_string()),
            None => error(401, "invalid_token"),
        },
        (Method::Post, "/auth/logout") => {
            if let Some(token) = token.as_deref() {
                auth.logout(token);
            }
            Response::from_string(String::new()).with_status_code(StatusCode(204)).with_header(header("Cache-Control", "no-store"))
        }
        (_, "/auth/login" | "/auth/me" | "/auth/logout") => error(405, "method_not_allowed"),
        _ => error(404, "not_found"),
    };
    let _ = request.respond(response);
}

/// Longest translation request body read: 50 subtitle lines with their context.
const TRANSLATE_BODY_LIMIT: u64 = 128 * 1024;

fn respond_translate(auth: &Auth, translator: &Translator, mut request: Request) {
    let signed_in = request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .and_then(|header| bearer(header.value.as_str()))
        .is_some_and(|token| auth.user(token).is_some());
    let response = if *request.method() != Method::Post {
        error(405, "method_not_allowed")
    } else if !signed_in {
        error(401, "invalid_token")
    } else {
        let mut body = Vec::new();
        let read = request.as_reader().take(TRANSLATE_BODY_LIMIT + 1).read_to_end(&mut body);
        match read.ok().filter(|_| body.len() as u64 <= TRANSLATE_BODY_LIMIT).and_then(|_| serde_json::from_slice(&body).ok()) {
            Some(fields) => match translate_batch(&fields, |text, target, source, mode, context| translator.translate(text, target, source, mode, context)) {
                Ok(result) => json(200, &result.to_string()),
                Err(code) => error(400, code),
            },
            None => error(400, "bad_request"),
        }
    };
    let _ = request.respond(response);
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
    json(status, &format!("{{\"code\":\"{code}\"}}"))
}

fn json(status: u16, body: &str) -> Refused {
    Response::from_string(body)
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
    fn answers_sign_in() {
        let users = std::env::temp_dir().join(format!("onplay-auth-http-{}", std::process::id()));
        std::fs::write(&users, format!("vtvlive {}\n", crate::auth::hash_password("12345678"))).unwrap();
        let auth = Arc::new(Auth::new(&users));
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let base = format!("http://{}", server.server_addr().to_ip().unwrap());
        std::thread::spawn(move || {
            for request in server.incoming_requests() {
                respond_auth(&auth, request);
            }
        });
        let status = |result: Result<ureq::Response, ureq::Error>| match result {
            Ok(response) => (response.status(), response.into_string().unwrap()),
            Err(ureq::Error::Status(code, response)) => (code, response.into_string().unwrap()),
            Err(error) => panic!("{error}"),
        };
        let login = |body: &str| status(ureq::post(&format!("{base}/auth/login")).set("Content-Type", "application/json").send_string(body));
        assert_eq!(login(r#"{"user":"vtvlive","password":"sai"}"#), (401, r#"{"code":"invalid_credentials"}"#.to_owned()));
        assert_eq!(login("not json").0, 400);
        let (code, body) = login(r#"{"user":"vtvlive","password":"12345678"}"#);
        assert_eq!(code, 200);
        let body: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(body["user"], "vtvlive");
        let token = body["token"].as_str().unwrap().to_owned();
        let me = |token: &str| status(ureq::get(&format!("{base}/auth/me")).set("Authorization", &format!("Bearer {token}")).call());
        assert_eq!(me(&token), (200, r#"{"user":"vtvlive"}"#.to_owned()));
        assert_eq!(status(ureq::post(&format!("{base}/auth/logout")).set("Authorization", &format!("Bearer {token}")).call()).0, 204);
        assert_eq!(me(&token).0, 401);
        assert_eq!(status(ureq::get(&format!("{base}/auth/login")).call()).0, 405);
        std::fs::remove_file(users).unwrap();
    }

    #[test]
    fn translate_needs_sign_in() {
        let users = std::env::temp_dir().join(format!("onplay-translate-http-{}", std::process::id()));
        std::fs::write(&users, format!("vtvlive {}\n", crate::auth::hash_password("12345678"))).unwrap();
        let auth = Arc::new(Auth::new(&users));
        let (token, _) = auth.login("vtvlive", "12345678").unwrap();
        let translator = Arc::new(Translator::new("http://127.0.0.1:9"));
        let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
        let base = format!("http://{}", server.server_addr().to_ip().unwrap());
        std::thread::spawn(move || {
            for request in server.incoming_requests() {
                respond_translate(&auth, &translator, request);
            }
        });
        let post = |token: &str, body: &str| match ureq::post(&format!("{base}/translate"))
            .set("Authorization", &format!("Bearer {token}"))
            .set("Content-Type", "application/json")
            .send_string(body)
        {
            Ok(response) => (response.status(), response.into_string().unwrap()),
            Err(ureq::Error::Status(code, response)) => (code, response.into_string().unwrap()),
            Err(error) => panic!("{error}"),
        };
        let body = r#"{"source":"en","target":"vi","texts":["Hello"]}"#;
        assert_eq!(post("wrong", body).0, 401);
        assert_eq!(post(&token, r#"{"source":"en","target":"xx","texts":["Hello"]}"#), (400, r#"{"code":"invalid_target"}"#.to_owned()));
        // No router on port 9: the line fails, the request does not.
        let (code, body) = post(&token, body);
        assert_eq!(code, 200);
        assert!(body.contains(r#""error""#), "{body}");
        assert_eq!(status_of(ureq::get(&format!("{base}/translate")).call()), 405);
        std::fs::remove_file(users).unwrap();
    }

    fn status_of(result: Result<ureq::Response, ureq::Error>) -> u16 {
        match result {
            Ok(response) => response.status(),
            Err(ureq::Error::Status(code, _)) => code,
            Err(error) => panic!("{error}"),
        }
    }

    #[test]
    fn names_content_types() {
        assert_eq!(content_type("/j/hls/k/master.m3u8"), "application/vnd.apple.mpegurl");
        assert_eq!(content_type("/j/hls/k/seg00001.m4s"), "video/mp4");
        assert_eq!(content_type("/j/subtitles.srt"), "application/x-subrip; charset=utf-8");
    }
}
