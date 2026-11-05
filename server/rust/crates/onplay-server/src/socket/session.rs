//! One connection: options, then PCM chunks until `{"type": "end"}`.

use std::net::TcpStream;
use std::sync::atomic::Ordering;
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::sync::Arc;
use std::time::{Duration, Instant};

use onplay_stt::diarize::SpeakerModels;
use onplay_stt::EngineConfig;
use serde_json::{json, Value};
use tungstenite::WebSocket;

use super::client_config::{ClientConfig, Speakers};
use super::outbox::Outbox;
use super::transport::{Ended, Incoming, Transport};
use super::MAX_MESSAGE_BYTES;
use crate::engine_lock::EngineGuard;
use crate::models::embedding_is_valid;
use crate::pipeline::{self, Input, Setup, Shared};
use crate::sessions::{self, Recorder, WavFile, MAX_CAPTURE_BYTES};
use crate::{Event, Outcome, Services};

const CONFIG_TIMEOUT: Duration = Duration::from_secs(10);
/// End-of-input diarization keeps at most 10 minutes of PCM16 at 16 kHz.
const MAX_SPEAKER_BYTES: usize = 16_000 * 2 * 600;
/// PCM chunks waiting for the recognition worker before reading pauses.
const INPUT_QUEUE: usize = 64;
const INPUT_RETRY: Duration = Duration::from_millis(5);
/// PCM16 mono at 16 kHz.
const PCM_BYTES_PER_SECOND: f64 = 32_000.0;

enum Failure {
    Closed,
    /// Sent to the client in an `error` event, then the connection closes.
    Message(String),
}

impl From<Ended> for Failure {
    fn from(_: Ended) -> Self {
        Self::Closed
    }
}

fn fail<T>(text: impl Into<String>) -> Result<T, Failure> {
    Err(Failure::Message(text.into()))
}

/// The started session, torn down when the connection ends.
struct Running {
    shared: Arc<Shared>,
    input: Option<SyncSender<Input>>,
    /// Disconnects when the session's recognition job has finished on the worker.
    recognition: Option<Receiver<()>>,
    /// PCM for end-of-input diarization; dropped past 10 minutes.
    recording: Option<Vec<u8>>,
    capture: Option<WavFile>,
    input_finished: bool,
    _engine: EngineGuard,
}

pub fn run(socket: WebSocket<TcpStream>, services: &Arc<Services>, connection: u64) {
    let opened = Instant::now();
    let (outbox, outgoing) = Outbox::new();
    let Ok(mut transport) = Transport::new(socket, outgoing) else { return };
    let mut running = None;
    let mut outcome = Outcome::Closed;
    if let Err(Failure::Message(text)) = serve(&mut transport, &outbox, services, &mut running, connection) {
        let _ = outbox.emit(&json!({"type": "error", "text": text}));
        outcome = Outcome::Error(text);
    }
    outbox.cancel();
    if let Some(running) = running.as_mut() {
        // Unblocks the recognition job, which closes the connection when it ends; the
        // engine lock is released only after that.
        running.input.take();
        if let Some(finished) = running.recognition.take() {
            let _ = finished.recv();
        }
        if let Some(recorder) = running.shared.recorder.as_ref().filter(|_| !running.shared.completed.load(Ordering::SeqCst)) {
            recorder.status("cancelled");
        }
    }
    outbox.close();
    drop(outbox);
    // Send what is queued and finish the closing handshake.
    while transport.next(None).is_ok() {}
    let shared = running.as_ref().map(|running| &running.shared);
    if shared.is_some_and(|shared| shared.completed.load(Ordering::SeqCst)) {
        outcome = Outcome::Done;
    }
    let received = shared.map_or(0, |shared| shared.received.load(Ordering::Relaxed));
    services.observer.event(Event::Ended {
        connection,
        outcome,
        duration: opened.elapsed(),
        audio: Duration::from_secs_f64(received as f64 / PCM_BYTES_PER_SECOND),
        segments: shared.map_or(0, |shared| shared.segment_id.load(Ordering::Relaxed)),
    });
}

fn serve(transport: &mut Transport, outbox: &Outbox, services: &Arc<Services>, running: &mut Option<Running>, connection: u64) -> Result<(), Failure> {
    let config = match transport.next(Some(Instant::now() + CONFIG_TIMEOUT))? {
        Some(Incoming::Text(text)) => ClientConfig::parse(&text).or_else(fail)?,
        Some(Incoming::Binary(_)) => return fail("Cấu hình không hợp lệ."),
        None => return fail("Không nhận được cấu hình trong 10 giây."),
    };
    *running = Some(start(&config, outbox, services)?);
    let running = running.as_mut().expect("just started");
    services.observer.event(Event::Started {
        connection,
        source: config.source.clone(),
        target: config.target.clone(),
        speech: config.speech,
        speakers: config.speakers,
        session: running.shared.recorder.as_ref().map(|recorder| recorder.id().to_owned()),
    });
    loop {
        let Some(message) = transport.next(None)? else { continue };
        if running.input_finished {
            return fail("PCM đã kết thúc.");
        }
        match message {
            Incoming::Binary(pcm) => receive(transport, running, services, pcm)?,
            Incoming::Text(text) => match serde_json::from_str::<Value>(&text) {
                Ok(message) if message.get("type").and_then(Value::as_str) == Some("end") => {
                    running.input_finished = true;
                    let recording = running.recording.take();
                    running.capture.take();
                    send(transport, running, Input::End { recording })?;
                }
                _ => return fail("Thông điệp streaming không hợp lệ."),
            },
        }
    }
}

fn start(config: &ClientConfig, outbox: &Outbox, services: &Arc<Services>) -> Result<Running, Failure> {
    let paths = &services.config;
    let warn = |text: &str| {
        let _ = outbox.emit(&json!({"type": "warning", "text": text}));
    };
    let mut speakers = config.speakers;
    let live_speakers = speakers != Speakers::Off && embedding_is_valid(&paths.embedding);
    if speakers != Speakers::Off && paths.embedding.is_file() && !live_speakers {
        speakers = Speakers::Off;
        warn("Kiểm tra model người nói thất bại. Chạy ./scripts/setup/speakers.sh. Vẫn nhận dạng chữ.");
    }
    let store = config.store || paths.capture.transcript();
    let Some(engine) = services.lock.try_acquire() else { return fail("Engine đang chạy phiên khác. Hãy dừng phiên đó trước.") };
    let id = sessions::new_id();
    let recorder = if store {
        let recorder = Recorder::create(&paths.session_dir, &id, &config.target, &config.source).or_else(|error| fail(error.to_string()))?;
        Some(Arc::new(recorder))
    } else {
        None
    };
    let shared = Arc::new(Shared::new(outbox.clone(), config, services.translator.clone(), services.speech.clone(), recorder.clone()));
    pipeline::start_lanes(&shared);
    let _ = outbox.emit(&json!({"type": "status", "text": "Đang nạp model · không tải video lên server…"}));
    let capture = if paths.capture.audio() { Some(sessions::open_audio(&paths.session_dir, &id).or_else(|error| fail(error.to_string()))?) } else { None };
    // "live" keeps realtime labels and voices but skips end-of-input diarization.
    let mut diarize = speakers == Speakers::Full;
    if diarize && !(paths.segmentation.is_file() && paths.embedding.is_file()) {
        diarize = false;
        warn("Thiếu model người nói. Chạy ./scripts/setup/speakers.sh. Vẫn nhận dạng chữ.");
    }
    let setup = Setup {
        engine: EngineConfig {
            model: paths.stt.clone(),
            threads: config.threads,
            speaker_model: live_speakers.then(|| paths.embedding.clone()),
            low_latency: !config.target.is_empty(),
        },
        chunk_ms: config.chunk_ms,
        diarize: diarize.then(|| SpeakerModels { stt: paths.stt.clone(), segmentation: paths.segmentation.clone(), embedding: paths.embedding.clone() }),
    };
    let (input, received) = mpsc::sync_channel(INPUT_QUEUE);
    let recognition = {
        let (shared, passes) = (shared.clone(), services.clone());
        services.recognition.run(move |models| pipeline::run(models, &shared, &setup, &received, &passes.context_passes))
    };
    Ok(Running {
        shared,
        input: Some(input),
        recognition: Some(recognition),
        recording: diarize.then(Vec::new),
        capture,
        input_finished: false,
        _engine: engine,
    })
}

fn receive(transport: &mut Transport, running: &mut Running, services: &Services, pcm: Vec<u8>) -> Result<(), Failure> {
    if pcm.is_empty() || pcm.len() % 2 != 0 || pcm.len() > MAX_MESSAGE_BYTES {
        return fail("PCM16 chunk không hợp lệ.");
    }
    if let Some(recording) = &mut running.recording {
        if recording.len() + pcm.len() <= MAX_SPEAKER_BYTES {
            recording.extend_from_slice(&pcm);
        } else {
            running.recording = None;
            running.shared.warn("Audio dài hơn 10 phút: vẫn hiển thị nhãn người nói tạm, bỏ bước phân đoạn lại cuối file.");
        }
    }
    let received = running.shared.received.load(Ordering::Relaxed);
    if let Some(capture) = &mut running.capture {
        if received + pcm.len() as u64 <= MAX_CAPTURE_BYTES && capture.write(&pcm).is_err() {
            return fail(format!("Không ghi được audio phiên vào {}.", services.config.session_dir.display()));
        }
    }
    running.shared.received.fetch_add(pcm.len() as u64, Ordering::Relaxed);
    send(transport, running, Input::Pcm(pcm))
}

/// Hands input to the recognition worker, writing events while it catches up. Input after
/// the recognition job stopped (engine error or done) is dropped; that job is
/// already closing the connection.
fn send(transport: &mut Transport, running: &Running, mut input: Input) -> Result<(), Failure> {
    let Some(sender) = &running.input else { return Ok(()) };
    loop {
        match sender.try_send(input) {
            Ok(()) | Err(TrySendError::Disconnected(_)) => return Ok(()),
            Err(TrySendError::Full(back)) => {
                input = back;
                transport.write_queued()?;
                std::thread::sleep(INPUT_RETRY);
            }
        }
    }
}
