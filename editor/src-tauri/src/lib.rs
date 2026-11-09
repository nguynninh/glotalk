mod auth;
mod commands;
mod error;
mod media;
pub mod ocr;
pub mod project;
pub mod speakers;
mod stt;

use tauri::Manager;

/// Chép model OCR đóng gói sẵn sang thư mục dữ liệu app (ghi được) và trỏ oar-ocr vào đó.
/// Model chưa đóng gói (tiny/medium) sẽ được tải về cùng thư mục khi người dùng chọn.
fn prepare_models(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    let data = app.path().app_data_dir()?.join("models");
    std::fs::create_dir_all(&data)?;
    let bundled = app.path().resource_dir()?.join("models");
    if let Ok(entries) = std::fs::read_dir(&bundled) {
        for e in entries.flatten() {
            let dest = data.join(e.file_name());
            let same = dest.metadata().ok().map(|m| m.len()) == e.metadata().ok().map(|m| m.len());
            if !same {
                std::fs::copy(e.path(), &dest)?;
            }
        }
    }
    // SAFETY: chạy một lần lúc khởi động trong setup, trước khi có lệnh OCR nào đọc biến này.
    unsafe { std::env::set_var("OAR_HOME", &data) };
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .manage(commands::OcrState::default())
        .manage(commands::RenderState::default())
        .manage(commands::SttState::default())
        .manage(commands::SpeakersState::default())
        .setup(|app| {
            if let Err(e) = prepare_models(app) {
                eprintln!("Không chuẩn bị được model OCR: {e}");
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::check_tools,
            commands::probe_video,
            commands::probe_media,
            commands::extract_audio,
            commands::audio_peaks,
            commands::thumbnails,
            commands::read_text,
            commands::write_text,
            commands::create_project,
            commands::path_exists,
            commands::auth_login,
            commands::auth_me,
            commands::auth_logout,
            commands::translate_lines,
            commands::ocr_extract,
            commands::ocr_cancel,
            commands::overlay_begin,
            commands::overlay_write,
            commands::render_video,
            commands::render_cancel,
            commands::stt_check,
            commands::stt_transcribe,
            commands::stt_cancel,
            commands::speakers_detect,
            commands::speakers_cancel,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
