# Video dubbing for the iOS app (design)

Status: phase 1 is implemented in `onplay-server` and checked by
`onplay-server-cli/tests/socket.rs` on a local clip; it has not yet run through the
public tunnel or on an iPhone. Phase 2 is proposed only. See also
[README](../README.md) ("Dub jobs") and [ARCHITECTURE](../ARCHITECTURE.md).

## Goal

An iPhone app in which a user gives a video and gets it back dubbed into Vietnamese
or English, with subtitles. Recognition, translation and speech run on the Mac that
already runs `onplay-server`. The work is split into two phases that share jobs,
progress and result downloads and differ only in input and authentication:

| | Phase 1 | Phase 2 |
|---|---|---|
| Distribution | Internal TestFlight testers or ad hoc builds only | App Store |
| Input | A video link (YouTube and other `yt-dlp` sites) | Audio the app extracts from a video on the phone |
| Authentication | Per-tester bearer token entered in Settings | App Attest |
| Result | HLS stream with dub and subtitles (online and offline), plus both SRTs | Dub track, spans and SRTs; the app builds the video |

Out of scope: on-device recognition or translation, voices that imitate the original
speakers, dubbing into languages other than `vi` and `en` (subtitles-only works for
the other targets), user accounts, push notifications.

## What exists today

- `onplay-dub` is a library (`onplay_dub::run(project, options, observer)`) that takes
  a video file or an http(s) link and writes `<name>.<target>.mp4`,
  `<name>.<target>.srt` and `<name>.original.srt`. It reports progress through
  `Observer` events (`Downloading`, `Recognizing { offset, duration }`, `Recognized`,
  `Translated`, `Transcribed { segments }`, `Spoken`, `Muxing`) and never logs. The
  source language is detected per segment by the translation router; there is no
  source-language option. `subtitles_only` keeps the original audio.
- It needs `ffmpeg`, `yt-dlp` for links, the `build/onplay-speakers` binary and the
  translation router on `127.0.0.1:5002`.
- `onplay-server` is a WebSocket server on `127.0.0.1:8766` for live microphone
  sessions. `stt.qiverse.top` is a Cloudflare tunnel that routes the whole hostname to
  that port. Access control is the `Origin` allowlist only; there is no user
  authentication. Client messages are limited to 32,000 bytes. The server sends a
  WebSocket ping after 20 s without client traffic and drops the client if no pong
  follows within 20 s (`socket/transport.rs`).
- The server has no dubbing support and no HTTP API. `tiny_http` is already a
  workspace dependency (used by the translation router).
- Measured on an M4: a 12:26 Chinese YouTube video was dubbed to Vietnamese in about
  3 minutes including the download; the output MP4 was 221 MB. A 3:50 clip took
  54-58 s (README).

## App Store constraints

Not re-checked against the current guidelines while writing this; confirm before
submission.

- **Third-party media.** Guideline 5.2.3 rejects apps that save, convert or download
  media from third-party sources such as YouTube without authorization. This also
  applies to Beta App Review, so phase 1 must stay with internal TestFlight testers or
  ad hoc builds, and the App Store build must not contain the link input or any token.
- **Privacy.** Video links, uploaded audio and the returned transcripts are user data:
  the App Store app needs a privacy policy, matching App Privacy answers, and consent
  before the first upload. The server must not keep them beyond the job (dub jobs
  ignore `ONPLAY_CAPTURE_AUDIO`) and must delete them after the retention period.
- **No accounts.** Without accounts there is no account-deletion requirement and no
  Sign in with Apple requirement.
- **Model licenses.** NLLB-200 is CC-BY-NC-4.0 and is the router's default and the
  fallback for every route in `config/translation.yaml`
  ([translation-models.md](translation-models.md)). A public App Store app, especially
  a paid or ad-supported one, should not depend on it. Dubbing routes for the App
  Store app must use models whose licenses allow it (OPUS-MT CC-BY 4.0 with
  attribution, Qwen3 Apache-2.0, KorvaTTS Apache-2.0), and the app must show the
  required attributions. Which source languages remain covered without NLLB is open.
- **Reviewability.** The review team must reach a working server during review, so the
  service has to be up and must not depend on the developer's Mac being awake.

## Jobs (both phases)

Dubbing is a job that outlives the connection that started it. iOS suspends a
backgrounded app's sockets within seconds, so a job is not cancelled when its
WebSocket closes (unlike live sessions, which are cancelled on disconnect).

- A job has an id, an owner (the token in phase 1, the install in phase 2), a state, the
  latest progress and its output files.
- Jobs run one at a time on a dedicated job thread inside `onplay-server`. One dub run
  already uses 4 recognition threads and 3 x 2 speech threads, so parallel jobs would
  only compete with each other and with live sessions. Further jobs wait in a bounded
  queue (proposed: 8). Each owner has at most one queued or running job.
- Each job writes into `jobs/<id>/` (`ONPLAY_JOB_DIR`, default `jobs/` in the checkout,
  ignored by Git). Files are deleted 24 h after the job ends, when the app sends
  `delete`, and at startup. Jobs do not survive a server restart: after a restart every
  earlier job id answers `job_not_found`.
- A job does not take a live-session engine slot; live session messages and limits
  are unchanged.

## Phase 1 protocol (implemented)

### Connection

- WebSocket URL `wss://stt.qiverse.top`, the same socket as live sessions.
- Headers: `Origin: https://stt.qiverse.top` and `Authorization: Bearer <token>`. Set
  both on the `URLRequest` passed to `URLSessionWebSocketTask`. The server does not
  refuse the handshake for a missing or wrong token; it accepts the socket, answers
  the first command with the `invalid_token` error and closes, so the app always gets
  a structured code. A wrong `Origin` is still refused with HTTP 403 before the socket
  opens.
- The first text message decides the connection type. If its `type` is `dub`, `job`,
  `cancel`, `delete` or `list`, it is a job connection; anything else is parsed as a
  live-session configuration as today.
- Every client command carries `"version": 1`. Every server message on a job
  connection carries `"version": 1`. A version the server does not know is answered
  with `unsupported_version` and `"supported": [1]`. New optional fields may be added
  to version 1; the app must ignore fields it does not know.
- Keepalive: the existing 20 s server ping keeps the tunnel's 100 s idle timeout from
  firing during long silent steps (download, queue). `URLSessionWebSocketTask` answers
  pings itself; the app sends no heartbeat. A job connection that is dropped does not
  affect the job.

### Commands

| Command | Reply on the same connection |
|---|---|
| `dub` | `job` (state `queued`), then `progress` and `job` updates until the job ends, then the server closes |
| `job` | `job` with the current state, then the same stream as `dub` until the job ends; if it has already ended, that one `job` message and close |
| `cancel` | `job` with state `cancelled` (or the final state if it had already ended), then close |
| `delete` | `{"type":"deleted","job_id":"..."}`, then close; files are removed and the id becomes `job_not_found` |
| `list` | `{"type":"jobs","jobs":[...]}` with one `job` summary per job of this token, newest first, then close |

While a connection streams a job (after `dub` or `job`), the app may send `cancel` for
that job on the same connection. Any other command there is answered with
`invalid_message`. Only the token that created a job can see, cancel or delete it;
other tokens get `job_not_found`.

```json
{"version":1,"type":"dub","url":"https://www.youtube.com/watch?v=...","target_language":"vi"}
{"version":1,"type":"dub","url":"https://...","target_language":"fr","subtitles_only":true}
{"version":1,"type":"job","job_id":"j_7f3c9a2e"}
{"version":1,"type":"cancel","job_id":"j_7f3c9a2e"}
{"version":1,"type":"delete","job_id":"j_7f3c9a2e"}
{"version":1,"type":"list"}
```

`dub` fields:

| Field | Required | Values |
|---|---|---|
| `url` | yes | http(s) link `yt-dlp` can download; one video, playlists are refused |
| `target_language` | yes | `vi`, `en`, `zh`, `ja`, `ko`, `th`, `id`, `ru`, `ar`, `fr`, `de`, `es` |
| `subtitles_only` | no, default `false` | `true` keeps the original audio and only adds subtitles. Required for targets other than `vi` and `en`; without it they get `unsupported_language` |

There is no `source_language`: the source language is detected per segment, and
segments already in the target language keep their original audio. The app drops its
"From" field.

Before replying to `dub`, the server reads the link's metadata with `yt-dlp` (no
download yet); for direct media links (`.mp4`, `.m3u8`), which `yt-dlp` reports without
a duration, ffprobe reads it from the stream. This can take a few seconds; invalid
links, playlists, live streams and links to `localhost`, local names or IP addresses
fail here with `invalid_link`, and videos over the length limit with `too_long`.

### Job messages

```json
{"version":1,"type":"job","job_id":"j_7f3c9a2e","state":"queued","position":2,
 "target_language":"vi","subtitles_only":false,
 "title":"…","duration_s":746.4,"thumbnail_url":"https://i.ytimg.com/…",
 "created_at":"2026-11-06T10:15:00Z","expires_at":null}
```

- `state`: `queued`, `running`, `done`, `failed`, `cancelled`. The last three are final.
- `position`: jobs that start before this one, the running job included; only while
  `queued`.
- `title`, `duration_s` and `thumbnail_url` come from the `yt-dlp` metadata; `title`
  and `thumbnail_url` may be `null`. The thumbnail is the site's own URL; the server
  does not proxy it.
- `expires_at`: when the files will be deleted; set once the job is final.
- A `failed` job has `"error":{"code":"…","text":"…"}`.
- A `done` job has `playback` and `files`:

```json
"playback":{"path":"/jobs/j_7f3c9a2e/hls/Xq9…k2/master.m3u8"},
"files":{
  "subtitles":{"path":"/jobs/j_7f3c9a2e/subtitles.srt","size":48211,"content_type":"application/x-subrip"},
  "original_subtitles":{"path":"/jobs/j_7f3c9a2e/original.srt","size":51002,"content_type":"application/x-subrip"}
}
```

  Keys and file names are fixed, and `playback` is always an object with `path`.
  Every `path` is resolved against `https://stt.qiverse.top`. `playback` is the
  result video as HLS, for online playback and offline download (see "Playback over
  HLS"); with `subtitles_only` its audio is the original audio. The app does not use
  MP4, so the muxed MP4 is only an intermediate file and is not offered. The `list`
  reply uses the same `job` object without `type` and `version`.

### Progress

```json
{"version":1,"type":"progress","job_id":"j_7f3c9a2e","stage":"recognizing","done":180,"total":746}
```

Stages in order; recognition, translation and speech overlap, so `stage` is the
earliest unfinished one:

| `stage` | `done` / `total` | Source event |
|---|---|---|
| `downloading` | none | `Downloading` |
| `recognizing` | seconds of audio recognized / total seconds | end time of the last recognized segment; total from `Recognizing { duration }` |
| `speaking` | segments spoken / segments to speak; absent with `subtitles_only` | `Spoken`, total from `Transcribed` |
| `muxing` | none | `Muxing` |

`Recognizing { offset }` only advances once per 590 s piece (`PIECE_SECONDS`), too
coarse for a progress bar, so `Recognized` gains the segment's end time (a small
`onplay-dub` event change). Translation has no stage of its own: each segment is
translated as soon as it is recognized, inside `recognizing`. Progress is sent at
most twice a second. While a job is `queued`, position changes are sent as `job`
messages.

### Errors

```json
{"version":1,"type":"error","code":"queue_full","text":"Máy chủ đang bận, thử lại sau.","retry_after_s":300}
```

`text` is a Vietnamese message the app may show; the app decides by `code`. A
command error is followed by close. A job that fails while running ends with a `job`
message in state `failed`, not an `error` message.

| `code` | When |
|---|---|
| `unsupported_version` | Missing or unknown `version` |
| `invalid_message` | Malformed JSON, unknown `type`, missing field, or a command not allowed on this connection |
| `invalid_token` | Missing, unknown or revoked token |
| `dub_disabled` | The server runs without dubbing (no token file, missing `ffmpeg`/`yt-dlp`, router down) |
| `invalid_link` | Not http(s), not downloadable, a playlist or a live stream |
| `too_long` | Longer than the limit (proposed 15 min); the reply has `max_duration_s` |
| `unsupported_language` | Unknown target, or not `vi`/`en` without `subtitles_only` |
| `queue_full` | Queue is full; has `retry_after_s` |
| `job_limit` | This token already has a queued or running job; has `job_id` of that job |
| `job_not_found` | Unknown id, another token's job, deleted, expired after 24 h or lost in a restart |

Failure codes inside a `failed` job:

| `code` | When |
|---|---|
| `download_failed` | `yt-dlp` could not download the video |
| `no_speech` | No speech recognized |
| `translation_failed` | The translation router failed |
| `speech_failed` | Speech synthesis failed |
| `internal` | Anything else (`ffmpeg`, disk, setup) |

### Downloads over HTTP

- A `tiny_http` listener on `127.0.0.1:8767` inside `onplay-server` serves
  `GET` and `HEAD` under `/jobs/`. A tunnel ingress rule sends `path: ^/jobs/` on
  `stt.qiverse.top` there, before the catch-all rule for port 8766. The tunnel
  configuration is not in this repository and must be changed where it is managed.
- `files` (`/jobs/<id>/<file>`) need `Authorization: Bearer <token>`, the same token
  as the job. Tokens are never accepted in the query string. `playback` needs no
  header (see below).
- Responses: `Content-Length`, `Content-Type`, `Accept-Ranges: bytes`, a strong
  `ETag` and `Last-Modified`. Files never change once the job is `done`. A single
  `Range: bytes=a-b` gets `206`; `If-Range` with a stale validator gets the full file;
  an unsatisfiable range gets `416`. Multiple ranges are not supported.
- Errors: `401` with `{"code":"invalid_token"}`, `404` with `{"code":"job_not_found"}`
  for unknown, foreign, deleted or expired jobs and unknown file names.
- Everything under `/jobs/<id>/` stays available until `expires_at`, 24 h after the
  job ended, including while a download is in progress. The app does not send
  `delete` after saving offline, since that would break online playback; the job
  expires on its own. `delete` remains for a user who removes a job.
- To verify on the real tunnel: that Cloudflare forwards `Range`, `If-Range` and
  `Authorization` unchanged and does not cache these responses.

### Playback over HLS

PlayerSDK plays HLS (`.m3u8`) URLs and cannot add headers. The app plays `playback`
online by giving the URL to `PlayerView`, and saves it for offline use with
`AVAssetDownloadURLSession` (`AVAggregateAssetDownloadTask`, background, all subtitle
renditions) into a `.movpkg`. Offline needs no other endpoint.

- After muxing, the server repackages the MP4 into HLS with `ffmpeg`
  (`-hls_playlist_type vod`, fMP4 segments of about 6 s, each a separate file, no
  `EXT-X-BYTERANGE`). Media playlists have `#EXT-X-PLAYLIST-TYPE:VOD` and
  `#EXT-X-ENDLIST`; without them PlayerSDK's proxy treats the stream as live and does
  not cache it, and offline download fails.
- Codecs: H.264 video and AAC audio, copied without re-encoding when the source is
  H.264. `yt-dlp` prefers H.264, but when only another codec is available (VP9, AV1)
  the video is re-encoded to H.264 first, which makes such jobs slower.
- One variant only: the master playlist has one `#EXT-X-STREAM-INF` with
  `BANDWIDTH`, `AVERAGE-BANDWIDTH`, `CODECS`, `RESOLUTION` and `SUBTITLES`. Offline
  download takes the highest variant, so more variants would only cost disk.
- No encryption (no AES-128, no FairPlay).
- Subtitles: two `#EXT-X-MEDIA` renditions, `TYPE=SUBTITLES`, `GROUP-ID="subs"`, with
  `LANGUAGE`, `NAME`, `AUTOSELECT=YES` and no `FORCED`: the translation with
  `DEFAULT=YES`, the original with `DEFAULT=NO`. Each is a media playlist with one
  WebVTT segment for the whole video, converted from the SRT, starting with an
  `X-TIMESTAMP-MAP` header that maps `LOCAL:00:00:00.000` to the first video
  segment's start time (90 kHz `MPEGTS`).
- Layout: `/jobs/<id>/hls/<key>/master.m3u8`, its media playlists and segments, all with
  relative URIs. `<key>` is 32 random bytes, base64url, created per job.
- Access: `/jobs/<id>/hls/<key>/…` needs no token; the unguessable key is the access
  check. It is in the path, not the query, so PlayerSDK's proxy keeps it when it
  rewrites URLs. The key grants only this job's output, never the tester token, and
  stops working when the job is deleted or expires. Requests are not logged with
  their path.
- Caching: playlists are served as `application/vnd.apple.mpegurl`, segments as
  `video/mp4` and `text/vtt`. Nothing changes after `done`, so responses get
  `Cache-Control: private, max-age` up to `expires_at`: PlayerSDK's proxy may keep
  them, Cloudflare does not. Segments support `Range` like the other files.
- PlayerSDK reads subtitles through the `.legible` media selection group
  (`loadMediaSelectionGroup(for: .legible)`), which is how AVFoundation exposes HLS
  WebVTT renditions, so it needs no change. It ignores `FORCED` options.
- Not yet checked: whether `DEFAULT=YES` makes AVFoundation select the translation
  automatically in PlayerSDK, which only reports the selection AVFoundation already
  made; and offline playback of the saved `.movpkg` with both subtitle renditions.
  Both need a real iPhone; HLS download does not run on the simulator.

### Tokens

- Tokens are random 32-byte values, base64url-encoded, one per tester, created with a
  script and handed out privately. The server reads `ONPLAY_DUB_TOKENS`, a file outside
  Git with one `name sha256-of-token` line per tester, so the file itself holds no
  usable token. The server rereads the file when it changes: a new line works and a
  removed line is revoked on the next request, without a restart. A revoked tester's
  running jobs continue and their HLS links work until they expire.
- The app stores the token in the Keychain, entered on its Settings screen. No token
  is compiled into any build, and the App Store build has neither the field nor the
  link tab.
- Logs keep job ids, token names, durations, states and error codes; never tokens,
  links, titles or transcripts.

### Limits

- Length: proposed 15 min (`ONPLAY_DUB_MAX_SECONDS`), checked against the `yt-dlp`
  metadata before the job is queued.
- One queued or running job per token, queue length 8, total size of `jobs/`
  bounded; when the disk limit would be exceeded, new jobs get `queue_full`.

### Before the app can test end to end

The server side will provide, when phase 1 is deployed: a tester token, a short
test clip link the server has dubbed successfully, and a note that the server accepts
jobs (until then `dub` answers `dub_disabled`).

## Phase 2: upload from the device (proposed)

1. The user picks a video with `PhotosPicker` or the Files app. The app checks the
   length limit and extracts mono 16 kHz audio with AVFoundation (12 minutes is about
   23 MB as PCM16).
2. The app sends `dub` without `url`, with `"duration_s"` and `"audio_bytes"`, then the
   PCM as binary messages of at most 32,000 bytes and `{"type":"end"}`. Jobs start in
   an `uploading` state. An interrupted upload is restarted, not resumed, in the first
   version.
3. The server runs a new audio-input, no-mux mode of `onplay-dub`: `run` accepts a mono
   PCM16 16 kHz WAV, skips `fetch`, `extract_audio` and `mux`, and returns the dub
   track (WAV or AAC), the spans already computed by `timeline::place` and both SRTs.
   The CLI keeps its current behavior.
4. The app downloads these files and builds the video: original video, original audio
   lowered to the background volume inside each span (`AVMutableAudioMix` volume
   ramps), the dub track and a subtitle track.

The video itself never leaves the phone. This avoids multi-gigabyte uploads, the
Cloudflare free plan's 100 MB request-body limit, and storing users' videos on the
server.

Authentication is App Attest: each install registers a key, and commands carry an
assertion the server verifies; the install id replaces the token as job owner and
quota unit. The server never fetches a URL in this phase.

Known costs, from the iOS side:

- PlayerSDK's `PlayerView` only takes a URL, not an `AVComposition`. The app would have
  to export the composition to a file before playback, costing time and storage, and
  whether the export keeps a subtitle track is untested. The alternative is extending
  PlayerSDK.
- App Attest is unavailable on the simulator (`DCAppAttestService.isSupported` is
  `false`). Development builds need a server-side fallback, and every real test needs
  an iPhone.

## Capacity

One Mac running one job at a time handles roughly 4 minutes of video per minute of
processing (from the 12:26 run, which also included downloading). That is a few
hundred minutes of video per hour at best, shared with live sessions. A public
launch needs either a limit that matches this (quotas, queue) or more servers; the
job design does not assume a single machine, but the queue is in-process today.

## Open questions

1. Is the App Store app free, paid or ad-supported? This decides how strictly NLLB
   must be removed and whether the service needs billing.
2. Which source languages must be supported without NLLB?
3. Where does the service run for review and launch, given it currently runs on a
   development Mac behind a tunnel?
4. Should a running dub job slow down while live sessions are active?
5. The existing live service (`streaming-audio.md`) also has no authentication; does
   the App Store app use it too, and should it get App Attest as well?
6. Phase 2 playback: export the composition to a file, or extend PlayerSDK?

## Verification plan

- Rust tests for command parsing and error codes, job state transitions, queue and
  per-token limits, token checks, retention, and `Range`/`If-Range` responses.
- A socket test like `onplay-server-cli/tests/socket.rs` that dubs a short local
  clip through a job, reattaches with `job`, downloads the files over HTTP with a
  range request, and checks `cancel` and `delete`.
- Through the real tunnel: keepalive across a download longer than 100 s, `Range` and
  `Authorization` forwarding, and the `/jobs/` ingress rule.
- `mediastreamvalidator` on the generated playlists (VOD tags, codecs, WebVTT
  timestamp map).
- On a real iPhone: reattaching after the app was killed, online playback of
  `playback` in PlayerSDK with both subtitle renditions and the default selection,
  offline download into a `.movpkg` while backgrounded, and offline playback. Phase 2 adds the upload
  while backgrounded and composition output (audio levels, sync, subtitles) against the
  Mac `onplay-dub` result for the same clip.
