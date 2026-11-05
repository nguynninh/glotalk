//! Real-model socket checks, ported from `tests/live_stream.py` and `tests/speakers.py`.
//!
//! Starts `build/onplay-server` (from `./scripts/dev/build.sh`) rather than the cargo binary:
//! the server loads ONNX Runtime from `lib/` next to itself, the copy Sherpa ONNX already
//! loaded. Needs a free port 8766, `./scripts/setup/stt.sh` and `setup/speakers.sh`; run with
//! `cargo test -p onplay-server-cli -- --include-ignored` (as `scripts/dev/test.sh` does).
//! Translation stays optional: without a router the server reports translation errors only.

use std::io::ErrorKind;
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tungstenite::client::IntoClientRequest;
use tungstenite::http::HeaderValue;
use tungstenite::{Error, Message, WebSocket};

const ADDRESS: &str = "127.0.0.1:8766";
const ORIGIN: &str = "http://127.0.0.1:8765";
const MODEL: &str = "models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10";
const TIMEOUT: Duration = Duration::from_secs(20);

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

/// Kills the server when the test ends, including on assertion failure.
struct Server(Child);

impl Server {
    fn start() -> Self {
        assert!(TcpStream::connect(ADDRESS).is_err(), "Port 8766 is in use; stop the running onplay-server first.");
        let binary = root().join("build/onplay-server");
        assert!(binary.is_file(), "{} not found; run ./scripts/dev/build.sh", binary.display());
        let server = Self(Command::new(binary).stdout(Stdio::null()).spawn().unwrap());
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
        let mut request = format!("ws://{ADDRESS}").into_client_request().unwrap();
        request.headers_mut().insert("Origin", HeaderValue::from_static(ORIGIN));
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

/// The server refuses a second session while one holds the engine.
fn engine_free(timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let mut client = Client::connect(json!({}));
        let free = kind(&client.recv()) != "error";
        client.close();
        if free {
            return true;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    false
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

/// One test: the server allows one session at a time on a fixed port.
#[test]
#[ignore = "needs downloaded models and build/onplay-server"]
fn streaming_socket() {
    let vi = pcm(&root().join("models/vi.wav"));
    let en = pcm(&root().join(MODEL).join("test_wavs/en.wav"));
    let _server = Server::start();
    live_stream(&vi);
    two_speakers(&vi, &en);
    live_speaker_mode(&vi, &en);
    disabled_mode(&vi);
    cancellation_releases_engine(&vi);
}
