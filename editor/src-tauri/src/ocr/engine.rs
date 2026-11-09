//! Bọc PaddleOCR (PP-OCRv6 qua ONNX Runtime) bằng crate oar-ocr.
use crate::error::{AppError, AppResult};
use image::RgbImage;
use oar_ocr::download;
use oar_ocr::oarocr::{OAROCRBuilder, PpOcrV6Size, OAROCR};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModelSize {
    Tiny,
    Small,
    Medium,
}

/// Bằng `drop_score` mặc định của PaddleOCR.
const MIN_BOX_CONF: f32 = 0.5;
/// Box thấp hơn tỉ lệ này so với box cao nhất trong frame bị coi là chữ nền.
const MIN_REL_HEIGHT: f32 = 0.6;

impl ModelSize {
    /// Tên file trong registry của oar-ocr, khớp `PpOcrV6Size` (các hàm bên đó là `pub(crate)`).
    fn files(self) -> [&'static str; 3] {
        match self {
            Self::Tiny => ["pp-ocrv6_tiny_det.onnx", "pp-ocrv6_tiny_rec.onnx", "ppocrv6_tiny_dict.txt"],
            Self::Small => ["pp-ocrv6_small_det.onnx", "pp-ocrv6_small_rec.onnx", "ppocrv6_dict.txt"],
            Self::Medium => ["pp-ocrv6_medium_det.onnx", "pp-ocrv6_medium_rec.onnx", "ppocrv6_dict.txt"],
        }
    }
}

/// Tải trước các file model còn thiếu vào `OAR_HOME`, báo `(byte đã tải, tổng byte)`.
///
/// oar-ocr không có callback tiến độ, nên đo file `.<tên>.<pid>.<n>.part` nó đang ghi.
/// Huỷ chỉ dừng việc chờ: file đang tải dở vẫn tải tiếp ở luồng nền và được giữ lại cho lần sau.
pub fn ensure_models(size: ModelSize, cancel: &AtomicBool, mut progress: impl FnMut(u64, u64)) -> AppResult<()> {
    let dir = download::cache_dir();
    let missing: Vec<_> = size
        .files()
        .iter()
        .filter_map(|name| download::find(name))
        .filter(|e| std::fs::metadata(dir.join(e.name)).map(|m| m.len() != e.size).unwrap_or(true))
        .collect();
    let total: u64 = missing.iter().map(|e| e.size).sum();
    let mut done = 0;
    for entry in missing {
        let name = entry.name;
        let worker = std::thread::spawn(move || download::fetch(name));
        let prefix = format!(".{name}.{}.", std::process::id());
        while !worker.is_finished() {
            if cancel.load(Ordering::Relaxed) {
                return Err(AppError::Other("Đã huỷ tải model.".into()));
            }
            let part = std::fs::read_dir(&dir)
                .into_iter()
                .flatten()
                .flatten()
                .filter(|f| f.file_name().to_str().is_some_and(|n| n.starts_with(&prefix) && n.ends_with(".part")))
                .filter_map(|f| f.metadata().ok().map(|m| m.len()))
                .max()
                .unwrap_or(0);
            progress(done + part.min(entry.size), total);
            std::thread::sleep(Duration::from_millis(250));
        }
        worker
            .join()
            .map_err(|_| AppError::Other(format!("Luồng tải {name} bị lỗi.")))?
            .map_err(|e| AppError::Other(format!("Không tải được model {name}: {e}")))?;
        done += entry.size;
        progress(done, total);
    }
    Ok(())
}

pub struct OcrEngine {
    pub size: ModelSize,
    ocr: OAROCR,
}

/// Kết quả OCR một frame: các dòng chữ từ trên xuống, ghép bằng xuống dòng.
#[derive(Debug, Clone, Default)]
pub struct FrameText {
    pub text: String,
    pub conf: f32,
}

impl OcrEngine {
    /// Lần đầu sẽ tải model từ ModelScope (kiểm tra SHA-256) vào thư mục `OAR_HOME`.
    pub fn new(size: ModelSize) -> AppResult<Self> {
        let preset = match size {
            ModelSize::Tiny => PpOcrV6Size::Tiny,
            ModelSize::Small => PpOcrV6Size::Small,
            ModelSize::Medium => PpOcrV6Size::Medium,
        };
        let ocr = OAROCRBuilder::pp_ocrv6(preset)
            .build()
            .map_err(|e| AppError::Other(format!("Không khởi tạo được OCR: {e}")))?;
        Ok(Self { size, ocr })
    }

    pub fn recognize(&self, images: Vec<RgbImage>) -> AppResult<Vec<FrameText>> {
        if images.is_empty() {
            return Ok(vec![]);
        }
        let results = self.ocr.predict(images).map_err(|e| AppError::Other(format!("Lỗi OCR: {e}")))?;
        Ok(results.iter().map(|r| {
            // (tâm y, chiều cao, x trái, chữ, độ tin cậy)
            let mut items: Vec<(f32, f32, f32, &str, f32)> = r
                .text_regions
                .iter()
                .filter_map(|reg| {
                    let text = reg.text.as_deref()?.trim();
                    if text.is_empty() {
                        return None;
                    }
                    let pts = &reg.bounding_box.points;
                    let (min_y, max_y) = pts.iter().fold((f32::MAX, f32::MIN), |(a, b), p| (a.min(p.y), b.max(p.y)));
                    let min_x = pts.iter().map(|p| p.x).fold(f32::MAX, f32::min);
                    Some(((min_y + max_y) / 2.0, max_y - min_y, min_x, text, reg.confidence.unwrap_or(0.0)))
                })
                .collect();
            // Bỏ chữ nền lọt vào vùng quét: box tin cậy thấp, hoặc thấp hẳn so với box cao nhất.
            // Đo trên .dev/hardsub.mp4: box phụ đề cao 55–67px, tin cậy ≥ 0.98; chữ nền ≤ 25px
            // nhưng tin cậy có khi tới 0.91, nên chỉ lọc tin cậy là không đủ.
            items.retain(|i| i.4 >= MIN_BOX_CONF);
            let tallest = items.iter().map(|i| i.1).fold(0.0, f32::max);
            items.retain(|i| i.1 >= tallest * MIN_REL_HEIGHT);
            items.sort_by(|a, b| a.0.total_cmp(&b.0));
            // Gom các box cùng dòng (tâm y gần nhau), trong dòng xếp trái → phải.
            let mut lines: Vec<Vec<(f32, f32, f32, &str, f32)>> = Vec::new();
            for it in items {
                match lines.last_mut() {
                    Some(line) if (it.0 - line[0].0).abs() < line[0].1.max(it.1) * 0.5 => line.push(it),
                    _ => lines.push(vec![it]),
                }
            }
            let mut confs = Vec::new();
            let text = lines
                .iter_mut()
                .map(|line| {
                    line.sort_by(|a, b| a.2.total_cmp(&b.2));
                    confs.extend(line.iter().map(|i| i.4));
                    line.iter().map(|i| i.3).collect::<Vec<_>>().join(" ")
                })
                .collect::<Vec<_>>()
                .join("\n");
            let conf = if confs.is_empty() { 0.0 } else { confs.iter().sum::<f32>() / confs.len() as f32 };
            FrameText { text, conf }
        }).collect())
    }
}

#[cfg(test)]
mod download_tests {
    use super::*;

    /// Tải thật model Tiny (~6 MB): `OAR_HOME=<thư mục trống> GLOTALK_OCR_DOWNLOAD=1 cargo test --lib ensure_models -- --nocapture`.
    #[test]
    fn ensure_models_reports_bytes() {
        if std::env::var("GLOTALK_OCR_DOWNLOAD").is_err() {
            return;
        }
        let mut seen = Vec::new();
        ensure_models(ModelSize::Tiny, &AtomicBool::new(false), |done, total| seen.push((done, total))).unwrap();
        println!("{} lần báo, cuối: {:?}", seen.len(), seen.last());
        assert!(seen.windows(2).all(|w| w[0].0 <= w[1].0));
        let &(done, total) = seen.last().unwrap();
        assert!(total > 0 && done == total);
    }
}
