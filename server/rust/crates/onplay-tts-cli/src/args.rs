//! Command-line parsing without extra dependencies.

use std::path::PathBuf;

pub const USAGE: &str = "\
Usage: onplay-tts --text TEXT --output OUT.wav [options]
       onplay-tts --batch [options]   (JSON request per stdin line)
       onplay-tts --list-voices [--assets DIR]

Options:
  --text TEXT        Text to speak (or --text-file PATH)
  --text-file PATH   Read UTF-8 text from a file
  --output PATH      PCM16 mono WAV to write
  --lang TAG         Language tag (default: vi)
  --voice NAME       Voice style name (default: khanh_vy)
  --steps N          Flow-matching steps 1-32 (default: 32)
  --speed X          Speed 0.5-2.0 (default: 1.05)
  --seed N           Seed for the noise generator
  --noise PATH       Replay raw float32 little-endian noise instead of generating it
  --threads N        ONNX Runtime intra-op threads
  --device NAME      cpu, coreml, coreml-gpu or coreml-ane (default: cpu)
  --assets DIR       KorvaTTS asset directory (default: Hugging Face cache snapshot)
  --ort-dylib PATH   ONNX Runtime dylib (default: $ORT_DYLIB_PATH)
  --batch            Keep models loaded; serve JSON requests from stdin
  --list-voices      Print available voices and exit";

#[derive(Debug, Default)]
pub struct Args {
    pub text: Option<String>,
    pub text_file: Option<PathBuf>,
    pub output: Option<PathBuf>,
    pub lang: Option<String>,
    pub voice: Option<String>,
    pub steps: Option<usize>,
    pub speed: Option<f32>,
    pub seed: Option<u64>,
    pub noise: Option<PathBuf>,
    pub threads: Option<usize>,
    pub device: Option<String>,
    pub assets: Option<PathBuf>,
    pub ort_dylib: Option<PathBuf>,
    pub list_voices: bool,
    pub batch: bool,
}

pub fn parse(mut raw: impl Iterator<Item = String>) -> Result<Args, String> {
    let mut args = Args::default();
    while let Some(flag) = raw.next() {
        match flag.as_str() {
            "--list-voices" => {
                args.list_voices = true;
                continue;
            }
            "--batch" => {
                args.batch = true;
                continue;
            }
            _ => {}
        }
        let value = raw.next().ok_or_else(|| format!("{flag} needs a value"))?;
        match flag.as_str() {
            "--text" => args.text = Some(value),
            "--text-file" => args.text_file = Some(value.into()),
            "--output" => args.output = Some(value.into()),
            "--lang" => args.lang = Some(value),
            "--voice" => args.voice = Some(value),
            "--steps" => args.steps = Some(number(&flag, &value)?),
            "--speed" => args.speed = Some(number(&flag, &value)?),
            "--seed" => args.seed = Some(number(&flag, &value)?),
            "--noise" => args.noise = Some(value.into()),
            "--threads" => args.threads = Some(number(&flag, &value)?),
            "--device" => args.device = Some(value),
            "--assets" => args.assets = Some(value.into()),
            "--ort-dylib" => args.ort_dylib = Some(value.into()),
            _ => return Err(format!("unknown option {flag}")),
        }
    }
    if args.batch && (args.text.is_some() || args.text_file.is_some() || args.output.is_some() || args.noise.is_some()) {
        return Err("--batch takes text and output from stdin requests".into());
    }
    if !args.list_voices && !args.batch {
        if args.text.is_some() == args.text_file.is_some() {
            return Err("give exactly one of --text or --text-file".into());
        }
        if args.output.is_none() {
            return Err("--output is required".into());
        }
    }
    Ok(args)
}

fn number<T: std::str::FromStr>(flag: &str, value: &str) -> Result<T, String> {
    value.parse().map_err(|_| format!("{flag}: invalid number '{value}'"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_str(line: &str) -> Result<Args, String> {
        parse(line.split(' ').map(str::to_owned))
    }

    #[test]
    fn requires_text_and_output() {
        assert!(parse_str("--text hi").is_err());
        assert!(parse_str("--output a.wav").is_err());
        let args = parse_str("--text hi --output a.wav --steps 8").unwrap();
        assert_eq!((args.text.as_deref(), args.steps), (Some("hi"), Some(8)));
    }

    #[test]
    fn rejects_unknown_and_bad_numbers() {
        assert!(parse_str("--bogus 1").is_err());
        assert!(parse_str("--text hi --output a.wav --speed fast").is_err());
        assert!(parse_str("--list-voices").unwrap().list_voices);
        assert!(parse_str("--batch --steps 8").unwrap().batch);
        assert!(parse_str("--batch --text hi").is_err());
        assert_eq!(parse_str("--batch --threads 2").unwrap().threads, Some(2));
    }
}
