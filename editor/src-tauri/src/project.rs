//! Model dữ liệu dùng chung cho mọi bước pipeline.
//! Nên tách ra crate riêng để server Rust hiện có dùng chung.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Segment {
    pub id: u32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub text_src: String,
    pub text_vi: String,
    pub speaker: Option<String>,
    pub audio_path: Option<String>,
}

/// Vùng OCR / vùng thoại theo tỉ lệ 0..1 so với khung hình.
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct Region {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}
