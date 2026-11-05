//! Text rules shared by requests and model output, matching Python `str` semantics.

mod normalize;
pub(crate) mod placeholders;
pub(crate) mod whitespace;

pub(crate) use normalize::{normalize, TEXT_ERROR};

/// Python `unicodedata.category(c) == 'Cc'`.
pub(crate) fn is_control(c: char) -> bool {
    c.is_control()
}
