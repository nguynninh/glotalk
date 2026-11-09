//! One dub job as an `onplay-dub --progress` child process.
//!
//! A child process keeps the job's memory (three speech synthesizers, ffmpeg, yt-dlp) out of
//! the server and makes cancellation a kill. It runs in its own process group so a kill also
//! reaches `onplay-speakers`, ffmpeg and yt-dlp; its temporary files go to the job's `work/`
//! directory (`TMPDIR`), which is removed with the job even after a kill.

use std::io::{BufRead, BufReader};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

use serde_json::Value;

/// Failure codes the child reports; anything else is `internal`.
const CODES: [&str; 5] = ["download_failed", "no_speech", "translation_failed", "speech_failed", "internal"];

pub struct Spec<'a> {
    pub bin: &'a Path,
    pub root: &'a Path,
    pub translate_url: &'a str,
    pub url: &'a str,
    pub target: &'a str,
    pub subtitles_only: bool,
    pub work: &'a Path,
    pub hls: &'a Path,
}

#[derive(Debug, PartialEq)]
pub enum Finished {
    Done { subtitles: PathBuf, original: PathBuf },
    Failed(&'static str),
}

pub fn spawn(spec: &Spec) -> std::io::Result<Child> {
    let temp = spec.work.join("tmp");
    std::fs::create_dir_all(&temp)?;
    let mut command = Command::new(spec.bin);
    command
        .arg(spec.url)
        .args(["--target", spec.target, "--translate-url", spec.translate_url, "--progress"])
        .arg("--root")
        .arg(spec.root)
        .arg("--output")
        .arg(spec.work.join("video.mp4"))
        .arg("--hls")
        .arg(spec.hls);
    if spec.subtitles_only {
        command.arg("--subtitles-only");
    }
    command.env("TMPDIR", &temp).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).process_group(0).spawn()
}

/// Passes each event line to `on_event` until the child exits.
pub fn wait(mut child: Child, mut on_event: impl FnMut(&Value)) -> Finished {
    let stdout = child.stdout.take().expect("stdout is piped");
    let mut stderr = child.stderr.take().expect("stderr is piped");
    // Drained so the child never blocks on a full pipe; its text may hold the link, so it
    // is dropped rather than logged.
    let drain = std::thread::spawn(move || std::io::copy(&mut stderr, &mut std::io::sink()));
    let mut finished = None;
    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { break };
        let Ok(event) = serde_json::from_str::<Value>(&line) else { continue };
        match event.get("event").and_then(Value::as_str) {
            Some("done") => finished = done(&event),
            Some("error") => finished = Some(failed(&event)),
            _ => on_event(&event),
        }
    }
    let _ = drain.join();
    let success = child.wait().is_ok_and(|status| status.success());
    match finished {
        Some(Finished::Done { .. }) if !success => Finished::Failed("internal"),
        Some(finished) => finished,
        None => Finished::Failed("internal"),
    }
}

fn done(event: &Value) -> Option<Finished> {
    let path = |key: &str| event.get(key).and_then(Value::as_str).map(PathBuf::from);
    Some(Finished::Done { subtitles: path("subtitles")?, original: path("original_subtitles")? })
}

fn failed(event: &Value) -> Finished {
    let code = event.get("code").and_then(Value::as_str).unwrap_or_default();
    Finished::Failed(CODES.iter().find(|known| **known == code).copied().unwrap_or("internal"))
}

/// Sends `signal` (`TERM` or `KILL`) to the process group led by `pid`.
pub fn signal_group(pid: u32, signal: &str) {
    let _ = Command::new("/bin/kill")
        .args([format!("-{signal}"), "--".to_owned(), format!("-{pid}")])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// Size of every file under `path`; 0 when it does not exist.
pub fn disk_usage(path: &Path) -> u64 {
    let Ok(metadata) = std::fs::symlink_metadata(path) else { return 0 };
    if !metadata.is_dir() {
        return metadata.len();
    }
    std::fs::read_dir(path).map_or(0, |entries| entries.filter_map(Result::ok).map(|entry| disk_usage(&entry.path())).sum())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn child(script: &str) -> Child {
        Command::new("/bin/sh").args(["-c", script]).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap()
    }

    #[test]
    fn reads_events_and_result() {
        let mut events = Vec::new();
        let script = r#"echo '{"event":"muxing"}'; echo noise >&2; echo '{"event":"done","subtitles":"/j/s.srt","original_subtitles":"/j/o.srt"}'"#;
        let finished = wait(child(script), |event| events.push(event.clone()));
        assert_eq!(events, [json!({"event": "muxing"})]);
        assert_eq!(finished, Finished::Done { subtitles: "/j/s.srt".into(), original: "/j/o.srt".into() });
    }

    #[test]
    fn reports_failure_codes() {
        assert_eq!(wait(child(r#"echo '{"event":"error","code":"no_speech"}'; exit 1"#), |_| {}), Finished::Failed("no_speech"));
        assert_eq!(wait(child(r#"echo '{"event":"error","code":"odd"}'; exit 1"#), |_| {}), Finished::Failed("internal"));
        assert_eq!(wait(child("exit 0"), |_| {}), Finished::Failed("internal"));
    }

    #[test]
    fn kills_the_whole_process_group() {
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "sleep 30 & sleep 30; echo late"]).stdout(Stdio::piped()).stderr(Stdio::piped()).process_group(0);
        let child = command.spawn().unwrap();
        let pid = child.id();
        signal_group(pid, "TERM");
        let started = std::time::Instant::now();
        assert_eq!(wait(child, |_| {}), Finished::Failed("internal"));
        assert!(started.elapsed() < std::time::Duration::from_secs(5));
    }
}
