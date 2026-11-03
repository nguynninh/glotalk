# Segment speech over WebSocket

This opt-in protocol extends the existing WebSocket bridge. STT accepts PCM
incrementally; translation and speech run on separate workers. Each speech event
contains a complete WAV for one finalized segment, not incremental TTS samples.
The HTTP `/api/translate-audio` contract is unchanged.

## Connection and input

Local endpoint: `ws://127.0.0.1:8766`. Public deployments must route `/ws` to
that port and configure `ONPLAY_PUBLIC_ORIGIN` for the client's Origin header.
Use WSS for remote clients. Origin validation is not authentication.

Send this JSON as the first message:

```json
{"target_language":"vi","source_language":"en","speech":true,"speakers":false,"chunk":100}
```

`speech` defaults to false and requires a supported `target_language`.
Wait for `{"type":"ready"}`, then send binary PCM16 little-endian, mono,
16 kHz frames. For example, 100 ms is 3,200 bytes. Each frame must be nonempty,
even-sized and at most 32,000 bytes. Do not include WAV headers.
Send `{"type":"end"}` to finish input; continue receiving until `done`.
Disconnect to cancel a session. Only one inference session can run at a time,
shared with HTTP uploads.

## Output and playback

Existing `partial`, `final` and `translation` JSON events remain available.
Finals and translations carry a segment `id`. With speech enabled, live
translations also produce JSON events shaped like:

```json
{"type":"audio","id":0,"revision":"live","language":"vi","content_type":"audio/wav","encoding":"base64","data":"...","tts_ms":120.0}
```

`data` is base64 of the complete WAV (PCM16 mono 44,100 Hz).
`tts_ms` measures synthesis time, excluding queue wait and network delivery.
Decode and play WAVs in segment order while continuing microphone upload and
WebSocket receive on separate tasks. Interleaved transcript events are normal.
Do not also call `/api/tts` for these translations, or speech will play twice.
Base64 increases audio size by roughly one third; configure the client receive
limit for audio messages (the TTS WAV size limit is 32 MiB).

Only `live` translations produce speech. Offline speaker refinement never
replays revised translations. `done` follows completion of pending translation
and speech work; it does not mean the mobile playback queue has finished.

An `audio_error` disables further speech for that connection and discards queued
speech, while transcription and translation continue. Already delivered audio
remains valid. Restart the session to retry speech. The queue holds at most four
waiting segments plus one active synthesis; exceeding it triggers `audio_error`.
The existing translation queue and its errors are unchanged.

## Limits

KorvaTTS runs locally and returns a complete segment WAV. Cancellation discards
late results and pending work; active inference cannot be interrupted and keeps
the shared TTS engine occupied until it finishes. Speech synthesis currently
supports Vietnamese and English.
There is no remote playback acknowledgement or interruption protocol yet.
The mobile client must explicitly implement this protocol; existing HTTP clients
do not gain streaming automatically. Latency and public WSS deployment require
separate end-to-end verification.
