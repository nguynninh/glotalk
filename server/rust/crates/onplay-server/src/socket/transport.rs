//! The connection thread's view of the socket: reads client messages while writing
//! queued events, keeps the connection alive and closes it.

use std::io::ErrorKind;
use std::net::TcpStream;
use std::sync::mpsc::{Receiver, TryRecvError};
use std::time::{Duration, Instant};

use tungstenite::{Error, Message, WebSocket};

use super::outbox::Outgoing;
use crate::events::{Direction, FrameKind};
use crate::trace::Trace;

/// Read timeout between checks for queued events: the most an event waits to be written
/// while the client is quiet. 5 ms costs about 200 idle wakeups a second per connection.
const POLL: Duration = Duration::from_millis(5);
/// Ping an idle client every 20 s and drop it without a pong 20 s later, so a vanished
/// mobile client cannot hold the engine.
const PING_INTERVAL: Duration = Duration::from_secs(20);
const PONG_TIMEOUT: Duration = Duration::from_secs(20);
const CLOSE_TIMEOUT: Duration = Duration::from_secs(2);

/// A data message from the client.
pub enum Incoming {
    Text(String),
    Binary(Vec<u8>),
}

/// The connection ended (closed, failed, timed out or over the size limit).
pub struct Ended;

pub struct Transport {
    socket: WebSocket<TcpStream>,
    outgoing: Receiver<Outgoing>,
    last_seen: Instant,
    ping_sent: Option<Instant>,
    closing: Option<Instant>,
    trace: Trace,
}

impl Transport {
    pub fn new(socket: WebSocket<TcpStream>, outgoing: Receiver<Outgoing>, trace: Trace) -> Result<Self, Ended> {
        socket.get_ref().set_read_timeout(Some(POLL)).map_err(|_| Ended)?;
        Ok(Self { socket, outgoing, last_seen: Instant::now(), ping_sent: None, closing: None, trace })
    }

    /// Next client message, writing queued events meanwhile. `Ok(None)` when `deadline`
    /// passes first.
    pub fn next(&mut self, deadline: Option<Instant>) -> Result<Option<Incoming>, Ended> {
        loop {
            self.write_queued()?;
            self.keep_alive()?;
            if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
                return Ok(None);
            }
            match self.socket.read() {
                Ok(message) => {
                    self.traced(Direction::In, &message);
                    self.last_seen = Instant::now();
                    self.ping_sent = None;
                    match message {
                        Message::Text(text) if self.closing.is_none() => return Ok(Some(Incoming::Text(text))),
                        Message::Binary(bytes) if self.closing.is_none() => return Ok(Some(Incoming::Binary(bytes))),
                        // Pings are answered by tungstenite; pongs only prove liveness.
                        _ => {}
                    }
                }
                Err(Error::Io(error)) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    // Flush pongs and partial writes that the read could not complete.
                    match self.socket.flush() {
                        Ok(()) => {}
                        Err(Error::Io(error)) if error.kind() == ErrorKind::WouldBlock => {}
                        Err(_) => return Err(Ended),
                    }
                }
                Err(_) => return Err(Ended),
            }
        }
    }

    /// Writes queued events without reading, for when input must wait.
    pub fn write_queued(&mut self) -> Result<(), Ended> {
        loop {
            match self.outgoing.try_recv() {
                Ok(Outgoing::Message(message)) => {
                    self.traced(Direction::Out, &message);
                    self.socket.send(message).map_err(|_| Ended)?;
                }
                Ok(Outgoing::Close) => self.start_close()?,
                Err(TryRecvError::Empty) => return Ok(()),
                // Every sender is gone: nothing more will be said, so close.
                Err(TryRecvError::Disconnected) => return self.start_close(),
            }
        }
    }

    fn start_close(&mut self) -> Result<(), Ended> {
        if self.closing.is_none() {
            self.closing = Some(Instant::now());
            self.trace.frame(Direction::Out, FrameKind::Close, 0, None);
            self.socket.close(None).map_err(|_| Ended)?;
        }
        Ok(())
    }

    fn traced(&self, direction: Direction, message: &Message) {
        let (kind, json) = match message {
            Message::Text(text) => (FrameKind::Text, Some(text.as_str())),
            // Server binary frames carry a JSON header (see `Outbox::emit_with_payload`);
            // client binary frames are PCM.
            Message::Binary(bytes) if direction == Direction::Out => (FrameKind::Binary, binary_header(bytes)),
            Message::Binary(_) => (FrameKind::Binary, None),
            Message::Ping(_) => (FrameKind::Ping, None),
            Message::Pong(_) => (FrameKind::Pong, None),
            Message::Close(_) | Message::Frame(_) => (FrameKind::Close, None),
        };
        self.trace.frame(direction, kind, message.len(), json);
    }

    fn keep_alive(&mut self) -> Result<(), Ended> {
        let now = Instant::now();
        if self.closing.is_some_and(|since| now - since > CLOSE_TIMEOUT) {
            return Err(Ended);
        }
        match self.ping_sent {
            Some(sent) if now - sent > PONG_TIMEOUT => Err(Ended),
            None if now - self.last_seen > PING_INTERVAL && self.closing.is_none() => {
                self.ping_sent = Some(now);
                self.trace.frame(Direction::Out, FrameKind::Ping, 0, None);
                self.socket.send(Message::Ping(Vec::new())).map_err(|_| Ended)
            }
            _ => Ok(()),
        }
    }
}

/// The JSON header of a server binary frame: 4-byte big-endian length, then the header.
fn binary_header(frame: &[u8]) -> Option<&str> {
    let length = u32::from_be_bytes(frame.get(..4)?.try_into().ok()?) as usize;
    std::str::from_utf8(frame.get(4..4 + length)?).ok()
}
