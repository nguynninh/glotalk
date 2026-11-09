//! Command-line parsing without extra dependencies.

use std::path::PathBuf;

use onplay_dub::Options;

pub const USAGE: &str = "\
Usage: onplay-dub INPUT [options]

INPUT is a video file or an http(s) link (links need yt-dlp).

Options:
  --target LANG        Target language (default: vi)
  --output PATH        Output MP4 (default: <input>.<target>.mp4)
  --subtitles-only     Keep the original audio, add subtitles
  --background X       Original audio volume under the dub, 0-1 (default: 0.15)
  --threads N          Recognition threads (default: 4)
  --tts-workers N      Parallel synthesizers (default: 3)
  --tts-threads N      Threads per synthesizer (default: 2)
  --translate-url URL  Translation router (default: http://127.0.0.1:5002)
  --root DIR           onplay-stt checkout with build/ and models/ (default: .)
  --hls DIR            Also package the output as HLS with WebVTT subtitles into DIR
  --progress           Print progress and the result as JSON lines (for onplay-server)

Prints the output video, translated SRT and original SRT paths.";

pub struct Args {
    pub options: Options,
    pub root: PathBuf,
    pub hls: Option<PathBuf>,
    pub progress: bool,
}

pub fn parse(mut raw: impl Iterator<Item = String>) -> Result<Args, String> {
    let mut input = None;
    let mut options = Options::new(String::new(), "vi");
    let mut root = PathBuf::from(".");
    let (mut hls, mut progress) = (None, false);
    while let Some(arg) = raw.next() {
        if !arg.starts_with("--") {
            if input.replace(arg).is_some() {
                return Err("only one INPUT is allowed".into());
            }
            continue;
        }
        if arg == "--subtitles-only" {
            options.subtitles_only = true;
            continue;
        }
        if arg == "--progress" {
            progress = true;
            continue;
        }
        let value = raw.next().ok_or_else(|| format!("{arg} needs a value"))?;
        match arg.as_str() {
            "--target" => options.target = value,
            "--output" => options.output = Some(value.into()),
            "--background" => options.background = number(&arg, &value)?,
            "--threads" => options.threads = number(&arg, &value)?,
            "--tts-workers" => options.tts_workers = number(&arg, &value)?,
            "--tts-threads" => options.tts_threads = number(&arg, &value)?,
            "--translate-url" => options.translation_endpoint = value,
            "--root" => root = value.into(),
            "--hls" => hls = Some(value.into()),
            _ => return Err(format!("unknown option {arg}")),
        }
    }
    options.input = input.ok_or("INPUT is required")?;
    Ok(Args { options, root, hls, progress })
}

fn number<T: std::str::FromStr>(flag: &str, value: &str) -> Result<T, String> {
    value.parse().map_err(|_| format!("{flag}: invalid number '{value}'"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_str(line: &str) -> Result<Args, String> {
        parse(line.split(' ').filter(|arg| !arg.is_empty()).map(str::to_owned))
    }

    #[test]
    fn parses_input_and_options() {
        let args = parse_str("a.mp4 --target en --tts-workers 2 --subtitles-only --root /r").unwrap();
        assert_eq!(args.options.input, "a.mp4");
        assert_eq!((args.options.target.as_str(), args.options.tts_workers), ("en", 2));
        assert!(args.options.subtitles_only);
        assert_eq!(args.root, PathBuf::from("/r"));
        assert!(args.hls.is_none() && !args.progress);
        let args = parse_str("a.mp4 --progress --hls /j/hls").unwrap();
        assert_eq!(args.hls, Some(PathBuf::from("/j/hls")));
        assert!(args.progress && !args.options.subtitles_only);
    }

    #[test]
    fn rejects_missing_or_extra_input_and_bad_values() {
        assert!(parse_str("--target vi").is_err());
        assert!(parse_str("a.mp4 b.mp4").is_err());
        assert!(parse_str("a.mp4 --threads many").is_err());
        assert!(parse_str("a.mp4 --bogus 1").is_err());
    }
}
