//! `--batch`: keep the models loaded and serve one JSON request per stdin line.
//!
//! Request:  {"text": "...", "lang": "vi", "voice": "khanh_vy", "output": "/path.wav",
//!            "speed": 1.05, "steps": 8, "max_seconds": 2.5}
//!           (speed, steps optional, default from flags; max_seconds raises speed to fit)
//! Response: {"ok": true, "output": "/path.wav", "seconds": 1.23}
//!           {"ok": false, "error": "..."}
//! One response line per request, in order. A failed request does not stop the loop.

use std::collections::HashMap;
use std::io::{BufRead, Write};
use std::path::PathBuf;

use onplay_tts::audio::write_pcm16_wav;
use onplay_tts::text::Language;
use onplay_tts::{Assets, GaussianNoise, SynthesisOptions, Synthesizer, VoiceStyle};
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    text: String,
    lang: String,
    voice: String,
    output: PathBuf,
    speed: Option<f32>,
    steps: Option<usize>,
    max_seconds: Option<f32>,
}

#[derive(Serialize)]
#[serde(untagged)]
enum Response {
    Done { ok: bool, output: PathBuf, seconds: f64 },
    Failed { ok: bool, error: String },
}

pub fn serve(assets: &Assets, synthesizer: &mut Synthesizer, defaults: SynthesisOptions) -> Result<(), String> {
    let mut voices: HashMap<String, VoiceStyle> = HashMap::new();
    let mut noise = GaussianNoise::new(None);
    let stdout = std::io::stdout();
    for line in std::io::stdin().lock().lines() {
        let line = line.map_err(|error| format!("stdin: {error}"))?;
        if line.trim().is_empty() {
            continue;
        }
        let response = match handle(&line, assets, synthesizer, defaults, &mut voices, &mut noise) {
            Ok((output, seconds)) => Response::Done { ok: true, output, seconds },
            Err(error) => Response::Failed { ok: false, error },
        };
        let mut out = stdout.lock();
        serde_json::to_writer(&mut out, &response).map_err(|error| error.to_string())?;
        writeln!(out).and_then(|()| out.flush()).map_err(|error| format!("stdout: {error}"))?;
    }
    Ok(())
}

fn handle(
    line: &str,
    assets: &Assets,
    synthesizer: &mut Synthesizer,
    defaults: SynthesisOptions,
    voices: &mut HashMap<String, VoiceStyle>,
    noise: &mut GaussianNoise,
) -> Result<(PathBuf, f64), String> {
    let request: Request = serde_json::from_str(line).map_err(|error| format!("invalid request: {error}"))?;
    let language = Language::parse(&request.lang).map_err(|error| error.to_string())?;
    if !voices.contains_key(&request.voice) {
        let path = assets.voice(&request.voice).map_err(|error| error.to_string())?;
        let voice = VoiceStyle::load(&path).map_err(|error| error.to_string())?;
        voices.insert(request.voice.clone(), voice);
    }
    let options = SynthesisOptions {
        total_steps: request.steps.unwrap_or(defaults.total_steps),
        speed: request.speed.unwrap_or(defaults.speed),
        max_duration: request.max_seconds,
        ..defaults
    };
    let wav = synthesizer
        .synthesize(&request.text, language, &voices[&request.voice], options, noise)
        .map_err(|error| error.to_string())?;
    write_pcm16_wav(&request.output, &wav, synthesizer.sample_rate()).map_err(|error| error.to_string())?;
    Ok((request.output, wav.len() as f64 / f64::from(synthesizer.sample_rate())))
}
