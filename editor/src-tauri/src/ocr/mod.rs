//! Trích xuất phụ đề cứng: ffmpeg cắt vùng → lọc frame không đổi → PaddleOCR → gộp thành câu.
pub mod engine;
pub mod frames;
pub mod merge;

use crate::error::AppResult;
use crate::project::Region;
use engine::{FrameText, ModelSize, OcrEngine};
use frames::{CropSpec, FrameReader, TextMask};
use merge::{MergeOptions, OcrSegment, Sample};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrJob {
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub duration_ms: u64,
    pub region: Option<Region>,
    /// Số frame quét mỗi giây (quyết định độ chính xác thời gian)
    pub scan_fps: f64,
    pub model: ModelSize,
    /// Bỏ bộ lọc frame không đổi, OCR mọi frame (chậm hơn nhiều)
    #[serde(default)]
    pub every_frame: bool,
    /// Quét tiếp từ mốc này (ms) thay vì từ đầu video
    #[serde(default)]
    pub start_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrProgress {
    pub phase: &'static str,
    pub done: u64,
    pub total: u64,
    pub ocr_calls: u64,
    pub last_text: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrOutput {
    pub segments: Vec<OcrSegment>,
    pub frames: u64,
    pub ocr_calls: u64,
    pub seconds: f64,
    /// false khi bị dừng giữa chừng: `segments` là phần đã chốt, quét tiếp từ `done_ms`.
    pub complete: bool,
    pub done_ms: u64,
}

/// Kết quả tạm trong lúc quét: các câu đã chốt (chỉ của lần chạy này) và mốc đã quét chắc chắn tới đó.
/// Bị dừng giữa chừng thì quét tiếp từ `done_ms`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrPartial {
    pub segments: Vec<OcrSegment>,
    pub done_ms: u64,
}

/// Mặt nạ chữ đổi quá ngưỡng này thì OCR lại.
const MASK_CHANGE: f64 = 0.35;
/// Dù mặt nạ báo không đổi, vẫn OCR kiểm tra mỗi chừng này giây (nền có hoa văn có thể đánh lừa mặt nạ).
const REFRESH_SECS: f64 = 1.0;
/// Chốt và gửi kết quả tạm mỗi chừng này giây (thời gian thực).
const CHECKPOINT_SECS: u64 = 10;
/// Chỉ chốt các mẫu cũ hơn frame hiện tại chừng này giây: dò lại mốc đổi chữ có thể sửa các mẫu gần đây.
const SETTLE_SECS: f64 = 2.0;

/// Nối câu vào danh sách; hai câu chồng lấn ở chỗ nối thì chia đôi phần chồng (như `merge`).
fn push_segment(out: &mut Vec<OcrSegment>, mut seg: OcrSegment) {
    if let Some(prev) = out.last_mut() {
        if prev.end > seg.start {
            let mid = (prev.end + seg.start) / 2;
            prev.end = mid;
            seg.start = mid;
            seg.end = seg.end.max(mid + 1);
        }
    }
    out.push(seg);
}

/// Chốt các câu trong `samples[base..]` đã kết thúc trước mẫu `limit`.
/// Câu đang vắt qua mốc thì lùi mốc về đầu câu để lần sau (hoặc lần quét tiếp) đọc trọn câu đó.
/// Trả về các câu đã chốt và chỉ số mẫu đầu tiên chưa chốt.
fn settle(samples: &[Sample], base: usize, limit: usize, o: MergeOptions) -> (Vec<OcrSegment>, usize) {
    let limit = limit.clamp(base, samples.len());
    if limit == base {
        return (Vec::new(), base);
    }
    let mut cut_t = samples.get(limit).map_or_else(|| samples[limit - 1].t + o.interval, |s| s.t);
    let mut done = Vec::new();
    for seg in merge::merge(&samples[base..], o) {
        if seg.end <= cut_t {
            done.push(seg);
        } else {
            cut_t = cut_t.min(seg.start);
            break;
        }
    }
    let cut = base + samples[base..limit].partition_point(|s| s.t < cut_t);
    (done, cut)
}

pub fn run(
    engine: &OcrEngine,
    job: &OcrJob,
    cancel: &AtomicBool,
    mut progress: impl FnMut(OcrProgress),
    mut partial: impl FnMut(OcrPartial),
) -> AppResult<OcrOutput> {
    let started = Instant::now();
    let fps = job.scan_fps.clamp(0.5, 30.0);
    let spec = CropSpec::new(job.width, job.height, job.region, 1280)?;
    let total = ((job.duration_ms as f64 / 1000.0) * fps).ceil() as u64;
    let start_ms = job.start_ms.min(job.duration_ms);
    // Số frame đã quét ở các lần trước, để thanh tiến độ tính trên cả video.
    let skipped = ((start_ms as f64 / 1000.0) * fps).floor() as u64;
    let mut reader = FrameReader::spawn(&job.path, spec, fps, start_ms)?;
    let px = (spec.out_w * spec.out_h) as usize;
    let refresh = (fps * REFRESH_SECS).round().max(1.0) as usize;
    let merge_opts = MergeOptions::for_fps(fps);

    let mut samples: Vec<Sample> = Vec::new();
    // Các frame kể từ lần OCR gần nhất (chỉ số trong `samples` + ảnh), để dò lại mốc đổi chữ.
    let mut since: Vec<(usize, image::RgbImage)> = Vec::new();
    let mut cur = FrameText::default();
    let mut key_mask: Option<TextMask> = None;
    let mut ocr_calls = 0u64;
    let mut last_emit = Instant::now();
    let mut last_checkpoint = Instant::now();
    // Câu đã chốt và chỉ số mẫu đầu tiên chưa chốt.
    let mut committed: Vec<OcrSegment> = Vec::new();
    let mut base = 0usize;
    let settle_n = (fps * SETTLE_SECS).ceil() as usize;

    let ocr_one = |img: image::RgbImage, calls: &mut u64| -> AppResult<FrameText> {
        *calls += 1;
        Ok(engine.recognize(vec![img])?.pop().unwrap_or_default())
    };
    let same_text = |a: &FrameText, b: &FrameText| {
        merge::similarity(&merge::normalize(&a.text), &merge::normalize(&b.text)) >= merge_opts.min_similarity
    };

    // Chốt các câu trước mẫu `limit`, trả về mốc đã quét chắc chắn (ms).
    let checkpoint = |samples: &[Sample], limit: usize, committed: &mut Vec<OcrSegment>, base: &mut usize| -> u64 {
        let (done, cut) = settle(samples, *base, limit, merge_opts);
        for seg in done {
            push_segment(committed, seg);
        }
        *base = cut;
        samples.get(cut).map_or_else(|| samples.last().map_or(start_ms, |s| s.t + merge_opts.interval), |s| s.t)
    };

    while let Some((t, img)) = reader.next_frame()? {
        if cancel.load(Ordering::Relaxed) {
            // Giữ phần đã quét để lần sau quét tiếp.
            let limit = since.first().map_or(samples.len(), |(i, _)| *i);
            let done_ms = checkpoint(&samples, limit, &mut committed, &mut base);
            return Ok(OcrOutput {
                segments: committed,
                frames: samples.len() as u64,
                ocr_calls,
                seconds: started.elapsed().as_secs_f64(),
                complete: false,
                done_ms,
            });
        }
        let idx = samples.len();
        let mask = TextMask::from_image(&img);
        let has_text = mask.has_text(px);
        let changed = match &key_mask {
            None => has_text,
            Some(k) => !has_text || mask.diff(k) >= MASK_CHANGE,
        };
        // Chỉ kiểm tra định kỳ khi mặt nạ thấy chữ: OCR frame trống chỉ đọc ra hoa văn nền thành câu giả.
        let due = has_text && since.len() + 1 >= refresh;

        if !job.every_frame && !changed && !due {
            // Coi như giữ nguyên chữ của lần OCR trước.
            samples.push(Sample { t, text: cur.text.clone(), conf: cur.conf });
            since.push((idx, img));
        } else {
            let periodic = !job.every_frame && !changed;
            let ft = if !job.every_frame && !has_text && !due { FrameText::default() } else { ocr_one(img, &mut ocr_calls)? };
            if periodic && !same_text(&ft, &cur) && !since.is_empty() {
                // Mặt nạ đã bỏ sót một lần đổi chữ: tìm nhị phân frame đầu tiên mang chữ mới.
                let (mut lo, mut hi) = (0usize, since.len());
                while lo < hi {
                    let mid = (lo + hi) / 2;
                    let probe = ocr_one(since[mid].1.clone(), &mut ocr_calls)?;
                    if same_text(&probe, &cur) { lo = mid + 1 } else { hi = mid }
                }
                for (i, _) in &since[lo..] {
                    samples[*i].text = ft.text.clone();
                    samples[*i].conf = ft.conf;
                }
            }
            samples.push(Sample { t, text: ft.text.clone(), conf: ft.conf });
            cur = ft;
            key_mask = has_text.then_some(mask);
            since.clear();
        }

        if last_emit.elapsed().as_millis() > 200 {
            last_emit = Instant::now();
            progress(OcrProgress { phase: "ocr", done: skipped + samples.len() as u64, total, ocr_calls, last_text: cur.text.clone() });
        }
        if last_checkpoint.elapsed().as_secs() >= CHECKPOINT_SECS {
            last_checkpoint = Instant::now();
            let limit = samples.len().saturating_sub(settle_n).min(since.first().map_or(usize::MAX, |(i, _)| *i));
            let done_ms = checkpoint(&samples, limit, &mut committed, &mut base);
            partial(OcrPartial { segments: committed.clone(), done_ms });
        }
    }

    let mut segments = committed;
    for seg in merge::merge(&samples[base..], merge_opts) {
        push_segment(&mut segments, seg);
    }
    progress(OcrProgress { phase: "done", done: total, total, ocr_calls, last_text: String::new() });
    Ok(OcrOutput {
        segments,
        frames: samples.len() as u64,
        ocr_calls,
        seconds: started.elapsed().as_secs_f64(),
        complete: true,
        done_ms: job.duration_ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Chạy: GLOTALK_HARDSUB=.dev/hardsub.mp4 cargo test --lib ocr::tests -- --nocapture
    /// Video test có 4 câu: 0.5–2.5s, 3.0–5.0s, 5.03–7.0s, 7.8–9.6s ở dải y 580..690 / 720.
    #[test]
    fn extracts_burned_in_subtitles() {
        let Ok(path) = std::env::var("GLOTALK_HARDSUB") else { return };
        let info = crate::media::ffmpeg::probe(&path).unwrap();
        let engine = OcrEngine::new(ModelSize::Small).expect("engine");
        let job = OcrJob {
            path,
            width: info.width,
            height: info.height,
            duration_ms: info.duration_ms,
            region: Some(Region { x: 0.0, y: 0.78, w: 1.0, h: 0.2 }),
            scan_fps: 10.0,
            model: ModelSize::Small,
            every_frame: false,
            start_ms: 0,
        };
        let out = run(&engine, &job, &AtomicBool::new(false), |_| {}, |_| {}).unwrap();
        println!("{} frame, {} lần OCR, {:.2}s", out.frames, out.ocr_calls, out.seconds);
        for s in &out.segments {
            println!("{:>5}–{:>5}  {:.2}  {}", s.start, s.end, s.conf, s.text);
        }
        let expect = [(500, 2500, "你到底想怎么样"), (3000, 5000, "我只是想保护你"), (5033, 7000, "别再骗我了"), (7800, 9600, "跟我走吧")];
        assert_eq!(out.segments.len(), expect.len());
        for (s, (a, b, t)) in out.segments.iter().zip(expect) {
            // Model đôi khi trả chữ phồn thể (別/别), nên so độ giống thay vì so tuyệt đối.
            assert!(merge::similarity(&s.text, t) >= 0.75, "sai chữ: {s:?}");
            assert!(s.start.abs_diff(a) <= 100 && s.end.abs_diff(b) <= 100, "lệch thời gian: {s:?}");
        }
        assert!(out.ocr_calls < out.frames / 3, "bộ lọc frame phải bỏ được phần lớn lần OCR");
    }

    fn samples(seq: &[&str]) -> Vec<Sample> {
        seq.iter().enumerate().map(|(i, t)| Sample { t: i as u64 * 100, text: t.to_string(), conf: 0.95 }).collect()
    }

    /// Chốt từng phần rồi ghép lại phải ra đúng như gộp một lần, kể cả khi mốc chốt rơi giữa câu.
    #[test]
    fn settle_in_pieces_matches_single_merge() {
        let seq = ["", "AAAA", "AAAA", "AAAA", "", "", "BBBB", "BBBB", "BBBB", "CCCC", "CCCC", "", "", "DDDD", "DDDD", ""];
        let s = samples(&seq);
        let o = MergeOptions::for_fps(10.0);
        let whole = merge::merge(&s, o);
        for limits in [vec![2], vec![7, 10], vec![4, 8, 12, 14], vec![1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]] {
            let (mut out, mut base) = (Vec::new(), 0);
            for l in limits {
                let (done, cut) = settle(&s, base, l, o);
                assert!(cut >= base && cut <= l);
                done.into_iter().for_each(|x| push_segment(&mut out, x));
                base = cut;
            }
            merge::merge(&s[base..], o).into_iter().for_each(|x| push_segment(&mut out, x));
            assert_eq!(out, whole);
        }
    }

    /// Quét tiếp từ `done_ms` trên các mẫu mới phải nối liền với phần đã chốt.
    #[test]
    fn resume_from_done_ms() {
        let seq = ["", "AAAA", "AAAA", "AAAA", "", "BBBB", "BBBB", "BBBB", "BBBB", ""];
        let s = samples(&seq);
        let o = MergeOptions::for_fps(10.0);
        let (done, cut) = settle(&s, 0, 7, o);
        assert_eq!(done.iter().map(|x| x.text.as_str()).collect::<Vec<_>>(), ["AAAA"]);
        assert_eq!(s[cut].t, 500, "câu B đang dở nên mốc lùi về đầu câu B");
        let mut out = done;
        merge::merge(&s[cut..], o).into_iter().for_each(|x| push_segment(&mut out, x));
        assert_eq!(out, merge::merge(&s, o));
    }
}
