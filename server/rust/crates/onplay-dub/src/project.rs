//! Paths inside the onplay-stt checkout: native binaries, models and the ONNX Runtime dylib.

use std::io::Read;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::error::{Error, Result};

const STT_MODEL: &str = "models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10";
const STT_FILES: [&str; 4] = [
    "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
    "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
    "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
    "tokens.txt",
];
const SEGMENTATION: &str = "models/speakers/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx";
const EMBEDDING: &str = "models/speakers/embedding.onnx";
/// Same check as `valid_embedding()` in `ui/ws_stream.py`.
const EMBEDDING_SHA256: &str = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11";
const SPEAKERS_BIN: &str = "build/onplay-speakers";
const ORT_DYLIB: &str = ".deps/sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib/lib/libonnxruntime.dylib";

/// A checkout whose recognition binary and models exist.
#[derive(Debug, Clone)]
pub struct Project {
    root: PathBuf,
}

impl Project {
    pub fn open(root: impl Into<PathBuf>) -> Result<Self> {
        let project = Self { root: root.into() };
        let required = project.speaker_args().into_iter().chain([project.speakers_bin(), project.ort_dylib()]);
        for path in required {
            if !path.is_file() {
                return Err(Error::Setup(format!(
                    "missing {}; run ./scripts/setup/stt.sh, ./scripts/setup/speakers.sh and ./scripts/dev/build.sh",
                    path.display()
                )));
            }
        }
        let embedding = project.root.join(EMBEDDING);
        if sha256(&embedding)? != EMBEDDING_SHA256 {
            return Err(Error::Setup(format!("{} has an unexpected checksum; run ./scripts/setup/speakers.sh", embedding.display())));
        }
        Ok(project)
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn speakers_bin(&self) -> PathBuf {
        self.root.join(SPEAKERS_BIN)
    }

    /// Model arguments for `onplay-speakers`, before the audio path and thread count.
    pub fn speaker_args(&self) -> Vec<PathBuf> {
        let model = self.root.join(STT_MODEL);
        STT_FILES
            .iter()
            .map(|name| model.join(name))
            .chain([self.root.join(SEGMENTATION), self.root.join(EMBEDDING)])
            .collect()
    }

    pub fn ort_dylib(&self) -> PathBuf {
        self.root.join(ORT_DYLIB)
    }
}

fn sha256(path: &Path) -> Result<String> {
    let mut file = std::fs::File::open(path).map_err(Error::io(path))?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0; 1 << 20];
    loop {
        let read = file.read(&mut buffer).map_err(Error::io(path))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect())
}
