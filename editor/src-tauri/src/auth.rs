//! Đăng nhập tài khoản editor qua server Glotalk (`/auth/` của onplay-server, xem `server/src/auth.rs`),
//! dịch phụ đề (`POST /translate`) và đọc câu lồng tiếng (`POST /speak`) bằng token đó, cùng cổng HTTP.
//!
//! Chạy bên Rust như STT: webview gọi thẳng sang domain khác sẽ vướng CORS.
//! Lỗi sai tài khoản/khoá tạm trả về khoá i18n (`login.*`) để màn đăng nhập dịch theo ngôn ngữ đang chọn.
use crate::error::{AppError, AppResult};
use serde::Serialize;
use serde_json::{json, Value};
use std::time::Duration;

/// Cổng HTTP của server khi chạy cục bộ (`DOWNLOAD_ADDRESS`); qua tunnel thì cùng host.
const LOCAL_AUTH_PORT: u16 = 8767;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Login {
    pub token: String,
    pub user: String,
    /// Giây Unix.
    pub expires_at: u64,
}

/// Gốc URL của `/auth/` từ địa chỉ server trong Cài đặt: `https://host` giữ nguyên host,
/// `ws://`/`http://` cục bộ chuyển sang cổng HTTP của server.
fn base(server: &str) -> AppResult<String> {
    let s = server.trim().trim_end_matches('/');
    let (secure, rest) = if let Some(r) = s.strip_prefix("https://").or_else(|| s.strip_prefix("wss://")) {
        (true, r)
    } else if let Some(r) = s.strip_prefix("http://").or_else(|| s.strip_prefix("ws://")) {
        (false, r)
    } else if !s.is_empty() {
        (true, s)
    } else {
        return Err(AppError::Other("Chưa nhập địa chỉ server.".into()));
    };
    let host = rest.split('/').next().unwrap_or(rest);
    Ok(if secure {
        format!("https://{host}")
    } else {
        let name = host.rsplit_once(':').map_or(host, |(name, _)| name);
        format!("http://{name}:{LOCAL_AUTH_PORT}")
    })
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder().timeout_global(Some(Duration::from_secs(15))).http_status_as_error(false).build().into()
}

fn read(mut response: ureq::http::Response<ureq::Body>) -> AppResult<(u16, Value)> {
    let status = response.status().as_u16();
    let text = response.body_mut().read_to_string().map_err(net)?;
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

fn net(e: ureq::Error) -> AppError {
    AppError::Other(format!("Không kết nối được server: {e}"))
}

fn unexpected(status: u16, body: &Value) -> AppError {
    AppError::Other(format!("Server trả lỗi {status}: {}", body.get("code").and_then(Value::as_str).unwrap_or("?")))
}

pub fn login(server: &str, user: &str, password: &str) -> AppResult<Login> {
    let url = format!("{}/auth/login", base(server)?);
    let body = json!({ "user": user, "password": password }).to_string();
    let response = agent().post(&url).header("Content-Type", "application/json").send(body.as_str()).map_err(net)?;
    match read(response)? {
        (200, v) => Ok(Login {
            token: v["token"].as_str().unwrap_or_default().to_owned(),
            user: v["user"].as_str().unwrap_or(user).to_owned(),
            expires_at: v["expiresAt"].as_u64().unwrap_or(0),
        }),
        // Server không cho biết tên hay mật khẩu sai; màn đăng nhập báo theo ô mật khẩu.
        (401, _) => Err(AppError::Other("login.wrongPass".into())),
        (429, _) => Err(AppError::Other("login.tooMany".into())),
        // Server cũ chưa có `/auth/`, hoặc tunnel chưa chuyển `/auth/` sang cổng 8767.
        (404, _) => Err(AppError::Other("login.noApi".into())),
        (status, v) => Err(unexpected(status, &v)),
    }
}

/// Tên tài khoản nếu token còn hiệu lực; `None` nếu server từ chối token.
pub fn me(server: &str, token: &str) -> AppResult<Option<String>> {
    let url = format!("{}/auth/me", base(server)?);
    let response = agent().get(&url).header("Authorization", &format!("Bearer {token}")).call().map_err(net)?;
    match read(response)? {
        (200, v) => Ok(v["user"].as_str().map(str::to_owned)),
        (401, _) => Ok(None),
        (status, v) => Err(unexpected(status, &v)),
    }
}

pub fn logout(server: &str, token: &str) -> AppResult<()> {
    let url = format!("{}/auth/logout", base(server)?);
    agent().post(&url).header("Authorization", &format!("Bearer {token}")).send_empty().map_err(net)?;
    Ok(())
}

/// Một câu đã dịch: `text`, hoặc `error` nếu riêng câu đó lỗi (các câu khác vẫn dịch).
#[derive(Debug, Serialize, serde::Deserialize)]
pub struct Translated {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// Dịch một lô câu (tối đa 50) theo thứ tự; `context` là vài câu gốc đứng trước lô để dịch liền mạch.
/// Lỗi chung trả về khoá i18n (`tr.*`) cho thông báo.
pub fn translate(server: &str, token: &str, source: &str, target: &str, texts: &[String], context: &[String]) -> AppResult<Vec<Translated>> {
    let url = format!("{}/translate", base(server)?);
    let body = json!({ "source": source, "target": target, "texts": texts, "context": context }).to_string();
    // Server dịch lần lượt từng câu và chờ khi router bận, nên một lô có thể lâu.
    let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(Duration::from_secs(300))).http_status_as_error(false).build().into();
    let response = agent
        .post(&url)
        .header("Authorization", &format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .send(body.as_str())
        .map_err(net)?;
    match read(response)? {
        (200, v) => serde_json::from_value(v["translations"].clone()).map_err(|e| AppError::Other(format!("Server trả kết quả dịch lạ: {e}"))),
        (401, _) => Err(AppError::Other("tr.signIn".into())),
        // Server cũ chưa có `/translate`, hoặc tunnel chưa chuyển `/translate` sang cổng 8767.
        (404, _) => Err(AppError::Other("tr.noApi".into())),
        (status, v) => Err(unexpected(status, &v)),
    }
}

/// Đọc một câu lồng tiếng (`POST /speak`): WAV PCM16 mono của server.
/// `max_seconds` để server tăng tốc (tối đa 2x) cho vừa; câu vẫn có thể dài hơn.
/// Lỗi chung trả về khoá i18n (`dub.*`); lỗi riêng của câu trả về thông báo của server.
pub fn speak(server: &str, token: &str, language: &str, text: &str, voice: &str, max_seconds: f64) -> AppResult<Vec<u8>> {
    let url = format!("{}/speak", base(server)?);
    let body = json!({ "language": language, "text": text, "voice": voice, "max_seconds": max_seconds }).to_string();
    let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(Duration::from_secs(120))).http_status_as_error(false).build().into();
    let mut response = agent
        .post(&url)
        .header("Authorization", &format!("Bearer {token}"))
        .header("Content-Type", "application/json")
        .send(body.as_str())
        .map_err(net)?;
    if response.status().as_u16() == 200 {
        return response.body_mut().with_config().limit(64 * 1024 * 1024).read_to_vec().map_err(net);
    }
    match read(response)? {
        (401, _) => Err(AppError::Other("dub.signIn".into())),
        // Server cũ chưa có `/speak`, hoặc tunnel chưa chuyển `/speak` sang cổng 8767.
        (404, _) => Err(AppError::Other("dub.noApi".into())),
        (503, _) => Err(AppError::Other("dub.unavailable".into())),
        (400, v) if v["code"] == "invalid_speech" => Err(AppError::Other(format!("Server từ chối câu: {}", v["text"].as_str().unwrap_or("?")))),
        (status, v) => Err(unexpected(status, &v)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base_maps_server_address() {
        assert_eq!(base("https://stt.qiverse.top/").unwrap(), "https://stt.qiverse.top");
        assert_eq!(base("stt.qiverse.top").unwrap(), "https://stt.qiverse.top");
        assert_eq!(base("ws://127.0.0.1:8766").unwrap(), "http://127.0.0.1:8767");
        assert_eq!(base("http://localhost").unwrap(), "http://localhost:8767");
        assert!(base(" ").is_err());
    }

    /// Đăng nhập server thật: `GLOTALK_AUTH=ws://127.0.0.1:8766 GLOTALK_AUTH_USER=vtvlive GLOTALK_AUTH_PASS=… cargo test --lib auth_live`.
    #[test]
    fn auth_live() {
        let (Ok(server), Ok(user), Ok(pass)) = (std::env::var("GLOTALK_AUTH"), std::env::var("GLOTALK_AUTH_USER"), std::env::var("GLOTALK_AUTH_PASS")) else { return };
        let session = login(&server, &user, &pass).unwrap();
        assert_eq!(me(&server, &session.token).unwrap().as_deref(), Some(user.as_str()));
        logout(&server, &session.token).unwrap();
        assert_eq!(me(&server, &session.token).unwrap(), None);
    }
}
