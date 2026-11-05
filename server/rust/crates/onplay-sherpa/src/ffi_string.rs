//! Conversions between Rust strings and the C strings the API borrows or returns.

use std::ffi::{c_char, CStr, CString};
use std::path::Path;

use crate::error::{Error, Result};

pub(crate) fn path(path: &Path) -> Result<CString> {
    path.to_str().and_then(|text| CString::new(text).ok()).ok_or_else(|| Error::Path(path.into()))
}

pub(crate) fn text(text: &str) -> Result<CString> {
    CString::new(text).map_err(|_| Error::Nul)
}

/// Literal options such as `c"cpu"` live for the whole program.
pub(crate) fn literal(text: &'static CStr) -> *const c_char {
    text.as_ptr()
}

/// Copies a possibly NULL, NUL-terminated C string; invalid UTF-8 is replaced.
///
/// # Safety
/// `pointer` must be NULL or point to a NUL-terminated string valid for this call.
pub(crate) unsafe fn copy(pointer: *const c_char) -> String {
    if pointer.is_null() {
        return String::new();
    }
    // SAFETY: non-NULL and NUL-terminated per this function's contract.
    unsafe { CStr::from_ptr(pointer) }.to_string_lossy().into_owned()
}

pub(crate) fn sample_count(samples: &[f32]) -> Result<i32> {
    i32::try_from(samples.len()).map_err(|_| Error::TooManySamples(samples.len()))
}
