//! The Python router's HTTP contract: `GET /health`, `GET /metrics` and a bounded JSON
//! `POST /translate`, answering JSON with `Cache-Control: no-store`.

use std::io::Read;

use onplay_translation::{Engine, Error};
use serde::Serialize;
use serde_json::{json, Value};
use tiny_http::{Header, Method, Request, Response};

const MAX_BODY: usize = 131_072;

pub fn handle(engine: &Engine, mut request: Request) {
    let (status, body) = route(engine, &mut request);
    let mut response = Response::from_string(body).with_status_code(status);
    for (name, value) in [("Content-Type", "application/json"), ("Cache-Control", "no-store")] {
        response.add_header(Header::from_bytes(name, value).expect("valid header"));
    }
    // The client may already be gone; there is nobody left to tell.
    let _ = request.respond(response);
}

/// A status and its JSON body, serialized directly so fields keep their declared order.
type Reply = (u16, String);

fn reply(status: u16, body: &impl Serialize) -> Reply {
    (status, serde_json::to_string(body).expect("response serializes"))
}

fn error(status: u16, message: &str) -> Reply {
    reply(status, &json!({"error": message}))
}

fn route(engine: &Engine, request: &mut Request) -> Reply {
    match (request.method(), request.url()) {
        (Method::Get, "/health") => {
            let models: Vec<&str> = engine.config().models.iter().map(|(name, _)| name.as_str()).collect();
            reply(200, &json!({"status": "ok", "models": models}))
        }
        (Method::Get, "/metrics") => reply(200, &engine.metrics()),
        (Method::Post, "/translate") => translate(engine, request),
        (Method::Get | Method::Post, _) => error(404, "Unknown endpoint"),
        _ => error(501, "Unsupported method"),
    }
}

fn header<'a>(request: &'a Request, name: &'static str) -> Option<&'a str> {
    request.headers().iter().find(|header| header.field.equiv(name)).map(|header| header.value.as_str())
}

fn translate(engine: &Engine, request: &mut Request) -> Reply {
    let content_type = header(request, "Content-Type").unwrap_or_default().split(';').next().unwrap_or_default().trim();
    if header(request, "Transfer-Encoding").is_some() || content_type != "application/json" {
        return error(400, "Expected bounded application/json body");
    }
    let Ok(length) = header(request, "Content-Length").unwrap_or("0").trim().parse::<usize>() else {
        return error(400, "Invalid Content-Length");
    };
    if !(1..=MAX_BODY).contains(&length) {
        return error(413, "Body must be 1 byte to 128 KiB");
    }
    let mut raw = Vec::with_capacity(length);
    if request.as_reader().take(length as u64).read_to_end(&mut raw).is_err() || raw.len() != length {
        return error(400, "Incomplete body");
    }
    let data: Value = match serde_json::from_slice(&raw) {
        Ok(data) => data,
        Err(parse) => return error(400, &parse.to_string()),
    };
    match engine.translate(&data) {
        Ok(translation) => reply(200, &translation),
        Err(Error::Invalid(message)) => error(400, &message),
        Err(busy @ Error::Busy) => error(409, &busy.to_string()),
        Err(_) => error(503, "Local translation failed; check engine and model setup"),
    }
}
