//! Wrapper gọi ffmpeg/ffprobe dạng sidecar.
use crate::error::{AppError, AppResult};
use serde::Serialize;
use std::path::PathBuf;
use std::process::Command;

/// App GUI trên macOS không kế thừa PATH của shell, nên dò thêm các thư mục phổ biến.
pub fn find_tool(name: &'static str) -> AppResult<PathBuf> {
    let exe = if cfg!(windows) { format!("{name}.exe") } else { name.to_string() };
    let mut dirs: Vec<PathBuf> = std::env::var_os("PATH")
        .map(|p| std::env::split_paths(&p).collect())
        .unwrap_or_default();
    dirs.extend(["/opt/homebrew/bin", "/usr/local/bin"].map(PathBuf::from));
    dirs.into_iter()
        .map(|d| d.join(&exe))
        .find(|p| p.is_file())
        .ok_or(AppError::ToolMissing(name))
}

#[derive(Debug, Serialize)]
pub struct ToolStatus {
    pub ffmpeg: Option<String>,
    pub ffprobe: Option<String>,
    pub encoders: Vec<String>,
}

pub fn check_tools() -> ToolStatus {
    let version = |name| {
        find_tool(name).ok().and_then(|p| {
            let out = Command::new(p).arg("-version").output().ok()?;
            String::from_utf8_lossy(&out.stdout).lines().next().map(str::to_string)
        })
    };
    ToolStatus {
        ffmpeg: version("ffmpeg"),
        ffprobe: version("ffprobe"),
        encoders: hw_encoders(),
    }
}

/// Liệt kê encoder GPU khả dụng (NVENC, QSV, AMF, VideoToolbox).
fn hw_encoders() -> Vec<String> {
    let Ok(ff) = find_tool("ffmpeg") else { return vec![] };
    let Ok(out) = Command::new(ff).args(["-hide_banner", "-encoders"]).output() else {
        return vec![];
    };
    let text = String::from_utf8_lossy(&out.stdout);
    ["h264_nvenc", "hevc_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox", "hevc_videotoolbox"]
        .into_iter()
        .filter(|e| text.contains(e))
        .map(str::to_string)
        .collect()
}

#[derive(Debug, Serialize)]
pub struct VideoInfo {
    pub path: String,
    pub duration_ms: u64,
    pub width: u32,
    pub height: u32,
    pub fps: f64,
    pub has_audio: bool,
}

pub fn probe(path: &str) -> AppResult<VideoInfo> {
    let out = Command::new(find_tool("ffprobe")?)
        .args(["-v", "error", "-print_format", "json", "-show_format", "-show_streams", path])
        .output()?;
    if !out.status.success() {
        return Err(AppError::Other(String::from_utf8_lossy(&out.stderr).into()));
    }
    let v: serde_json::Value = serde_json::from_slice(&out.stdout)?;
    let streams = v["streams"].as_array().cloned().unwrap_or_default();
    let video = streams
        .iter()
        .find(|s| s["codec_type"] == "video")
        .ok_or_else(|| AppError::Other("File không có luồng video".into()))?;
    let fps = video["avg_frame_rate"]
        .as_str()
        .and_then(|r| r.split_once('/'))
        .and_then(|(n, d)| Some(n.parse::<f64>().ok()? / d.parse::<f64>().ok()?))
        .filter(|f| f.is_finite())
        .unwrap_or(0.0);
    let duration = v["format"]["duration"].as_str().and_then(|d| d.parse::<f64>().ok()).unwrap_or(0.0);
    Ok(VideoInfo {
        path: path.to_string(),
        duration_ms: (duration * 1000.0) as u64,
        width: video["width"].as_u64().unwrap_or(0) as u32,
        height: video["height"].as_u64().unwrap_or(0) as u32,
        fps,
        has_audio: streams.iter().any(|s| s["codec_type"] == "audio"),
    })
}

/// Tách audio mono 16kHz để gửi lên server STT.
/// Loại media khi đưa vào timeline.
#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum MediaKind {
    Video,
    Audio,
    Image,
}

#[derive(Debug, Serialize)]
pub struct MediaInfo {
    pub path: String,
    pub kind: MediaKind,
    /// 0 với ảnh tĩnh.
    pub duration_ms: u64,
    pub width: u32,
    pub height: u32,
    pub fps: f64,
    pub has_audio: bool,
}

/// Như `probe` nhưng nhận cả file âm thanh và ảnh tĩnh.
pub fn probe_media(path: &str) -> AppResult<MediaInfo> {
    let out = Command::new(find_tool("ffprobe")?)
        .args(["-v", "error", "-print_format", "json", "-show_format", "-show_streams", path])
        .output()?;
    if !out.status.success() {
        return Err(AppError::Other(String::from_utf8_lossy(&out.stderr).into()));
    }
    let v: serde_json::Value = serde_json::from_slice(&out.stdout)?;
    let streams = v["streams"].as_array().cloned().unwrap_or_default();
    let has_audio = streams.iter().any(|s| s["codec_type"] == "audio");
    // Ảnh bìa nhúng trong mp3/m4a là luồng video có cờ attached_pic, không phải video thật.
    let video = streams
        .iter()
        .find(|s| s["codec_type"] == "video" && s["disposition"]["attached_pic"].as_u64() != Some(1));
    let duration = v["format"]["duration"].as_str().and_then(|d| d.parse::<f64>().ok());
    let format = v["format"]["format_name"].as_str().unwrap_or("");
    let (kind, video) = match video {
        Some(vs) if duration.is_none() || format == "image2" || format.ends_with("_pipe") => (MediaKind::Image, Some(vs)),
        Some(vs) => (MediaKind::Video, Some(vs)),
        None if has_audio => (MediaKind::Audio, None),
        None => return Err(AppError::Other("File không có luồng video hay âm thanh".into())),
    };
    let fps = video
        .and_then(|s| s["avg_frame_rate"].as_str())
        .and_then(|r| r.split_once('/'))
        .and_then(|(n, d)| Some(n.parse::<f64>().ok()? / d.parse::<f64>().ok()?))
        .filter(|f| f.is_finite())
        .unwrap_or(0.0);
    Ok(MediaInfo {
        path: path.to_string(),
        duration_ms: if kind == MediaKind::Image { 0 } else { (duration.unwrap_or(0.0) * 1000.0) as u64 },
        width: video.and_then(|s| s["width"].as_u64()).unwrap_or(0) as u32,
        height: video.and_then(|s| s["height"].as_u64()).unwrap_or(0) as u32,
        fps: if kind == MediaKind::Video { fps } else { 0.0 },
        kind,
        has_audio,
    })
}

pub fn extract_audio(input: &str, output: &str) -> AppResult<()> {
    let out = Command::new(find_tool("ffmpeg")?)
        .args(["-y", "-hide_banner", "-loglevel", "error", "-i", input, "-vn", "-ac", "1", "-ar", "16000", output])
        .output()?;
    if out.status.success() {
        Ok(())
    } else {
        Err(AppError::Other(String::from_utf8_lossy(&out.stderr).into()))
    }
}

/// Thư mục cache riêng cho từng video (đổi khi file đổi).
fn cache_dir(input: &str, kind: &str) -> AppResult<PathBuf> {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    input.hash(&mut h);
    if let Ok(m) = std::fs::metadata(input) {
        m.len().hash(&mut h);
        m.modified().ok().hash(&mut h);
    }
    let dir = std::env::temp_dir().join("glotalk-editor").join(kind).join(format!("{:016x}", h.finish()));
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// Peak âm lượng (0..1) theo từng bucket, dùng vẽ waveform trên timeline.
pub fn audio_peaks(input: &str, per_second: u32) -> AppResult<Vec<f32>> {
    use std::io::Read;
    use std::process::Stdio;
    const RATE: u32 = 8000;
    let bucket = (RATE / per_second.max(1)).max(1) as usize;
    let mut child = Command::new(find_tool("ffmpeg")?)
        .args(["-hide_banner", "-loglevel", "error", "-i", input, "-vn", "-ac", "1", "-ar", &RATE.to_string(), "-f", "s16le", "-"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut stdout = child.stdout.take().expect("stdout");
    let mut buf = vec![0u8; 64 * 1024];
    let (mut peaks, mut cur, mut n) = (Vec::new(), 0i32, 0usize);
    let mut carry: Option<u8> = None;
    loop {
        let read = stdout.read(&mut buf)?;
        if read == 0 { break; }
        let mut bytes = &buf[..read];
        if let Some(lo) = carry.take() {
            let s = i16::from_le_bytes([lo, bytes[0]]) as i32;
            cur = cur.max(s.abs());
            n += 1;
            bytes = &bytes[1..];
        }
        let mut chunks = bytes.chunks_exact(2);
        for c in &mut chunks {
            cur = cur.max((i16::from_le_bytes([c[0], c[1]]) as i32).abs());
            n += 1;
            if n == bucket {
                peaks.push(cur as f32 / 32768.0);
                cur = 0;
                n = 0;
            }
        }
        carry = chunks.remainder().first().copied();
    }
    if n > 0 { peaks.push(cur as f32 / 32768.0); }
    child.wait()?;
    Ok(peaks)
}

/// Xuất `count` ảnh thumbnail đều nhau, trả về đường dẫn file (frontend đọc qua asset protocol).
pub fn thumbnails(input: &str, duration_ms: u64, count: u32, height: u32) -> AppResult<Vec<String>> {
    let dir = cache_dir(input, &format!("thumbs-{count}-{height}"))?;
    let list = |d: &PathBuf| -> AppResult<Vec<String>> {
        let mut files: Vec<String> = std::fs::read_dir(d)?
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|x| x == "jpg"))
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        files.sort();
        Ok(files)
    };
    let cached = list(&dir)?;
    if cached.len() as u32 >= count { return Ok(cached); }
    let fps = count as f64 / (duration_ms.max(1) as f64 / 1000.0);
    let out = Command::new(find_tool("ffmpeg")?)
        .args(["-y", "-hide_banner", "-loglevel", "error", "-i", input,
            "-vf", &format!("fps={fps:.6},scale=-2:{height}"), "-frames:v", &count.to_string(), "-q:v", "5"])
        .arg(dir.join("t_%04d.jpg"))
        .output()?;
    if !out.status.success() {
        return Err(AppError::Other(String::from_utf8_lossy(&out.stderr).into()));
    }
    list(&dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Ảnh và âm thanh tạo bằng lavfi nên không cần file mẫu; bỏ qua nếu máy không có ffmpeg.
    #[test]
    fn probe_media_kinds() {
        let Ok(ff) = find_tool("ffmpeg") else { return };
        let dir = std::env::temp_dir().join("glotalk_probe_media");
        std::fs::create_dir_all(&dir).unwrap();
        let png = dir.join("t.png");
        let mp3 = dir.join("t.mp3");
        let make = |args: &[&str]| assert!(Command::new(&ff).args(args).status().unwrap().success());
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=green:s=320x240", "-frames:v", "1", png.to_str().unwrap()]);
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "sine=d=2", mp3.to_str().unwrap()]);
        let img = probe_media(png.to_str().unwrap()).expect("png");
        assert_eq!((img.kind, img.width, img.duration_ms, img.has_audio), (MediaKind::Image, 320, 0, false));
        let aud = probe_media(mp3.to_str().unwrap()).expect("mp3");
        assert_eq!(aud.kind, MediaKind::Audio);
        assert!((aud.duration_ms as i64 - 2000).abs() < 100, "{}", aud.duration_ms);
    }

    /// Chạy: GLOTALK_SAMPLE=/đường/dẫn/video.mp4 cargo test
    #[test]
    fn probe_and_extract_sample() {
        let Ok(sample) = std::env::var("GLOTALK_SAMPLE") else { return };
        let info = probe(&sample).expect("probe");
        assert!(info.width > 0 && info.height > 0 && info.duration_ms > 0);
        assert!(info.has_audio);
        let media = probe_media(&sample).expect("probe_media");
        assert_eq!(media.kind, MediaKind::Video);
        assert_eq!(media.duration_ms, info.duration_ms);
        let out = std::env::temp_dir().join("glotalk_test.16k.wav");
        extract_audio(&sample, out.to_str().unwrap()).expect("extract");
        assert!(out.metadata().unwrap().len() > 0);
        let peaks = audio_peaks(&sample, 50).expect("peaks");
        assert!((peaks.len() as i64 - 500).abs() < 5, "peaks {}", peaks.len());
        assert!(peaks.iter().any(|p| *p > 0.1));
        let thumbs = thumbnails(&sample, info.duration_ms, 10, 54).expect("thumbs");
        assert_eq!(thumbs.len(), 10);
        println!("{info:?}\nencoders: {:?}\npeaks: {}, thumbs: {}", check_tools().encoders, peaks.len(), thumbs.len());
    }
}
