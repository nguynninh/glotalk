//! Xuất timeline thành một file video bằng một lệnh ffmpeg (`filter_complex`).
//! Không phụ thuộc Tauri: nhận `RenderJob`, báo tiến độ qua callback, huỷ bằng `AtomicBool`.
use super::ffmpeg::find_tool;
use crate::error::{AppError, AppResult};
use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Read};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeySpec {
    /// #rrggbb
    pub color: String,
    /// Tham số `similarity` / `blend` của filter `chromakey` (đã quy đổi ở frontend).
    pub similarity: f64,
    pub blend: f64,
    /// Khử viền màu khoá 0..1 (filter `despill`), 0 = tắt.
    #[serde(default)]
    pub spill: f64,
}

/// Một lớp hình, theo thứ tự từ dưới lên.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoLayer {
    pub path: String,
    pub image: bool,
    pub start_ms: u64,
    pub dur_ms: u64,
    pub in_ms: u64,
    pub src_w: u32,
    pub src_h: u32,
    pub opacity: f64,
    pub scale: f64,
    /// Lệch tâm theo tỉ lệ khung hình
    pub x: f64,
    pub y: f64,
    pub key: Option<KeySpec>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioClip {
    pub path: String,
    pub start_ms: u64,
    pub dur_ms: u64,
    pub in_ms: u64,
    /// Âm lượng cuối cùng (track × clip)
    pub volume: f64,
    /// Âm lượng riêng kênh trái/phải của track, nhân thêm sau `volume`
    #[serde(default = "unity")]
    pub volume_l: f64,
    #[serde(default = "unity")]
    pub volume_r: f64,
}

fn unity() -> f64 {
    1.0
}

/// Chỉnh màu toàn khung, tương đương preview: nhân kênh R/G/B (cân bằng trắng + phơi sáng), rồi tương phản, bão hoà.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GradeSpec {
    pub r: f64,
    pub g: f64,
    pub b: f64,
    pub contrast: f64,
    pub saturation: f64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RenderJob {
    pub output: String,
    pub width: u32,
    pub height: u32,
    pub fps: f64,
    pub duration_ms: u64,
    /// libx264, h264_videotoolbox, hevc_videotoolbox, libx265…
    pub encoder: String,
    pub layers: Vec<VideoLayer>,
    pub audio: Vec<AudioClip>,
    /// File ffconcat chứa chuỗi PNG chữ/phụ đề phủ lên trên cùng (sau chỉnh màu).
    pub graphics: Option<String>,
    pub grade: Option<GradeSpec>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RenderProgress {
    pub done_ms: u64,
    pub total_ms: u64,
}

const ENCODERS: &[&str] = &["libx264", "libx265", "h264_videotoolbox", "hevc_videotoolbox", "h264_nvenc", "hevc_nvenc", "h264_qsv", "h264_amf"];

fn even(v: f64) -> u32 {
    ((v / 2.0).round() as u32 * 2).max(2)
}
fn secs(ms: u64) -> String {
    format!("{:.3}", ms as f64 / 1000.0)
}

/// Kích thước và vị trí lớp sau khi vừa khung + scale + lệch (giống `layerRect` ở frontend).
fn layer_rect(l: &VideoLayer, w: u32, h: u32) -> (u32, u32, i64, i64) {
    let fit = (w as f64 / l.src_w.max(1) as f64).min(h as f64 / l.src_h.max(1) as f64) * l.scale;
    let lw = even(l.src_w as f64 * fit);
    let lh = even(l.src_h as f64 * fit);
    let x = ((w as f64 - lw as f64) / 2.0 + l.x * w as f64).round() as i64;
    let y = ((h as f64 - lh as f64) / 2.0 + l.y * h as f64).round() as i64;
    (lw, lh, x, y)
}

/// Đối số ffmpeg (không gồm tên chương trình). Tách riêng để kiểm thử được mà không chạy ffmpeg.
pub fn build_args(job: &RenderJob) -> AppResult<Vec<String>> {
    if !ENCODERS.contains(&job.encoder.as_str()) {
        return Err(AppError::Other(format!("Encoder không hỗ trợ: {}", job.encoder)));
    }
    if job.width < 16 || job.height < 16 || job.duration_ms == 0 {
        return Err(AppError::Other("Timeline trống hoặc kích thước khung không hợp lệ".into()));
    }
    let (w, h) = (job.width / 2 * 2, job.height / 2 * 2);
    let fps = if job.fps > 0.0 { job.fps } else { 30.0 };
    let total = secs(job.duration_ms);
    let mut a: Vec<String> = ["-y", "-hide_banner", "-loglevel", "error", "-nostats", "-progress", "pipe:1"].map(String::from).to_vec();
    let input = |args: Vec<String>, a: &mut Vec<String>| a.extend(args);
    let mut n = 0usize;
    let mut f: Vec<String> = Vec::new();

    // Nền đen dài đúng bằng timeline.
    input(vec!["-f".into(), "lavfi".into(), "-i".into(), format!("color=c=black:s={w}x{h}:r={fps}:d={total}")], &mut a);
    f.push(format!("[{n}:v]format=yuv420p[b0]"));
    n += 1;
    let mut base = "b0".to_string();

    for (k, l) in job.layers.iter().enumerate() {
        if l.image {
            input(vec!["-loop".into(), "1".into(), "-framerate".into(), format!("{fps}"), "-t".into(), secs(l.dur_ms), "-i".into(), l.path.clone()], &mut a);
        } else {
            // -ss trước -i: tua nhanh theo keyframe rồi giải mã tới đúng điểm (ffmpeg ≥ 2.1 chính xác tới frame).
            input(vec!["-ss".into(), secs(l.in_ms), "-t".into(), secs(l.dur_ms), "-i".into(), l.path.clone()], &mut a);
        }
        let (lw, lh, x, y) = layer_rect(l, w, h);
        let mut chain = format!("[{n}:v]fps={fps},scale={lw}:{lh},setsar=1");
        if let Some(key) = &l.key {
            let hex = key.color.trim_start_matches('#');
            if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
                return Err(AppError::Other(format!("Màu khoá không hợp lệ: {}", key.color)));
            }
            chain += &format!(",format=yuva420p,chromakey=0x{hex}:{:.4}:{:.4}", key.similarity.clamp(0.0001, 1.0), key.blend.clamp(0.0, 1.0));
        }
        chain += ",format=rgba";
        if let Some(key) = l.key.as_ref().filter(|k| k.spill > 0.0) {
            // despill chỉ có loại xanh lá/xanh dương: chọn theo kênh trội của màu khoá.
            let n = u32::from_str_radix(key.color.trim_start_matches('#'), 16).unwrap_or(0x00ff00);
            let kind = if (n >> 8) & 255 >= n & 255 { "green" } else { "blue" };
            chain += &format!(",despill=type={kind}:mix=0.5:expand={:.3}", key.spill.clamp(0.0, 1.0));
        }
        if l.opacity < 0.999 {
            chain += &format!(",colorchannelmixer=aa={:.3}", l.opacity.clamp(0.0, 1.0));
        }
        chain += &format!(",setpts=PTS-STARTPTS+{}/TB[l{k}]", secs(l.start_ms));
        f.push(chain);
        let next = format!("b{}", k + 1);
        f.push(format!("[{base}][l{k}]overlay=x={x}:y={y}:eof_action=pass:format=auto[{next}]"));
        base = next;
        n += 1;
    }

    if let Some(g) = &job.grade {
        f.push(format!(
            "[{base}]format=gbrp,colorchannelmixer=rr={:.4}:gg={:.4}:bb={:.4},eq=contrast={:.4}:saturation={:.4}[graded]",
            g.r, g.g, g.b, g.contrast, g.saturation
        ));
        base = "graded".into();
    }

    if let Some(list) = &job.graphics {
        input(vec!["-f".into(), "concat".into(), "-safe".into(), "0".into(), "-i".into(), list.clone()], &mut a);
        f.push(format!("[{n}:v]format=rgba,setpts=PTS-STARTPTS[gfx]"));
        f.push(format!("[{base}][gfx]overlay=eof_action=pass:format=auto[withgfx]"));
        base = "withgfx".into();
        n += 1;
    }
    f.push(format!("[{base}]format=yuv420p[vout]"));

    // Âm thanh: mỗi clip cắt, chỉnh âm lượng, dời tới vị trí rồi trộn; có thêm nền im lặng để luôn có track tiếng đủ dài.
    input(vec!["-f".into(), "lavfi".into(), "-t".into(), total.clone(), "-i".into(), "anullsrc=r=48000:cl=stereo".into()], &mut a);
    let mut mix = vec![format!("[{n}:a]")];
    n += 1;
    for (k, c) in job.audio.iter().enumerate() {
        input(vec!["-ss".into(), secs(c.in_ms), "-t".into(), secs(c.dur_ms), "-i".into(), c.path.clone()], &mut a);
        // Nguồn mono đã thành stereo sau aformat nên pan tách được hai tai.
        let pan = if c.volume_l != 1.0 || c.volume_r != 1.0 {
            format!(",pan=stereo|c0={:.4}*c0|c1={:.4}*c1", c.volume_l.max(0.0), c.volume_r.max(0.0))
        } else {
            String::new()
        };
        f.push(format!(
            "[{n}:a]aresample=48000,aformat=channel_layouts=stereo,volume={:.4}{pan},adelay={}:all=1[a{k}]",
            c.volume.max(0.0),
            c.start_ms
        ));
        mix.push(format!("[a{k}]"));
        n += 1;
    }
    f.push(format!("{}amix=inputs={}:normalize=0:duration=first[aout]", mix.join(""), mix.len()));

    a.extend(["-filter_complex".into(), f.join(";"), "-map".into(), "[vout]".into(), "-map".into(), "[aout]".into()]);
    a.extend(["-c:v".into(), job.encoder.clone()]);
    match job.encoder.as_str() {
        "libx264" | "libx265" => a.extend(["-preset", "medium", "-crf", "18"].map(String::from)),
        _ => {
            // Encoder phần cứng: bitrate theo số điểm ảnh/giây (~0.15 bit/px), đủ cho nội dung có chuyển động.
            let kbps = ((w as f64 * h as f64 * fps * 0.15) / 1000.0).round().max(2000.0) as u64;
            a.extend(["-b:v".into(), format!("{kbps}k")]);
        }
    }
    a.extend(["-pix_fmt", "yuv420p", "-r"].map(String::from));
    a.push(format!("{fps}"));
    a.extend(["-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-t"].map(String::from));
    a.push(total);
    a.push(job.output.clone());
    Ok(a)
}

/// Kill và đợi ffmpeg ở mọi đường thoát (huỷ, lỗi, panic).
struct ChildGuard(Child);
impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

pub fn run(job: &RenderJob, cancel: &AtomicBool, mut progress: impl FnMut(RenderProgress)) -> AppResult<()> {
    let args = build_args(job)?;
    let child = Command::new(find_tool("ffmpeg")?)
        .args(&args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    let mut guard = ChildGuard(child);
    // Đọc stderr ở luồng riêng để ffmpeg không bị chặn khi bộ đệm đầy.
    let mut stderr = guard.0.stderr.take().expect("stderr");
    let err_reader = std::thread::spawn(move || {
        let mut s = String::new();
        let _ = stderr.read_to_string(&mut s);
        s
    });
    let stdout = guard.0.stdout.take().expect("stdout");
    let total = job.duration_ms;
    let mut last = Instant::now() - Duration::from_secs(1);
    for line in BufReader::new(stdout).lines() {
        if cancel.load(Ordering::SeqCst) {
            drop(guard);
            let _ = std::fs::remove_file(&job.output);
            return Err(AppError::Other("Đã huỷ xuất video".into()));
        }
        let line = line?;
        // out_time_us (ffmpeg mới) hoặc out_time_ms (cũ, cũng là micro giây).
        if let Some(v) = line.strip_prefix("out_time_us=").or_else(|| line.strip_prefix("out_time_ms=")) {
            if let Ok(us) = v.trim().parse::<i64>() {
                if last.elapsed() >= Duration::from_millis(200) {
                    last = Instant::now();
                    progress(RenderProgress { done_ms: (us.max(0) as u64 / 1000).min(total), total_ms: total });
                }
            }
        }
    }
    let status = guard.0.wait()?;
    let err = err_reader.join().unwrap_or_default();
    if cancel.load(Ordering::SeqCst) {
        let _ = std::fs::remove_file(&job.output);
        return Err(AppError::Other("Đã huỷ xuất video".into()));
    }
    if !status.success() {
        return Err(AppError::Other(format!("ffmpeg lỗi: {}", err.trim())));
    }
    progress(RenderProgress { done_ms: total, total_ms: total });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layer(path: &str) -> VideoLayer {
        VideoLayer { path: path.into(), image: false, start_ms: 1000, dur_ms: 2000, in_ms: 500, src_w: 640, src_h: 360, opacity: 1.0, scale: 1.0, x: 0.0, y: 0.0, key: None }
    }
    fn job(dir: &std::path::Path) -> RenderJob {
        RenderJob {
            output: dir.join("out.mp4").to_string_lossy().into(),
            width: 320,
            height: 180,
            fps: 30.0,
            duration_ms: 3000,
            encoder: "libx264".into(),
            layers: vec![],
            audio: vec![],
            graphics: None,
            grade: None,
        }
    }

    #[test]
    fn layer_rect_fits_and_offsets() {
        let mut l = layer("a");
        l.scale = 0.5;
        l.x = 0.25;
        assert_eq!(layer_rect(&l, 1920, 1080), (960, 540, 960, 270));
        let mut tall = layer("b");
        tall.src_w = 1080;
        tall.src_h = 1920;
        assert_eq!(layer_rect(&tall, 1920, 1080).0, 608);
    }

    #[test]
    fn rejects_bad_encoder_and_key() {
        let dir = std::env::temp_dir();
        let mut j = job(&dir);
        j.encoder = "rm -rf".into();
        assert!(build_args(&j).is_err());
        let mut j = job(&dir);
        let mut l = layer("a");
        l.key = Some(KeySpec { color: "#00ff00;x".into(), similarity: 0.1, blend: 0.05, spill: 0.0 });
        j.layers.push(l);
        assert!(build_args(&j).is_err());
    }

    /// Dựng thật một timeline nhỏ từ nguồn tạo bằng lavfi: nền + lớp phông xanh đã khoá + âm thanh.
    /// Bỏ qua nếu máy không có ffmpeg.
    #[test]
    fn renders_keyed_layer_and_audio() {
        let (Ok(ff), Ok(fp)) = (find_tool("ffmpeg"), find_tool("ffprobe")) else { return };
        let dir = std::env::temp_dir().join("glotalk_render_test");
        std::fs::create_dir_all(&dir).unwrap();
        let src = dir.join("bg.mp4");
        let green = dir.join("green.mp4");
        let tone = dir.join("tone.wav");
        let make = |args: &[&str]| assert!(Command::new(&ff).args(args).status().unwrap().success());
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:d=3:r=30", "-pix_fmt", "yuv420p", src.to_str().unwrap()]);
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=0x00ff00:s=320x180:d=3:r=30", "-pix_fmt", "yuv420p", green.to_str().unwrap()]);
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "sine=f=440:d=2", tone.to_str().unwrap()]);
        let mut j = job(&dir);
        let mut bg = layer(src.to_str().unwrap());
        bg.start_ms = 0;
        bg.in_ms = 0;
        bg.dur_ms = 3000;
        let mut g = layer(green.to_str().unwrap());
        g.key = Some(KeySpec { color: "#00ff00".into(), similarity: 0.1, blend: 0.02, spill: 0.2 });
        j.layers = vec![bg, g];
        j.audio = vec![AudioClip { path: tone.to_str().unwrap().into(), start_ms: 500, dur_ms: 1500, in_ms: 0, volume: 0.8, volume_l: 1.0, volume_r: 0.5 }];
        let cancel = AtomicBool::new(false);
        let mut seen = 0;
        run(&j, &cancel, |_| seen += 1).expect("render");
        assert!(seen > 0);
        // Khung giữa clip phông xanh phải vẫn đỏ (phông đã bị xoá), và có đủ 2 luồng.
        let probe = Command::new(&fp)
            .args(["-v", "error", "-show_entries", "stream=codec_type:format=duration", "-of", "json", &j.output])
            .output()
            .unwrap();
        let v: serde_json::Value = serde_json::from_slice(&probe.stdout).unwrap();
        let types: Vec<_> = v["streams"].as_array().unwrap().iter().map(|s| s["codec_type"].as_str().unwrap().to_string()).collect();
        assert_eq!(types, ["video", "audio"]);
        let dur: f64 = v["format"]["duration"].as_str().unwrap().parse().unwrap();
        assert!((dur - 3.0).abs() < 0.15, "duration {dur}");
        let px = Command::new(&ff)
            .args(["-v", "error", "-ss", "2", "-i", &j.output, "-frames:v", "1", "-vf", "crop=2:2:160:90,scale=1:1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"])
            .output()
            .unwrap();
        let (r, g, b) = (px.stdout[0], px.stdout[1], px.stdout[2]);
        assert!(r > 180 && g < 80 && b < 80, "pixel {r},{g},{b}");
    }

    /// Lớp ảnh tĩnh + chuỗi PNG chữ (ffconcat): 0–1 s phủ xanh dương, sau đó trong suốt để lộ ảnh vàng.
    #[test]
    fn renders_image_layer_and_graphics_track() {
        let Ok(ff) = find_tool("ffmpeg") else { return };
        let dir = std::env::temp_dir().join("glotalk_render_gfx_test");
        std::fs::create_dir_all(&dir).unwrap();
        let make = |args: &[&str]| assert!(Command::new(&ff).args(args).status().unwrap().success());
        let img = dir.join("yellow.png");
        let blue = dir.join("g0.png");
        let clear = dir.join("g1.png");
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=yellow:s=160x90", "-frames:v", "1", img.to_str().unwrap()]);
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=blue:s=320x180,format=rgba", "-frames:v", "1", blue.to_str().unwrap()]);
        make(&["-v", "error", "-y", "-f", "lavfi", "-i", "color=c=black@0.0:s=320x180,format=rgba", "-frames:v", "1", clear.to_str().unwrap()]);
        let list = dir.join("gfx.ffconcat");
        std::fs::write(&list, "ffconcat version 1.0\nfile 'g0.png'\nduration 1\nfile 'g1.png'\nduration 2\nfile 'g1.png'\n").unwrap();
        let mut j = job(&dir);
        let mut l = layer(img.to_str().unwrap());
        l.image = true;
        l.start_ms = 0;
        l.in_ms = 0;
        l.dur_ms = 3000;
        l.src_w = 160;
        l.src_h = 90;
        j.layers = vec![l];
        j.graphics = Some(list.to_string_lossy().into());
        run(&j, &AtomicBool::new(false), |_| {}).expect("render");
        let pixel = |t: &str| {
            let o = Command::new(&ff)
                .args(["-v", "error", "-ss", t, "-i", &j.output, "-frames:v", "1", "-vf", "crop=2:2:160:90,scale=1:1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"])
                .output()
                .unwrap();
            (o.stdout[0], o.stdout[1], o.stdout[2])
        };
        let (r, g, b) = pixel("0.5");
        assert!(b > 180 && r < 80 && g < 80, "t=0.5 {r},{g},{b}");
        let (r, g, b) = pixel("2");
        assert!(r > 180 && g > 180 && b < 80, "t=2 {r},{g},{b}");
    }

    /// Dựng một job JSON do frontend tạo ra. Chạy: GLOTALK_RENDER_JOB=/đường/dẫn/job.json cargo test --lib render_job_from_env
    #[test]
    fn render_job_from_env() {
        let Ok(path) = std::env::var("GLOTALK_RENDER_JOB") else { return };
        let job: RenderJob = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).expect("job json");
        let mut last = 0;
        run(&job, &AtomicBool::new(false), |p| last = p.done_ms).expect("render");
        assert_eq!(last, job.duration_ms);
        println!("rendered {}", job.output);
    }
}
