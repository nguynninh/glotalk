# ONPlay Translation — HTTPS API for mobile

Updated: 2026-11-02. This guide covers HTTPS integration for Android/iOS.
The browser UI's WebSocket transport is outside this mobile API contract.
Existing access and tunnel configuration are unchanged.

## Configuration

| Setting | Value |
|---|---|
| Base URL | `https://stt.qiverse.top` |
| Translation | `POST /api/translate` |
| Audio-to-audio translation | `POST /api/translate-audio?target=vi` |
| Speech audio | `POST /api/tts` |
| Audio transcription | `POST /api/transcribe` — unchanged |
| Translation request Content-Type | `application/json; charset=utf-8` |
| Suggested application User-Agent | `ONPlayMobile/1.0` |
| Authentication | No API key or Authorization header is currently required |
| Origin | Not required for these HTTPS POST endpoints |
| Response encoding | JSON UTF-8; `/api/tts` and `/api/translate-audio` success return binary WAV |

Keep normal TLS certificate validation enabled. Use the public HTTPS address on
a phone: `127.0.0.1` on the phone points to the phone, not the STT server.
The application User-Agent identifies the mobile client; it is not authentication.

For one-request audio output, use `/api/translate-audio?target=vi` (documented below).
For separate text/segment results, the mobile HTTPS flow is:

1. Send WAV audio to `/api/transcribe`.
2. Keep each returned segment's `start`, `end`, `speaker` and original `text`.
3. Send the text of each non-empty segment to `/api/translate`, sequentially.
4. Attach the returned translation to that segment in the app's local model.

For input that is already text, call `/api/translate` directly.

## Translate text

```http
POST /api/translate HTTP/1.1
Host: stt.qiverse.top
Content-Type: application/json; charset=utf-8
User-Agent: ONPlayMobile/1.0
```

```json
{
  "text": "Hello, how are you today?",
  "source": "en",
  "target": "vi",
  "mode": "standard"
}
```

| Field | Required | Default | Meaning |
|---|---|---|---|
| `text` | Yes | — | Non-empty plain text, at most 10,000 characters |
| `target` | Yes | — | Target ISO 639-1 language code |
| `source` | No | `auto` | Source code, or automatic detection |
| `mode` | No | `standard` | `standard` for the fast native router; `context` for experimental Qwen |
| `context` | No | `[]` | At most five previous source text strings; useful with context mode |

Supported translation codes: `en`, `vi`, `zh`, `ja`, `ko`, `th`, `id`, `ru`,
`ar`, `fr`, `de`, `es`. `auto` is valid only for the source. Source metadata is
preferable for short or ambiguous text. It controls translation, not recognition.
The STT audio model has its own, narrower language coverage; see
[mobile-api.md](mobile-api.md).

Send actual JSON, not a JSON string containing escaped JSON. The complete body
must fit within 64 KiB, including any context. Each context item must be a
non-empty string of at most 10,000 characters. Split long paragraphs at sentence
boundaries; token limits also apply inside the model. Line breaks and supported
literal placeholders (`{name}`, `{{user}}`, `${value}`, `%s`) are preserved.

### cURL

```sh
curl --fail-with-body --max-time 10 \
  'https://stt.qiverse.top/api/translate' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -H 'User-Agent: ONPlayMobile/1.0' \
  --data '{"text":"Hello, how are you today?","source":"en","target":"vi","mode":"standard"}'
```

### Success response

A response observed through the public HTTPS endpoint on 2026-11-02:

```json
{
  "text": "Xin chào, hôm nay anh thế nào?",
  "source": "en",
  "target": "vi",
  "model": "opus-fast",
  "fallback": false,
  "cached": false,
  "latency_ms": 35.72,
  "within_budget": true
}
```

| Field | Meaning |
|---|---|
| `text` | Translated text; the original request field remains backward compatible |
| `source` | Resolved source language |
| `target` | Target language |
| `model` | Model that actually produced the result |
| `fallback` | Primary failed and the configured fallback produced this result |
| `cached` | Result reused from the bounded in-memory cache |
| `latency_ms` | Router processing time, including source detection and any fallback; excludes mobile network RTT |
| `within_budget` | Router processing finished within 500 ms; not a network or end-to-end SLA |

Treat model names as metadata rather than an enum required for parsing. Current
fast names are `opus-fast` and `nllb-fast`; `identity` means source equals target
and the router returned normalized text without inference. Ignore unknown extra
response fields to remain compatible with additive API changes.

The example latency is one observation, not a guarantee. Mobile should measure
its own elapsed request time as well.

### Experimental context mode

```json
{
  "text": "Where is it?",
  "source": "en",
  "target": "vi",
  "mode": "context",
  "context": ["I bought a new book yesterday."]
}
```

Qwen context mode can take several seconds and does **not** meet the 500 ms
translation target. Use `standard` for interactive mobile translation. Context
is provided by the client on each request; the HTTPS API does not maintain
conversation history between requests. Include only previous segments from the
same conversation. The cache key includes context, source, target and route.

## Keep the dialogue structure on mobile

The translation endpoint accepts text; it does not alter segment boundaries or
assign speaker IDs. For example, the app may combine the two HTTPS responses
into this local object:

```json
{
  "id": 0,
  "start": 0.5,
  "end": 2.8,
  "speaker": 0,
  "source": {"text": "Hello", "language": "en"},
  "translation": {"text": "Xin chào", "language": "vi", "model": "opus-fast"}
}
```

This combined object is a **mobile-side representation**, not the response of a
new endpoint. Assign `id` from the index in `/api/transcribe`'s `segments` array.
Preserve the original audio timestamps and speaker number. Never replace source
text with a translation. On a translation failure, keep the transcript and offer
a bounded retry. Skip translation for empty source text.

## Audio request — existing endpoint

```sh
curl --fail-with-body --max-time 120 \
  'https://stt.qiverse.top/api/transcribe' \
  -H 'Content-Type: audio/wav' \
  -H 'User-Agent: ONPlayMobile/1.0' \
  --data-binary '@audio.wav'
```

Body: complete WAV bytes, including the WAV header. No multipart, Base64 or JSON.
Use a known-size body so the client sets Content-Length; chunked uploads are
unsupported. Requirements: PCM16 little-endian, mono, 16,000 Hz, over zero and
at most 60 seconds, complete file at most 2 MiB.

The response remains:

```json
{
  "type": "speakers",
  "segments": [
    {"start": 0.5, "end": 2.8, "speaker": 0, "text": "Hello"}
  ]
}
```

This HTTPS upload returns one completed JSON response, not incremental events.
If the app sends short recordings to get results sooner, timestamps and speaker
IDs restart for each request. Speaker IDs are session-local, not real identities.
See [the existing WAV API guide](mobile-api.md) for the full STT contract.

## Android configuration example

Use the app's existing OkHttp version. Run blocking `execute()` calls off the UI
thread; the snippet below only creates the request.

```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

val translationClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS)
    .build()

fun translationRequest(text: String, source: String, target: String): Request {
    val payload = JSONObject()
        .put("text", text)
        .put("source", source)
        .put("target", target)
        .put("mode", "standard")
    return Request.Builder()
        .url("https://stt.qiverse.top/api/translate")
        .header("User-Agent", "ONPlayMobile/1.0")
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
}
```

Execute with the app's existing background request/error-handling code. Close
the response body, check HTTP status and Content-Type, then decode the JSON.
Use a separate 120-second timeout policy for WAV uploads. The 10-second translation
timeout is a transport failure limit, not a 500 ms performance claim. Requests
need Android's normal Internet permission; public HTTPS does not require enabling
cleartext traffic or weakening TLS validation.

## iOS configuration example

Foundation async/await transport example for iOS 15+:

```swift
import Foundation

struct TranslationResponse: Decodable {
    let text: String
    let source: String
    let target: String
    let model: String
    let latency_ms: Double
    let cached: Bool
    let fallback: Bool
    let within_budget: Bool?
}

func translateText(_ text: String, source: String = "auto", target: String) async throws -> TranslationResponse {
    var request = URLRequest(url: URL(string: "https://stt.qiverse.top/api/translate")!)
    request.httpMethod = "POST"
    request.timeoutInterval = 10
    request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
    request.setValue("ONPlayMobile/1.0", forHTTPHeaderField: "User-Agent")
    request.httpBody = try JSONSerialization.data(withJSONObject: [
        "text": text, "source": source, "target": target, "mode": "standard"
    ])
    let (data, response) = try await URLSession.shared.data(for: request)
    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
          http.value(forHTTPHeaderField: "Content-Type")?.lowercased().contains("application/json") == true else {
        throw URLError(.badServerResponse)
    }
    return try JSONDecoder().decode(TranslationResponse.self, from: data)
}
```

Use the app's error UI/retry policy around this function. Public HTTPS uses normal
system certificate validation; this example does not require an ATS exception.
For WAV uploads, use a known-size WAV `Data`/file body with `audio/wav` and the
separate upload timeout policy. Capture, microphone permission and PCM conversion
remain responsibilities of the mobile application.

## Errors and retry

Application errors use JSON `{"error":"message"}`. A proxy/network failure may
return HTML instead, so check status and Content-Type before decoding.

| HTTP | Translation cause | Client action |
|---|---|---|
| 400 | Invalid JSON, source/target/mode/context or missing/empty text | Fix the request; do not retry unchanged |
| 404 | Wrong path, including nonexistent combined endpoint | Use the exact paths in this guide |
| 408 | Request body interrupted or timed out | Retry only after checking connectivity |
| 413 | Empty/oversized body or missing Content-Length | Send a known-size body within 64 KiB |
| 415 | Content-Type is not application/json | Correct the header |
| 503 | Router unavailable/busy, model failure or invalid model output | Retry with bounded backoff; keep original text |

The router serializes inference. Send dialogue segments sequentially, not all in
parallel. The internal busy response is mapped to 503 by the public translation
client. A suggested retry policy is at most two retries after 250 ms and 1 second,
for transient failures only; retries are outside the 500 ms processing target.
Raw WAV uploads have additional errors such as 409 (STT engine busy) and 422
(invalid audio); see `mobile-api.md`.

A 403 HTML response can come from the public edge policy before the application.
Mobile clients should use their application User-Agent and report the status to
the backend team; an Origin header is not an HTTPS API credential. Do not disable
TLS validation to work around network/edge errors.

## Latency and verification

Interactive configuration: `mode: "standard"`, short finalized text segments,
explicit source when known, and one request at a time. Native INT8 OPUS routes
serve English/Vietnamese/Chinese; Chinese/Vietnamese may pivot through English.
Other configured languages use native INT8 NLLB. Qwen is reserved for explicit
context-mode requests. No semantic quality improvement is implied by the faster
route; specialized quality routing still needs a representative benchmark.

Local benchmark on 2026-11-02: `.venv/bin/python tests/translation_latency.py`
ran 25 uncached HTTP requests, including English/Vietnamese, Chinese/Vietnamese,
Japanese/Vietnamese and an English sentence over 100 characters. Observed p50
53.56 ms, p95 283.94 ms, maximum 377.64 ms. Details are in the local ignored
`build/translation-latency.json` artifact. These measurements cover the tested
short inputs on this machine, not an all-input upper bound. Long text, contention,
fallback, STT finalization and mobile network time may exceed 500 ms.

Public HTTPS was checked with cURL and application User-Agents `okhttp/4.12.0`
and `ONPlayMobile/1.0`; translation returned HTTP 200 and `model: "opus-fast"`.
Android/iOS application integration has not been run on a device. The code
snippets are transport examples, not a complete mobile SDK.

NLLB is CC-BY-NC-4.0 and its model card excludes production deployment from its
intended use. The current NLLB routes are research/local experiments, not a
production release approval. See [model provenance and licenses](translation-models.md).
The endpoint currently has no application authentication and no uptime SLA.

## Translated speech audio

After receiving translated `text`, send it with the translation target language:

```sh
curl --fail-with-body http://127.0.0.1:8765/api/tts \
  -H 'Content-Type: application/json' \
  --data '{"text":"Xin chào","language":"vi"}' \
  --output translation.wav
```

The endpoint is implemented on the local server. Restart the server after updating.
Public HTTPS routing for this new endpoint has not been verified; mobile clients
should use their server's HTTPS base URL once that route is available.

Success returns raw `audio/wav`: mono signed PCM16 at 44,100 Hz. Save the response
bytes to a WAV file or play them with the platform media player. Keep the existing
segment ID alongside the audio in the client; synthesis does not reproduce the
original voice or align its duration to the source timestamps. No audio URL is
stored on the server. Call sequentially per segment, or combine translated text
up to 3,000 characters. Skip empty translations. Allow time for local model
inference; cancelling the HTTP request does not interrupt active synthesis.

The JSON body limit is 32 KiB. Speech synthesis supports Vietnamese and English,
using the KorvaTTS `khanh_vy` voice. The first startup downloads model assets into
the local Hugging Face cache. Other language codes are not supported for speech.
Errors are JSON: 400 invalid input/framing, 413 body limit, 415 content type,
408 upload timeout/interruption, 503 engine busy, unsupported language,
synthesis failure or output over 32 MiB. Keep translated text available on
failure. KorvaTTS runs locally and does not send speech text to an external API.

## Audio to translated audio

`POST /api/translate-audio?target=en` accepts the same raw WAV body as
`/api/transcribe`. The server performs STT with a resident model and a fresh stream
per request, without speaker segmentation, then translates the transcript
in standard mode with automatic source text language detection, and synthesizes
the translation. The client makes one request and receives the audio bytes.

```sh
curl --fail-with-body --max-time 240 \
  'http://127.0.0.1:8765/api/translate-audio?target=en' \
  -H 'Content-Type: audio/wav' \
  --data-binary @models/vi.wav --output translated-en.wav
```

Input must be mono PCM16 16 kHz WAV, up to 60 seconds and 2 MiB, with
Content-Length (no multipart or chunked encoding). Exactly one `target` query
parameter is required, using a supported translation language code. KorvaTTS
speech currently supports Vietnamese and English targets. The STT model and the
translation router must also be available.

Success is HTTP 200 with `Content-Type: audio/wav`, mono PCM16 at 44,100 Hz.
HTTP 204 means no recognized speech; there is no file to play. The output is
one continuous synthesized voice, without source speaker cloning, source pause
alignment, or transcript JSON. `/api/transcribe` retains speaker segmentation.
`Server-Timing` reports `stt`, `translate` and `tts` milliseconds for HTTP 200
(only `stt` for HTTP 204), excluding upload/download. The STT worker is warmed at
server startup; first TTS use and uncached translation can be slower.

Errors return JSON: 400 invalid target/framing, 408 upload timeout, 409 busy,
413 upload size, 415 content type, 422 invalid WAV/duration or text limit,
500 recognition worker/model failure, 503 translation/TTS failure or busy TTS,
504 recognition timeout. Transcript limit: 10,000 characters; translated speech
limit: 3,000 characters; audio output limit: 32 MiB. Send shorter audio if text
limits are exceeded. The shared inference lock is held for the whole request,
including translation and speech synthesis. Use a 240-second client timeout;
this is not a processing latency guarantee. Request cancellation may leave the
current bounded processing step running. The audio translation path keeps PCM
in memory; speech synthesis temporary files are removed. The model remains in
memory between requests. A failed worker is discarded and started again on the
next request; the failed request is not replayed automatically.

Restart the local server after updating. Public HTTPS routing for the new path
has not been verified; use the HTTPS server base URL on mobile once configured.
