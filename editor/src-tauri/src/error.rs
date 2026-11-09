use serde::Serialize;

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("Không tìm thấy {0}. Hãy cài đặt và thêm vào PATH.")]
    ToolMissing(&'static str),
    #[error("Lỗi I/O: {0}")]
    Io(#[from] std::io::Error),
    #[error("Lỗi đọc JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("{0}")]
    Other(String),
}

// Trả lỗi về frontend dưới dạng chuỗi để hiển thị trong log.
impl Serialize for AppError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

pub type AppResult<T> = Result<T, AppError>;
