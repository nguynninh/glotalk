//! ONNX Runtime loading and the four KorvaTTS graphs.

mod device;
mod graphs;

use std::path::Path;

pub use device::Device;
pub use graphs::Graphs;

use crate::error::{Error, Result};

/// Loads the ONNX Runtime dylib once per process. Call before [`Graphs::load`].
///
/// The crate links ONNX Runtime dynamically so it shares the runtime shipped with the
/// Sherpa ONNX SDK instead of bundling another copy.
pub fn init_runtime(dylib: &Path) -> Result<()> {
    if !dylib.is_file() {
        return Err(Error::MissingAsset(dylib.into()));
    }
    ort::init_from(dylib.to_string_lossy()).with_name("onplay-tts").commit()?;
    Ok(())
}
