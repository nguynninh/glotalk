mod args;
mod batch;
mod noise_file;

use std::process::ExitCode;

use onplay_tts::audio::write_pcm16_wav;
use onplay_tts::text::Language;
use onplay_tts::{init_runtime, Assets, Device, GaussianNoise, NoiseSource, SynthesisOptions, Synthesizer, VoiceStyle};

use args::{Args, USAGE};
use noise_file::NoiseFile;

fn main() -> ExitCode {
    let args = match args::parse(std::env::args().skip(1)) {
        Ok(args) => args,
        Err(error) => {
            eprintln!("Error: {error}\n\n{USAGE}");
            return ExitCode::from(2);
        }
    };
    match run(args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("Error: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: Args) -> Result<(), String> {
    let assets = match &args.assets {
        Some(path) => Assets::open(path),
        None => Assets::from_huggingface_cache(),
    }
    .map_err(|error| error.to_string())?;
    if args.list_voices {
        println!("{}", assets.voices().map_err(|error| error.to_string())?.join("\n"));
        return Ok(());
    }

    let defaults = SynthesisOptions::default();
    let options = SynthesisOptions {
        total_steps: args.steps.unwrap_or(defaults.total_steps),
        speed: args.speed.unwrap_or(defaults.speed),
        ..defaults
    };
    let dylib = args
        .ort_dylib
        .clone()
        .or_else(|| std::env::var_os("ORT_DYLIB_PATH").map(Into::into))
        .ok_or("set --ort-dylib or ORT_DYLIB_PATH to the ONNX Runtime dylib")?;
    init_runtime(&dylib).map_err(|error| error.to_string())?;
    let device = Device::parse(args.device.as_deref().unwrap_or("cpu")).map_err(|error| error.to_string())?;
    let mut synthesizer = Synthesizer::load(&assets, device, args.threads).map_err(|error| error.to_string())?;
    if args.batch {
        return batch::serve(&assets, &mut synthesizer, options);
    }
    synthesize_once(&args, &assets, &mut synthesizer, options)
}

fn synthesize_once(
    args: &Args,
    assets: &Assets,
    synthesizer: &mut Synthesizer,
    options: SynthesisOptions,
) -> Result<(), String> {
    let text = match (&args.text, &args.text_file) {
        (Some(text), _) => text.clone(),
        (_, Some(path)) => std::fs::read_to_string(path).map_err(|error| format!("{}: {error}", path.display()))?,
        _ => unreachable!("args::parse requires text"),
    };
    let language = Language::parse(args.lang.as_deref().unwrap_or("vi")).map_err(|error| error.to_string())?;
    let voice_path = assets.voice(args.voice.as_deref().unwrap_or("khanh_vy")).map_err(|error| error.to_string())?;
    let voice = VoiceStyle::load(&voice_path).map_err(|error| error.to_string())?;
    let mut noise: Box<dyn NoiseSource> = match &args.noise {
        Some(path) => Box::new(NoiseFile::open(path)?),
        None => Box::new(GaussianNoise::new(args.seed)),
    };
    let wav = synthesizer
        .synthesize(&text, language, &voice, options, noise.as_mut())
        .map_err(|error| error.to_string())?;
    let output = args.output.as_ref().expect("args::parse requires output");
    write_pcm16_wav(output, &wav, synthesizer.sample_rate()).map_err(|error| error.to_string())?;
    eprintln!("Wrote {} ({:.2} s)", output.display(), wav.len() as f64 / f64::from(synthesizer.sample_rate()));
    Ok(())
}
