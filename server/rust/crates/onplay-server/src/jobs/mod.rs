//! Dub jobs for the iOS app (`docs/video-dubbing-design.md`, phase 1): a link is dubbed by
//! an `onplay-dub` child process into HLS and SRT files that outlive the connection.
//!
//! Jobs run one at a time on a queue thread; at most [`QUEUE_LIMIT`] wait and each tester
//! token has at most one queued or running job. Connections follow a job through
//! [`Outbox`] subscribers. Files live in `<job_dir>/<id>/` until [`RETENTION`] after the job
//! ended, `delete`, or the next start; jobs do not survive a restart.

mod progress;
mod runner;
pub mod tokens;

use std::collections::{HashMap, VecDeque};
use std::os::unix::fs::DirBuilderExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use onplay_dub::fetch::LinkInfo;
use serde_json::{json, Map, Value};

use crate::config::Config;
use crate::events::{Event, JobChange, Observer};
use crate::socket::Outbox;
use crate::time::iso8601;
use progress::Progress;
use runner::Finished;
use tokens::TokenFile;

pub const VERSION: u64 = 1;
pub const QUEUE_LIMIT: usize = 8;
pub const RETENTION: Duration = Duration::from_secs(24 * 3600);
const PROGRESS_INTERVAL: Duration = Duration::from_millis(500);
const RETRY_AFTER_S: u64 = 300;
/// A cancelled child gets this long after `TERM` before `KILL`.
const KILL_GRACE: Duration = Duration::from_secs(10);
const SWEEP_INTERVAL: Duration = Duration::from_secs(60);
const SUBTITLES: &str = "subtitles.srt";
const ORIGINAL: &str = "original.srt";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Queued,
    Running,
    Done,
    Failed,
    Cancelled,
}

impl State {
    pub fn name(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Running => "running",
            Self::Done => "done",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }

    pub fn is_final(self) -> bool {
        matches!(self, Self::Done | Self::Failed | Self::Cancelled)
    }
}

/// The Vietnamese message sent with an error or failure code.
pub fn text(code: &str) -> &'static str {
    match code {
        "unsupported_version" => "Ứng dụng cần cập nhật để dùng máy chủ này.",
        "invalid_message" => "Yêu cầu không hợp lệ.",
        "invalid_token" => "Token không hợp lệ hoặc đã bị thu hồi.",
        "dub_disabled" => "Máy chủ chưa nhận lồng tiếng video.",
        "invalid_link" => "Không đọc được video từ link này.",
        "too_long" => "Video dài hơn giới hạn cho phép.",
        "unsupported_language" => "Ngôn ngữ này chỉ hỗ trợ phụ đề.",
        "queue_full" => "Máy chủ đang bận, thử lại sau.",
        "job_limit" => "Bạn đang có một video chờ xử lý.",
        "job_not_found" => "Không tìm thấy video này; có thể đã hết hạn.",
        "download_failed" => "Không tải được video.",
        "no_speech" => "Không nhận dạng được lời nói trong video.",
        "translation_failed" => "Dịch thất bại.",
        "speech_failed" => "Tạo giọng đọc thất bại.",
        _ => "Lỗi máy chủ.",
    }
}

/// A command refused with an error code and extra fields for the `error` message.
#[derive(Debug, Clone, PartialEq)]
pub struct Refusal {
    pub code: &'static str,
    pub extra: Map<String, Value>,
}

impl Refusal {
    pub fn new(code: &'static str) -> Self {
        Self { code, extra: Map::new() }
    }

    pub fn with(mut self, key: &str, value: impl Into<Value>) -> Self {
        self.extra.insert(key.into(), value.into());
        self
    }
}

/// What a dub command asks for, already validated.
pub struct Request {
    pub url: String,
    pub target: String,
    pub subtitles_only: bool,
}

/// What a connection attached to a job receives.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Attach {
    /// `job` updates and `progress` until the job ends.
    Stream,
    /// Only the final `job` message (after `cancel`).
    FinalOnly,
}

/// Why an HTTP request gets no file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NotServed {
    Unauthorized,
    NotFound,
}

/// A file to serve and when it stops being served.
pub struct Served {
    pub path: PathBuf,
    pub expires: SystemTime,
}

struct Subscriber {
    outbox: Outbox,
    attach: Attach,
}

struct Job {
    id: String,
    /// Path secret of the HLS directory.
    key: String,
    owner: String,
    url: String,
    target: String,
    subtitles_only: bool,
    info: LinkInfo,
    created: SystemTime,
    started: Option<Instant>,
    ended: Option<SystemTime>,
    state: State,
    error: Option<&'static str>,
    progress: Progress,
    progress_sent: Option<Instant>,
    /// File key, name and size, once done.
    files: Vec<(&'static str, &'static str, u64)>,
    subscribers: Vec<Subscriber>,
    cancel: bool,
    deleted: bool,
}

impl Job {
    fn expires(&self) -> Option<SystemTime> {
        self.ended.map(|ended| ended + RETENTION)
    }

    fn served(&self, now: SystemTime) -> bool {
        self.state == State::Done && !self.deleted && self.expires().is_some_and(|expires| expires > now)
    }

    /// The `job` object; `position` while queued.
    fn summary(&self, position: Option<usize>) -> Value {
        let mut job = json!({
            "job_id": self.id,
            "state": self.state.name(),
            "target_language": self.target,
            "subtitles_only": self.subtitles_only,
            "title": self.info.title,
            "duration_s": self.info.duration,
            "thumbnail_url": self.info.thumbnail,
            "created_at": iso8601(self.created),
            "expires_at": self.expires().map(iso8601),
        });
        if let Some(position) = position {
            job["position"] = position.into();
        }
        if let Some(code) = self.error {
            job["error"] = json!({"code": code, "text": text(code)});
        }
        if self.state == State::Done {
            job["playback"] = json!({"path": format!("/jobs/{}/hls/{}/{}", self.id, self.key, onplay_dub::hls::MASTER)});
            let files: Map<String, Value> = self
                .files
                .iter()
                .map(|(key, name, size)| {
                    let file = json!({"path": format!("/jobs/{}/{name}", self.id), "size": size, "content_type": "application/x-subrip"});
                    ((*key).to_owned(), file)
                })
                .collect();
            job["files"] = files.into();
        }
        job
    }
}

#[derive(Default)]
struct Inner {
    jobs: HashMap<String, Job>,
    queue: VecDeque<String>,
    /// The running job and its child's process group, once spawned.
    running: Option<(String, Option<u32>)>,
}

impl Inner {
    /// Jobs that start before this one, the running one included.
    fn position(&self, id: &str) -> Option<usize> {
        let index = self.queue.iter().position(|queued| queued == id)?;
        Some(index + usize::from(self.running.is_some()))
    }

    fn owned(&mut self, owner: &str, id: &str) -> Result<&mut Job, Refusal> {
        self.jobs.get_mut(id).filter(|job| job.owner == owner && !job.deleted).ok_or(Refusal::new("job_not_found"))
    }

    /// Sends the job's state to its subscribers; a final state closes them.
    fn publish(&mut self, id: &str) {
        let position = self.position(id);
        let Some(job) = self.jobs.get_mut(id) else { return };
        let message = message(job.summary(position));
        let last = job.state.is_final();
        job.subscribers.retain(|subscriber| {
            if !last && subscriber.attach == Attach::FinalOnly {
                return true;
            }
            let sent = subscriber.outbox.emit(&message).is_ok();
            if last {
                subscriber.outbox.close();
            }
            sent && !last
        });
    }

    fn publish_positions(&mut self) {
        for id in self.queue.clone() {
            self.publish(&id);
        }
    }
}

/// `version` and `type: job` around a job object.
fn message(mut job: Value) -> Value {
    job["version"] = VERSION.into();
    job["type"] = "job".into();
    job
}

pub struct Jobs {
    dir: PathBuf,
    bin: PathBuf,
    root: PathBuf,
    translate_url: String,
    max_seconds: f64,
    max_bytes: u64,
    local_links: bool,
    tokens: Option<TokenFile>,
    /// Why dubbing is off; `None` when jobs are accepted.
    disabled: Option<String>,
    inner: Mutex<Inner>,
    queued: Condvar,
    observer: Arc<dyn Observer>,
}

impl Jobs {
    /// Loads the tokens and checks the tools. When dubbing is on, removes job directories
    /// left from an earlier run and starts the queue and retention threads.
    pub fn start(config: &Config, observer: Arc<dyn Observer>) -> Arc<Self> {
        let tokens = TokenFile::open(&config.dub_tokens);
        let mut disabled = tokens.as_ref().err().cloned();
        if disabled.is_none() && !config.dub_bin.is_file() {
            disabled = Some(format!("missing {}; run ./scripts/dev/build.sh", config.dub_bin.display()));
        }
        for (tool, flag) in [("ffmpeg", "-version"), ("ffprobe", "-version"), ("yt-dlp", "--version")] {
            if disabled.is_none() && !runs(tool, flag) {
                disabled = Some(format!("{tool} is required: brew install {}", if tool == "ffprobe" { "ffmpeg" } else { tool }));
            }
        }
        if disabled.is_none() {
            if let Err(error) = clear(&config.job_dir) {
                disabled = Some(format!("cannot prepare {}: {error}", config.job_dir.display()));
            }
        }
        let jobs = Arc::new(Self {
            dir: config.job_dir.clone(),
            bin: config.dub_bin.clone(),
            root: config.root.clone(),
            translate_url: config.translate_url.clone(),
            max_seconds: config.dub_max_seconds,
            max_bytes: config.job_max_bytes,
            local_links: config.dub_local_links,
            tokens: tokens.ok(),
            disabled,
            inner: Mutex::new(Inner::default()),
            queued: Condvar::new(),
            observer,
        });
        if jobs.disabled.is_none() {
            let (runner, sweeper) = (jobs.clone(), jobs.clone());
            std::thread::spawn(move || runner.run_queue());
            std::thread::spawn(move || sweeper.sweep());
        }
        jobs
    }

    pub fn disabled(&self) -> Option<&str> {
        self.disabled.as_deref()
    }

    pub fn max_seconds(&self) -> f64 {
        self.max_seconds
    }

    pub fn local_links(&self) -> bool {
        self.local_links
    }

    /// The tester name for a bearer token.
    pub fn owner(&self, token: Option<&str>) -> Option<String> {
        self.tokens.as_ref()?.name(token?)
    }

    /// Whether the translation router answers its health check.
    pub fn router_ready(&self) -> bool {
        onplay_dub::translate::Translator::new(&self.translate_url).check().is_ok()
    }

    /// Refuses early what [`Jobs::create`] would refuse, before the link is read.
    pub fn admit(&self, owner: &str) -> Result<(), Refusal> {
        self.admit_locked(&self.lock(), owner)
    }

    fn admit_locked(&self, inner: &Inner, owner: &str) -> Result<(), Refusal> {
        let active = inner.jobs.values().find(|job| job.owner == owner && !job.deleted && !job.state.is_final());
        if let Some(job) = active {
            return Err(Refusal::new("job_limit").with("job_id", job.id.clone()));
        }
        if inner.queue.len() >= QUEUE_LIMIT || runner::disk_usage(&self.dir) > self.max_bytes {
            return Err(Refusal::new("queue_full").with("retry_after_s", RETRY_AFTER_S));
        }
        Ok(())
    }

    /// Queues a job and streams it to `outbox`, starting with its `job` message.
    pub fn create(&self, owner: &str, request: Request, info: LinkInfo, outbox: &Outbox) -> Result<String, Refusal> {
        let mut inner = self.lock();
        self.admit_locked(&inner, owner)?;
        let id = format!("j_{:016x}", rand::random::<u64>());
        let duration = info.duration;
        let target = request.target.clone();
        let job = Job {
            id: id.clone(),
            key: URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>()),
            owner: owner.to_owned(),
            url: request.url,
            target: request.target,
            subtitles_only: request.subtitles_only,
            info,
            created: SystemTime::now(),
            started: None,
            ended: None,
            state: State::Queued,
            error: None,
            progress: Progress::default(),
            progress_sent: None,
            files: Vec::new(),
            subscribers: vec![Subscriber { outbox: outbox.clone(), attach: Attach::Stream }],
            cancel: false,
            deleted: false,
        };
        inner.jobs.insert(id.clone(), job);
        inner.queue.push_back(id.clone());
        inner.publish(&id);
        drop(inner);
        self.queued.notify_one();
        self.observe(&id, owner, JobChange::Queued { duration, target });
        Ok(id)
    }

    /// Sends the job's state to `outbox` and, unless it has ended, keeps it subscribed.
    pub fn attach(&self, owner: &str, id: &str, outbox: &Outbox, attach: Attach) -> Result<(), Refusal> {
        let mut inner = self.lock();
        let position = inner.position(id);
        let job = inner.owned(owner, id)?;
        let _ = outbox.emit(&message(job.summary(position)));
        if job.state.is_final() {
            outbox.close();
            return Ok(());
        }
        if attach == Attach::Stream {
            if let Some(progress) = job.progress.message() {
                let _ = outbox.emit(&progress_message(progress, id));
            }
        }
        job.subscribers.push(Subscriber { outbox: outbox.clone(), attach });
        Ok(())
    }

    /// Cancels a queued or running job; an ended job is left as it is.
    pub fn cancel(&self, owner: &str, id: &str) -> Result<(), Refusal> {
        let mut inner = self.lock();
        let job = inner.owned(owner, id)?;
        match job.state {
            State::Queued => {
                job.state = State::Cancelled;
                job.ended = Some(SystemTime::now());
                inner.queue.retain(|queued| queued != id);
                inner.publish(id);
                inner.publish_positions();
                drop(inner);
                self.observe(id, owner, JobChange::Ended { state: State::Cancelled.name(), code: None, took: Duration::ZERO });
            }
            State::Running => {
                job.cancel = true;
                if let Some((_, Some(pid))) = inner.running {
                    stop(pid);
                }
            }
            State::Done | State::Failed | State::Cancelled => {}
        }
        Ok(())
    }

    /// Removes a job and its files; a job still queued or running is cancelled first and
    /// removed when its child has exited.
    pub fn delete(&self, owner: &str, id: &str) -> Result<(), Refusal> {
        self.cancel(owner, id)?;
        let mut inner = self.lock();
        let job = inner.owned(owner, id)?;
        job.deleted = true;
        if job.state.is_final() {
            inner.jobs.remove(id);
            drop(inner);
            self.remove_files(id);
            self.observe(id, owner, JobChange::Removed { expired: false });
        }
        Ok(())
    }

    /// The token's jobs, newest first.
    pub fn list(&self, owner: &str) -> Vec<Value> {
        let inner = self.lock();
        let mut jobs: Vec<&Job> = inner.jobs.values().filter(|job| job.owner == owner && !job.deleted).collect();
        jobs.sort_by(|a, b| b.created.cmp(&a.created).then_with(|| b.id.cmp(&a.id)));
        jobs.iter().map(|job| job.summary(inner.position(&job.id))).collect()
    }

    /// `subtitles.srt` or `original.srt` of a done job owned by the token.
    pub fn file(&self, id: &str, name: &str, token: Option<&str>) -> Result<Served, NotServed> {
        let owner = self.owner(token).ok_or(NotServed::Unauthorized)?;
        let inner = self.lock();
        let job = inner.jobs.get(id).filter(|job| job.owner == owner && job.served(SystemTime::now())).ok_or(NotServed::NotFound)?;
        if name != SUBTITLES && name != ORIGINAL {
            return Err(NotServed::NotFound);
        }
        Ok(Served { path: self.dir.join(id).join(name), expires: job.expires().unwrap_or_else(SystemTime::now) })
    }

    /// A file of a done job's HLS directory; the key in the path is the access check.
    pub fn hls_file(&self, id: &str, key: &str, name: &str) -> Result<Served, NotServed> {
        let inner = self.lock();
        let job = inner.jobs.get(id).filter(|job| job.served(SystemTime::now()) && same(&job.key, key)).ok_or(NotServed::NotFound)?;
        let plain = !name.is_empty() && !name.starts_with('.') && name.bytes().all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte));
        if !plain {
            return Err(NotServed::NotFound);
        }
        Ok(Served { path: self.dir.join(id).join("hls").join(key).join(name), expires: job.expires().unwrap_or_else(SystemTime::now) })
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn observe(&self, id: &str, owner: &str, change: JobChange) {
        self.observer.event(Event::Job { job: id.to_owned(), owner: owner.to_owned(), change });
    }

    fn remove_files(&self, id: &str) {
        let _ = std::fs::remove_dir_all(self.dir.join(id));
    }

    fn run_queue(&self) {
        loop {
            let mut inner = self.lock();
            while inner.queue.is_empty() {
                inner = self.queued.wait(inner).unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            let id = inner.queue.pop_front().expect("queue is not empty");
            let Some(job) = inner.jobs.get_mut(&id) else { continue };
            job.state = State::Running;
            job.started = Some(Instant::now());
            let (url, target, subtitles_only, key, owner) = (job.url.clone(), job.target.clone(), job.subtitles_only, job.key.clone(), job.owner.clone());
            inner.running = Some((id.clone(), None));
            inner.publish(&id);
            inner.publish_positions();
            drop(inner);
            self.observe(&id, &owner, JobChange::Started);

            let work = self.dir.join(&id).join("work");
            let hls = self.dir.join(&id).join("hls").join(&key);
            let spec = runner::Spec {
                bin: &self.bin,
                root: &self.root,
                translate_url: &self.translate_url,
                url: &url,
                target: &target,
                subtitles_only,
                work: &work,
                hls: &hls,
            };
            let finished = match runner::spawn(&spec) {
                Ok(child) => {
                    let mut inner = self.lock();
                    inner.running = Some((id.clone(), Some(child.id())));
                    if inner.jobs.get(&id).is_some_and(|job| job.cancel) {
                        stop(child.id());
                    }
                    drop(inner);
                    runner::wait(child, |event| self.progress(&id, event))
                }
                Err(_) => Finished::Failed("internal"),
            };
            self.finish(&id, &owner, finished, &work);
        }
    }

    fn progress(&self, id: &str, event: &Value) {
        let mut inner = self.lock();
        let Some(job) = inner.jobs.get_mut(id) else { return };
        let changed = job.progress.apply(event);
        let due = job.progress_sent.map_or(true, |sent| sent.elapsed() >= PROGRESS_INTERVAL);
        let Some(progress) = job.progress.message().filter(|_| changed || due) else { return };
        job.progress_sent = Some(Instant::now());
        let message = progress_message(progress, id);
        job.subscribers.retain(|subscriber| subscriber.attach == Attach::FinalOnly || subscriber.outbox.emit(&message).is_ok());
    }

    fn finish(&self, id: &str, owner: &str, finished: Finished, work: &Path) {
        let cancelled = self.lock().jobs.get(id).map_or(true, |job| job.cancel);
        let job_dir = self.dir.join(id);
        let (state, code, files) = match finished {
            _ if cancelled => (State::Cancelled, None, Vec::new()),
            Finished::Done { subtitles, original } => match keep(&job_dir, &subtitles, &original) {
                Ok(files) => (State::Done, None, files),
                Err(_) => (State::Failed, Some("internal"), Vec::new()),
            },
            Finished::Failed(code) => (State::Failed, Some(code), Vec::new()),
        };
        let _ = std::fs::remove_dir_all(work);
        if state != State::Done {
            self.remove_files(id);
        }
        let mut inner = self.lock();
        inner.running = None;
        let Some(job) = inner.jobs.get_mut(id) else { return };
        job.state = state;
        job.error = code;
        job.files = files;
        job.ended = Some(SystemTime::now());
        let took = job.started.map_or(Duration::ZERO, |started| started.elapsed());
        let deleted = job.deleted;
        inner.publish(id);
        if deleted {
            inner.jobs.remove(id);
        }
        drop(inner);
        self.observe(id, owner, JobChange::Ended { state: state.name(), code, took });
        if deleted {
            self.remove_files(id);
            self.observe(id, owner, JobChange::Removed { expired: false });
        }
    }

    /// Removes jobs [`RETENTION`] after they ended.
    fn sweep(&self) {
        loop {
            std::thread::sleep(SWEEP_INTERVAL);
            let now = SystemTime::now();
            let mut inner = self.lock();
            let expired: Vec<(String, String)> = inner
                .jobs
                .values()
                .filter(|job| job.expires().is_some_and(|expires| expires <= now))
                .map(|job| (job.id.clone(), job.owner.clone()))
                .collect();
            for (id, _) in &expired {
                inner.jobs.remove(id);
            }
            drop(inner);
            for (id, owner) in expired {
                self.remove_files(&id);
                self.observe(&id, &owner, JobChange::Removed { expired: true });
            }
        }
    }
}

fn progress_message(mut progress: Value, id: &str) -> Value {
    progress["version"] = VERSION.into();
    progress["job_id"] = id.into();
    progress
}

/// Moves the SRT outputs next to the HLS directory under their fixed names.
fn keep(job_dir: &Path, subtitles: &Path, original: &Path) -> std::io::Result<Vec<(&'static str, &'static str, u64)>> {
    let mut files = Vec::new();
    for (key, name, source) in [("subtitles", SUBTITLES, subtitles), ("original_subtitles", ORIGINAL, original)] {
        let path = job_dir.join(name);
        std::fs::rename(source, &path)?;
        files.push((key, name, std::fs::metadata(&path)?.len()));
    }
    Ok(files)
}

/// `TERM` to a child's process group, then `KILL` if it is still there after the grace.
fn stop(pid: u32) {
    runner::signal_group(pid, "TERM");
    std::thread::spawn(move || {
        std::thread::sleep(KILL_GRACE);
        runner::signal_group(pid, "KILL");
    });
}

/// Creates `dir` (0700) and removes job directories left by an earlier run; other entries
/// are left alone in case the directory was misconfigured.
fn clear(dir: &Path) -> std::io::Result<()> {
    std::fs::DirBuilder::new().recursive(true).mode(0o700).create(dir)?;
    for entry in std::fs::read_dir(dir)?.filter_map(Result::ok) {
        let name = entry.file_name();
        let is_job = name.to_str().is_some_and(|name| name.len() == 18 && name.starts_with("j_") && name[2..].bytes().all(|byte| byte.is_ascii_hexdigit()));
        if is_job && entry.file_type().is_ok_and(|kind| kind.is_dir()) {
            std::fs::remove_dir_all(entry.path())?;
        }
    }
    Ok(())
}

fn runs(program: &str, flag: &str) -> bool {
    Command::new(program).arg(flag).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|status| status.success())
}

/// Compares secrets without stopping at the first difference.
fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(id: &str, state: State) -> Job {
        Job {
            id: id.into(),
            key: "k".into(),
            owner: "alice".into(),
            url: "https://example.com/v".into(),
            target: "vi".into(),
            subtitles_only: false,
            info: LinkInfo { title: Some("Talk".into()), duration: 30.0, thumbnail: None },
            created: SystemTime::UNIX_EPOCH,
            started: None,
            ended: None,
            state,
            error: None,
            progress: Progress::default(),
            progress_sent: None,
            files: Vec::new(),
            subscribers: Vec::new(),
            cancel: false,
            deleted: false,
        }
    }

    #[test]
    fn summarizes_jobs_for_the_app() {
        let mut done = job("j_1", State::Done);
        done.ended = Some(SystemTime::UNIX_EPOCH);
        done.files = vec![("subtitles", SUBTITLES, 12)];
        assert_eq!(
            done.summary(None),
            json!({
                "job_id": "j_1", "state": "done", "target_language": "vi", "subtitles_only": false, "title": "Talk",
                "duration_s": 30.0, "thumbnail_url": null, "created_at": "1970-01-01T00:00:00Z", "expires_at": "1970-01-02T00:00:00Z",
                "playback": {"path": "/jobs/j_1/hls/k/master.m3u8"},
                "files": {"subtitles": {"path": "/jobs/j_1/subtitles.srt", "size": 12, "content_type": "application/x-subrip"}},
            })
        );
        let mut failed = job("j_2", State::Failed);
        failed.error = Some("no_speech");
        assert_eq!(failed.summary(None)["error"], json!({"code": "no_speech", "text": text("no_speech")}));
        assert_eq!(job("j_3", State::Queued).summary(Some(2))["position"], 2);
    }

    #[test]
    fn counts_the_running_job_in_positions() {
        let mut inner = Inner::default();
        inner.queue.extend(["a".to_owned(), "b".to_owned()]);
        assert_eq!(inner.position("b"), Some(1));
        inner.running = Some(("r".into(), None));
        assert_eq!((inner.position("a"), inner.position("b"), inner.position("r")), (Some(1), Some(2), None));
    }

    #[test]
    fn publishes_to_subscribers_and_closes_on_the_final_state() {
        let mut inner = Inner::default();
        let (stream, stream_rx) = Outbox::new();
        let (last, last_rx) = Outbox::new();
        let mut running = job("j_1", State::Running);
        running.subscribers = vec![Subscriber { outbox: stream, attach: Attach::Stream }, Subscriber { outbox: last, attach: Attach::FinalOnly }];
        inner.jobs.insert("j_1".into(), running);
        inner.publish("j_1");
        assert_eq!(stream_rx.try_iter().count(), 1);
        assert_eq!(last_rx.try_iter().count(), 0);
        inner.jobs.get_mut("j_1").unwrap().state = State::Cancelled;
        inner.publish("j_1");
        // The final message, then the close.
        assert_eq!(stream_rx.try_iter().count(), 2);
        assert_eq!(last_rx.try_iter().count(), 2);
        assert!(inner.jobs["j_1"].subscribers.is_empty());
    }

    #[test]
    fn clears_only_job_directories() {
        let dir = std::env::temp_dir().join(format!("onplay-jobs-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("j_0123456789abcdef/hls")).unwrap();
        std::fs::create_dir_all(dir.join("keep")).unwrap();
        clear(&dir).unwrap();
        assert!(!dir.join("j_0123456789abcdef").exists());
        assert!(dir.join("keep").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn compares_secrets() {
        assert!(same("abc", "abc"));
        assert!(!same("abc", "abd"));
        assert!(!same("abc", "ab"));
    }
}
