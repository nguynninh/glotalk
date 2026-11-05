//! The binary finds the Sherpa ONNX and ONNX Runtime dylibs in `lib/` next to itself, like
//! the STT tools; `scripts/dev/build.sh` copies them there.

fn main() {
    println!("cargo:rustc-link-arg-bins=-Wl,-rpath,@loader_path/lib");
}
