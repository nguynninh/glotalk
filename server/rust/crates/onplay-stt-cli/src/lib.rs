//! Shared pieces of the `onplay-stt`, `onplay-recognize` and `onplay-speakers` binaries,
//! which keep the command lines and output protocols of the C++ tools they replace.

use std::process::ExitCode;

pub mod format;

/// Runs a tool body; errors print `Error: MESSAGE` to stderr and exit with status 1.
pub fn run(body: impl FnOnce() -> Result<ExitCode, String>) -> ExitCode {
    match body() {
        Ok(code) => code,
        Err(message) => {
            eprintln!("Error: {message}");
            ExitCode::FAILURE
        }
    }
}

/// Parses a decimal integer, rejecting trailing characters like C++ `std::stoi` with `end`.
pub fn integer(text: &str, message: &str) -> Result<i32, String> {
    text.parse().map_err(|_| message.to_owned())
}
