//! `onplay-server`: the streaming WebSocket server, which replaced the Python server.
//!
//! Run as `build/onplay-server` (see `scripts/dev/server.sh`). The project root is the parent
//! of the binary's directory unless `ONPLAY_ROOT` is set; ONNX Runtime is
//! `lib/libonnxruntime.dylib` next to the binary unless `ORT_DYLIB_PATH` is set, the same
//! library the Sherpa ONNX runtime loads, so speech and recognition share one copy.
//! `ONPLAY_LOG=1` prints one line per connection event: client address, Origin and
//! User-Agent, options, engine slot and outcome, and one per dub job change.
//! `ONPLAY_DEBUG=1` also prints every frame and pipeline step with timings. Neither prints
//! audio, recognized text, tokens or video links.
//!
//! Dub jobs are on when `ONPLAY_DUB_TOKENS` (default `dub-tokens` in the root) lists tester
//! tokens; their results are then served on `http://127.0.0.1:8767`.

mod log;

use std::path::PathBuf;
use std::process::ExitCode;

use onplay_server::config::{parse_max_sessions, Capture};
use onplay_server::{Config, Services, Silent};

fn main() -> ExitCode {
    let executable = std::env::current_exe().ok().and_then(|path| path.canonicalize().ok());
    let directory = executable.as_ref().and_then(|path| path.parent()).map(PathBuf::from).unwrap_or_default();
    let root = std::env::var_os("ONPLAY_ROOT").map_or_else(|| directory.parent().map(PathBuf::from).unwrap_or_default(), PathBuf::from);
    let dylib = std::env::var_os("ORT_DYLIB_PATH").map_or_else(|| directory.join("lib/libonnxruntime.dylib"), PathBuf::from);

    let config = Config::from_env(&root);
    if let Some(value) = std::env::var("ONPLAY_CAPTURE_AUDIO").ok().filter(|value| Capture::parse(value).is_none()) {
        eprintln!("Error: ONPLAY_CAPTURE_AUDIO={value:?}; use 0 (nothing), 1 (transcript and audio), 2 (transcript) or 3 (audio).");
        return ExitCode::FAILURE;
    }
    if let Some(value) = std::env::var("ONPLAY_MAX_SESSIONS").ok().filter(|value| parse_max_sessions(value).is_none()) {
        eprintln!("Error: ONPLAY_MAX_SESSIONS={value:?}; use a positive number of concurrent sessions.");
        return ExitCode::FAILURE;
    }
    // Before any model loads: exiting while a background thread is inside ONNX Runtime
    // session creation crashes the process.
    let listener = match onplay_server::bind(&config) {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("Error: cannot listen on {}: {error}", config.address);
            return ExitCode::FAILURE;
        }
    };
    let downloads = match config.dub_tokens.is_file().then(|| onplay_server::bind_downloads(&config)).transpose() {
        Ok(downloads) => downloads,
        Err(error) => {
            eprintln!("Error: cannot listen on {}: {error}", config.download_address);
            return ExitCode::FAILURE;
        }
    };
    let enabled = |name: &str| std::env::var(name).is_ok_and(|value| value == "1");
    let debug = enabled("ONPLAY_DEBUG");
    let services = if debug || enabled("ONPLAY_LOG") { Services::start(config, log::Log { debug }) } else { Services::start(config, Silent) };
    // Mobile sessions translate, which uses low-latency endpointing, with the default 2 threads.
    services.preload(2, true);
    // Speech is optional: recognition still works and sessions report `audio_error`.
    match onplay_tts::init_runtime(&dylib).map_err(|error| error.to_string()).and_then(|()| services.speech.load()) {
        Ok(()) => {}
        Err(error) => eprintln!("KorvaTTS initialization failed ({error}); check model assets and dependencies."),
    }
    let config = &services.config;
    println!("ONPlay STT stream: ws://{} (allowed Origin: {})", config.address, config.origins.join(", "));
    println!("Concurrent sessions: up to {}", config.max_sessions);
    println!("Capture: {}", match config.capture {
        Capture::Off => "off (only sessions that send store: true)",
        Capture::All => "transcript and audio",
        Capture::Transcript => "transcript",
        Capture::Audio => "audio",
    });
    match services.jobs.disabled() {
        None => println!("Dub jobs: on, results on http://{} (jobs in {})", config.download_address, config.job_dir.display()),
        Some(reason) => println!("Dub jobs: off ({reason})"),
    }
    match onplay_server::serve(services, listener, downloads) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("Error: {error}");
            ExitCode::FAILURE
        }
    }
}
