use std::path::PathBuf;

pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// A C constructor returned NULL; Sherpa prints its own reason to stderr.
    #[error("{0}")]
    Create(&'static str),
    #[error("path is not valid UTF-8 without NUL bytes: {0}")]
    Path(PathBuf),
    #[error("text contains a NUL byte")]
    Nul,
    #[error("{0} samples exceed the C API's int range")]
    TooManySamples(usize),
}
