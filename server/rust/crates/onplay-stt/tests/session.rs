//! Session contracts with the real models, ported from `tests/session.cpp`.
//! Needs `./scripts/setup/stt.sh` (and `setup/speakers.sh` for the speaker case); run with
//! `cargo test -p onplay-stt -- --include-ignored` (as `scripts/dev/test.sh` does).

use std::path::{Path, PathBuf};

use onplay_stt::{Engine, EngineConfig, Error, ModelFiles, WaveReader};

const MODEL: &str = "models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10";

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn config() -> EngineConfig {
    let model = root().join(MODEL);
    EngineConfig {
        model: ModelFiles {
            encoder: model.join("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx"),
            decoder: model.join("decoder-epoch-75-avg-11-chunk-16-left-128.onnx"),
            joiner: model.join("joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx"),
            tokens: model.join("tokens.txt"),
        },
        threads: 2,
        speaker_model: None,
        low_latency: false,
    }
}

fn feed(session: &mut onplay_stt::Session<'_, impl FnMut(&onplay_stt::Transcript)>, repeats: usize) {
    for _ in 0..repeats {
        let mut audio = WaveReader::open(&root().join("models/vi.wav")).unwrap();
        let mut chunk = vec![0.0; 1600];
        loop {
            let count = audio.next(&mut chunk).unwrap();
            if count == 0 {
                break;
            }
            session.accept_audio(&chunk[..count]).unwrap();
        }
    }
}

#[test]
#[ignore = "needs downloaded models"]
fn rejects_invalid_configuration() {
    let mut bad = config();
    bad.model.encoder = "/missing-onplay-model.onnx".into();
    assert!(matches!(Engine::load(&bad), Err(Error::InvalidArgument(_))));
    let mut bad = config();
    bad.speaker_model = Some("/missing-onplay-speaker.onnx".into());
    assert!(matches!(Engine::load(&bad), Err(Error::InvalidArgument(_))));
    let mut bad = config();
    bad.threads = 0;
    assert!(matches!(Engine::load(&bad), Err(Error::InvalidArgument(_))));
}

#[test]
#[ignore = "needs downloaded models"]
fn validates_audio_and_finishes_idempotently() {
    let engine = Engine::load(&config()).unwrap();
    let mut results = 0;
    let mut session = engine.session(|_| results += 1).unwrap();
    assert!(matches!(session.accept_audio(&[f32::NAN]), Err(Error::InvalidArgument(_))));
    assert!(matches!(session.accept_audio(&[1.1]), Err(Error::InvalidArgument(_))));
    assert!(matches!(session.accept_audio(&[0.0; 16_001]), Err(Error::InvalidArgument(_))));
    for _ in 0..40 {
        session.accept_audio(&[0.0; 1600]).unwrap();
    }
    session.finish().unwrap();
    session.finish().unwrap();
    assert!(matches!(session.accept_audio(&[0.0; 1600]), Err(Error::Finished)));
    drop(session);
    assert_eq!(results, 0, "silence generated a transcript");
}

#[test]
#[ignore = "needs downloaded models"]
fn low_latency_bounds_final_segments() {
    let mut config = config();
    config.low_latency = true;
    let engine = Engine::load(&config).unwrap();
    let mut finals = 0;
    let mut session = engine.session(|transcript| finals += usize::from(transcript.is_final)).unwrap();
    // About 28 seconds of continuous speech must finalize at least twice before finish(),
    // so translation can start within the 12-second bound.
    feed(&mut session, 7);
    drop(session);
    assert!(finals >= 2, "low-latency speech produced {finals} final segments");
}

#[test]
#[ignore = "needs downloaded models and ./scripts/setup/speakers.sh"]
fn labels_live_speakers_before_finish() {
    let mut config = config();
    config.speaker_model = Some(root().join("models/speakers/embedding.onnx"));
    let engine = Engine::load(&config).unwrap();
    let mut labelled = 0;
    let mut session = engine.session(|transcript| labelled += usize::from(!transcript.is_final && transcript.speaker > 0)).unwrap();
    feed(&mut session, 1);
    session.finish().unwrap();
    session.finish().unwrap();
    drop(session);
    assert!(labelled > 0, "expected a live speaker label before finish");
}
