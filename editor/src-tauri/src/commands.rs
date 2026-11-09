//! Các lệnh frontend gọi qua `invoke()`.
use crate::error::AppResult;
use crate::media::ffmpeg::{self, MediaInfo, ToolStatus, VideoInfo};
use crate::media::render;
use crate::ocr::{self, engine::OcrEngine, OcrJob, OcrOutput, OcrProgress};
use crate::stt::{self, SttJob, SttOutput};
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

/// Tạo thư mục dự án mới, trả về đường dẫn file `.glotalk` bên trong.
#[tauri::command]
pub fn create_project(dir: String) -> AppResult<String> {
    Ok(crate::project::create_project_dir(std::path::Path::new(&dir))?.to_string_lossy().into_owned())
}

#[tauri::command]
pub fn path_exists(path: String) -> bool {
    std::path::Path::new(&path).exists()
}

// ===== Đăng nhập =====

fn joined<T>(r: Result<AppResult<T>, tauri::Error>) -> AppResult<T> {
    r.map_err(|e| crate::error::AppError::Other(e.to_string()))?
}

#[tauri::command]
pub async fn auth_login(server: String, user: String, password: String) -> AppResult<crate::auth::Login> {
    joined(tauri::async_runtime::spawn_blocking(move || crate::auth::login(&server, &user, &password)).await)
}

#[tauri::command]
pub async fn auth_me(server: String, token: String) -> AppResult<Option<String>> {
    joined(tauri::async_runtime::spawn_blocking(move || crate::auth::me(&server, &token)).await)
}

#[tauri::command]
pub async fn translate_lines(
    server: String,
    token: String,
    source: String,
    target: String,
    texts: Vec<String>,
    context: Vec<String>,
) -> AppResult<Vec<crate::auth::Translated>> {
    joined(tauri::async_runtime::spawn_blocking(move || crate::auth::translate(&server, &token, &source, &target, &texts, &context)).await)
}

#[tauri::command]
pub async fn auth_logout(server: String, token: String) -> AppResult<()> {
    joined(tauri::async_runtime::spawn_blocking(move || crate::auth::logout(&server, &token)).await)
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
    // Webview tải lại (vd. HMR) thì mất trạng thái nhưng job cũ vẫn chạy và không còn ai nhận kết quả:
    // dừng job đó rồi chạy job mới, thay vì báo lỗi khiến nút "Tiếp tục quét" như không có tác dụng.
    while state.running.swap(true, Ordering::SeqCst) {
        state.cancel.store(true, Ordering::SeqCst);
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    state.cancel.store(false, Ordering::SeqCst);
    let cached = state.engine.lock().expect("ocr lock").clone().filter(|e| e.size == job.model);
    let cancel = state.cancel.clone();
    let result = tauri::async_runtime::spawn_blocking(move || -> AppResult<(Arc<OcrEngine>, OcrOutput)> {
        let engine = match cached {
            Some(e) => e,
            None => {
                ocr::engine::ensure_models(job.model, &cancel, |done, total| {
                    let _ = app.emit("ocr-progress", OcrProgress { phase: "download", done, total, ocr_calls: 0, last_text: String::new() });
                })?;
                let _ = app.emit("ocr-progress", OcrProgress { phase: "model", done: 0, total: 0, ocr_calls: 0, last_text: String::new() });
                Arc::new(OcrEngine::new(job.model)?)
            }
        };
        let out = ocr::run(
            &engine,
            &job,
            &cancel,
            |p| {
                let _ = app.emit("ocr-progress", p);
            },
            |p| {
                let _ = app.emit("ocr-partial", p);
            },
        )?;
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

// ===== STT qua server =====

/// Chỉ một phiên nhận dạng một lúc; huỷ được.
#[derive(Default)]
pub struct SttState {
    cancel: Arc<AtomicBool>,
    running: AtomicBool,
}

#[tauri::command]
pub async fn stt_check(server: String) -> AppResult<String> {
    stt::check(&server).await
}

#[tauri::command]
pub async fn stt_transcribe(app: tauri::AppHandle, state: State<'_, SttState>, job: SttJob) -> AppResult<SttOutput> {
    if state.running.swap(true, Ordering::SeqCst) {
        return Err(crate::error::AppError::Other("Nhận dạng giọng nói đang chạy.".into()));
    }
    state.cancel.store(false, Ordering::SeqCst);
    let last = Mutex::new(std::time::Instant::now() - std::time::Duration::from_secs(1));
    let result = stt::transcribe(job, state.cancel.clone(), |p| {
        // Tiến độ tối đa 5 lần/giây; câu mới chốt luôn được gửi.
        let mut at = last.lock().expect("stt lock");
        if p.last_text.is_empty() && at.elapsed().as_millis() < 200 {
            return;
        }
        *at = std::time::Instant::now();
        let _ = app.emit("stt-progress", p);
    })
    .await;
    state.running.store(false, Ordering::SeqCst);
    result
}

#[tauri::command]
pub fn stt_cancel(state: State<'_, SttState>) {
    state.cancel.store(true, Ordering::SeqCst);
}
