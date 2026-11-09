//! Giải mã frame đã crop theo vùng phụ đề, đọc thẳng RGB từ stdout của ffmpeg (không ghi file tạm).
use crate::error::{AppError, AppResult};
use crate::media::ffmpeg::find_tool;
use crate::project::Region;
use image::RgbImage;
use std::io::Read;
use std::process::{Child, ChildStdout, Command, Stdio};

/// Vùng crop theo pixel của video gốc và kích thước đầu ra sau khi thu nhỏ.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CropSpec {
    pub x: u32,
    pub y: u32,
    pub w: u32,
    pub h: u32,
    pub out_w: u32,
    pub out_h: u32,
}

const fn even(v: u32) -> u32 {
    v & !1
}

impl CropSpec {
    /// `region` theo tỉ lệ 0..1; `None` = cả khung hình. Thu nhỏ để chiều rộng không quá `max_w`.
    pub fn new(src_w: u32, src_h: u32, region: Option<Region>, max_w: u32) -> AppResult<Self> {
        let r = region.unwrap_or(Region { x: 0.0, y: 0.0, w: 1.0, h: 1.0 });
        let clamp = |v: f64| v.clamp(0.0, 1.0);
        let x = even((clamp(r.x) * src_w as f64) as u32);
        let y = even((clamp(r.y) * src_h as f64) as u32);
        let w = even(((clamp(r.x + r.w) * src_w as f64) as u32).saturating_sub(x));
        let h = even(((clamp(r.y + r.h) * src_h as f64) as u32).saturating_sub(y));
        if w < 16 || h < 16 {
            return Err(AppError::Other("Vùng OCR quá nhỏ.".into()));
        }
        let scale = (max_w as f64 / w as f64).min(1.0);
        let out_w = even((w as f64 * scale).round() as u32).max(16);
        let out_h = even((h as f64 * scale).round() as u32).max(16);
        Ok(Self { x, y, w, h, out_w, out_h })
    }
}

pub struct FrameReader {
    child: Child,
    stdout: ChildStdout,
    spec: CropSpec,
    fps: f64,
    index: u64,
    start_ms: u64,
}

impl FrameReader {
    /// `start_ms` > 0: tua tới mốc đó trước khi đọc (quét tiếp); thời điểm trả về vẫn tính từ đầu video.
    pub fn spawn(path: &str, spec: CropSpec, fps: f64, start_ms: u64) -> AppResult<Self> {
        let vf = format!(
            "fps={fps:.6},crop={}:{}:{}:{},scale={}:{}:flags=area",
            spec.w, spec.h, spec.x, spec.y, spec.out_w, spec.out_h
        );
        let mut cmd = Command::new(find_tool("ffmpeg")?);
        cmd.args(["-hide_banner", "-loglevel", "error", "-nostdin"]);
        if start_ms > 0 {
            cmd.args(["-ss", &format!("{:.3}", start_ms as f64 / 1000.0)]);
        }
        let mut child = cmd
            .args(["-i", path, "-an", "-sn", "-vf", &vf])
            .args(["-pix_fmt", "rgb24", "-f", "rawvideo", "-"])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()?;
        let stdout = child.stdout.take().expect("stdout");
        Ok(Self { child, stdout, spec, fps, index: 0, start_ms })
    }

    /// Frame tiếp theo kèm thời điểm (ms). `None` khi hết video.
    pub fn next_frame(&mut self) -> AppResult<Option<(u64, RgbImage)>> {
        let len = (self.spec.out_w * self.spec.out_h * 3) as usize;
        let mut buf = vec![0u8; len];
        let mut filled = 0;
        while filled < len {
            match self.stdout.read(&mut buf[filled..])? {
                0 => return Ok(None),
                n => filled += n,
            }
        }
        let t = self.start_ms + (self.index as f64 * 1000.0 / self.fps).round() as u64;
        self.index += 1;
        Ok(Some((t, RgbImage::from_raw(self.spec.out_w, self.spec.out_h, buf).expect("kích thước frame"))))
    }
}

impl Drop for FrameReader {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Mặt nạ "chữ phụ đề": pixel sáng nằm sát pixel rất tối (chữ trắng/vàng có viền đen).
/// Dùng để bỏ qua OCR khi phụ đề không đổi, dù nền video phía sau chuyển động.
pub struct TextMask {
    bits: Vec<bool>,
    pub count: usize,
}

impl TextMask {
    pub fn from_image(img: &RgbImage) -> Self {
        let (w, h) = (img.width() as usize, img.height() as usize);
        let luma: Vec<u8> = img
            .pixels()
            .map(|p| ((p[0] as u32 * 299 + p[1] as u32 * 587 + p[2] as u32 * 114) / 1000) as u8)
            .collect();
        let mut bits = vec![false; w * h];
        let mut count = 0;
        const R: usize = 2;
        for y in R..h.saturating_sub(R) {
            for x in R..w.saturating_sub(R) {
                if luma[y * w + x] < 185 {
                    continue;
                }
                let dark_near = (y - R..=y + R).any(|yy| (x - R..=x + R).any(|xx| luma[yy * w + xx] < 70));
                if dark_near {
                    bits[y * w + x] = true;
                    count += 1;
                }
            }
        }
        Self { bits, count }
    }

    /// Tỉ lệ khác nhau (0..1) so với mặt nạ khác, theo số pixel chữ.
    pub fn diff(&self, other: &TextMask) -> f64 {
        let xor = self.bits.iter().zip(&other.bits).filter(|(a, b)| a != b).count();
        xor as f64 / self.count.max(other.count).max(1) as f64
    }

    /// Có đủ pixel chữ để coi là đang có phụ đề không.
    pub fn has_text(&self, total_px: usize) -> bool {
        self.count as f64 / total_px as f64 > 0.002
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crop_is_even_and_scaled() {
        let r = Region { x: 0.1, y: 0.8, w: 0.8, h: 0.15 };
        let c = CropSpec::new(1920, 1080, Some(r), 1280).unwrap();
        assert_eq!((c.x % 2, c.y % 2, c.w % 2, c.h % 2), (0, 0, 0, 0));
        assert_eq!(c.w, 1536);
        assert!(c.out_w <= 1280 && c.out_w % 2 == 0 && c.out_h % 2 == 0);
        assert!(CropSpec::new(1920, 1080, Some(Region { x: 0.5, y: 0.5, w: 0.001, h: 0.001 }), 1280).is_err());
    }

    /// Quét tiếp từ giữa video: frame đầu tiên mang đúng mốc `start_ms` và nội dung khớp thời điểm thật.
    #[test]
    fn reader_seeks_to_start_ms() {
        let Ok(ff) = find_tool("ffmpeg") else { return };
        let dir = std::env::temp_dir().join("glotalk_reader_seek");
        std::fs::create_dir_all(&dir).unwrap();
        let mp4 = dir.join("bw.mp4");
        // 2s đen rồi 2s trắng.
        let graph = "color=c=black:s=64x64:r=25:d=2[a];color=c=white:s=64x64:r=25:d=2[b];[a][b]concat=n=2:v=1:a=0";
        let ok = Command::new(ff).args(["-v", "error", "-y", "-filter_complex", graph, "-pix_fmt", "yuv420p", mp4.to_str().unwrap()]).status().unwrap();
        assert!(ok.success());
        let spec = CropSpec::new(64, 64, None, 1280).unwrap();
        let mut r = FrameReader::spawn(mp4.to_str().unwrap(), spec, 10.0, 1500).unwrap();
        let mut frames = Vec::new();
        while let Some((t, img)) = r.next_frame().unwrap() {
            frames.push((t, img.get_pixel(32, 32)[0] > 128));
        }
        assert_eq!(frames[0].0, 1500);
        assert!((24..=26).contains(&frames.len()), "{} frame", frames.len());
        let first_white = frames.iter().find(|f| f.1).expect("có frame trắng").0;
        assert!(first_white.abs_diff(2000) <= 100, "frame trắng đầu tiên ở {first_white}ms");
    }

    #[test]
    fn mask_detects_outlined_text_only() {
        // Nền trắng phẳng (không có viền tối) → không phải chữ.
        let white = RgbImage::from_pixel(64, 32, image::Rgb([255, 255, 255]));
        assert_eq!(TextMask::from_image(&white).count, 0);
        // Ô trắng có viền đen → là chữ.
        let mut img = RgbImage::from_pixel(64, 32, image::Rgb([90, 120, 200]));
        for y in 10..22 {
            for x in 10..40 {
                let edge = y < 12 || y > 19 || x < 12 || x > 37;
                img.put_pixel(x, y, if edge { image::Rgb([0, 0, 0]) } else { image::Rgb([255, 255, 255]) });
            }
        }
        let m = TextMask::from_image(&img);
        assert!(m.count > 0 && m.has_text(64 * 32));
        assert_eq!(m.diff(&TextMask::from_image(&img)), 0.0);
    }
}
