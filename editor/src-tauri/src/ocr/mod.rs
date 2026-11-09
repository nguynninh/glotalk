//! Trích xuất phụ đề cứng: ffmpeg cắt vùng → lọc frame không đổi → PaddleOCR → gộp thành câu.
pub mod engine;
pub mod frames;
pub mod merge;

use crate::error::{AppError, AppResult};
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
}

/// Mặt nạ chữ đổi quá ngưỡng này thì OCR lại.
const MASK_CHANGE: f64 = 0.35;
/// Dù mặt nạ báo không đổi, vẫn OCR kiểm tra mỗi chừng này giây (nền có hoa văn có thể đánh lừa mặt nạ).
const REFRESH_SECS: f64 = 1.0;

pub fn run(
    engine: &OcrEngine,
    job: &OcrJob,
    cancel: &AtomicBool,
    mut progress: impl FnMut(OcrProgress),
) -> AppResult<OcrOutput> {
    let started = Instant::now();
    let fps = job.scan_fps.clamp(0.5, 30.0);
    let spec = CropSpec::new(job.width, job.height, job.region, 1280)?;
    let total = ((job.duration_ms as f64 / 1000.0) * fps).ceil() as u64;
    let mut reader = FrameReader::spawn(&job.path, spec, fps)?;
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

    let ocr_one = |img: image::RgbImage, calls: &mut u64| -> AppResult<FrameText> {
        *calls += 1;
        Ok(engine.recognize(vec![img])?.pop().unwrap_or_default())
    };
    let same_text = |a: &FrameText, b: &FrameText| {
        merge::similarity(&merge::normalize(&a.text), &merge::normalize(&b.text)) >= merge_opts.min_similarity
    };

    while let Some((t, img)) = reader.next_frame()? {
        if cancel.load(Ordering::Relaxed) {
            return Err(AppError::Other("Đã huỷ OCR.".into()));
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
            progress(OcrProgress { phase: "ocr", done: samples.len() as u64, total, ocr_calls, last_text: cur.text.clone() });
        }
    }

    let segments = merge::merge(&samples, merge_opts);
    progress(OcrProgress { phase: "done", done: total, total, ocr_calls, last_text: String::new() });
    Ok(OcrOutput { segments, frames: samples.len() as u64, ocr_calls, seconds: started.elapsed().as_secs_f64() })
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
        };
        let out = run(&engine, &job, &AtomicBool::new(false), |_| {}).unwrap();
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
}
