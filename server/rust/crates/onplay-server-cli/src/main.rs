//! `onplay-server`: the streaming WebSocket server, which replaced the Python server.
//!
//! Run as `build/onplay-server` (see `scripts/dev/server.sh`). The project root is the parent
//! of the binary's directory unless `ONPLAY_ROOT` is set; ONNX Runtime is
//! `lib/libonnxruntime.dylib` next to the binary unless `ORT_DYLIB_PATH` is set, the same
//! library the Sherpa ONNX runtime loads, so speech and recognition share one copy.
//! `ONPLAY_LOG=1` prints one line per connection event: client address, Origin and
//! User-Agent, options and outcome, never audio or recognized text.

use std::path::PathBuf;
use std::process::ExitCode;

use onplay_server::config::Capture;
use onplay_server::socket::Speakers;
use onplay_server::events::Client;
use onplay_server::{Config, Event, Outcome, Services, Silent};

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
    // Before any model loads: exiting while a background thread is inside ONNX Runtime
    // session creation crashes the process.
    let listener = match onplay_server::bind(&config) {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("Error: cannot listen on {}: {error}", config.address);
            return ExitCode::FAILURE;
        }
    };
    let services = if std::env::var("ONPLAY_LOG").is_ok_and(|value| value == "1") {
        Services::start(config, |event| println!("{}", describe(&event)))
    } else {
        Services::start(config, Silent)
    };
    // Mobile sessions translate, which uses low-latency endpointing, with the default 2 threads.
    services.preload(2, true);
    // Speech is optional: recognition still works and sessions report `audio_error`.
    match onplay_tts::init_runtime(&dylib).map_err(|error| error.to_string()).and_then(|()| services.speech.load()) {
        Ok(()) => {}
        Err(error) => eprintln!("KorvaTTS initialization failed ({error}); check model assets and dependencies."),
    }
    let config = &services.config;
    println!("ONPlay STT stream: ws://{} (allowed Origin: {})", config.address, config.origins.join(", "));
    println!("Capture: {}", match config.capture {
        Capture::Off => "off (only sessions that send store: true)",
        Capture::All => "transcript and audio",
        Capture::Transcript => "transcript",
        Capture::Audio => "audio",
    });
    match onplay_server::serve(services, listener) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("Error: {error}");
            ExitCode::FAILURE
        }
    }
}

fn describe(event: &Event) -> String {
    match event {
        Event::Refused { client } => format!("refused {}", describe_client(client)),
        Event::Opened { connection, client } => format!("#{connection} opened {}", describe_client(client)),
        Event::Started { connection, source, target, speech, speakers, session } => {
            let target = if target.is_empty() { "transcript only" } else { target };
            let speakers = match speakers {
                Speakers::Off => "off",
                Speakers::Live => "live",
                Speakers::Full => "full",
            };
            let session = session.as_deref().map(|id| format!(", session {id}")).unwrap_or_default();
            format!("#{connection} started {source} -> {target}, speech {}, speakers {speakers}{session}", if *speech { "on" } else { "off" })
        }
        Event::Ended { connection, outcome, duration, audio, segments } => {
            let outcome = match outcome {
                Outcome::Done => "done".to_owned(),
                Outcome::Closed => "client left".to_owned(),
                Outcome::Error(text) => format!("error: {text}"),
            };
            format!("#{connection} ended ({outcome}) after {:.1} s, {:.1} s audio, {segments} segments", duration.as_secs_f64(), audio.as_secs_f64())
        }
    }
}

fn describe_client(client: &Client) -> String {
    let missing = |value: &Option<String>| value.clone().unwrap_or_else(|| "-".to_owned());
    format!("{} (Origin {}, User-Agent {})", client.address, missing(&client.origin), missing(&client.user_agent))
}
