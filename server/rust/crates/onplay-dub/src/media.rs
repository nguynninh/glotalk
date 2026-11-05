//! ffmpeg operations: 16 kHz extraction, piece cutting and the final mux.

use std::path::Path;

use crate::command;
use crate::error::{Error, Result};

pub const STT_RATE: u32 = 16_000;

/// Extracts mono PCM16 16 kHz audio and returns its duration in seconds.
pub fn extract_audio(video: &Path, output: &Path) -> Result<f64> {
    command::run("ffmpeg", [
        "-v", "error", "-y", "-i", &video.to_string_lossy(), "-vn", "-ac", "1", "-ar", "16000",
        "-c:a", "pcm_s16le", &output.to_string_lossy(),
    ])?;
    let samples = pcm16_mono_samples(output, STT_RATE)?;
    Ok(samples as f64 / f64::from(STT_RATE))
}

/// Copies `[offset, offset + length)` seconds of a WAV into a new WAV.
pub fn cut(audio: &Path, offset: u64, length: u64, output: &Path) -> Result<()> {
    command::run("ffmpeg", [
        "-v", "error", "-y", "-ss", &offset.to_string(), "-t", &length.to_string(),
        "-i", &audio.to_string_lossy(), "-c:a", "pcm_s16le", &output.to_string_lossy(),
    ])
}

/// What to mix into the output audio.
pub struct Dub<'a> {
    pub track: &'a Path,
    /// Intervals where dubbed speech plays; the original is lowered only there.
    pub spans: &'a [(f64, f64)],
    pub background: f64,
}

/// Writes the output MP4: copied video, original or mixed audio, soft subtitle track.
pub fn mux(video: &Path, dub: Option<Dub>, subtitles: &Path, language: &str, output: &Path, work: &Path) -> Result<()> {
    let mut inputs: Vec<String> = vec!["-v".into(), "error".into(), "-y".into(), "-i".into(), path(video)];
    let mut maps: Vec<String> = vec!["-map".into(), "0:v:0?".into()];
    let subtitle_input = match &dub {
        Some(dub) => {
            inputs.extend(["-i".into(), path(dub.track)]);
            let script = work.join("dub.filter");
            std::fs::write(&script, duck_filter(dub.spans, dub.background)).map_err(Error::io(&script))?;
            maps.extend(["-/filter_complex".into(), path(&script), "-map".into(), "[a]".into()]);
            2
        }
        None => {
            maps.extend(["-map".into(), "0:a:0?".into()]);
            1
        }
    };
    inputs.extend(["-i".into(), path(subtitles)]);
    maps.extend(["-map".into(), format!("{subtitle_input}:0")]);
    let codecs = ["-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-c:s", "mov_text", "-metadata:s:s:0"];
    let args = inputs
        .into_iter()
        .chain(maps)
        .chain(codecs.iter().map(|arg| (*arg).to_owned()))
        .chain([format!("language={}", iso639_2(language)), path(output)]);
    command::run("ffmpeg", args)
}

fn path(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

/// Lowers the original audio to `background` inside each span and mixes in the dub track.
fn duck_filter(spans: &[(f64, f64)], background: f64) -> String {
    let ducks: String = spans
        .iter()
        .map(|(start, end)| format!("volume={background}:enable='between(t,{start:.3},{end:.3})',"))
        .collect();
    format!("[0:a:0]{ducks}anull[bg];[bg][1:a]amix=inputs=2:duration=first:normalize=0[a]")
}

fn iso639_2(language: &str) -> &'static str {
    match language {
        "vi" => "vie", "en" => "eng", "zh" => "zho", "ja" => "jpn", "ko" => "kor", "th" => "tha",
        "id" => "ind", "ru" => "rus", "ar" => "ara", "fr" => "fra", "de" => "deu", "es" => "spa",
        _ => "und",
    }
}

/// Sample count of a mono PCM16 WAV at `rate`, read from its chunk headers.
fn pcm16_mono_samples(path: &Path, rate: u32) -> Result<u64> {
    use std::io::{Read, Seek, SeekFrom};
    let invalid = |reason: &str| Error::Media(format!("{}: {reason}", path.display()));
    let mut file = std::fs::File::open(path).map_err(Error::io(path))?;
    let mut header = [0u8; 12];
    file.read_exact(&mut header).map_err(|_| invalid("not a WAV file"))?;
    if &header[..4] != b"RIFF" || &header[8..] != b"WAVE" {
        return Err(invalid("not a WAV file"));
    }
    let mut format_ok = false;
    loop {
        let mut chunk = [0u8; 8];
        file.read_exact(&mut chunk).map_err(|_| invalid("no data chunk"))?;
        let size = u32::from_le_bytes([chunk[4], chunk[5], chunk[6], chunk[7]]);
        let mut skip = i64::from(size) + i64::from(size % 2);
        match &chunk[..4] {
            b"fmt " => {
                let mut fmt = [0u8; 16];
                file.read_exact(&mut fmt).map_err(|_| invalid("short fmt chunk"))?;
                let channels = u16::from_le_bytes([fmt[2], fmt[3]]);
                let sample_rate = u32::from_le_bytes([fmt[4], fmt[5], fmt[6], fmt[7]]);
                let bits = u16::from_le_bytes([fmt[14], fmt[15]]);
                format_ok = channels == 1 && sample_rate == rate && bits == 16;
                skip -= 16;
            }
            b"data" if format_ok => return Ok(u64::from(size) / 2),
            b"data" => return Err(invalid(&format!("expected mono PCM16 {rate} Hz"))),
            _ => {}
        }
        file.seek(SeekFrom::Current(skip)).map_err(Error::io(path))?;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ducks_only_inside_spans() {
        assert_eq!(
            duck_filter(&[(1.0, 2.5)], 0.15),
            "[0:a:0]volume=0.15:enable='between(t,1.000,2.500)',anull[bg];[bg][1:a]amix=inputs=2:duration=first:normalize=0[a]"
        );
        assert!(duck_filter(&[], 0.15).starts_with("[0:a:0]anull[bg]"));
    }

    #[test]
    fn reads_wav_sample_count_past_extra_chunks() {
        let dir = std::env::temp_dir().join(format!("onplay-dub-media-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("a.wav");
        let mut wav = onplay_tts::audio::encode_pcm16_wav(&[0.0; 320], 16_000).unwrap();
        // A LIST chunk between fmt and data, as ffmpeg writes.
        let list = [b"LIST".as_slice(), &4u32.to_le_bytes(), b"INFO"].concat();
        wav.splice(36..36, list);
        std::fs::write(&path, &wav).unwrap();
        assert_eq!(pcm16_mono_samples(&path, 16_000).unwrap(), 320);
        assert!(pcm16_mono_samples(&path, 44_100).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
