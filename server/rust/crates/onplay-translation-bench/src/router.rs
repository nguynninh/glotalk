//! The router under test as a child process: started fresh for every run so the cache is
//! empty and startup is measured, and always killed with its process group.

use std::fs::File;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use crate::client::Client;
use crate::error::{Error, Result};

const POLL: Duration = Duration::from_millis(200);

pub struct Router {
    child: Child,
}

impl Router {
    /// Runs `command` in `cwd` with output in `log`, then waits until `client` sees a healthy
    /// router. Returns the router and the time from spawn to healthy.
    pub fn start(command: &[String], cwd: &Path, log: &Path, client: &Client, timeout: Duration) -> Result<(Self, Duration)> {
        let (program, args) = command.split_first().ok_or_else(|| Error::InvalidArgument("router command is empty".into()))?;
        if client.healthy() {
            return Err(Error::Router(format!("{} already answers; stop that router first", client.endpoint())));
        }
        let output = File::create(log).map_err(Error::io(log))?;
        let errors = output.try_clone().map_err(Error::io(log))?;
        let started = Instant::now();
        let child = Command::new(program)
            .args(args)
            .current_dir(cwd)
            .stdin(Stdio::null())
            .stdout(output)
            .stderr(errors)
            .process_group(0)
            .spawn()
            .map_err(Error::io(program))?;
        let mut router = Self { child };
        while !client.healthy() {
            if let Some(status) = router.child.try_wait().map_err(Error::io(program))? {
                return Err(Error::Router(format!("exited with {status} before it was ready; see {}", log.display())));
            }
            if started.elapsed() > timeout {
                return Err(Error::Router(format!("not ready after {timeout:?}; see {}", log.display())));
            }
            std::thread::sleep(POLL);
        }
        Ok((router, started.elapsed()))
    }

    pub fn pid(&self) -> u32 {
        self.child.id()
    }
}

impl Drop for Router {
    fn drop(&mut self) {
        // The command may be a script that started the router as a child; end the group.
        let _ = Command::new("kill").arg("-KILL").arg(format!("-{}", self.child.id())).status();
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
