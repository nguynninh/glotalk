mod args;

use std::process::ExitCode;

use onplay_dub::hls::{self, Subtitles};
use onplay_dub::{Error, Event, Outputs, Project, Silent};
use serde_json::{json, Value};

use args::{Args, USAGE};

fn main() -> ExitCode {
    let args = match args::parse(std::env::args().skip(1)) {
        Ok(args) => args,
        Err(error) => {
            eprintln!("Error: {error}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    let progress = |event: Event| println!("{}", describe(event));
    let observer: &dyn onplay_dub::Observer = if args.progress { &progress } else { &Silent };
    let result = Project::open(&args.root).and_then(|project| onplay_dub::run(&project, &args.options, observer)).and_then(|outputs| {
        if let Some(dir) = &args.hls {
            package(&args, &outputs, dir)?;
        }
        Ok(outputs)
    });
    match (result, args.progress) {
        (Ok(outputs), false) => {
            println!("{}", outputs.video.display());
            println!("{}", outputs.translated_srt.display());
            println!("{}", outputs.original_srt.display());
            ExitCode::SUCCESS
        }
        (Ok(outputs), true) => {
            let path = |path: &std::path::Path| path.to_string_lossy().into_owned();
            println!(
                "{}",
                json!({"event": "done", "video": path(&outputs.video), "subtitles": path(&outputs.translated_srt),
                       "original_subtitles": path(&outputs.original_srt), "source_language": outputs.source_language})
            );
            ExitCode::SUCCESS
        }
        (Err(error), progress) => {
            if progress {
                println!("{}", json!({"event": "error", "code": error.code(), "text": error.to_string()}));
            }
            eprintln!("Error: {error}");
            ExitCode::FAILURE
        }
    }
}

/// HLS with the translation as the default subtitles and the original as the second.
fn package(args: &Args, outputs: &Outputs, dir: &std::path::Path) -> Result<(), Error> {
    let target = args.options.target.as_str();
    let source = outputs.source_language.as_deref();
    let subtitles = [
        Subtitles { srt: &outputs.translated_srt, language: Some(target), name: hls::language_name(target).into(), default: true, stem: "subtitles" },
        Subtitles {
            srt: &outputs.original_srt,
            language: source,
            name: format!("{} (original)", source.map_or("Original", hls::language_name)),
            default: false,
            stem: "original",
        },
    ];
    hls::package(&outputs.video, &subtitles, dir)
}

/// One `--progress` line per pipeline event.
fn describe(event: Event) -> Value {
    match event {
        Event::Downloading => json!({"event": "downloading"}),
        Event::Recognizing { offset, duration } => json!({"event": "recognizing", "offset": offset, "duration": duration}),
        Event::Recognized { index, end } => json!({"event": "recognized", "index": index, "end": end}),
        Event::Translated { index } => json!({"event": "translated", "index": index}),
        Event::Transcribed { segments, speech } => json!({"event": "transcribed", "segments": segments, "speech": speech}),
        Event::Spoken { index } => json!({"event": "spoken", "index": index}),
        Event::Muxing => json!({"event": "muxing"}),
    }
}
