//! Real-model socket checks, ported from `tests/live_stream.py` and `tests/speakers.py`.
//!
//! Starts `build/onplay-server` (from `./scripts/dev/build.sh`) rather than the cargo binary:
//! the server loads ONNX Runtime from `lib/` next to itself, the copy Sherpa ONNX already
//! loaded. Needs a free port 8766, `./scripts/setup/stt.sh` and `setup/speakers.sh`; run with
//! `cargo test -p onplay-server-cli -- --include-ignored` (as `scripts/dev/test.sh` does).
//! Translation stays optional: without a router the server reports translation errors only,
//! and the dub job check stops after the checks that need no dubbing.
//!
//! Dub jobs also need port 8767, ffmpeg and yt-dlp; the test serves a generated clip on a
//! local port, which `ONPLAY_DUB_LOCAL_LINKS=1` lets the server accept.

use std::io::{BufRead, BufReader, ErrorKind, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tungstenite::client::IntoClientRequest;
use tungstenite::http::HeaderValue;
use tungstenite::{Error, Message, WebSocket};

const ADDRESS: &str = "127.0.0.1:8766";
const DOWNLOADS: &str = "127.0.0.1:8767";
const TOKEN: &str = "socket-test-token";
const ORIGIN: &str = "http://127.0.0.1:8765";
const MODEL: &str = "models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10";
const TIMEOUT: Duration = Duration::from_secs(20);
/// `ONPLAY_MAX_SESSIONS` for the test server.
const SESSIONS: usize = 2;

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

/// The `data` chunk of a mono 16 kHz PCM16 WAV file, skipping chunks such as `LIST`.
fn pcm(path: &Path) -> Vec<u8> {
    let bytes = std::fs::read(path).unwrap();
    assert_eq!(&bytes[..4], b"RIFF");
    let mut pos = 12;
    let mut format = None;
    while pos + 8 <= bytes.len() {
        let size = u32::from_le_bytes(bytes[pos + 4..pos + 8].try_into().unwrap()) as usize;
        let body = &bytes[pos + 8..pos + 8 + size];
        match &bytes[pos..pos + 4] {
            b"fmt " => format = Some((u16::from_le_bytes([body[2], body[3]]), u32::from_le_bytes(body[4..8].try_into().unwrap()), u16::from_le_bytes([body[14], body[15]]))),
            b"data" => {
                assert_eq!(format, Some((1, 16_000, 16)), "{}", path.display());
                return body.to_vec();
            }
            _ => {}
        }
        pos += 8 + size + size % 2;
    }
    panic!("{}: no data chunk", path.display());
}

fn silence(seconds: usize) -> Vec<u8> {
    vec![0; 32_000 * seconds]
}

/// A directory under the system temp dir, removed when dropped.
struct Scratch(PathBuf);

impl Scratch {
    fn new(name: &str) -> Self {
        let path = std::env::temp_dir().join(format!("onplay-socket-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&path);
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// Kills the server when the test ends, including on assertion failure.
struct Server(Child, #[allow(dead_code)] Scratch);

impl Server {
    fn start() -> Self {
        assert!(TcpStream::connect(ADDRESS).is_err(), "Port 8766 is in use; stop the running onplay-server first.");
        assert!(TcpStream::connect(DOWNLOADS).is_err(), "Port 8767 is in use.");
        let binary = root().join("build/onplay-server");
        assert!(binary.is_file(), "{} not found; run ./scripts/dev/build.sh", binary.display());
        let scratch = Scratch::new("jobs");
        let hash: String = Sha256::digest(TOKEN.as_bytes()).iter().map(|byte| format!("{byte:02x}")).collect();
        std::fs::write(scratch.0.join("tokens"), format!("tester {hash}\n")).unwrap();
        let child = Command::new(binary)
            .env("ONPLAY_MAX_SESSIONS", SESSIONS.to_string())
            .env("ONPLAY_DUB_TOKENS", scratch.0.join("tokens"))
            .env("ONPLAY_JOB_DIR", scratch.0.join("jobs"))
            .env("ONPLAY_DUB_LOCAL_LINKS", "1")
            .stdout(Stdio::null())
            .spawn()
            .unwrap();
        let server = Self(child, scratch);
        // The server listens only after loading its models.
        let deadline = Instant::now() + Duration::from_secs(30);
        while TcpStream::connect(ADDRESS).is_err() {
            assert!(Instant::now() < deadline, "onplay-server did not start on 8766");
            std::thread::sleep(Duration::from_millis(200));
        }
        server
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

struct Client(WebSocket<TcpStream>);

impl Client {
    fn connect(options: Value) -> Self {
        Self::connect_with(options, None)
    }

    /// A job connection, with `Authorization: Bearer <token>` when given.
    fn connect_with(options: Value, token: Option<&str>) -> Self {
        let mut request = format!("ws://{ADDRESS}").into_client_request().unwrap();
        request.headers_mut().insert("Origin", HeaderValue::from_static(ORIGIN));
        if let Some(token) = token {
            request.headers_mut().insert("Authorization", HeaderValue::from_str(&format!("Bearer {token}")).unwrap());
        }
        let (socket, _) = tungstenite::client(request, TcpStream::connect(ADDRESS).unwrap()).unwrap();
        let mut client = Self(socket);
        client.send(Message::Text(options.to_string()));
        client
    }

    fn send(&mut self, message: Message) {
        self.0.send(message).unwrap();
    }

    fn send_pcm(&mut self, audio: &[u8]) {
        for chunk in audio.chunks(3200) {
            self.send(Message::Binary(chunk.to_vec()));
        }
    }

    fn end(&mut self) {
        self.send(Message::Text(json!({"type": "end"}).to_string()));
    }

    /// The next JSON event before `deadline`, or `None` on timeout or close.
    fn poll(&mut self, deadline: Instant) -> Option<Value> {
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now()).max(Duration::from_millis(1));
            self.0.get_ref().set_read_timeout(Some(remaining)).unwrap();
            match self.0.read() {
                Ok(Message::Text(text)) => return Some(serde_json::from_str(&text).unwrap()),
                Ok(_) => {}
                Err(Error::Io(error)) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    if Instant::now() >= deadline {
                        return None;
                    }
                }
                Err(Error::ConnectionClosed | Error::AlreadyClosed) => return None,
                Err(error) => panic!("socket: {error}"),
            }
        }
    }

    fn recv(&mut self) -> Value {
        self.poll(Instant::now() + TIMEOUT).expect("no event within 20 s")
    }

    /// Events up to and including the first of type `ready` or `error`.
    fn until_ready(&mut self) -> Vec<Value> {
        let mut events = Vec::new();
        loop {
            let event = self.recv();
            let kind = kind(&event).to_owned();
            events.push(event);
            if kind == "ready" || kind == "error" {
                return events;
            }
        }
    }

    /// Remaining events until the server closes the connection.
    fn rest(&mut self, events: &mut Vec<Value>) {
        while let Some(event) = self.poll(Instant::now() + TIMEOUT) {
            events.push(event);
        }
    }

    fn close(mut self) {
        let _ = self.0.close(None);
        let _ = self.0.flush();
    }
}

fn kind(event: &Value) -> &str {
    event["type"].as_str().unwrap_or_default()
}

fn text(event: &Value) -> &str {
    event["text"].as_str().unwrap_or_default()
}

fn speaker(event: &Value) -> i64 {
    event["speaker"].as_i64().unwrap_or(0)
}

fn finals(events: &[Value]) -> Vec<&Value> {
    events.iter().filter(|event| kind(event) == "final").collect()
}

/// `SESSIONS` ready sessions with their events, once every engine slot is free; slots of
/// closed connections are released when their recognition jobs end.
fn hold_all(timeout: Duration) -> Option<(Vec<Client>, Vec<Vec<Value>>)> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let mut clients: Vec<Client> = (0..SESSIONS).map(|_| Client::connect(json!({}))).collect();
        let events: Vec<Vec<Value>> = clients.iter_mut().map(Client::until_ready).collect();
        if events.iter().all(|events| kind(events.last().unwrap()) == "ready") {
            return Some((clients, events));
        }
        clients.into_iter().for_each(Client::close);
        std::thread::sleep(Duration::from_millis(200));
    }
    None
}

/// Every engine slot is free.
fn engine_free(timeout: Duration) -> bool {
    hold_all(timeout).map(|(clients, _)| clients.into_iter().for_each(Client::close)).is_some()
}

fn run(audio: &[u8], speakers: Value, expect_live: bool) -> Vec<Value> {
    let mut client = Client::connect(json!({"speakers": speakers}));
    let mut events = client.until_ready();
    assert_eq!(kind(events.last().unwrap()), "ready", "{events:?}");
    client.send_pcm(audio);
    if expect_live {
        loop {
            let event = client.recv();
            assert!(!matches!(kind(&event), "done" | "error"), "{events:?}");
            let live = kind(&event) == "partial" && speaker(&event) > 0;
            events.push(event);
            if live {
                break;
            }
        }
    }
    client.end();
    client.rest(&mut events);
    assert_eq!(kind(events.last().unwrap()), "done", "{events:?}");
    assert!(engine_free(Duration::from_secs(10)), "Session lock leaked");
    events
}

/// No upload or full-file conversion: PCM paced in real time yields partials before `end`.
fn live_stream(vi: &[u8]) {
    let mut client = Client::connect(json!({"threads": 2, "chunk": 100}));
    while kind(&client.recv()) != "ready" {}
    let start = Instant::now();
    let mut events: Vec<(Duration, Value)> = Vec::new();
    for chunk in vi.chunks(3200) {
        client.send(Message::Binary(chunk.to_vec()));
        let paced = Instant::now() + Duration::from_secs_f64(chunk.len() as f64 / 32_000.0);
        while let Some(event) = client.poll(paced) {
            events.push((start.elapsed(), event));
        }
    }
    assert!(events.iter().any(|(_, event)| kind(event) == "partial"), "{events:?}");
    client.end();
    let deadline = Instant::now() + Duration::from_secs(10);
    while let Some(event) = client.poll(deadline) {
        let last = matches!(kind(&event), "done" | "error");
        events.push((start.elapsed(), event));
        if last {
            break;
        }
    }
    client.close();
    assert_eq!(kind(&events.last().unwrap().1), "done", "{events:?}");
    assert!(events.iter().any(|(_, event)| kind(event) == "final" && text(event).contains("đất nước")), "{events:?}");
    let first = events.iter().find(|(_, event)| kind(event) == "partial").unwrap().0;
    assert!(first < Duration::from_secs(2), "first partial after {first:?}");
    // The engine is released after `done`; the Python checks were separate processes.
    assert!(engine_free(Duration::from_secs(10)), "Session lock leaked");
}

fn two_speakers(vi: &[u8], en: &[u8]) {
    let events = run(&[vi, &silence(3), en, &silence(3), vi].concat(), json!(true), true);
    let segments = events.iter().find(|event| kind(event) == "speakers").expect("speakers event")["segments"].as_array().unwrap().clone();
    let labels: std::collections::HashSet<i64> = segments.iter().map(speaker).collect();
    assert_eq!(labels.len(), 2, "{segments:?}");
    let vietnamese: Vec<&Value> = segments.iter().filter(|s| text(s).contains("đất nước")).collect();
    let english: Vec<&Value> = segments.iter().filter(|s| text(s).contains("AFTER EARLY")).collect();
    assert!(vietnamese.len() == 2 && english.len() == 1, "{segments:?}");
    assert!(speaker(vietnamese[0]) == speaker(vietnamese[1]) && speaker(vietnamese[0]) != speaker(english[0]), "{segments:?}");
    assert!(segments.iter().all(|s| 0.0 <= s["start"].as_f64().unwrap() && s["start"].as_f64() < s["end"].as_f64()), "{segments:?}");
    assert!(!finals(&events).is_empty(), "{events:?}");
    assert!(events.last().unwrap()["metrics"]["speaker_s"].as_f64().unwrap() > 0.0);
    let live: Vec<&Value> = finals(&events).into_iter().filter(|e| text(e).contains("đất nước")).collect();
    assert!(live.len() == 2 && speaker(live[0]) == speaker(live[1]) && speaker(live[0]) > 0, "{events:?}");
    // The existing ASR handles English -> Vietnamese; Vietnamese -> English can lose text.
    let events = run(&[en, &silence(3), vi].concat(), json!(true), true);
    let live = finals(&events);
    assert!(live.len() == 2 && speaker(live[0]) != speaker(live[1]), "{events:?}");
    assert!(live.iter().all(|e| speaker(e) > 0), "{events:?}");
}

fn live_speaker_mode(vi: &[u8], en: &[u8]) {
    let events = run(&[en, &silence(3), vi].concat(), json!("live"), true);
    let live = finals(&events);
    assert!(live.len() == 2 && speaker(live[0]) != speaker(live[1]), "{events:?}");
    assert!(live.iter().all(|e| speaker(e) > 0), "{events:?}");
    assert!(!events.iter().any(|e| kind(e) == "speakers"), "{events:?}");
    assert!(events.last().unwrap()["metrics"].get("speaker_s").is_none(), "{events:?}");
    let mut client = Client::connect(json!({"speakers": "offline"}));
    assert_eq!(kind(&client.recv()), "error");
    client.close();
}

fn disabled_mode(vi: &[u8]) {
    let events = run(vi, json!(false), false);
    assert!(!events.iter().any(|e| kind(e) == "speakers"), "{events:?}");
    assert!(finals(&events).iter().any(|e| text(e).contains("đất nước")), "{events:?}");
    let events = run(&silence(3), json!(true), false);
    assert!(!events.iter().any(|e| kind(e) == "speakers"), "{events:?}");
}

fn cancellation_releases_engine(vi: &[u8]) {
    let mut client = Client::connect(json!({"speakers": true}));
    while kind(&client.recv()) != "ready" {}
    client.send_pcm(vi);
    client.end();
    loop {
        let event = client.recv();
        if kind(&event) == "status" && text(&event).contains("bản chữ gốc") {
            break;
        }
    }
    client.close();
    assert!(engine_free(Duration::from_secs(20)), "Cancellation did not release the engine");
    let events = run(vi, json!(false), false);
    assert_eq!(kind(events.last().unwrap()), "done");
}

/// `SESSIONS` sessions recognize at once; one more is refused with an `error` event.
fn concurrent_sessions(vi: &[u8]) {
    let (mut clients, mut events) = hold_all(Duration::from_secs(10)).expect("engine slots not released");
    let mut extra = Client::connect(json!({}));
    let refused = extra.until_ready();
    assert_eq!(kind(refused.last().unwrap()), "error", "{refused:?}");
    extra.close();
    for client in &mut clients {
        client.send_pcm(vi);
        client.end();
    }
    for (client, events) in clients.iter_mut().zip(&mut events) {
        client.rest(events);
        assert_eq!(kind(events.last().unwrap()), "done", "{events:?}");
        assert!(finals(events).iter().any(|event| text(event).contains("đất nước")), "{events:?}");
    }
    clients.into_iter().for_each(Client::close);
    assert!(engine_free(Duration::from_secs(10)), "Session slot leaked");
}

/// A plain HTTP response.
struct Http {
    status: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Http {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(key, _)| key.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
    }

    fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
}

/// One HTTP/1.1 request to the download port; `head` sends `HEAD` instead of `GET`.
fn http(path: &str, headers: &[(&str, &str)], head: bool) -> Http {
    let mut stream = TcpStream::connect(DOWNLOADS).unwrap();
    stream.set_read_timeout(Some(TIMEOUT)).unwrap();
    let method = if head { "HEAD" } else { "GET" };
    let mut request = format!("{method} {path} HTTP/1.1\r\nHost: {DOWNLOADS}\r\nConnection: close\r\n");
    for (name, value) in headers {
        request += &format!("{name}: {value}\r\n");
    }
    stream.write_all(format!("{request}\r\n").as_bytes()).unwrap();
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();
    let status = line.split(' ').nth(1).unwrap().parse().unwrap();
    let mut headers = Vec::new();
    loop {
        line.clear();
        reader.read_line(&mut line).unwrap();
        let Some((name, value)) = line.trim_end().split_once(": ") else { break };
        headers.push((name.to_owned(), value.to_owned()));
    }
    let mut body = Vec::new();
    if !head {
        reader.read_to_end(&mut body).unwrap();
    }
    Http { status, headers, body }
}

/// Serves files from `dir` over HTTP on a free local port until the test process ends, so
/// yt-dlp and ffprobe can read a clip as a link. Range requests get the whole file.
fn serve_files(dir: PathBuf) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for stream in listener.incoming().filter_map(Result::ok) {
            let dir = dir.clone();
            std::thread::spawn(move || {
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut request = String::new();
                if reader.read_line(&mut request).is_err() {
                    return;
                }
                let mut line = String::new();
                while reader.read_line(&mut line).is_ok_and(|read| read > 2) {
                    line.clear();
                }
                let mut parts = request.split(' ');
                let (method, path) = (parts.next().unwrap_or_default(), parts.next().unwrap_or_default());
                let mut stream = stream;
                let response = match std::fs::read(dir.join(path.trim_start_matches('/'))) {
                    Ok(body) => {
                        let head = format!("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                        [head.into_bytes(), if method == "HEAD" { Vec::new() } else { body }].concat()
                    }
                    Err(_) => b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),
                };
                let _ = stream.write_all(&response);
            });
        }
    });
    port
}

/// An H.264/AAC MP4 of `audio` (PCM16 16 kHz) repeated `times`, over a gray picture.
fn clip(audio: &[u8], times: usize, path: &Path) {
    let wav = path.with_extension("pcm");
    std::fs::write(&wav, audio.repeat(times)).unwrap();
    let status = Command::new("ffmpeg")
        .args(["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=gray:s=320x240:r=25", "-f", "s16le", "-ar", "16000", "-ac", "1", "-i"])
        .arg(&wav)
        .args(["-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart"])
        .arg(path)
        .status()
        .unwrap();
    assert!(status.success(), "ffmpeg could not make {}", path.display());
}

/// A job connection's events until the server closes it.
fn job_events(command: Value, token: Option<&str>, timeout: Duration) -> Vec<Value> {
    let mut client = Client::connect_with(command, token);
    let mut events = Vec::new();
    let deadline = Instant::now() + timeout;
    while let Some(event) = client.poll(deadline) {
        events.push(event);
    }
    events
}

fn error_code(events: &[Value]) -> &str {
    assert_eq!(events.len(), 1, "{events:?}");
    assert_eq!(kind(&events[0]), "error", "{events:?}");
    events[0]["code"].as_str().unwrap()
}

fn router_ready() -> bool {
    TcpStream::connect("127.0.0.1:5002").is_ok()
}

fn dub_jobs(vi: &[u8]) {
    let list = json!({"version": 1, "type": "list"});
    assert_eq!(error_code(&job_events(list.clone(), None, TIMEOUT)), "invalid_token");
    assert_eq!(error_code(&job_events(list.clone(), Some("wrong"), TIMEOUT)), "invalid_token");
    let old = job_events(json!({"version": 9, "type": "list"}), Some(TOKEN), TIMEOUT);
    assert_eq!(error_code(&old), "unsupported_version");
    assert_eq!(old[0]["supported"], json!([1]));
    assert_eq!(job_events(list.clone(), Some(TOKEN), TIMEOUT), [json!({"version": 1, "type": "jobs", "jobs": []})]);
    let missing = json!({"version": 1, "type": "job", "job_id": "j_0000000000000000"});
    assert_eq!(error_code(&job_events(missing, Some(TOKEN), TIMEOUT)), "job_not_found");
    let french = json!({"version": 1, "type": "dub", "url": "https://example.com/v", "target_language": "fr"});
    assert_eq!(error_code(&job_events(french, Some(TOKEN), TIMEOUT)), "unsupported_language");
    if !router_ready() {
        let dub = json!({"version": 1, "type": "dub", "url": "https://example.com/v", "target_language": "vi"});
        assert_eq!(error_code(&job_events(dub, Some(TOKEN), TIMEOUT)), "dub_disabled");
        eprintln!("Translation router not running on 5002: skipped the dub job run.");
        return;
    }

    let media = Scratch::new("media");
    clip(vi, 1, &media.0.join("short.mp4"));
    clip(vi, 15, &media.0.join("long.mp4"));
    let port = serve_files(media.0.clone());
    let link = |name: &str| format!("http://127.0.0.1:{port}/{name}");
    let missing = json!({"version": 1, "type": "dub", "url": link("missing.mp4"), "target_language": "en"});
    assert_eq!(error_code(&job_events(missing, Some(TOKEN), Duration::from_secs(60))), "invalid_link");

    // A full job: queued, progress, done, then the files over HTTP.
    let dub = json!({"version": 1, "type": "dub", "url": link("short.mp4"), "target_language": "en"});
    let events = job_events(dub, Some(TOKEN), Duration::from_secs(300));
    assert_eq!((kind(&events[0]), events[0]["state"].as_str()), ("job", Some("queued")), "{events:?}");
    assert_eq!((events[0]["title"].as_str(), events[0]["duration_s"].as_f64().map(f64::round)), (Some("short"), Some(4.0)));
    let stages: Vec<&str> = events.iter().filter(|event| kind(event) == "progress").filter_map(|event| event["stage"].as_str()).collect();
    for stage in ["downloading", "recognizing", "muxing"] {
        assert!(stages.contains(&stage), "no {stage} progress: {events:?}");
    }
    let done = events.last().unwrap();
    assert_eq!((kind(done), done["state"].as_str()), ("job", Some("done")), "{events:?}");
    assert!(done["expires_at"].is_string());
    let id = done["job_id"].as_str().unwrap().to_owned();
    let playback = done["playback"]["path"].as_str().unwrap().to_owned();
    let hls = playback.trim_end_matches("master.m3u8").to_owned();

    let master = http(&playback, &[], false);
    assert_eq!((master.status, master.header("Content-Type")), (200, Some("application/vnd.apple.mpegurl")));
    assert!(master.header("Cache-Control").is_some_and(|value| value.starts_with("private, max-age=")));
    let master = master.text();
    assert!(master.contains("TYPE=SUBTITLES") && master.contains("LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES"), "{master}");
    assert!(master.contains("CODECS=\"avc1.") && master.contains("SUBTITLES=\"subs\""), "{master}");
    let stream = http(&format!("{hls}stream.m3u8"), &[], false).text();
    assert!(stream.contains("#EXT-X-PLAYLIST-TYPE:VOD") && stream.contains("#EXT-X-ENDLIST"), "{stream}");
    assert!(http(&format!("{hls}subtitles.vtt"), &[], false).text().starts_with("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:"));
    assert_eq!(http(&playback.replace("/hls/", "/hls/x"), &[], false).status, 404);

    let srt = done["files"]["subtitles"]["path"].as_str().unwrap();
    assert_eq!(http(srt, &[], false).status, 401);
    let bearer = format!("Bearer {TOKEN}");
    let full = http(srt, &[("Authorization", &bearer)], false);
    assert_eq!(full.status, 200);
    assert_eq!(full.body.len() as u64, done["files"]["subtitles"]["size"].as_u64().unwrap());
    let etag = full.header("ETag").unwrap().to_owned();
    let part = http(srt, &[("Authorization", &bearer), ("Range", "bytes=0-9"), ("If-Range", &etag)], false);
    assert_eq!((part.status, part.body.as_slice()), (206, &full.body[..10]));
    assert_eq!(part.header("Content-Range"), Some(format!("bytes 0-9/{}", full.body.len()).as_str()));
    let stale = http(srt, &[("Authorization", &bearer), ("Range", "bytes=0-9"), ("If-Range", "\"old\"")], false);
    assert_eq!(stale.status, 200);
    let head = http(srt, &[("Authorization", &bearer)], true);
    assert_eq!((head.status, head.header("Content-Length")), (200, Some(full.body.len().to_string().as_str())));

    let listed = job_events(list.clone(), Some(TOKEN), TIMEOUT);
    assert_eq!(listed[0]["jobs"][0]["job_id"], json!(id));
    let again = job_events(json!({"version": 1, "type": "job", "job_id": id}), Some(TOKEN), TIMEOUT);
    assert_eq!((again.len(), again[0]["state"].as_str()), (1, Some("done")));

    // One job per token: a second dub is refused, and cancel from another connection ends
    // the first one's stream.
    let mut running = Client::connect_with(json!({"version": 1, "type": "dub", "url": link("long.mp4"), "target_language": "en"}), Some(TOKEN));
    let first = running.recv();
    assert_eq!(first["state"], "queued", "{first}");
    let other = job_events(json!({"version": 1, "type": "dub", "url": link("short.mp4"), "target_language": "en"}), Some(TOKEN), TIMEOUT);
    assert_eq!(error_code(&other), "job_limit");
    assert_eq!(other[0]["job_id"], first["job_id"]);
    let cancelled = job_events(json!({"version": 1, "type": "cancel", "job_id": first["job_id"]}), Some(TOKEN), Duration::from_secs(60));
    assert_eq!(cancelled.last().map(|event| event["state"].clone()), Some(json!("cancelled")), "{cancelled:?}");
    let mut rest = Vec::new();
    running.rest(&mut rest);
    assert_eq!(rest.last().map(|event| event["state"].clone()), Some(json!("cancelled")), "{rest:?}");

    // Delete removes the files and the id.
    let deleted = job_events(json!({"version": 1, "type": "delete", "job_id": id}), Some(TOKEN), TIMEOUT);
    assert_eq!(deleted, [json!({"version": 1, "type": "deleted", "job_id": id})]);
    assert_eq!(http(&playback, &[], false).status, 404);
    assert_eq!(error_code(&job_events(json!({"version": 1, "type": "job", "job_id": id}), Some(TOKEN), TIMEOUT)), "job_not_found");
}

/// One test: the server limits concurrent sessions on a fixed port.
#[test]
#[ignore = "needs downloaded models and build/onplay-server"]
fn streaming_socket() {
    let vi = pcm(&root().join("models/vi.wav"));
    let en = pcm(&root().join(MODEL).join("test_wavs/en.wav"));
    let _server = Server::start();
    live_stream(&vi);
    concurrent_sessions(&vi);
    two_speakers(&vi, &en);
    live_speaker_mode(&vi, &en);
    disabled_mode(&vi);
    cancellation_releases_engine(&vi);
    dub_jobs(&vi);
}
