pub type Result<T, E = Error> = std::result::Result<T, E>;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// `config/translation.yaml` is missing or invalid.
    #[error("invalid translation config: {0}")]
    Config(String),
    /// The request is invalid (HTTP 400).
    #[error("{0}")]
    Invalid(String),
    /// The request's lane is already translating (HTTP 409).
    #[error("Translation engine busy; retry later")]
    Busy,
    /// Every model on the route failed (HTTP 503).
    #[error("{0}")]
    Failed(String),
}

impl Error {
    pub(crate) fn invalid(message: &str) -> Self {
        Self::Invalid(message.to_owned())
    }
}
