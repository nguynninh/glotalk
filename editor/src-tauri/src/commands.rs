//! Các lệnh frontend gọi qua `invoke()`.
use crate::error::AppResult;
use crate::media::ffmpeg::{self, MediaInfo, ToolStatus, VideoInfo};
use crate::media::render;
use crate::ocr::{self, engine::OcrEngine, OcrJob, OcrOutput, OcrProgress};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{Emitter, State};

#[tauri::command]
pub fn check_tools() -> ToolStatus {
    ffmpeg::check_tools()
}

#[tauri::command]
pub async fn probe_video(path: String) -> AppResult<VideoInfo> {
    tauri::async_runtime::spawn_blocking(move || ffmpeg::probe(&path))
        .await
        .map_err(|e| crate::error::AppError::Other(e.to_string()))?
}

#[tauri::command]
pub async fn probe_media(path: String) -> AppResult<MediaInfo> {
    tauri::async_runtime::spawn_blocking(move || ffmpeg::probe_media(&path))
        .await
        .map_err(|e| crate::error::AppError::Other(e.to_string()))?
}

#[tauri::command]
pub async fn extract_audio(input: String, output: String) -> AppResult<String> {
    let out = output.clone();
    tauri::async_runtime::spawn_blocking(move || ffmpeg::extract_audio(&input, &out))
        .await
        .map_err(|e| crate::error::AppError::Other(e.to_string()))??;
    Ok(output)
}

#[tauri::command]
pub async fn audio_peaks(path: String, per_second: u32) -> AppResult<Vec<f32>> {
    tauri::async_runtime::spawn_blocking(move || ffmpeg::audio_peaks(&path, per_second))
        .await
        .map_err(|e| crate::error::AppError::Other(e.to_string()))?
}

#[tauri::command]
pub async fn thumbnails(path: String, duration_ms: u64, count: u32, height: u32) -> AppResult<Vec<String>> {
    tauri::async_runtime::spawn_blocking(move || ffmpeg::thumbnails(&path, duration_ms, count, height))
        .await
        .map_err(|e| crate::error::AppError::Other(e.to_string()))?
}

#[tauri::command]
pub fn read_text(path: String) -> AppResult<String> {
    Ok(std::fs::read_to_string(path)?)
}

#[tauri::command]
pub fn write_text(path: String, content: String) -> AppResult<()> {
    Ok(std::fs::write(path, content)?)
}

// ===== OCR =====

/// Engine OCR được giữ lại giữa các lần chạy (nạp model mất vài giây).
#[derive(Default)]
pub struct OcrState {
    engine: Mutex<Option<Arc<OcrEngine>>>,
    cancel: Arc<AtomicBool>,
    running: AtomicBool,
}

#[tauri::command]
pub async fn ocr_extract(app: tauri::AppHandle, state: State<'_, OcrState>, job: OcrJob) -> AppResult<OcrOutput> {
    if state.running.swap(true, Ordering::SeqCst) {
        return Err(crate::error::AppError::Other("OCR đang chạy.".into()));
    }
    state.cancel.store(false, Ordering::SeqCst);
    let cached = state.engine.lock().expect("ocr lock").clone().filter(|e| e.size == job.model);
    let cancel = state.cancel.clone();
    let result = tauri::async_runtime::spawn_blocking(move || -> AppResult<(Arc<OcrEngine>, OcrOutput)> {
        let engine = match cached {
            Some(e) => e,
            None => {
                let _ = app.emit("ocr-progress", OcrProgress { phase: "model", done: 0, total: 0, ocr_calls: 0, last_text: String::new() });
                Arc::new(OcrEngine::new(job.model)?)
            }
        };
        let out = ocr::run(&engine, &job, &cancel, |p| {
            let _ = app.emit("ocr-progress", p);
        })?;
        Ok((engine, out))
    })
    .await
    .map_err(|e| crate::error::AppError::Other(e.to_string()));
    state.running.store(false, Ordering::SeqCst);
    let (engine, out) = result??;
    *state.engine.lock().expect("ocr lock") = Some(engine);
    Ok(out)
}

#[tauri::command]
pub fn ocr_cancel(state: State<'_, OcrState>) {
    state.cancel.store(true, Ordering::SeqCst);
}

/// Trạng thái xuất video: chỉ một job một lúc, huỷ được; thư mục ảnh chữ/phụ đề của job hiện tại.
#[derive(Default)]
pub struct RenderState {
    cancel: Arc<AtomicBool>,
    running: AtomicBool,
    overlay_dir: Mutex<Option<std::path::PathBuf>>,
}

/// Tạo thư mục mới cho ảnh chữ/phụ đề của lần xuất sắp tới, trả về đường dẫn.
#[tauri::command]
pub fn overlay_begin(state: State<'_, RenderState>) -> AppResult<String> {
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    let dir = std::env::temp_dir().join("glotalk-editor").join("overlays").join(stamp.to_string());
    std::fs::create_dir_all(&dir)?;
    *state.overlay_dir.lock().expect("render lock") = Some(dir.clone());
    Ok(dir.to_string_lossy().into_owned())
}

/// Ghi một file vào thư mục của `overlay_begin`. Thân request là byte thô (tránh mã hoá JSON từng byte),
/// tên file trong header `x-name`, chỉ gồm chữ, số, `_`, `-`, `.` để không ghi ra ngoài thư mục.
#[tauri::command]
pub fn overlay_write(state: State<'_, RenderState>, request: tauri::ipc::Request<'_>) -> AppResult<()> {
    use crate::error::AppError;
    let name = request.headers().get("x-name").and_then(|v| v.to_str().ok()).unwrap_or("");
    if name.is_empty() || name.starts_with('.') || !name.chars().all(|c| c.is_ascii_alphanumeric() || "_-.".contains(c)) {
        return Err(AppError::Other(format!("Tên file không hợp lệ: {name}")));
    }
    let dir = state.overlay_dir.lock().expect("render lock").clone().ok_or_else(|| AppError::Other("Chưa gọi overlay_begin".into()))?;
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err(AppError::Other("Cần dữ liệu dạng byte".into()));
    };
    std::fs::write(dir.join(name), bytes)?;
    Ok(())
}

#[tauri::command]
pub async fn render_video(app: tauri::AppHandle, state: State<'_, RenderState>, job: render::RenderJob) -> AppResult<String> {
    if state.running.swap(true, Ordering::SeqCst) {
        return Err(crate::error::AppError::Other("Đang xuất video.".into()));
    }
    state.cancel.store(false, Ordering::SeqCst);
    let cancel = state.cancel.clone();
    let output = job.output.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        render::run(&job, &cancel, |p| {
            let _ = app.emit("render-progress", p);
        })
    })
    .await
    .map_err(|e| crate::error::AppError::Other(e.to_string()));
    state.running.store(false, Ordering::SeqCst);
    result??;
    Ok(output)
}

#[tauri::command]
pub fn render_cancel(state: State<'_, RenderState>) {
    state.cancel.store(true, Ordering::SeqCst);
}
