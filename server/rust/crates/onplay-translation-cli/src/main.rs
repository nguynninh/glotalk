//! `onplay-translation`: the local translation router, which replaced the Python
//! `ui/translation_engine.py`.
//!
//! Run as `build/onplay-translation` (see `scripts/dev/translation.sh`). It reads
//! `config/translation.yaml` and the models under the project root, which is the parent of the
//! binary's directory unless `ONPLAY_ROOT` is set, warms the CTranslate2 models, then serves
//! `http://127.0.0.1:5002` (`ONPLAY_TRANSLATION_ADDRESS` overrides it) with one thread per
//! request.

mod http;

use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;

use onplay_translation::{Config, Engine};

const ADDRESS: &str = "127.0.0.1:5002";

fn main() -> ExitCode {
    let executable = std::env::current_exe().ok().and_then(|path| path.canonicalize().ok());
    let directory = executable.as_ref().and_then(|path| path.parent()).map(PathBuf::from).unwrap_or_default();
    let root = std::env::var_os("ONPLAY_ROOT").map_or_else(|| directory.parent().map(PathBuf::from).unwrap_or_default(), PathBuf::from);

    let engine = match Config::load(&root).map(Engine::new) {
        Ok(engine) => Arc::new(engine),
        Err(error) => return fail(&error),
    };
    if let Err(error) = engine.warmup() {
        return fail(&error);
    }
    let address = std::env::var("ONPLAY_TRANSLATION_ADDRESS").unwrap_or_else(|_| ADDRESS.to_owned());
    let server = match tiny_http::Server::http(&address) {
        Ok(server) => server,
        Err(error) => return fail(&error),
    };
    println!("ONPlay translation router: http://{address}");
    for request in server.incoming_requests() {
        let engine = Arc::clone(&engine);
        std::thread::spawn(move || http::handle(&engine, request));
    }
    ExitCode::SUCCESS
}

fn fail(error: &dyn std::fmt::Display) -> ExitCode {
    eprintln!("Error: {error}");
    ExitCode::FAILURE
}
