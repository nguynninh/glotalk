//! Phân biệt người nói theo mốc từng câu phụ đề (OCR, STT hay SRT nhập).
//!
//! Mỗi câu lấy một đoạn âm thanh đúng theo mốc của nó, tính đặc trưng giọng bằng CAM++
//! (cùng model server dùng), rồi gom cụm một lần cho cả video nên nhãn nhất quán từ đầu đến cuối.
//! Câu quá ngắn không đủ tin cậy để gom cụm, chỉ được gán vào nhân vật gần nhất.
pub mod cluster;
pub mod fbank;
pub mod pitch;

use crate::error::{AppError, AppResult};
use cluster::{agglomerate, dot, normalize};
use fbank::{Fbank, BINS};
use ort::session::Session;
use ort::value::TensorRef;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

/// `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`, như `server/scripts/setup/speakers.sh`.
pub const MODEL_URL: &str =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx";
pub const MODEL_FILE: &str = "speaker_campplus_zh-cn_16k.onnx";
pub const MODEL_BYTES: u64 = 28_281_138;

const RATE: usize = 16_000;
/// Câu ngắn hơn thì đặc trưng giọng nhiễu, không đưa vào gom cụm.
const RELIABLE_MS: u64 = 1_200;
/// Dưới mức này không tính đặc trưng giọng.
const MIN_MS: u64 = 400;
/// Câu dài chỉ lấy đoạn giữa; 10 s đủ cho đặc trưng ổn định.
const MAX_MS: u64 = 10_000;
/// Câu ngắn và cụm một câu chỉ được gán vào nhân vật có độ tương đồng tối thiểu này.
const ASSIGN_MIN: f32 = 0.3;
/// Số câu dài nhất mỗi nhân vật dùng để đo cao độ.
const PITCH_LINES: usize = 20;

#[derive(Debug, Clone, Copy, Deserialize)]
pub struct Span {
    pub start: u64,
    pub end: u64,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Options {
    /// Độ tương đồng tối thiểu để gộp hai nhóm câu thành một nhân vật (0..1).
    pub threshold: f32,
    /// > 0: ép đúng số nhân vật, bỏ qua `threshold`.
    pub speakers: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpeakerInfo {
    /// Nhãn 1-based, xếp theo tổng thời lượng nói giảm dần.
    pub label: u32,
    pub lines: usize,
    pub seconds: f64,
    pub pitch_hz: Option<f32>,
    /// "male" | "female", hoặc None khi không chắc.
    pub gender: Option<&'static str>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Outcome {
    /// Nhãn của từng câu theo thứ tự đầu vào; 0 là không xác định được.
    pub labels: Vec<u32>,
    pub speakers: Vec<SpeakerInfo>,
}

pub fn model_path(dir: &Path) -> PathBuf {
    dir.join(MODEL_FILE)
}

/// Tải model vào `dir` nếu chưa có, báo `(byte đã tải, tổng byte)`.
pub fn ensure_model(dir: &Path, cancel: &AtomicBool, mut progress: impl FnMut(u64, u64)) -> AppResult<PathBuf> {
    let path = model_path(dir);
    if std::fs::metadata(&path).is_ok_and(|m| m.len() == MODEL_BYTES) {
        return Ok(path);
    }
    std::fs::create_dir_all(dir)?;
    let part = dir.join(format!(".{MODEL_FILE}.{}.part", std::process::id()));
    let net = |e: ureq::Error| AppError::Other(format!("Không tải được model giọng nói: {e}"));
    let mut response = ureq::get(MODEL_URL).call().map_err(net)?;
    let mut reader = response.body_mut().with_config().limit(MODEL_BYTES + 1).reader();
    let mut file = std::fs::File::create(&part)?;
    let mut buf = vec![0u8; 1 << 16];
    let mut done = 0u64;
    let result = (|| -> AppResult<()> {
        loop {
            if cancel.load(Ordering::Relaxed) {
                return Err(AppError::Other("Đã huỷ tải model.".into()));
            }
            let n = std::io::Read::read(&mut reader, &mut buf)?;
            if n == 0 {
                break;
            }
            std::io::Write::write_all(&mut file, &buf[..n])?;
            done += n as u64;
            progress(done, MODEL_BYTES);
        }
        if done != MODEL_BYTES {
            return Err(AppError::Other(format!("Model giọng nói tải về sai kích thước ({done} byte).")));
        }
        Ok(())
    })();
    drop(file);
    match result {
        Ok(()) => {
            std::fs::rename(&part, &path)?;
            Ok(path)
        }
        Err(e) => {
            let _ = std::fs::remove_file(&part);
            Err(e)
        }
    }
}

/// Model CAM++: fbank `[1, khung, 80]` → vector đặc trưng giọng.
pub struct Embedder {
    session: Session,
    input: String,
    fbank: Fbank,
}

fn ort_err(e: impl std::fmt::Display) -> AppError {
    AppError::Other(format!("Model giọng nói lỗi: {e}"))
}

impl Embedder {
    pub fn load(path: &Path) -> AppResult<Self> {
        let threads = std::thread::available_parallelism().map_or(4, |n| n.get()).min(8);
        let session = Session::builder()
            .map_err(ort_err)?
            .with_intra_threads(threads)
            .map_err(ort_err)?
            .commit_from_file(path)
            .map_err(ort_err)?;
        let input = session.inputs().first().ok_or_else(|| ort_err("không có đầu vào"))?.name().to_owned();
        Ok(Self { session, input, fbank: Fbank::new() })
    }

    /// Vector đã chuẩn hoá L2, hoặc None khi đoạn quá ngắn.
    pub fn embed(&mut self, samples: &[f32]) -> AppResult<Option<Vec<f32>>> {
        let frames = Fbank::frames(samples.len());
        if frames < 20 {
            return Ok(None);
        }
        let feats = self.fbank.compute(samples);
        let tensor = TensorRef::from_array_view(([1usize, frames, BINS], &feats[..])).map_err(ort_err)?;
        let outputs = self.session.run(ort::inputs![self.input.as_str() => tensor]).map_err(ort_err)?;
        let (_, data) = outputs[0].try_extract_tensor::<f32>().map_err(ort_err)?;
        let mut v = data.to_vec();
        normalize(&mut v);
        Ok(Some(v))
    }
}

/// Mẫu số thực của câu `span`, đổi từ PCM16 ngay lúc cần: giữ cả video dạng `f32` tốn gấp đôi
/// (1 giờ ≈ 230 MB thay vì 115 MB).
fn slice(pcm: &[u8], span: Span) -> Vec<f32> {
    let (mut start, mut end) = (span.start, span.end.max(span.start));
    if end - start > MAX_MS {
        let mid = (start + end) / 2;
        (start, end) = (mid - MAX_MS / 2, mid + MAX_MS / 2);
    }
    let at = |ms: u64| ((ms as usize) * RATE / 1000 * 2).min(pcm.len() / 2 * 2);
    pcm[at(start)..at(end)].chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0).collect()
}

/// `pcm`: PCM16 little-endian mono 16 kHz của cả video. `progress(câu đã xử lý, tổng)`.
pub fn run(
    embedder: &mut Embedder,
    pcm: &[u8],
    spans: &[Span],
    options: Options,
    cancel: &AtomicBool,
    mut progress: impl FnMut(usize, usize),
) -> AppResult<Outcome> {
    let mut embeddings: Vec<Option<Vec<f32>>> = Vec::with_capacity(spans.len());
    for (i, &span) in spans.iter().enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err(AppError::Other("Đã huỷ.".into()));
        }
        let long_enough = span.end.saturating_sub(span.start) >= MIN_MS;
        embeddings.push(if long_enough { embedder.embed(&slice(pcm, span))? } else { None });
        progress(i + 1, spans.len());
    }
    Ok(assign(spans, &embeddings, pcm, options))
}

/// Gom cụm câu dài, gán câu ngắn vào nhân vật gần nhất, rồi đo cao độ từng nhân vật.
fn assign(spans: &[Span], embeddings: &[Option<Vec<f32>>], pcm: &[u8], options: Options) -> Outcome {
    let dur = |i: usize| spans[i].end.saturating_sub(spans[i].start);
    let with_vec: Vec<usize> = (0..spans.len()).filter(|&i| embeddings[i].is_some()).collect();
    let mut reliable: Vec<usize> = with_vec.iter().copied().filter(|&i| dur(i) >= RELIABLE_MS).collect();
    if reliable.len() < 2 {
        reliable = with_vec.clone();
    }
    let vectors: Vec<Vec<f32>> = reliable.iter().map(|&i| embeddings[i].clone().expect("đã lọc")).collect();
    let groups = agglomerate(&vectors, options.threshold, options.speakers);
    let mut cluster: Vec<Option<usize>> = vec![None; spans.len()];
    for (&i, &g) in reliable.iter().zip(&groups) {
        cluster[i] = Some(g);
    }
    let k = groups.iter().max().map_or(0, |m| m + 1);
    let centroids = |cluster: &[Option<usize>]| -> Vec<Vec<f32>> {
        (0..k)
            .map(|g| {
                let mut c = vec![0.0f32; vectors.first().map_or(0, Vec::len)];
                for (i, _) in cluster.iter().enumerate().filter(|(_, x)| **x == Some(g)) {
                    c.iter_mut().zip(embeddings[i].as_ref().expect("có cụm thì có vector")).for_each(|(a, b)| *a += b);
                }
                normalize(&mut c);
                c
            })
            .collect()
    };
    let nearest = |v: &[f32], centroids: &[Vec<f32>], skip: Option<usize>| {
        centroids.iter().enumerate().filter(|(g, _)| Some(*g) != skip).map(|(g, c)| (g, dot(v, c))).max_by(|a, b| a.1.total_cmp(&b.1))
    };
    // Cụm chỉ một câu thường là tiếng ồn/nhạc hoặc câu lạc giọng: gộp vào nhân vật gần nhất nếu đủ giống.
    if options.speakers == 0 && k > 1 {
        let cents = centroids(&cluster);
        for g in 0..k {
            let members: Vec<usize> = (0..spans.len()).filter(|&i| cluster[i] == Some(g)).collect();
            let [only] = members[..] else { continue };
            if let Some((to, s)) = nearest(embeddings[only].as_ref().expect("có vector"), &cents, Some(g)) {
                if s >= ASSIGN_MIN && (0..spans.len()).any(|i| cluster[i] == Some(to) && i != only) {
                    cluster[only] = Some(to);
                }
            }
        }
    }
    let cents = centroids(&cluster);
    for &i in &with_vec {
        if cluster[i].is_none() {
            cluster[i] = nearest(embeddings[i].as_ref().expect("có vector"), &cents, None).filter(|(_, s)| *s >= ASSIGN_MIN).map(|(g, _)| g);
        }
    }

    // Đánh nhãn lại 1..n theo tổng thời lượng nói, bỏ cụm đã rỗng.
    let mut totals: Vec<(usize, u64)> = (0..k).map(|g| (g, (0..spans.len()).filter(|&i| cluster[i] == Some(g)).map(dur).sum())).filter(|(_, t)| *t > 0).collect();
    totals.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let label_of = |g: usize| totals.iter().position(|(x, _)| *x == g).map_or(0, |p| p as u32 + 1);
    let labels: Vec<u32> = cluster.iter().map(|c| c.map_or(0, label_of)).collect();
    let speakers = totals
        .iter()
        .enumerate()
        .map(|(p, &(g, total))| {
            let mut members: Vec<usize> = (0..spans.len()).filter(|&i| cluster[i] == Some(g)).collect();
            let lines = members.len();
            members.sort_by_key(|&i| std::cmp::Reverse(dur(i)));
            let f0: Vec<f32> = members.iter().take(PITCH_LINES).flat_map(|&i| pitch::voiced_f0(&slice(pcm, spans[i]))).collect();
            let pitch_hz = pitch::median(f0);
            SpeakerInfo { label: p as u32 + 1, lines, seconds: total as f64 / 1000.0, pitch_hz, gender: pitch_hz.and_then(pitch::gender) }
        })
        .collect();
    Outcome { labels, speakers }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unit(v: &[f32]) -> Option<Vec<f32>> {
        let mut v = v.to_vec();
        normalize(&mut v);
        Some(v)
    }

    #[test]
    fn assigns_short_lines_and_orders_labels() {
        let spans = [
            Span { start: 0, end: 2_000 },
            Span { start: 2_000, end: 5_000 },
            Span { start: 5_000, end: 7_000 },
            Span { start: 7_000, end: 7_600 },
            Span { start: 8_000, end: 8_200 },
        ];
        let embeddings = vec![unit(&[1.0, 0.0]), unit(&[0.0, 1.0]), unit(&[0.95, 0.1]), unit(&[0.1, 1.0]), None];
        let out = assign(&spans, &embeddings, &[], Options { threshold: 0.6, speakers: 0 });
        // Nhân vật A nói 4 s (câu 0, 2), B nói 3,6 s (câu 1 và câu ngắn 3).
        assert_eq!(out.labels, vec![1, 2, 1, 2, 0]);
        assert_eq!(out.speakers.iter().map(|s| s.lines).collect::<Vec<_>>(), vec![2, 2]);
    }

    /// Chạy model thật trên file có nhiều người nói:
    /// `GLOTALK_SPEAKERS_FILE=<wav 16 kHz mono> cargo test --release --lib speakers_live -- --nocapture`.
    /// Mốc câu đọc từ `GLOTALK_SPEAKERS_SPANS` (mỗi dòng: hai số đầu tiên là giây bắt đầu/kết thúc),
    /// không có thì chia đều đoạn 2 s. Model lấy từ `GLOTALK_SPEAKERS_MODEL` hoặc tải vào thư mục tạm.
    #[test]
    fn speakers_live() {
        let Ok(file) = std::env::var("GLOTALK_SPEAKERS_FILE") else { return };
        let model = std::env::var("GLOTALK_SPEAKERS_MODEL").map(PathBuf::from).unwrap_or_else(|_| {
            ensure_model(&std::env::temp_dir().join("glotalk-speakers"), &AtomicBool::new(false), |_, _| {}).unwrap()
        });
        let bytes = std::fs::read(&file).unwrap();
        // Bỏ header WAV 44 byte chuẩn.
        let pcm = &bytes[44..];
        let total_ms = (pcm.len() / 2 * 1000 / RATE) as u64;
        let spans: Vec<Span> = match std::env::var("GLOTALK_SPEAKERS_SPANS") {
            Ok(path) => std::fs::read_to_string(path)
                .unwrap()
                .lines()
                .filter_map(|line| {
                    let mut n = line.split_whitespace().filter_map(|w| w.parse::<f64>().ok());
                    Some(Span { start: (n.next()? * 1000.0) as u64, end: (n.next()? * 1000.0) as u64 })
                })
                .collect(),
            Err(_) => (0..total_ms / 2000).map(|i| Span { start: i * 2000, end: i * 2000 + 2000 }).collect(),
        };
        let mut embedder = Embedder::load(&model).unwrap();
        let started = std::time::Instant::now();
        let out = run(&mut embedder, pcm, &spans, Options { threshold: 0.5, speakers: 0 }, &AtomicBool::new(false), |_, _| {}).unwrap();
        println!("{} đoạn trong {:?}", spans.len(), started.elapsed());
        for (span, label) in spans.iter().zip(&out.labels) {
            let f0 = pitch::median(pitch::voiced_f0(&slice(pcm, *span)));
            println!("{:>7} {:>7} nhãn {label} F0 {f0:?}", span.start, span.end);
        }
        for s in &out.speakers {
            println!("{s:?}");
        }
        assert!(!out.speakers.is_empty());
    }
}
