//! Waiting out a shared engine that refuses overlapping work.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

const BUSY_WAIT: Duration = Duration::from_secs(30);
const BUSY_POLL: Duration = Duration::from_millis(50);

/// Retries `call` while it fails with a busy error, for at most 30 s or until `cancelled`,
/// instead of disabling the rest of the session.
pub fn wait_busy<T, E>(cancelled: &AtomicBool, is_busy: impl Fn(&E) -> bool, mut call: impl FnMut() -> Result<T, E>) -> Result<T, E> {
    let deadline = Instant::now() + BUSY_WAIT;
    loop {
        match call() {
            Err(error) if is_busy(&error) && !cancelled.load(Ordering::Relaxed) && Instant::now() < deadline => {
                std::thread::sleep(BUSY_POLL);
            }
            outcome => return outcome,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retries_busy_until_success_and_stops_when_cancelled() {
        let mut calls = 0;
        let ok: Result<u8, bool> = wait_busy(&AtomicBool::new(false), |busy| *busy, || {
            calls += 1;
            if calls < 3 { Err(true) } else { Ok(7) }
        });
        assert_eq!((ok, calls), (Ok(7), 3));
        let cancelled: Result<u8, bool> = wait_busy(&AtomicBool::new(true), |busy| *busy, || Err(true));
        assert_eq!(cancelled, Err(true));
    }
}
