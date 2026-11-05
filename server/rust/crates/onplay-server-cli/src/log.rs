//! Stdout lines for `ONPLAY_LOG=1` (connection events) and `ONPLAY_DEBUG=1` (also every
//! frame and pipeline step). Never audio or recognized text: the server redacts frames.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use onplay_server::events::{Client, Direction, FrameKind, Stage};
use onplay_server::socket::Speakers;
use onplay_server::{Event, Observer, Outcome};

pub struct Log {
    pub debug: bool,
}

impl Observer for Log {
    fn event(&self, event: Event) {
        println!("{} {}", timestamp(SystemTime::now()), describe(&event));
    }

    fn detailed(&self) -> bool {
        self.debug
    }
}

fn describe(event: &Event) -> String {
    match event {
        Event::Refused { client } => format!("  -  refused  {}", describe_client(client)),
        Event::Opened { connection, client } => format!("#{connection:<3} open     {}", describe_client(client)),
        Event::Started { connection, source, target, speech, speakers, session, slot, active, capacity } => {
            let target = if target.is_empty() { "transcript" } else { target };
            let speakers = match speakers {
                Speakers::Off => "off",
                Speakers::Live => "live",
                Speakers::Full => "full",
            };
            let speech = if *speech { "on" } else { "off" };
            let session = session.as_deref().map(|id| format!("  session={id}")).unwrap_or_default();
            format!("#{connection:<3} start    {source} -> {target}  speech={speech}  speakers={speakers}  slot={}  active={active}/{capacity}{session}", slot + 1)
        }
        Event::Ended { connection, outcome, duration, audio, segments } => {
            let outcome = match outcome {
                Outcome::Done => "done".to_owned(),
                Outcome::Closed => "client-left".to_owned(),
                Outcome::Error(text) => format!("error {text:?}"),
            };
            format!("#{connection:<3} end      {outcome}  after={}  audio={}  segments={segments}", seconds(*duration), seconds(*audio))
        }
        Event::Frame { connection, at, direction, kind, bytes, fields } => {
            let arrow = match direction {
                Direction::In => "<-",
                Direction::Out => "->",
            };
            let kind = match kind {
                FrameKind::Text => "text",
                FrameKind::Binary => "binary",
                FrameKind::Ping => "ping",
                FrameKind::Pong => "pong",
                FrameKind::Close => "close",
            };
            let mut line = format!("#{connection:<3} {:>9}  {arrow} {kind:<6} {bytes:>6} B", offset(*at));
            if let Some(fields) = fields {
                let mut fields = fields.clone();
                let message = fields.as_object_mut().and_then(|map| map.remove("type")).and_then(|kind| kind.as_str().map(str::to_owned));
                if let Some(message) = message {
                    line += &format!("  {message}");
                }
                if fields.as_object().map_or(true, |map| !map.is_empty()) {
                    line += &format!("  {fields}");
                }
            }
            line
        }
        Event::Stage { connection, at, stage, took } => {
            let stage = match stage {
                Stage::Queued => "queued",
                Stage::Engine => "engine",
                Stage::Finish => "finish",
                Stage::Diarization => "diarization",
                Stage::Drain => "drain",
            };
            format!("#{connection:<3} {:>9}  stage  {stage} took {:.1} ms", offset(*at), took.as_secs_f64() * 1000.0)
        }
    }
}

fn describe_client(client: &Client) -> String {
    let origin = client.origin.as_deref().unwrap_or("-");
    let agent = client.user_agent.as_deref().map_or_else(|| "-".to_owned(), |agent| format!("{agent:?}"));
    format!("{}  origin={origin}  ua={agent}", client.address)
}

fn seconds(duration: Duration) -> String {
    format!("{:.1}s", duration.as_secs_f64())
}

/// Time since the connection opened.
fn offset(at: Duration) -> String {
    format!("+{:.3}s", at.as_secs_f64())
}

/// `2026-11-05T10:40:45.870Z` (UTC).
fn timestamp(now: SystemTime) -> String {
    let since = now.duration_since(UNIX_EPOCH).unwrap_or_default();
    let (days, rest) = ((since.as_secs() / 86_400) as i64, since.as_secs() % 86_400);
    let (year, month, day) = civil(days);
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z", rest / 3600, rest / 60 % 60, rest % 60, since.subsec_millis())
}

/// Proleptic Gregorian date of `days` since 1970-01-01 (Howard Hinnant's `civil_from_days`).
fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(month <= 2), month, day)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_utc_timestamps() {
        assert_eq!(timestamp(UNIX_EPOCH), "1970-01-01T00:00:00.000Z");
        let moment = UNIX_EPOCH + Duration::from_millis(1_793_875_245_870);
        assert_eq!(timestamp(moment), "2026-11-05T10:40:45.870Z");
        assert_eq!(civil(11_016), (2000, 2, 29));
    }
}
