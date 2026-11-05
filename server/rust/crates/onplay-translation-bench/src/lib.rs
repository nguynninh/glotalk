//! Black-box benchmark of a translation router (`build/onplay-translation`, or any router
//! with the same HTTP contract) on text from recorded ONPlay sessions.
//!
//! sessions/*.jsonl -> [`corpus`] items -> [`run`]: start the router ([`router`]), send every
//! item over HTTP ([`client`]), sample memory ([`memory`]) -> [`report`] per run
//! -> [`compare`] two reports: identical output and latency/memory side by side.
//!
//! The router is only reached through `GET /health` and `POST /translate`, so every
//! implementation is measured with the same client, input, models and config.

pub mod client;
pub mod compare;
pub mod corpus;
pub mod error;
pub mod events;
pub mod memory;
pub mod report;
pub mod router;
pub mod run;
pub mod stats;

pub use compare::Comparison;
pub use corpus::Item;
pub use error::{Error, Result};
pub use events::{Event, Observer, Silent};
pub use report::{Record, Summary};
pub use run::{run, Options};
