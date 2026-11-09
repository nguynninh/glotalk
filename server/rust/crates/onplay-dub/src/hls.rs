//! HLS packaging of the output video for players that only take `.m3u8` URLs, including
//! saving it for offline playback with `AVAssetDownloadURLSession`.
//!
//! One fMP4 variant (H.264 or HEVC with AAC; other video codecs are re-encoded to H.264),
//! VOD media playlists, and one WebVTT rendition per subtitle file, each a single segment.
//! Every URI is relative, so the directory can be served under any path.

use std::path::Path;

use crate::command;
use crate::error::{Error, Result};

pub const MASTER: &str = "master.m3u8";
const STREAM: &str = "stream.m3u8";
/// Written by ffmpeg for its `#EXT-X-STREAM-INF` line, then replaced by [`MASTER`].
const FFMPEG_MASTER: &str = "ffmpeg-master.m3u8";
const SEGMENT_SECONDS: &str = "6";

/// One subtitle rendition.
pub struct Subtitles<'a> {
    pub srt: &'a Path,
    /// BCP 47 code such as `vi`; `None` when unknown.
    pub language: Option<&'a str>,
    /// Shown in the player's subtitle menu; unique within the stream.
    pub name: String,
    pub default: bool,
    /// File name stem for its playlist and WebVTT file.
    pub stem: &'a str,
}

/// Writes `master.m3u8` and everything it references into `dir`, which must not exist yet.
pub fn package(video: &Path, subtitles: &[Subtitles], dir: &Path) -> Result<()> {
    std::fs::create_dir_all(dir).map_err(Error::io(dir))?;
    let codec = probe(video, "stream=codec_name")?;
    let mut args: Vec<String> = ["-v", "error", "-y", "-i"].map(String::from).to_vec();
    args.push(path(video));
    args.extend(["-map", "0:v:0", "-map", "0:a:0?"].map(String::from));
    args.extend(video_codec(&codec).iter().map(|arg| (*arg).to_owned()));
    args.extend(
        [
            "-c:a", "copy", "-f", "hls", "-hls_time", SEGMENT_SECONDS, "-hls_playlist_type", "vod",
            "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4", "-hls_flags", "independent_segments",
            "-master_pl_name", FFMPEG_MASTER, "-hls_segment_filename",
        ]
        .map(String::from),
    );
    args.push(path(&dir.join("seg%05d.m4s")));
    args.push(path(&dir.join(STREAM)));
    command::run("ffmpeg", args)?;

    let ffmpeg_master = dir.join(FFMPEG_MASTER);
    let master = std::fs::read_to_string(&ffmpeg_master).map_err(Error::io(&ffmpeg_master))?;
    std::fs::remove_file(&ffmpeg_master).map_err(Error::io(&ffmpeg_master))?;
    let stream_info = master
        .lines()
        .find(|line| line.starts_with("#EXT-X-STREAM-INF:"))
        .ok_or_else(|| Error::Media("ffmpeg wrote no #EXT-X-STREAM-INF".into()))?;
    let stream = dir.join(STREAM);
    let playlist = std::fs::read_to_string(&stream).map_err(Error::io(&stream))?;
    let duration = playlist_duration(&playlist);
    // Cue times are video time from 0; map them to the first segment's presentation time.
    let first = format!("concat:{}|{}", path(&dir.join("init.mp4")), path(&dir.join("seg00000.m4s")));
    let start = probe(Path::new(&first), "stream=start_time").ok().and_then(|start| start.parse::<f64>().ok()).unwrap_or(0.0);
    let mpegts = (start.max(0.0) * 90_000.0).round() as u64;

    for subtitle in subtitles {
        let srt = std::fs::read_to_string(subtitle.srt).map_err(Error::io(subtitle.srt))?;
        let vtt = dir.join(format!("{}.vtt", subtitle.stem));
        std::fs::write(&vtt, webvtt(&srt, mpegts)).map_err(Error::io(&vtt))?;
        let playlist = dir.join(format!("{}.m3u8", subtitle.stem));
        std::fs::write(&playlist, subtitle_playlist(&format!("{}.vtt", subtitle.stem), duration)).map_err(Error::io(&playlist))?;
    }
    let master = dir.join(MASTER);
    std::fs::write(&master, master_playlist(stream_info, subtitles)).map_err(Error::io(&master))
}

fn video_codec(codec: &str) -> &'static [&'static str] {
    match codec {
        "h264" => &["-c:v", "copy"],
        // Apple players need the `hvc1` sample entry for HEVC.
        "hevc" => &["-c:v", "copy", "-tag:v", "hvc1"],
        _ => &["-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p"],
    }
}

/// One `ffprobe` value of the first video stream.
fn probe(input: &Path, entry: &str) -> Result<String> {
    let output = command::output("ffprobe", [
        "-v", "error", "-select_streams", "v:0", "-show_entries", entry, "-of", "default=noprint_wrappers=1:nokey=1",
        &path(input),
    ])?;
    let value = String::from_utf8_lossy(&output).lines().next().unwrap_or_default().trim().to_owned();
    if value.is_empty() {
        return Err(Error::Media(format!("{}: no video stream", input.display())));
    }
    Ok(value)
}

fn playlist_duration(playlist: &str) -> f64 {
    playlist
        .lines()
        .filter_map(|line| line.strip_prefix("#EXTINF:"))
        .filter_map(|rest| rest.split(',').next()?.parse::<f64>().ok())
        .sum()
}

fn master_playlist(stream_info: &str, subtitles: &[Subtitles]) -> String {
    let mut lines = vec!["#EXTM3U".to_owned(), "#EXT-X-VERSION:7".to_owned(), "#EXT-X-INDEPENDENT-SEGMENTS".to_owned()];
    for subtitle in subtitles {
        let language = subtitle.language.map(|language| format!(",LANGUAGE=\"{language}\"")).unwrap_or_default();
        let default = if subtitle.default { "YES" } else { "NO" };
        lines.push(format!(
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"{}\"{language},DEFAULT={default},AUTOSELECT=YES,URI=\"{}.m3u8\"",
            subtitle.name.replace('"', "'"),
            subtitle.stem
        ));
    }
    let subtitles_group = if subtitles.is_empty() { "" } else { ",SUBTITLES=\"subs\"" };
    lines.push(format!("{stream_info}{subtitles_group}"));
    lines.push(STREAM.to_owned());
    lines.join("\n") + "\n"
}

fn subtitle_playlist(vtt: &str, duration: f64) -> String {
    format!(
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:{}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:{duration:.3},\n{vtt}\n#EXT-X-ENDLIST\n",
        duration.ceil() as u64
    )
}

/// SubRip to WebVTT: a header with the timestamp map, `.` before milliseconds, and no
/// `-->` inside cue text, where WebVTT would read it as timing.
fn webvtt(srt: &str, mpegts: u64) -> String {
    let mut vtt = format!("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:{mpegts},LOCAL:00:00:00.000\n\n");
    for line in srt.lines() {
        match line.split_once(" --> ") {
            Some((start, end)) if is_srt_time(start) && is_srt_time(end) => {
                vtt += &format!("{} --> {}", start.replace(',', "."), end.replace(',', "."));
            }
            _ => vtt += &line.replace("-->", "->"),
        }
        vtt.push('\n');
    }
    vtt
}

fn is_srt_time(text: &str) -> bool {
    let bytes = text.as_bytes();
    bytes.len() == 12 && bytes.iter().enumerate().all(|(i, byte)| match i {
        2 | 5 => *byte == b':',
        8 => *byte == b',',
        _ => byte.is_ascii_digit(),
    })
}

/// The language's own name for subtitle menus.
pub fn language_name(code: &str) -> &'static str {
    match code {
        "vi" => "Tiếng Việt", "en" => "English", "zh" => "中文", "ja" => "日本語", "ko" => "한국어",
        "th" => "ไทย", "id" => "Bahasa Indonesia", "ru" => "Русский", "ar" => "العربية",
        "fr" => "Français", "de" => "Deutsch", "es" => "Español",
        _ => "Original",
    }
}

fn path(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_srt_to_webvtt() {
        let srt = "1\n00:00:00,031 --> 00:00:04,064\nA --> B\n\n2\n01:02:05,500 --> 01:02:06,001\nC\n";
        assert_eq!(
            webvtt(srt, 3003),
            "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:3003,LOCAL:00:00:00.000\n\n1\n00:00:00.031 --> 00:00:04.064\nA -> B\n\n2\n01:02:05.500 --> 01:02:06.001\nC\n"
        );
    }

    #[test]
    fn writes_vod_playlists_with_subtitle_group() {
        assert_eq!(playlist_duration("#EXTINF:6.2,\na\n#EXTINF:7.8,\nb\n"), 14.0);
        assert_eq!(
            subtitle_playlist("vi.vtt", 14.0),
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:14\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:14.000,\nvi.vtt\n#EXT-X-ENDLIST\n"
        );
        let srt = Path::new("x.srt");
        let subtitles = [
            Subtitles { srt, language: Some("vi"), name: "Tiếng Việt".into(), default: true, stem: "subtitles" },
            Subtitles { srt, language: None, name: "Original".into(), default: false, stem: "original" },
        ];
        let master = master_playlist("#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.640028,mp4a.40.2\"", &subtitles);
        assert_eq!(
            master,
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n\
             #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Tiếng Việt\",LANGUAGE=\"vi\",DEFAULT=YES,AUTOSELECT=YES,URI=\"subtitles.m3u8\"\n\
             #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"Original\",DEFAULT=NO,AUTOSELECT=YES,URI=\"original.m3u8\"\n\
             #EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.640028,mp4a.40.2\",SUBTITLES=\"subs\"\nstream.m3u8\n"
        );
    }
}
