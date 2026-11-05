//! Streaming recognition of a WAV file or raw PCM16 on stdin (replaces `src/main.cpp`).
//!
//! stdout: `READY`, then `PARTIAL text` / `FINAL   text` (or `PARTIAL_SPEAKER n text` /
//! `FINAL_SPEAKER n text` with `--speaker-model`) and, for stdin, `PROGRESS seconds`.
//! stderr: `model_load_s=` and `audio_s= processing_s= rtf= wall_s=` metrics.

use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::{Duration, Instant};

use onplay_stt::pcm::pcm16_to_f32;
use onplay_stt::{Engine, EngineConfig, ModelFiles, Transcript, WaveReader};
use onplay_stt_cli::format::g;
use onplay_stt_cli::{integer, run};

const USAGE: &str = "Usage: onplay-stt ENCODER DECODER JOINER TOKENS AUDIO.wav|- [--realtime] [--threads=N] [--chunk-ms=N] [--speaker-model=PATH] [--low-latency]";

struct Options {
    realtime: bool,
    low_latency: bool,
    threads: i32,
    chunk_ms: i32,
    speaker_model: Option<PathBuf>,
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 5 {
        eprintln!("{USAGE}");
        return ExitCode::from(2);
    }
    run(|| {
        let options = parse(&args[5..])?;
        transcribe(&args, &options).map(|()| ExitCode::SUCCESS)
    })
}

fn parse(args: &[String]) -> Result<Options, String> {
    let mut options = Options { realtime: false, low_latency: false, threads: 2, chunk_ms: 100, speaker_model: None };
    for option in args {
        if option == "--realtime" {
            options.realtime = true;
        } else if option == "--low-latency" {
            options.low_latency = true;
        } else if let Some(value) = option.strip_prefix("--threads=") {
            options.threads = integer(value, "Invalid numeric option")?;
        } else if let Some(value) = option.strip_prefix("--chunk-ms=") {
            options.chunk_ms = integer(value, "Invalid numeric option")?;
        } else if let Some(value) = option.strip_prefix("--speaker-model=") {
            if value.is_empty() {
                return Err("Speaker model path required".into());
            }
            options.speaker_model = Some(value.into());
        } else {
            return Err(format!("Unknown option: {option}"));
        }
    }
    if !(1..=16).contains(&options.threads) || !(10..=1000).contains(&options.chunk_ms) {
        return Err("threads must be 1..16; chunk-ms must be 10..1000".into());
    }
    Ok(options)
}

/// Audio source: an incremental WAV reader, or raw PCM16 little-endian from stdin.
enum Source {
    Wave(WaveReader),
    Stdin { input: std::io::Stdin, bytes: Vec<u8> },
}

impl Source {
    /// Fills `buffer` like `std::cin.read`: blocks until full or end of input.
    fn next(&mut self, buffer: &mut [f32]) -> Result<usize, String> {
        match self {
            Self::Wave(wave) => wave.next(buffer).map_err(|error| error.to_string()),
            Self::Stdin { input, bytes } => {
                bytes.resize(buffer.len() * 2, 0);
                let mut filled = 0;
                let mut lock = input.lock();
                while filled < bytes.len() {
                    match lock.read(&mut bytes[filled..]) {
                        Ok(0) => break,
                        Ok(read) => filled += read,
                        Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                        Err(error) => return Err(error.to_string()),
                    }
                }
                if filled % 2 != 0 {
                    return Err("Truncated PCM16 sample".into());
                }
                Ok(pcm16_to_f32(&bytes[..filled], buffer))
            }
        }
    }
}

fn transcribe(args: &[String], options: &Options) -> Result<(), String> {
    let pcm_stdin = args[4] == "-";
    let mut source = if pcm_stdin {
        Source::Stdin { input: std::io::stdin(), bytes: Vec::new() }
    } else {
        Source::Wave(WaveReader::open(args[4].as_ref()).map_err(|error| error.to_string())?)
    };
    let config = EngineConfig {
        model: ModelFiles { encoder: args[0].clone().into(), decoder: args[1].clone().into(), joiner: args[2].clone().into(), tokens: args[3].clone().into() },
        threads: options.threads,
        speaker_model: options.speaker_model.clone(),
        low_latency: options.low_latency,
    };
    let load = Instant::now();
    let engine = Engine::load(&config).map_err(|error| error.to_string())?;
    let labelled = options.speaker_model.is_some();
    let stdout = std::io::stdout();
    let mut session = engine
        .session(|transcript: &Transcript| {
            let line = match (labelled, transcript.is_final) {
                (true, true) => format!("FINAL_SPEAKER {} {}", transcript.speaker, transcript.text),
                (true, false) => format!("PARTIAL_SPEAKER {} {}", transcript.speaker, transcript.text),
                (false, true) => format!("FINAL   {}", transcript.text),
                (false, false) => format!("PARTIAL {}", transcript.text),
            };
            // A closed stdout means the reader went away; the next write fails the run.
            let _ = writeln!(stdout.lock(), "{line}");
        })
        .map_err(|error| error.to_string())?;
    eprintln!("model_load_s={}", g(load.elapsed().as_secs_f64()));
    println!("READY");
    std::io::stdout().flush().map_err(|error| error.to_string())?;

    let start = Instant::now();
    let mut compute = Duration::ZERO;
    let mut buffer = vec![0.0f32; options.chunk_ms as usize * 16];
    let (mut position, mut reported) = (0u64, 0u64);
    loop {
        let count = source.next(&mut buffer)?;
        if count == 0 {
            break;
        }
        if options.realtime {
            let due = start + Duration::from_secs_f64((position + count as u64) as f64 / 16_000.0);
            std::thread::sleep(due.saturating_duration_since(Instant::now()));
        }
        let tick = Instant::now();
        session.accept_audio(&buffer[..count]).map_err(|error| error.to_string())?;
        compute += tick.elapsed();
        position += count as u64;
        if pcm_stdin && position - reported >= 16_000 {
            println!("PROGRESS {}", g(position as f64 / 16_000.0));
            reported = position;
        }
    }
    let tick = Instant::now();
    session.finish().map_err(|error| error.to_string())?;
    compute += tick.elapsed();
    let audio = position as f64 / 16_000.0;
    let processing = compute.as_secs_f64();
    eprintln!(
        "audio_s={} processing_s={} rtf={} wall_s={}",
        g(audio),
        g(processing),
        g(if audio > 0.0 { processing / audio } else { 0.0 }),
        g(start.elapsed().as_secs_f64())
    );
    Ok(())
}
