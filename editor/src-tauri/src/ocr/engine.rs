//! Bọc PaddleOCR (PP-OCRv6 qua ONNX Runtime) bằng crate oar-ocr.
use crate::error::{AppError, AppResult};
use image::RgbImage;
use oar_ocr::oarocr::{OAROCRBuilder, PpOcrV6Size, OAROCR};

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
