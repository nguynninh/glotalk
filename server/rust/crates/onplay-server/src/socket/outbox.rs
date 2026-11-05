//! Event sending from any thread; the connection thread owns the socket and writes them.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;

use serde_json::Value;
use tungstenite::Message;

pub enum Outgoing {
    Message(Message),
    /// Start the closing handshake after everything queued before it.
    Close,
}

/// The connection is gone; the session is cancelled.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Closed;

#[derive(Clone)]
pub struct Outbox {
    sender: Sender<Outgoing>,
    cancelled: Arc<AtomicBool>,
}

impl Outbox {
    pub fn new() -> (Self, Receiver<Outgoing>) {
        let (sender, receiver) = mpsc::channel();
        (Self { sender, cancelled: Arc::new(AtomicBool::new(false)) }, receiver)
    }

    /// Sends one JSON text frame.
    pub fn emit(&self, event: &Value) -> Result<(), Closed> {
        self.send(Outgoing::Message(Message::text(event.to_string())))
    }

    /// One binary frame keeps metadata and audio together across sender threads:
    /// 4-byte big-endian header length, the JSON header, then the payload.
    pub fn emit_with_payload(&self, event: &Value, payload: &[u8]) -> Result<(), Closed> {
        let header = event.to_string();
        let mut frame = Vec::with_capacity(4 + header.len() + payload.len());
        frame.extend_from_slice(&(header.len() as u32).to_be_bytes());
        frame.extend_from_slice(header.as_bytes());
        frame.extend_from_slice(payload);
        self.send(Outgoing::Message(Message::binary(frame)))
    }

    pub fn close(&self) {
        let _ = self.send(Outgoing::Close);
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }

    /// Set once the session is cancelled, for loops that poll it.
    pub fn cancelled_flag(&self) -> &AtomicBool {
        &self.cancelled
    }

    fn send(&self, outgoing: Outgoing) -> Result<(), Closed> {
        self.sender.send(outgoing).map_err(|_| {
            self.cancel();
            Closed
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn frames_payload_after_header_and_cancels_when_closed() {
        let (outbox, receiver) = Outbox::new();
        outbox.emit_with_payload(&json!({"type": "audio"}), b"RIFF").unwrap();
        let Ok(Outgoing::Message(Message::Binary(frame))) = receiver.recv() else { panic!("binary frame") };
        let header = br#"{"type":"audio"}"#;
        assert_eq!(frame[..4], (header.len() as u32).to_be_bytes());
        assert_eq!(&frame[4..4 + header.len()], header);
        assert_eq!(&frame[4 + header.len()..], b"RIFF");
        drop(receiver);
        assert_eq!(outbox.emit(&json!({})), Err(Closed));
        assert!(outbox.is_cancelled());
    }
}
