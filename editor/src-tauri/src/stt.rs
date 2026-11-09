//! Nhận dạng giọng nói qua server Glotalk (onplay-server, xem `server/docs/streaming-audio.md`).
//!
//! WebSocket chạy bên Rust vì server kiểm tra header `Origin`, mà webview không đặt được header đó.
//! Server không gửi mốc thời gian cho câu `final`, nên mốc được ước lượng từ sự kiện `progress`
//! (mỗi ~1 s âm thanh đã xử lý) rồi dịch về khoảng lặng gần nhất trong audio gốc.
use crate::error::{AppError, AppResult};
use crate::media::ffmpeg::find_tool;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message;

const RATE: usize = 16_000;
/// 100 ms PCM16 mỗi message, như tài liệu server khuyên (tối đa 32.000 byte).
const CHUNK_BYTES: usize = 3_200;
/// Server bỏ dịch khi 64 câu đang chờ; giữ số câu chưa có bản dịch dưới mức này.
const MAX_PENDING_TRANSLATIONS: i64 = 24;
/// Origin mặc định server chấp nhận cho kết nối `ws://` cục bộ (`LOCAL_ORIGIN` bên server).
const LOCAL_ORIGIN: &str = "http://127.0.0.1:8765";

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SttJob {
    pub server: String,
    pub path: String,
    pub source_language: String,
    /// Rỗng: chỉ nhận dạng, không dịch.
    pub target_language: String,
    /// Bật `speakers: true` của server: nhãn người nói từ phân đoạn cuối phiên.
    #[serde(default)]
    pub speakers: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SttSegment {
    pub start: u64,
    pub end: u64,
    pub text: String,
    pub translation: String,
    /// Nhãn người nói 1-based của server; 0 hoặc thiếu là chưa xác định.
    pub speaker: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SttOutput {
    pub segments: Vec<SttSegment>,
    pub audio_seconds: f64,
    pub seconds: f64,
    /// Cảnh báo từ server (ví dụ dịch không theo kịp), để hiện trong log.
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SttProgress {
    /// "audio" | "connect" | "stt"
    pub phase: &'static str,
    pub done_s: f64,
    pub total_s: f64,
    pub last_text: String,
}

/// `https://host` → (`wss://host`, Origin `https://host`); `ws://`/`http://` dùng Origin cục bộ.
fn endpoint(server: &str) -> AppResult<(String, String)> {
    let s = server.trim().trim_end_matches('/');
    let (secure, rest) = if let Some(r) = s.strip_prefix("https://").or_else(|| s.strip_prefix("wss://")) {
        (true, r)
    } else if let Some(r) = s.strip_prefix("http://").or_else(|| s.strip_prefix("ws://")) {
        (false, r)
    } else if !s.is_empty() {
        (true, s)
    } else {
        return Err(AppError::Other("Chưa nhập địa chỉ server.".into()));
    };
    let host = rest.split('/').next().unwrap_or(rest);
    Ok(if secure {
        (format!("wss://{rest}"), format!("https://{host}"))
    } else {
        (format!("ws://{rest}"), LOCAL_ORIGIN.to_owned())
    })
}

type Socket = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn connect(server: &str) -> AppResult<Socket> {
    let (url, origin) = endpoint(server)?;
    let mut request = url.as_str().into_client_request().map_err(|e| AppError::Other(format!("Địa chỉ server không hợp lệ: {e}")))?;
    request.headers_mut().insert("Origin", HeaderValue::from_str(&origin).map_err(|e| AppError::Other(e.to_string()))?);
    let connecting = tokio_tungstenite::connect_async(request);
    let (socket, _) = tokio::time::timeout(Duration::from_secs(15), connecting)
        .await
        .map_err(|_| AppError::Other(format!("Hết thời gian kết nối {url}.")))?
        .map_err(|e| AppError::Other(format!("Không kết nối được {url}: {e}")))?;
    Ok(socket)
}

fn event_text(event: &Value) -> String {
    event["text"].as_str().or_else(|| event["message"].as_str()).unwrap_or("lỗi không rõ").to_owned()
}

/// Gửi cấu hình rồi chờ `ready`; trả về các `status` nhận được trên đường đi qua `on_status`.
async fn start(socket: &mut Socket, config: Value, mut on_status: impl FnMut(&str)) -> AppResult<()> {
    socket.send(Message::text(config.to_string())).await.map_err(ws_err)?;
    // Lần đầu server có thể phải nạp model; status báo việc đó.
    let deadline = Instant::now() + Duration::from_secs(120);
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let msg = tokio::time::timeout(left, socket.next())
            .await
            .map_err(|_| AppError::Other("Server không trả `ready`.".into()))?;
        let Some(msg) = msg else { return Err(AppError::Other("Server đóng kết nối trước khi sẵn sàng.".into())) };
        let Message::Text(text) = msg.map_err(ws_err)? else { continue };
        let event: Value = serde_json::from_str(&text)?;
        match event["type"].as_str() {
            Some("ready") => return Ok(()),
            Some("status") => on_status(&event_text(&event)),
            Some("error") => return Err(AppError::Other(format!("Server: {}", event_text(&event)))),
            _ => {}
        }
    }
}

fn ws_err(e: tokio_tungstenite::tungstenite::Error) -> AppError {
    AppError::Other(format!("WebSocket: {e}"))
}

/// Mở một phiên chỉ nhận dạng, chờ `ready` rồi đóng: kiểm tra địa chỉ, Origin và slot phiên trống.
pub async fn check(server: &str) -> AppResult<String> {
    let started = Instant::now();
    let mut socket = connect(server).await?;
    start(&mut socket, json!({"source_language": "auto"}), |_| {}).await?;
    let _ = socket.send(Message::text(r#"{"type":"end"}"#)).await;
    let _ = socket.close(None).await;
    Ok(format!("{} ms", started.elapsed().as_millis()))
}

/// Giải mã âm thanh của file thành PCM16 mono 16 kHz trong bộ nhớ (1 giờ ≈ 115 MB).
pub(crate) fn decode_pcm(path: &str) -> AppResult<Vec<u8>> {
    let out = std::process::Command::new(find_tool("ffmpeg")?)
        .args(["-hide_banner", "-loglevel", "error", "-i", path, "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-"])
        .output()?;
    if !out.status.success() {
        return Err(AppError::Other(String::from_utf8_lossy(&out.stderr).into()));
    }
    Ok(out.stdout)
}

/// Mức âm (RMS) theo từng khung 20 ms, dùng tìm khoảng lặng khi canh mốc.
fn frame_energy(pcm: &[u8]) -> Vec<f32> {
    pcm.chunks(RATE / 50 * 2)
        .map(|f| {
            let n = (f.len() / 2).max(1) as f32;
            let sum: f32 = f.chunks_exact(2).map(|c| (i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0).powi(2)).sum();
            (sum / n).sqrt()
        })
        .collect()
}

/// Canh mốc thời gian của từng câu theo audio gốc.
///
/// `first_seen`/`final_at`: vị trí `progress` cuối cùng (giây) trước partial đầu và trước final.
/// Vị trí thật của final nằm trong [final_at, final_at + 1 s]; điểm kết thúc là khung còn tiếng
/// cuối cùng trong cửa sổ đó. Điểm bắt đầu là khung có tiếng đầu tiên sau câu trước.
fn align(raw: &[(f64, f64)], energy: &[f32], total_ms: u64) -> Vec<(u64, u64)> {
    const FRAME_MS: f64 = 20.0;
    let noise = {
        let mut sorted: Vec<f32> = energy.iter().copied().filter(|e| *e > 0.0).collect();
        sorted.sort_by(|a, b| a.total_cmp(b));
        sorted.get(sorted.len() / 10).copied().unwrap_or(0.0)
    };
    let voiced = |i: usize| energy.get(i).is_some_and(|e| *e > (noise * 3.0).max(0.01));
    let frame = |ms: f64| (ms / FRAME_MS).max(0.0) as usize;
    let mut prev_end = 0.0f64;
    raw.iter()
        .map(|&(first_seen, final_at)| {
            let win_end = ((final_at + 1.0) * 1000.0).min(total_ms as f64);
            let end = (frame(final_at * 1000.0)..frame(win_end))
                .rev()
                .find(|&i| voiced(i))
                .map(|i| (i + 1) as f64 * FRAME_MS + 150.0)
                .unwrap_or(win_end)
                .min(win_end);
            // Partial đầu thường đến sau khi bắt đầu nói vài trăm ms; tìm tiếng từ cuối câu trước.
            let latest_start = ((first_seen + 1.0) * 1000.0).min(end - 200.0);
            let start = (frame(prev_end)..frame(latest_start))
                .find(|&i| voiced(i))
                .map(|i| (i as f64 * FRAME_MS - 100.0).max(prev_end))
                .unwrap_or_else(|| (first_seen * 1000.0 - 300.0).max(prev_end))
                .min(end - 200.0)
                .max(prev_end);
            prev_end = end;
            (start as u64, end as u64)
        })
        .collect()
}

#[derive(Default)]
struct Line {
    first_seen: Option<f64>,
    final_at: Option<f64>,
    text: String,
    translation: String,
    revision: u8,
    /// Nhãn trực tiếp; nhãn phân đoạn cuối phiên được ưu tiên.
    speaker: u32,
}

fn revision_rank(r: &str) -> u8 {
    match r {
        "provisional" => 1,
        "live" => 2,
        "context" => 3,
        _ => 0,
    }
}

pub async fn transcribe(job: SttJob, cancel: Arc<AtomicBool>, progress: impl Fn(SttProgress)) -> AppResult<SttOutput> {
    let started = Instant::now();
    progress(SttProgress { phase: "audio", done_s: 0.0, total_s: 0.0, last_text: String::new() });
    let path = job.path.clone();
    let pcm = tauri::async_runtime::spawn_blocking(move || decode_pcm(&path))
        .await
        .map_err(|e| AppError::Other(e.to_string()))??;
    let total_s = pcm.len() as f64 / (RATE * 2) as f64;
    if pcm.len() < 2 {
        return Err(AppError::Other("File không có âm thanh.".into()));
    }

    progress(SttProgress { phase: "connect", done_s: 0.0, total_s, last_text: String::new() });
    let mut socket = connect(&job.server).await?;
    let translating = !job.target_language.is_empty();
    let mut config = if translating {
        json!({"source_language": job.source_language, "target_language": job.target_language})
    } else {
        json!({"source_language": job.source_language})
    };
    if job.speakers {
        config["speakers"] = true.into();
    }
    start(&mut socket, config, |s| progress(SttProgress { phase: "connect", done_s: 0.0, total_s, last_text: s.to_owned() })).await?;
    let (mut sink, mut stream) = socket.split();

    // Số câu đã chốt mà chưa có bản dịch `live`; bên gửi chờ khi con số này cao.
    let pending = Arc::new(AtomicI64::new(0));
    let pcm = Arc::new(pcm);
    let sender = {
        let (pcm, cancel, pending) = (pcm.clone(), cancel.clone(), pending.clone());
        tauri::async_runtime::spawn(async move {
            for chunk in pcm.chunks(CHUNK_BYTES) {
                while translating && pending.load(Ordering::Relaxed) > MAX_PENDING_TRANSLATIONS && !cancel.load(Ordering::Relaxed) {
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
                if cancel.load(Ordering::Relaxed) {
                    return sink.close().await.map_err(ws_err);
                }
                sink.send(Message::binary(chunk.to_vec())).await.map_err(ws_err)?;
            }
            sink.send(Message::text(r#"{"type":"end"}"#)).await.map_err(ws_err)?;
            Ok(())
        })
    };

    let mut lines: HashMap<u64, Line> = HashMap::new();
    let mut position = 0.0f64;
    let mut warnings = Vec::new();
    // Lượt nói từ sự kiện `speakers` (giây): phân đoạn lại cả bản ghi sau `end`.
    let mut turns: Vec<(f64, f64, u32)> = Vec::new();
    let mut finished = false;
    while let Some(msg) = stream.next().await {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let Message::Text(text) = msg.map_err(ws_err)? else { continue };
        let event: Value = serde_json::from_str(&text)?;
        let id = event["id"].as_u64();
        match (event["type"].as_str().unwrap_or_default(), id) {
            ("progress", _) => {
                position = event["seconds"].as_f64().unwrap_or(position);
                progress(SttProgress { phase: "stt", done_s: position, total_s, last_text: String::new() });
            }
            ("partial", Some(id)) => {
                lines.entry(id).or_default().first_seen.get_or_insert(position);
            }
            ("final", Some(id)) => {
                let line = lines.entry(id).or_default();
                line.first_seen.get_or_insert(position);
                line.final_at = Some(position);
                line.text = event["text"].as_str().unwrap_or_default().to_owned();
                line.speaker = event["speaker"].as_u64().unwrap_or(0) as u32;
                if translating {
                    pending.fetch_add(1, Ordering::Relaxed);
                }
                progress(SttProgress { phase: "stt", done_s: position, total_s, last_text: line.text.clone() });
            }
            // Bản dịch lại các đoạn phân theo người nói đánh id riêng, trùng id câu trực tiếp.
            ("translation", _) if event["revision"].as_str() == Some("refined") => {}
            ("speakers", _) => {
                turns = event["segments"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(|t| Some((t["start"].as_f64()?, t["end"].as_f64()?, t["speaker"].as_u64()? as u32)))
                    .collect();
            }
            ("translation", Some(id)) => {
                let rank = revision_rank(event["revision"].as_str().unwrap_or_default());
                let line = lines.entry(id).or_default();
                if rank == 2 {
                    pending.fetch_sub(1, Ordering::Relaxed);
                }
                if rank >= line.revision {
                    line.revision = rank;
                    line.translation = event["translation"]["text"].as_str().unwrap_or_default().to_owned();
                }
            }
            ("translation_error", _) => {
                // Server ngừng dịch cho phiên này; bên gửi không cần chờ nữa.
                pending.store(i64::MIN, Ordering::Relaxed);
                warnings.push(event_text(&event));
            }
            ("warning", _) => warnings.push(event_text(&event)),
            ("error", _) => return Err(AppError::Other(format!("Server: {}", event_text(&event)))),
            ("done", _) => {
                finished = true;
                break;
            }
            _ => {}
        }
    }
    sender.abort();
    if cancel.load(Ordering::Relaxed) {
        return Err(AppError::Other("Đã huỷ.".into()));
    }
    if !finished {
        return Err(AppError::Other("Server đóng kết nối giữa chừng.".into()));
    }

    let mut finals: Vec<(u64, Line)> = lines.into_iter().filter(|(_, l)| l.final_at.is_some() && !l.text.trim().is_empty()).collect();
    finals.sort_by_key(|(id, _)| *id);
    let raw: Vec<(f64, f64)> = finals.iter().map(|(_, l)| (l.first_seen.unwrap_or(0.0), l.final_at.unwrap_or(0.0))).collect();
    let spans = align(&raw, &frame_energy(&pcm), (total_s * 1000.0) as u64);
    let segments = finals
        .into_iter()
        .zip(spans)
        .map(|((_, l), (start, end))| {
            let speaker = speaker_at(&turns, start, end).unwrap_or(l.speaker);
            SttSegment { start, end, text: l.text, translation: l.translation, speaker }
        })
        .collect();
    Ok(SttOutput { segments, audio_seconds: total_s, seconds: started.elapsed().as_secs_f64(), warnings })
}

/// Nhãn của lượt nói trùng câu `[start, end)` (ms) lâu nhất; None khi không trùng lượt nào.
fn speaker_at(turns: &[(f64, f64, u32)], start: u64, end: u64) -> Option<u32> {
    let (start, end) = (start as f64 / 1000.0, end as f64 / 1000.0);
    turns
        .iter()
        .map(|&(s, e, speaker)| (speaker, end.min(e) - start.max(s)))
        .filter(|(_, overlap)| *overlap > 0.0)
        .max_by(|a, b| a.1.total_cmp(&b.1))
        .map(|(speaker, _)| speaker)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_maps_scheme_and_origin() {
        assert_eq!(endpoint("https://stt.qiverse.top/").unwrap(), ("wss://stt.qiverse.top".into(), "https://stt.qiverse.top".into()));
        assert_eq!(endpoint("stt.qiverse.top").unwrap(), ("wss://stt.qiverse.top".into(), "https://stt.qiverse.top".into()));
        assert_eq!(endpoint("ws://127.0.0.1:8766").unwrap(), ("ws://127.0.0.1:8766".into(), LOCAL_ORIGIN.into()));
        assert!(endpoint(" ").is_err());
    }

    #[test]
    fn align_snaps_to_voiced_frames() {
        // 4 s: lặng 0–1 s, có tiếng 1–2.5 s, lặng tiếp.
        let energy: Vec<f32> = (0..200).map(|i| if (50..125).contains(&i) { 0.3 } else { 0.001 }).collect();
        // Partial thấy ở progress 1 s, final ở progress 2 s (thật ra trong 2–3 s).
        let spans = align(&[(1.0, 2.0)], &energy, 4000);
        assert_eq!(spans, vec![(900, 2650)]);
    }

    #[test]
    fn speaker_at_takes_longest_overlap() {
        let turns = [(0.0, 2.0, 1), (2.0, 5.0, 2)];
        assert_eq!(speaker_at(&turns, 1500, 4000), Some(2));
        assert_eq!(speaker_at(&turns, 0, 1200), Some(1));
        assert_eq!(speaker_at(&turns, 6000, 7000), None);
    }

    /// Kết nối server thật: `GLOTALK_STT=https://stt.qiverse.top cargo test --lib stt_live -- --nocapture`.
    #[test]
    fn stt_live_check() {
        let Ok(server) = std::env::var("GLOTALK_STT") else { return };
        let result = tauri::async_runtime::block_on(check(&server));
        println!("{server}: {result:?}");
        result.unwrap();
    }

    /// Nhận dạng + dịch một file thật: thêm `GLOTALK_STT_FILE=<đường dẫn media>`.
    #[test]
    fn stt_live_transcribe() {
        let (Ok(server), Ok(path)) = (std::env::var("GLOTALK_STT"), std::env::var("GLOTALK_STT_FILE")) else { return };
        let job = SttJob { server, path, source_language: std::env::var("GLOTALK_STT_LANG").unwrap_or("auto".into()), target_language: "vi".into(), speakers: std::env::var("GLOTALK_STT_SPEAKERS").is_ok() };
        let out = tauri::async_runtime::block_on(transcribe(job, Arc::default(), |_| {})).unwrap();
        println!("{out:#?}");
        assert!(!out.segments.is_empty());
    }
}
