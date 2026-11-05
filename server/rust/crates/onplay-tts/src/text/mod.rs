//! Text preparation, mirroring `korvatts/text_processor.py`.
//!
//! The model consumes Unicode code points mapped through `unicode_indexer.json`, built on
//! NFKD text. Lengths are counted in code points, like Python `len(str)`.

mod chunk;
mod indexer;
mod lang;
mod normalize;
mod whitespace;

pub use chunk::chunk_text;
pub use indexer::UnicodeIndexer;
pub use lang::{chunk_limit, Language};
pub use normalize::normalize_text;
