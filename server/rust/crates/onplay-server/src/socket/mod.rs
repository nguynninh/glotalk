//! WebSocket listener: origin-checked handshake, then one thread per connection.

mod client_config;
mod job;
mod outbox;
mod session;
mod transport;

pub use client_config::{AudioEncoding, ClientConfig, Speakers};
pub use outbox::{Closed, Outbox};

use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tungstenite::http::StatusCode;
use tungstenite::protocol::WebSocketConfig;

use crate::events::Client;
use crate::jobs::tokens::bearer;
use crate::{Event, Services};

/// Largest client message: one second of PCM16 at 16 kHz.
pub const MAX_MESSAGE_BYTES: usize = 32_000;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

pub fn serve(services: Arc<Services>, listener: TcpListener) -> std::io::Result<()> {
    let connections = Arc::new(AtomicU64::new(0));
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let (services, connections) = (services.clone(), connections.clone());
        std::thread::spawn(move || accept(stream, &services, &connections));
    }
    Ok(())
}

// The handshake callback's error type is fixed by tungstenite.
#[allow(clippy::result_large_err)]
fn accept(stream: TcpStream, services: &Arc<Services>, connections: &AtomicU64) {
    if stream.set_nodelay(true).and_then(|()| stream.set_read_timeout(Some(HANDSHAKE_TIMEOUT))).is_err() {
        return;
    }
    let origins = &services.config.origins;
    let peer = stream.peer_addr().map(|address| address.ip().to_string()).unwrap_or_default();
    // Set by the handshake callback: the client's headers and whether it was refused.
    let mut client = Client::default();
    // Kept out of `client`, which is logged.
    let mut token = None;
    let mut refused = false;
    let check_origin = |request: &Request, response: Response| -> Result<Response, ErrorResponse> {
        client = describe_client(request, peer);
        token = request.headers().get("Authorization").and_then(|value| value.to_str().ok()).and_then(bearer).map(str::to_owned);
        if client.origin.as_ref().is_some_and(|origin| origins.iter().any(|allowed| allowed == origin)) {
            return Ok(response);
        }
        refused = true;
        let mut refusal = ErrorResponse::new(Some("Origin not allowed".into()));
        *refusal.status_mut() = StatusCode::FORBIDDEN;
        Err(refusal)
    };
    let config = WebSocketConfig { max_message_size: Some(MAX_MESSAGE_BYTES), max_frame_size: Some(MAX_MESSAGE_BYTES), ..Default::default() };
    // `.ok()` drops a failed handshake, which still holds the callback.
    match tungstenite::accept_hdr_with_config(stream, check_origin, Some(config)).ok() {
        Some(socket) => {
            let connection = connections.fetch_add(1, Ordering::Relaxed) + 1;
            services.observer.event(Event::Opened { connection, client });
            session::run(socket, services, connection, token);
        }
        None if refused => services.observer.event(Event::Refused { client }),
        // Not a WebSocket handshake, or the client left during it.
        None => {}
    }
}

fn describe_client(request: &Request, peer: String) -> Client {
    let header = |name: &str| request.headers().get(name).and_then(|value| value.to_str().ok()).map(str::trim).filter(|value| !value.is_empty());
    let forwarded = header("X-Forwarded-For").and_then(|list| list.split(',').next()).map(str::trim);
    Client {
        address: header("CF-Connecting-IP").or(forwarded).map_or(peer, str::to_owned),
        origin: header("Origin").map(str::to_owned),
        user_agent: header("User-Agent").map(str::to_owned),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(headers: &[(&str, &str)]) -> Request {
        let mut builder = Request::builder().uri("/ws");
        for (name, value) in headers {
            builder = builder.header(*name, *value);
        }
        builder.body(()).unwrap()
    }

    #[test]
    fn describes_client_from_tunnel_headers() {
        let tunnel = request(&[("CF-Connecting-IP", "203.0.113.7"), ("X-Forwarded-For", "198.51.100.1"), ("Origin", "https://stt.qiverse.top"), ("User-Agent", "ONPlayMobile/1.0")]);
        let client = describe_client(&tunnel, "127.0.0.1".into());
        assert_eq!(client, Client { address: "203.0.113.7".into(), origin: Some("https://stt.qiverse.top".into()), user_agent: Some("ONPlayMobile/1.0".into()) });
        assert_eq!(describe_client(&request(&[("X-Forwarded-For", " 198.51.100.1, 10.0.0.1")]), "127.0.0.1".into()).address, "198.51.100.1");
        assert_eq!(describe_client(&request(&[]), "127.0.0.1".into()), Client { address: "127.0.0.1".into(), origin: None, user_agent: None });
    }
}
