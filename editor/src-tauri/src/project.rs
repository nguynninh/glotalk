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

/// Thư mục con của một dự án: file trung gian (wav cho STT…) và file xuất (srt, mp4).
pub const WORK_DIR: &str = "work";
pub const EXPORT_DIR: &str = "export";

/// Tạo thư mục dự án `dir` (tên dự án là tên thư mục) cùng các thư mục con.
/// Trả về đường dẫn file `<tên>.glotalk` bên trong, chưa ghi file này.
/// Từ chối nếu `dir` đã có nội dung, để không trộn hai dự án vào một chỗ.
pub fn create_project_dir(dir: &std::path::Path) -> std::io::Result<std::path::PathBuf> {
    use std::io::{Error, ErrorKind};
    let name = dir
        .file_name()
        .and_then(|n| n.to_str())
        .map(str::trim)
        .filter(|n| !n.is_empty() && !n.starts_with('.'))
        .ok_or_else(|| Error::new(ErrorKind::InvalidInput, "Tên dự án không hợp lệ"))?
        .to_string();
    if dir.exists() && std::fs::read_dir(dir)?.next().is_some() {
        return Err(Error::new(ErrorKind::AlreadyExists, format!("Thư mục {} đã có dữ liệu", dir.display())));
    }
    std::fs::create_dir_all(dir.join(WORK_DIR))?;
    std::fs::create_dir_all(dir.join(EXPORT_DIR))?;
    Ok(dir.join(format!("{name}.glotalk")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_project_dir_layout() {
        let root = std::env::temp_dir().join(format!("glotalk-proj-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let dir = root.join("Phim A");
        let file = create_project_dir(&dir).unwrap();
        assert_eq!(file, dir.join("Phim A.glotalk"));
        assert!(dir.join(WORK_DIR).is_dir() && dir.join(EXPORT_DIR).is_dir());
        // Thư mục đã có nội dung thì không tạo đè.
        assert!(create_project_dir(&dir).is_err());
        assert!(create_project_dir(&root.join(".an")).is_err());
        std::fs::remove_dir_all(&root).unwrap();
    }
}
