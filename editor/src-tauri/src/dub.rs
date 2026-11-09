//! Lồng tiếng: đọc từng câu đã dịch qua server (`POST /speak`, giọng KorvaTTS theo nhân vật),
//! rồi ghép thành một file âm thanh dài bằng timeline cho track lồng tiếng.
//!
//! Mỗi câu được lưu trong `cache_dir` theo nội dung (ngôn ngữ, giọng, chữ, thời lượng cho phép),
//! nên chạy lại sau khi sửa vài câu chỉ đọc lại các câu đó. Một file chung thay vì một clip mỗi câu
//! vì lệnh xuất video mở mỗi clip âm thanh thành một input ffmpeg.
use crate::error::{AppError, AppResult};
use crate::media::ffmpeg::find_tool;
use serde::{Deserialize, Serialize};
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Mutex;

/// Câu cuối (không có câu sau) được nói quá hết câu thêm chừng này.
const TAIL_MS: u64 = 1500;
/// Server không nhận thời lượng ≤ 0; câu quá ngắn vẫn được đọc nhanh nhất có thể.
const MIN_BUDGET_MS: u64 = 300;
/// Khoảng có tiếng cách nhau ít hơn chừng này được gộp, để âm gốc không lên xuống liên tục.
const SPAN_GAP_MS: u64 = 400;
/// Server tổng hợp từng câu một; 2 request song song để thời gian mạng chồng lên thời gian đọc.
const WORKERS: usize = 2;
/// Sample rate của KorvaTTS (`tts.json`) và của file ghép.
const RATE: u32 = 44_100;
/// Mức bỏ qua khi tìm đoạn có tiếng trong WAV (PCM16).
const SILENCE: i16 = 300;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DubLine {
    pub id: u32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
    pub voice: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DubJob {
    pub server: String,
    pub token: String,
    /// "vi" | "en"
    pub language: String,
    /// Theo thứ tự thời gian, không chồng nhau (như danh sách phụ đề).
    pub lines: Vec<DubLine>,
    pub total_ms: u64,
    pub cache_dir: String,
    /// File `.m4a` sẽ ghi.
    pub output: String,
    /// File lồng tiếng của lần trước, xoá sau khi ghi xong file mới (chỉ `.m4a` cùng thư mục với `output`).
    #[serde(default)]
    pub replace: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DubFailure {
    pub id: u32,
    pub error: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DubOutput {
    pub output: String,
    pub spoken: usize,
    /// Câu lấy từ cache, không gọi server.
    pub cached: usize,
    pub failed: Vec<DubFailure>,
    /// Câu đọc nhanh tối đa vẫn lấn sang câu sau.
    pub overrun: Vec<u32>,
    /// Khoảng có giọng lồng tiếng [bắt đầu, kết thúc) tính bằng ms từ đầu file, để hạ âm gốc.
    pub spans: Vec<[u64; 2]>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DubProgress {
    /// "speak" | "mix"
    pub phase: &'static str,
    pub done: usize,
    pub total: usize,
}

/// Thời gian cho phép của câu `i`: tới đầu câu sau (ít nhất hết câu này).
fn budget_ms(lines: &[DubLine], i: usize) -> u64 {
    let l = &lines[i];
    let until = lines.get(i + 1).map_or(l.end_ms + TAIL_MS, |n| n.start_ms.max(l.end_ms));
    until.saturating_sub(l.start_ms).max(MIN_BUDGET_MS)
}

fn cache_path(dir: &Path, language: &str, line: &DubLine, budget: u64) -> PathBuf {
    let mut h = DefaultHasher::new();
    (language, line.voice.as_str(), line.text.trim(), budget).hash(&mut h);
    dir.join(format!("{:016x}.wav", h.finish()))
}

/// Chạy cả hai bước: đọc câu còn thiếu (hai luồng), rồi ghép. Huỷ giữa chừng thì các câu đã đọc vẫn ở cache.
pub fn run(
    job: &DubJob,
    cancel: &AtomicBool,
    speak: impl Fn(&DubLine, f64) -> AppResult<Vec<u8>> + Sync,
    progress: impl Fn(DubProgress) + Sync,
) -> AppResult<DubOutput> {
    let dir = PathBuf::from(&job.cache_dir);
    std::fs::create_dir_all(&dir)?;
    let lines = &job.lines;
    let paths: Vec<PathBuf> = (0..lines.len()).map(|i| cache_path(&dir, &job.language, &lines[i], budget_ms(lines, i))).collect();
    let todo: Vec<usize> = (0..lines.len()).filter(|&i| !paths[i].is_file()).collect();
    let cached = lines.len() - todo.len();
    let next = AtomicUsize::new(0);
    let done = AtomicUsize::new(0);
    let failed = Mutex::new(Vec::new());
    // Lỗi chung (sai token, server cũ): dừng hẳn thay vì báo lỗi từng câu.
    let fatal = Mutex::new(None::<AppError>);
    let stop = AtomicBool::new(false);
    progress(DubProgress { phase: "speak", done: 0, total: todo.len() });
    std::thread::scope(|scope| {
        for _ in 0..WORKERS.min(todo.len()) {
            scope.spawn(|| loop {
                if cancel.load(Ordering::SeqCst) || stop.load(Ordering::SeqCst) {
                    return;
                }
                let Some(&i) = todo.get(next.fetch_add(1, Ordering::SeqCst)) else { return };
                let budget = budget_ms(lines, i) as f64 / 1000.0;
                match speak(&lines[i], budget).and_then(|wav| {
                    read_wav(&wav)?;
                    // Ghi qua file tạm rồi đổi tên: huỷ giữa chừng không để lại WAV dở trong cache.
                    let tmp = paths[i].with_extension("part");
                    std::fs::write(&tmp, &wav)?;
                    std::fs::rename(&tmp, &paths[i])?;
                    Ok(())
                }) {
                    Ok(()) => {}
                    Err(AppError::Other(key)) if key.starts_with("dub.") => {
                        stop.store(true, Ordering::SeqCst);
                        fatal.lock().expect("dub lock").get_or_insert(AppError::Other(key));
                        return;
                    }
                    Err(e) => failed.lock().expect("dub lock").push(DubFailure { id: lines[i].id, error: e.to_string() }),
                }
                progress(DubProgress { phase: "speak", done: done.fetch_add(1, Ordering::SeqCst) + 1, total: todo.len() });
            });
        }
    });
    if let Some(e) = fatal.into_inner().expect("dub lock") {
        return Err(e);
    }
    if cancel.load(Ordering::SeqCst) {
        return Err(AppError::Other("Đã huỷ lồng tiếng".into()));
    }
    let mut failed = failed.into_inner().expect("dub lock");
    progress(DubProgress { phase: "mix", done: 0, total: lines.len() });
    let mut tracker = Spans::default();
    let mut spoken = 0;
    let mut last = std::time::Instant::now();
    encode(&job.output, cancel, |sink| {
        let mut m = Mixer::new(RATE, sink);
        for (i, line) in lines.iter().enumerate() {
            if cancel.load(Ordering::SeqCst) {
                return Err(AppError::Other("Đã huỷ lồng tiếng".into()));
            }
            if failed.iter().any(|f| f.id == line.id) {
                continue;
            }
            let wav = match std::fs::read(&paths[i]).map_err(AppError::from).and_then(|b| read_wav(&b)) {
                Ok(wav) => wav,
                Err(e) => {
                    let _ = std::fs::remove_file(&paths[i]);
                    failed.push(DubFailure { id: line.id, error: e.to_string() });
                    continue;
                }
            };
            // KorvaTTS luôn ra 44,1 kHz; khác thì bỏ câu thay vì phát sai cao độ.
            if wav.rate != RATE {
                failed.push(DubFailure { id: line.id, error: format!("Sample rate {} khác {RATE}", wav.rate) });
                continue;
            }
            m.place(line.start_ms, &wav.samples)?;
            tracker.add(line, lines.get(i + 1).map(|n| n.start_ms), &wav, job.total_ms);
            spoken += 1;
            if last.elapsed().as_millis() >= 200 {
                last = std::time::Instant::now();
                progress(DubProgress { phase: "mix", done: i + 1, total: lines.len() });
            }
        }
        m.finish(job.total_ms)
    })?;
    progress(DubProgress { phase: "mix", done: lines.len(), total: lines.len() });
    let dir_of = |p: &str| Path::new(p).parent().map(Path::to_path_buf);
    for old in &job.replace {
        if old != &job.output && old.ends_with(".m4a") && dir_of(old) == dir_of(&job.output) {
            let _ = std::fs::remove_file(old);
        }
    }
    Ok(DubOutput { output: job.output.clone(), spoken, cached, failed, overrun: tracker.overrun, spans: tracker.spans })
}

pub struct Wav {
    pub rate: u32,
    pub samples: Vec<i16>,
}

/// WAV PCM16 mono như server trả về (`encode_pcm16_wav` của onplay-tts).
pub fn read_wav(bytes: &[u8]) -> AppResult<Wav> {
    let bad = |why: &str| AppError::Other(format!("WAV lồng tiếng không hợp lệ: {why}"));
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err(bad("thiếu RIFF/WAVE"));
    }
    let (mut at, mut rate, mut fmt_ok) = (12usize, 0u32, false);
    while at + 8 <= bytes.len() {
        let id = &bytes[at..at + 4];
        let size = u32::from_le_bytes(bytes[at + 4..at + 8].try_into().unwrap()) as usize;
        let body = &bytes[at + 8..(at + 8 + size).min(bytes.len())];
        if id == b"fmt " && body.len() >= 16 {
            let format = u16::from_le_bytes([body[0], body[1]]);
            let channels = u16::from_le_bytes([body[2], body[3]]);
            rate = u32::from_le_bytes(body[4..8].try_into().unwrap());
            let bits = u16::from_le_bytes([body[14], body[15]]);
            fmt_ok = format == 1 && channels == 1 && bits == 16 && rate > 0;
            if !fmt_ok {
                return Err(bad("cần PCM16 mono"));
            }
        } else if id == b"data" {
            if !fmt_ok {
                return Err(bad("thiếu fmt"));
            }
            let samples = body.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect();
            return Ok(Wav { rate, samples });
        }
        at += 8 + size + (size & 1);
    }
    Err(bad("thiếu data"))
}

/// Khoảng có tiếng (để hạ âm gốc) và các câu lấn sang câu sau.
#[derive(Default)]
struct Spans {
    spans: Vec<[u64; 2]>,
    overrun: Vec<u32>,
}

impl Spans {
    fn add(&mut self, line: &DubLine, next_start: Option<u64>, wav: &Wav, total_ms: u64) {
        let to_ms = |n: usize| (n as u128 * 1000 / wav.rate as u128) as u64;
        if next_start.is_some_and(|next| line.start_ms + to_ms(wav.samples.len()) > next) {
            self.overrun.push(line.id);
        }
        let loud = |v: &i16| v.unsigned_abs() > SILENCE as u16;
        let (Some(a), Some(b)) = (wav.samples.iter().position(loud), wav.samples.iter().rposition(loud)) else { return };
        let span = [line.start_ms + to_ms(a), (line.start_ms + to_ms(b + 1)).min(total_ms)];
        match self.spans.last_mut() {
            Some(prev) if span[0] <= prev[1] + SPAN_GAP_MS => prev[1] = prev[1].max(span[1]),
            _ if span[0] < span[1] => self.spans.push(span),
            _ => {}
        }
    }
}

/// Cộng các câu vào luồng PCM16 theo mốc thời gian, ghi ra `out` phần đã chắc chắn xong
/// (trước mốc câu đang đặt), nên bộ nhớ chỉ giữ đoạn đang chồng tiếng chứ không phải cả phim.
struct Mixer<W: Write> {
    rate: u32,
    out: W,
    /// Chỉ số mẫu của `pending[0]`.
    base: usize,
    pending: Vec<i32>,
}

impl<W: Write> Mixer<W> {
    fn new(rate: u32, out: W) -> Self {
        Self { rate, out, base: 0, pending: Vec::new() }
    }

    fn at(&self, ms: u64) -> usize {
        (ms as u128 * self.rate as u128 / 1000) as usize
    }

    /// Ghi ra mọi mẫu trước `upto` (thêm im lặng nếu cần). Câu phải đặt theo thứ tự thời gian.
    fn flush(&mut self, upto: usize) -> AppResult<()> {
        if upto <= self.base {
            return Ok(());
        }
        let n = upto - self.base;
        let have = n.min(self.pending.len());
        let mut buf = Vec::with_capacity(n * 2);
        for &v in &self.pending[..have] {
            buf.extend_from_slice(&(v.clamp(i16::MIN as i32, i16::MAX as i32) as i16).to_le_bytes());
        }
        buf.resize(n * 2, 0);
        self.pending.drain(..have);
        self.base = upto;
        self.out.write_all(&buf).map_err(AppError::from)
    }

    fn place(&mut self, start_ms: u64, samples: &[i16]) -> AppResult<()> {
        let start = self.at(start_ms).max(self.base);
        self.flush(start)?;
        if self.pending.len() < samples.len() {
            self.pending.resize(samples.len(), 0);
        }
        for (o, &v) in self.pending.iter_mut().zip(samples) {
            *o += v as i32;
        }
        Ok(())
    }

    /// Ghi nốt tới đúng `total_ms`; phần câu vượt quá timeline bị cắt.
    fn finish(mut self, total_ms: u64) -> AppResult<()> {
        let end = self.at(total_ms);
        self.pending.truncate(end.saturating_sub(self.base));
        self.flush(end)?;
        self.out.flush().map_err(AppError::from)
    }
}

/// Kill và đợi ffmpeg ở mọi đường thoát.
struct ChildGuard(Child);
impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// `write` đẩy PCM16 mono `RATE` vào ffmpeg, ra AAC trong `.m4a` (WAV của cả bộ phim sẽ vài trăm MB).
/// Ghi vào file tạm rồi đổi tên, nên lỗi hay huỷ không để lại file dở.
fn encode(output: &str, cancel: &AtomicBool, write: impl FnOnce(&mut dyn Write) -> AppResult<()>) -> AppResult<()> {
    let tmp = format!("{output}.part.m4a");
    let child = Command::new(find_tool("ffmpeg")?)
        .args(["-y", "-hide_banner", "-loglevel", "error", "-f", "s16le", "-ar", &RATE.to_string(), "-ac", "1", "-i", "pipe:0"])
        .args(["-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", &tmp])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()?;
    let mut guard = ChildGuard(child);
    let mut stderr = guard.0.stderr.take().expect("stderr");
    let err_reader = std::thread::spawn(move || {
        let mut s = String::new();
        let _ = stderr.read_to_string(&mut s);
        s
    });
    let mut stdin = std::io::BufWriter::with_capacity(1 << 16, guard.0.stdin.take().expect("stdin"));
    let written = write(&mut stdin);
    let closed = stdin.into_inner().map(drop).map_err(|e| AppError::from(e.into_error()));
    let ok = written.is_ok() && closed.is_ok() && !cancel.load(Ordering::SeqCst);
    let status = if ok { Some(guard.0.wait()?) } else { None };
    drop(guard);
    let err = err_reader.join().unwrap_or_default();
    if status.is_none_or(|s| !s.success()) {
        let _ = std::fs::remove_file(&tmp);
        written?;
        if cancel.load(Ordering::SeqCst) {
            return Err(AppError::Other("Đã huỷ lồng tiếng".into()));
        }
        return Err(AppError::Other(format!("ffmpeg lỗi: {}", err.trim())));
    }
    std::fs::rename(&tmp, output)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(id: u32, start: u64, end: u64) -> DubLine {
        DubLine { id, start_ms: start, end_ms: end, text: format!("câu {id}"), voice: "huu_dat".into() }
    }

    fn wav(rate: u32, samples: &[i16]) -> Vec<u8> {
        let data: Vec<u8> = samples.iter().flat_map(|v| v.to_le_bytes()).collect();
        let mut b = Vec::new();
        b.extend_from_slice(b"RIFF");
        b.extend_from_slice(&(36 + data.len() as u32).to_le_bytes());
        b.extend_from_slice(b"WAVEfmt ");
        b.extend_from_slice(&16u32.to_le_bytes());
        b.extend_from_slice(&1u16.to_le_bytes());
        b.extend_from_slice(&1u16.to_le_bytes());
        b.extend_from_slice(&rate.to_le_bytes());
        b.extend_from_slice(&(rate * 2).to_le_bytes());
        b.extend_from_slice(&2u16.to_le_bytes());
        b.extend_from_slice(&16u16.to_le_bytes());
        b.extend_from_slice(b"data");
        b.extend_from_slice(&(data.len() as u32).to_le_bytes());
        b.extend_from_slice(&data);
        b
    }

    #[test]
    fn budget_runs_to_next_line() {
        let lines = [line(1, 0, 1000), line(2, 1500, 1600), line(3, 1700, 3000)];
        assert_eq!(budget_ms(&lines, 0), 1500);
        assert_eq!(budget_ms(&lines, 1), MIN_BUDGET_MS);
        assert_eq!(budget_ms(&lines, 2), 1300 + TAIL_MS);
    }

    #[test]
    fn reads_server_wav() {
        let w = read_wav(&wav(1000, &[1, -2, 3])).unwrap();
        assert_eq!((w.rate, w.samples), (1000, vec![1, -2, 3]));
        assert!(read_wav(b"nope").is_err());
    }

    #[test]
    fn mixes_in_order_and_cuts_at_the_end() {
        // 1 kHz cho dễ đếm: 1 mẫu = 1 ms.
        let mut out = Vec::new();
        let mut m = Mixer::new(1000, &mut out);
        m.place(100, &[1000; 500]).unwrap();
        m.place(400, &[1000; 100]).unwrap();
        m.place(2000, &[30_000; 100]).unwrap();
        m.place(2010, &[30_000; 10]).unwrap();
        m.finish(2050).unwrap();
        let pcm: Vec<i16> = out.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect();
        assert_eq!(pcm.len(), 2050);
        assert_eq!((pcm[50], pcm[150], pcm[450], pcm[700]), (0, 1000, 2000, 0), "câu chồng nhau thì cộng tiếng");
        assert_eq!(pcm[2015], i16::MAX, "cộng quá thì kẹp lại");
    }

    #[test]
    fn tracks_spans_and_overruns() {
        let loud = |n: usize| Wav { rate: 1000, samples: vec![1000; n] };
        let (a, b, c) = (line(1, 100, 300), line(2, 400, 600), line(3, 2000, 2500));
        let mut t = Spans::default();
        t.add(&a, Some(400), &loud(500), 2050);
        t.add(&b, Some(2000), &loud(100), 2050);
        t.add(&c, None, &loud(100), 2050);
        assert_eq!(t.overrun, vec![1]);
        assert_eq!(t.spans, vec![[100, 600], [2000, 2050]]);
    }

    #[test]
    fn caches_lines_and_stops_on_sign_in_error() {
        let dir = std::env::temp_dir().join(format!("glotalk_dub_test_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let job = DubJob {
            server: String::new(),
            token: String::new(),
            language: "vi".into(),
            lines: vec![line(1, 0, 500), line(2, 1000, 1500)],
            total_ms: 2000,
            cache_dir: dir.to_string_lossy().into(),
            output: dir.join("out.m4a").to_string_lossy().into(),
            replace: vec![],
        };
        let cancel = AtomicBool::new(false);
        let denied = run(&job, &cancel, |_, _| Err(AppError::Other("dub.signIn".into())), |_| {});
        assert!(matches!(denied, Err(AppError::Other(k)) if k == "dub.signIn"));
        if find_tool("ffmpeg").is_err() {
            return;
        }
        let calls = AtomicUsize::new(0);
        let speak = |l: &DubLine, _: f64| {
            calls.fetch_add(1, Ordering::SeqCst);
            if l.id == 2 { Err(AppError::Other("hỏng".into())) } else { Ok(wav(44_100, &vec![2000; 4410])) }
        };
        let out = run(&job, &cancel, speak, |_| {}).unwrap();
        assert_eq!((out.spoken, out.cached, out.failed.len()), (1, 0, 1));
        assert_eq!(out.spans, vec![[0, 100]]);
        assert!(Path::new(&out.output).is_file());
        // Lần sau câu 1 lấy từ cache, chỉ đọc lại câu lỗi.
        let again = run(&job, &cancel, speak, |_| {}).unwrap();
        assert_eq!((again.cached, calls.load(Ordering::SeqCst)), (1, 3));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
