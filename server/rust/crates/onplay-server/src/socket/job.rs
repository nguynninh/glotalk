//! A dub job connection: one command (`dub`, `job`, `cancel`, `delete`, `list`), then the
//! job's updates until it ends (`docs/video-dubbing-design.md`, phase 1).

use std::sync::mpsc;
use std::time::{Duration, Instant};

use onplay_dub::fetch::{probe, LinkInfo};
use serde_json::{json, Map, Value};

use super::outbox::Outbox;
use super::transport::{Ended, Incoming, Transport};
use crate::jobs::{text, Attach, Jobs, Refusal, Request, VERSION};
use crate::languages::is_target;

/// How often the connection is serviced (pings, queued events) while a link is read.
const PROBE_POLL: Duration = Duration::from_millis(100);

/// Whether the first message is a job command rather than live-session options, which
/// never have a `type`.
pub fn is_command(message: &Value) -> bool {
    message.get("type").is_some()
}

/// Serves a job connection whose first message is `command`. `Err` carries the error code
/// already sent to the client.
pub fn serve(transport: &mut Transport, outbox: &Outbox, jobs: &Jobs, command: &Value, token: Option<&str>) -> Result<(), String> {
    let refused = |refusal: Refusal| {
        let mut message = refusal.extra;
        message.insert("version".into(), VERSION.into());
        message.insert("type".into(), "error".into());
        message.insert("code".into(), refusal.code.into());
        message.insert("text".into(), text(refusal.code).into());
        let _ = outbox.emit(&Value::Object(message));
        refusal.code.to_owned()
    };
    run(transport, outbox, jobs, command, token).map_err(refused)
}

fn run(transport: &mut Transport, outbox: &Outbox, jobs: &Jobs, command: &Value, token: Option<&str>) -> Result<(), Refusal> {
    if command.get("version").and_then(Value::as_u64) != Some(VERSION) {
        return Err(Refusal::new("unsupported_version").with("supported", json!([VERSION])));
    }
    let kind = command.get("type").and_then(Value::as_str).unwrap_or_default();
    if !["dub", "job", "cancel", "delete", "list"].contains(&kind) {
        return Err(invalid());
    }
    if jobs.disabled().is_some() {
        return Err(Refusal::new("dub_disabled"));
    }
    let owner = jobs.owner(token).ok_or(Refusal::new("invalid_token"))?;
    let job_id = || command.get("job_id").and_then(Value::as_str).filter(|id| !id.is_empty()).ok_or_else(invalid);
    match kind {
        "dub" => {
            let request = parse_dub(command)?;
            jobs.admit(&owner)?;
            if !jobs.router_ready() {
                return Err(Refusal::new("dub_disabled"));
            }
            let info = read_link(transport, &request.url, jobs.local_links())?;
            if info.duration > jobs.max_seconds() {
                return Err(Refusal::new("too_long").with("max_duration_s", jobs.max_seconds()));
            }
            let id = jobs.create(&owner, request, info, outbox)?;
            follow(transport, jobs, &owner, &id)
        }
        "job" => {
            let id = job_id()?;
            jobs.attach(&owner, id, outbox, Attach::Stream)?;
            follow(transport, jobs, &owner, id)
        }
        "cancel" => {
            let id = job_id()?;
            jobs.cancel(&owner, id)?;
            jobs.attach(&owner, id, outbox, Attach::FinalOnly)?;
            drain(transport)
        }
        "delete" => {
            let id = job_id()?;
            jobs.delete(&owner, id)?;
            let _ = outbox.emit(&json!({"version": VERSION, "type": "deleted", "job_id": id}));
            outbox.close();
            drain(transport)
        }
        _ => {
            let _ = outbox.emit(&json!({"version": VERSION, "type": "jobs", "jobs": jobs.list(&owner)}));
            outbox.close();
            drain(transport)
        }
    }
}

fn invalid() -> Refusal {
    Refusal::new("invalid_message")
}

fn parse_dub(command: &Value) -> Result<Request, Refusal> {
    let url = command.get("url").and_then(Value::as_str).filter(|url| !url.is_empty()).ok_or_else(invalid)?;
    let target = command.get("target_language").and_then(Value::as_str).ok_or_else(invalid)?;
    let subtitles_only = match command.get("subtitles_only") {
        None => false,
        Some(Value::Bool(flag)) => *flag,
        Some(_) => return Err(invalid()),
    };
    if !is_target(target) || (!subtitles_only && !onplay_dub::voices::speakable(target)) {
        return Err(Refusal::new("unsupported_language"));
    }
    Ok(Request { url: url.to_owned(), target: target.to_owned(), subtitles_only })
}

/// Reads the link's metadata on another thread while the connection keeps answering pings.
fn read_link(transport: &mut Transport, url: &str, local_links: bool) -> Result<LinkInfo, Refusal> {
    let (done, result) = mpsc::channel();
    let url = url.to_owned();
    std::thread::spawn(move || drop(done.send(probe(&url, local_links))));
    loop {
        if let Ok(probed) = result.try_recv() {
            return probed.map_err(|error| match error {
                onplay_dub::Error::Setup(_) => Refusal::new("dub_disabled"),
                _ => Refusal::new("invalid_link"),
            });
        }
        match transport.next(Some(Instant::now() + PROBE_POLL)) {
            Ok(None) => {}
            Ok(Some(_)) => return Err(invalid()),
            Err(Ended) => return Err(Refusal::new("invalid_message")),
        }
    }
}

/// Serves a streaming connection until the job ends; the only command accepted meanwhile
/// is `cancel` for this job.
fn follow(transport: &mut Transport, jobs: &Jobs, owner: &str, id: &str) -> Result<(), Refusal> {
    loop {
        let message = match transport.next(None) {
            Ok(Some(message)) => message,
            Ok(None) => continue,
            Err(Ended) => return Ok(()),
        };
        let Incoming::Text(text) = message else { return Err(invalid()) };
        let command: Map<String, Value> = serde_json::from_str(&text).map_err(|_| invalid())?;
        let cancel = command.get("version").and_then(Value::as_u64) == Some(VERSION)
            && command.get("type").and_then(Value::as_str) == Some("cancel")
            && command.get("job_id").and_then(Value::as_str) == Some(id);
        if !cancel {
            return Err(invalid());
        }
        jobs.cancel(owner, id)?;
    }
}

/// Waits for the closing handshake the outbox started; anything sent meanwhile is ignored.
fn drain(transport: &mut Transport) -> Result<(), Refusal> {
    while transport.next(None).is_ok() {}
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tells_job_commands_from_session_options() {
        assert!(is_command(&json!({"version": 1, "type": "list"})));
        assert!(!is_command(&json!({"source_language": "auto", "target_language": "vi"})));
    }

    #[test]
    fn validates_dub_requests() {
        let request = parse_dub(&json!({"url": "https://youtu.be/x", "target_language": "vi"})).unwrap();
        assert_eq!((request.url.as_str(), request.target.as_str(), request.subtitles_only), ("https://youtu.be/x", "vi", false));
        let code = |command: Value| parse_dub(&command).err().map(|refusal| refusal.code);
        assert_eq!(code(json!({"url": "https://youtu.be/x", "target_language": "fr"})), Some("unsupported_language"));
        assert_eq!(code(json!({"url": "https://youtu.be/x", "target_language": "fr", "subtitles_only": true})), None);
        assert_eq!(code(json!({"url": "https://youtu.be/x", "target_language": "xx", "subtitles_only": true})), Some("unsupported_language"));
        assert_eq!(code(json!({"target_language": "vi"})), Some("invalid_message"));
        assert_eq!(code(json!({"url": "https://youtu.be/x", "target_language": "vi", "subtitles_only": "yes"})), Some("invalid_message"));
    }
}
