//! Binaries find the Sherpa ONNX and ONNX Runtime dylibs in `lib/` next to themselves, like
//! the C++ build (`-Wl,-rpath,@loader_path/lib`); `scripts/dev/build.sh` copies them there.

fn main() {
    println!("cargo:rustc-link-arg-bins=-Wl,-rpath,@loader_path/lib");
}
