//! Real-model checks of the built tools, ported from `tests/smoke.py` and the command-line
//! cases of `tests/speakers.py`. Needs `./scripts/setup/stt.sh` and `setup/speakers.sh`; run
//! with `cargo test -p onplay-stt-cli -- --include-ignored` (as `scripts/dev/test.sh` does).

use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::mpsc;
use std::time::Duration;

const MODEL: &str = "models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10";
const STT_FILES: [&str; 4] = [
    "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
    "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
    "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
    "tokens.txt",
];

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn stt_args() -> Vec<PathBuf> {
    STT_FILES.iter().map(|name| root().join(MODEL).join(name)).collect()
}

fn speaker_args() -> Vec<PathBuf> {
    let models = root().join("models/speakers");
    vec![models.join("sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx"), models.join("embedding.onnx")]
}

/// The `data` chunk of a mono 16 kHz PCM16 WAV file, skipping chunks such as `LIST`.
fn pcm(path: &Path) -> Vec<u8> {
    let bytes = std::fs::read(path).unwrap();
    assert_eq!(&bytes[..4], b"RIFF");
    let mut pos = 12;
    let mut format = None;
    while pos + 8 <= bytes.len() {
        let size = u32::from_le_bytes(bytes[pos + 4..pos + 8].try_into().unwrap()) as usize;
        let body = &bytes[pos + 8..pos + 8 + size];
        match &bytes[pos..pos + 4] {
            b"fmt " => format = Some((u16::from_le_bytes([body[2], body[3]]), u32::from_le_bytes(body[4..8].try_into().unwrap()), u16::from_le_bytes([body[14], body[15]]))),
            b"data" => {
                assert_eq!(format, Some((1, 16_000, 16)), "{}", path.display());
                return body.to_vec();
            }
            _ => {}
        }
        pos += 8 + size + size % 2;
    }
    panic!("{}: no data chunk", path.display());
}

fn write_wav(path: &Path, data: &[u8]) {
    let mut out = Vec::with_capacity(44 + data.len());
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data.len() as u32).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    for value in [1u16, 1] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&16_000u32.to_le_bytes());
    out.extend_from_slice(&32_000u32.to_le_bytes());
    for value in [2u16, 16] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(b"data");
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(data);
    std::fs::write(path, out).unwrap();
}

fn temporary(name: &str) -> PathBuf {
    Path::new(env!("CARGO_TARGET_TMPDIR")).join(format!("{}-{name}", std::process::id()))
}

fn finals(audio: &Path) -> Vec<String> {
    let output = Command::new(env!("CARGO_BIN_EXE_onplay-stt")).args(stt_args()).arg(audio).output().unwrap();
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    assert!(String::from_utf8_lossy(&output.stderr).contains("rtf="));
    let finals: Vec<String> = stdout.lines().filter_map(|line| line.strip_prefix("FINAL   ")).map(str::to_owned).collect();
    assert!(!finals.is_empty(), "{stdout}");
    finals
}

fn non_ascii(texts: &[String]) -> bool {
    texts.iter().any(|text| !text.is_ascii())
}

#[test]
#[ignore = "needs downloaded models"]
fn recognizes_english_vietnamese_and_language_transition() {
    let english = root().join(MODEL).join("test_wavs/en.wav");
    let vietnamese = root().join("models/vi.wav");
    assert!(finals(&english).join(" ").contains("AFTER EARLY"));
    let vi = finals(&vietnamese);
    assert!(non_ascii(&vi), "{vi:?}");
    // Separate utterances with silence to exercise endpoint/reset and language changes.
    let silence = vec![0u8; 16_000 * 2 * 3];
    let path = temporary("conversation.wav");
    write_wav(&path, &[pcm(&english), silence.clone(), pcm(&vietnamese), silence].concat());
    let conversation = finals(&path);
    std::fs::remove_file(&path).unwrap();
    assert!(conversation.len() >= 2, "{conversation:?}");
    assert!(conversation[0].contains("AFTER EARLY"), "{conversation:?}");
    assert!(non_ascii(&conversation[1..]), "{conversation:?}");
}

/// Fails instead of hanging when a regression deadlocks the tool.
fn speakers(audio: &Path, timeout: Duration) -> Output {
    let child = Command::new(env!("CARGO_BIN_EXE_onplay-speakers"))
        .args(stt_args()).args(speaker_args()).arg(audio).arg("2")
        .stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || sender.send(child.wait_with_output()));
    receiver.recv_timeout(timeout).expect("onplay-speakers timed out").unwrap()
}

#[test]
#[ignore = "needs downloaded models"]
fn speaker_duration_bound_rejects_before_inference() {
    let path = temporary("long.wav");
    write_wav(&path, &vec![0u8; 32_000 * 601]);
    let output = speakers(&path, Duration::from_secs(10));
    std::fs::remove_file(&path).unwrap();
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(!output.status.success() && stderr.contains("10 minutes"), "{stderr}");
}

#[test]
#[ignore = "needs downloaded models"]
fn multi_turn_diarization_with_helper_recognizer_completes() {
    // Many turns with a helper recognizer used to deadlock on the shared job queue.
    let output = speakers(&root().join("models/speakers/four-speakers-zh.wav"), Duration::from_secs(120));
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    assert!(String::from_utf8_lossy(&output.stdout).lines().count() > 1);
}
