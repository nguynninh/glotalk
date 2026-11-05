//! Offline diarization with per-turn recognition (replaces `src/speakers.cpp`).
//!
//! stdout: one `SPEAKER start end label text` line per turn in start order, printed as each
//! turn is recognized; labels are 1-based in order of first appearance.

use std::process::ExitCode;

use onplay_stt::diarize::{SpeakerModels, SpeakerTranscriber, MAX_SAMPLES};
use onplay_stt::{ModelFiles, WaveReader};
use onplay_stt_cli::format::g;
use onplay_stt_cli::{integer, run};

const USAGE: &str = "Usage: onplay-speakers ENCODER DECODER JOINER TOKENS SEGMENTATION EMBEDDING AUDIO.wav THREADS";

fn main() -> ExitCode {
    run(|| {
        let args: Vec<String> = std::env::args().skip(1).collect();
        if args.len() != 8 {
            return Err(USAGE.into());
        }
        let threads = integer(&args[7], "threads must be 1..16")?;
        if !(1..=16).contains(&threads) {
            return Err("threads must be 1..16".into());
        }
        if let Some(missing) = args[..6].iter().find(|path| !std::path::Path::new(path).is_file()) {
            return Err(format!("Missing model file: {missing}"));
        }
        let mut wave = WaveReader::open(args[6].as_ref()).map_err(|error| error.to_string())?;
        // Checked before loading models so oversized input fails fast.
        if wave.sample_count() > MAX_SAMPLES {
            return Err("Speaker preview supports at most 10 minutes".into());
        }
        let mut samples = vec![0.0f32; wave.sample_count() as usize];
        wave.next(&mut samples).map_err(|error| error.to_string())?;
        let models = SpeakerModels {
            stt: ModelFiles { encoder: args[0].clone().into(), decoder: args[1].clone().into(), joiner: args[2].clone().into(), tokens: args[3].clone().into() },
            segmentation: args[4].clone().into(),
            embedding: args[5].clone().into(),
        };
        let transcriber = SpeakerTranscriber::load(&models, threads).map_err(|error| error.to_string())?;
        transcriber
            .transcribe(&samples, |turn| {
                println!("SPEAKER {} {} {} {}", g(f64::from(turn.start)), g(f64::from(turn.end)), turn.speaker, turn.text);
                Ok(())
            })
            .map_err(|error| error.to_string())?;
        Ok(ExitCode::SUCCESS)
    })
}
