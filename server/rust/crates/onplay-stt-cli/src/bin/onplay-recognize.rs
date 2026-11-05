//! Serial whole-utterance recognition worker (replaces `src/recognize.cpp`).
//!
//! Private pipe protocol: after `READY\n`, each request is a uint32 little-endian byte count
//! followed by that many bytes of mono PCM16 16 kHz (at most 60 s); each reply is the decimal
//! byte count, `\n`, then the UTF-8 text.

use std::io::{Read, Write};
use std::process::ExitCode;

use onplay_stt::pcm::pcm16_to_f32;
use onplay_stt::utterance::UtteranceRecognizer;
use onplay_stt::ModelFiles;
use onplay_stt_cli::run;

const MAX_BYTES: u32 = 16_000 * 2 * 60;

fn main() -> ExitCode {
    run(|| {
        let args: Vec<String> = std::env::args().skip(1).collect();
        if args.len() != 4 {
            return Err("Expected encoder, decoder, joiner and tokens".into());
        }
        if !args.iter().all(|path| std::path::Path::new(path).is_file()) {
            return Err("Missing model file".into());
        }
        let model = ModelFiles { encoder: args[0].clone().into(), decoder: args[1].clone().into(), joiner: args[2].clone().into(), tokens: args[3].clone().into() };
        let recognizer = UtteranceRecognizer::load(&model, 2).map_err(|_| "Cannot initialize recognition model".to_owned())?;
        serve(&recognizer).map(|()| ExitCode::SUCCESS)
    })
}

fn serve(recognizer: &UtteranceRecognizer) -> Result<(), String> {
    let mut stdout = std::io::stdout().lock();
    let mut stdin = std::io::stdin().lock();
    stdout.write_all(b"READY\n").and_then(|()| stdout.flush()).map_err(|error| error.to_string())?;
    loop {
        let mut header = [0u8; 4];
        match read_full(&mut stdin, &mut header)? {
            0 => return Ok(()),
            4 => {}
            _ => return Err("Incomplete PCM header".into()),
        }
        let bytes = u32::from_le_bytes(header);
        if bytes == 0 || bytes % 2 != 0 || bytes > MAX_BYTES {
            return Err("Expected up to 60 seconds of mono PCM16 at 16 kHz".into());
        }
        let mut pcm = vec![0u8; bytes as usize];
        if read_full(&mut stdin, &mut pcm)? != pcm.len() {
            return Err("Incomplete PCM input".into());
        }
        let mut samples = vec![0.0f32; pcm.len() / 2];
        pcm16_to_f32(&pcm, &mut samples);
        let text = recognizer.transcribe(&samples).map_err(|error| error.to_string())?;
        write!(stdout, "{}\n{text}", text.len()).and_then(|()| stdout.flush()).map_err(|error| error.to_string())?;
    }
}

/// Reads until `out` is full or input ends; returns the bytes read.
fn read_full(input: &mut impl Read, out: &mut [u8]) -> Result<usize, String> {
    let mut filled = 0;
    while filled < out.len() {
        match input.read(&mut out[filled..]) {
            Ok(0) => break,
            Ok(read) => filled += read,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error.to_string()),
        }
    }
    Ok(filled)
}
