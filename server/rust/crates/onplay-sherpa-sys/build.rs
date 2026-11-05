//! Generates bindings from the pinned Sherpa ONNX header and links the C API dylib.
//!
//! Defaults point at the checkout's `.deps/` populated by `scripts/setup/stt.sh`; override with
//! `SHERPA_ONNX_INCLUDE_DIR` (directory containing `sherpa-onnx/c-api/c-api.h`) and
//! `SHERPA_ONNX_LIB_DIR` (directory containing `libsherpa-onnx-c-api.dylib`).

use std::env;
use std::path::PathBuf;

fn main() {
    let deps = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../.deps");
    let include = env::var_os("SHERPA_ONNX_INCLUDE_DIR").map_or_else(|| deps.join("include"), PathBuf::from);
    let lib = env::var_os("SHERPA_ONNX_LIB_DIR")
        .map_or_else(|| deps.join("sherpa-onnx-v2.0.1-osx-arm64-shared-no-tts-lib/lib"), PathBuf::from);
    let header = include.join("sherpa-onnx/c-api/c-api.h");
    if !header.is_file() {
        panic!("{} not found; run ./scripts/setup/stt.sh or set SHERPA_ONNX_INCLUDE_DIR", header.display());
    }
    println!("cargo:rerun-if-env-changed=SHERPA_ONNX_INCLUDE_DIR");
    println!("cargo:rerun-if-env-changed=SHERPA_ONNX_LIB_DIR");
    println!("cargo:rerun-if-changed={}", header.display());
    // Link against copies inside OUT_DIR: cargo adds search paths under target/ to the dylib
    // path of `cargo run` and `cargo test`, so those find the libraries without install steps.
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    for name in ["libsherpa-onnx-c-api.dylib", "libonnxruntime.dylib"] {
        let source = lib.join(name);
        println!("cargo:rerun-if-changed={}", source.display());
        std::fs::copy(&source, out.join(name))
            .unwrap_or_else(|error| panic!("copy {}: {error}; run ./scripts/setup/stt.sh or set SHERPA_ONNX_LIB_DIR", source.display()));
    }
    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=dylib=sherpa-onnx-c-api");

    let bindings = bindgen::Builder::default()
        .header(header.to_string_lossy())
        .allowlist_function("SherpaOnnx.*")
        .allowlist_type("SherpaOnnx.*")
        .derive_default(true)
        .layout_tests(true)
        .generate()
        .expect("generate Sherpa ONNX bindings");
    bindings.write_to_file(out.join("bindings.rs")).expect("write bindings");
}
