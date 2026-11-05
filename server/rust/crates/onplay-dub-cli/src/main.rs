mod args;

use std::process::ExitCode;

use onplay_dub::{Project, Silent};

use args::USAGE;

fn main() -> ExitCode {
    let args = match args::parse(std::env::args().skip(1)) {
        Ok(args) => args,
        Err(error) => {
            eprintln!("Error: {error}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    let result = Project::open(&args.root).and_then(|project| onplay_dub::run(&project, &args.options, &Silent));
    match result {
        Ok(outputs) => {
            println!("{}", outputs.video.display());
            println!("{}", outputs.translated_srt.display());
            println!("{}", outputs.original_srt.display());
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("Error: {error}");
            ExitCode::FAILURE
        }
    }
}
