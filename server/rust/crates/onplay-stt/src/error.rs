pub type Result<T, E = Error> = std::result::Result<T, E>;

/// Messages match the C++ engine, which callers and tests match on.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// `std::invalid_argument` in C++.
    #[error("{0}")]
    InvalidArgument(String),
    /// `std::logic_error`: audio after `finish()`.
    #[error("Session already finished")]
    Finished,
    /// `std::runtime_error`: native or file failures.
    #[error("{0}")]
    Runtime(String),
    #[error(transparent)]
    Native(#[from] onplay_sherpa::Error),
}

impl Error {
    pub(crate) fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidArgument(message.into())
    }

    pub(crate) fn runtime(message: impl Into<String>) -> Self {
        Self::Runtime(message.into())
    }
}
