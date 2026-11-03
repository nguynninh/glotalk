"""Integration check against the running HTTP server and real model."""
import http.client
import io
import wave
import json
from pathlib import Path

def post(body, kind='audio/wav', length=None):
    connection = http.client.HTTPConnection('127.0.0.1', 8765, timeout=100)
    headers = {'Content-Type': kind, 'Content-Length': str(len(body) if length is None else length)}
    connection.request('POST', '/api/transcribe', body, headers)
    response = connection.getresponse()
    result = response.status, json.loads(response.read())
    connection.close()
    return result

assert post(b'bad', 'application/json')[0] == 415
assert post(b'bad')[0] == 422
assert post(b'')[0] == 413
assert post(b'', length=2*1024*1024+1)[0] == 413
sample = Path(__file__).resolve().parents[1] / 'models/vi.wav'
status, result = post(sample.read_bytes())
assert status == 200, (status, result)
assert result['type'] == 'speakers', result
segments = result['segments']
assert any('đất nước' in segment['text'] for segment in segments), result
assert segments[0]['speaker'] == 0, result
assert all(0 <= segment['start'] < segment['end'] and segment['speaker'] >= 0 for segment in segments), result
silence = io.BytesIO()
with wave.open(silence, 'wb') as audio:
    audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
    audio.writeframes(bytes(32000 * 3))
assert post(silence.getvalue()) == (200, {'type': 'speakers', 'segments': []})
print('PASS: POST content type, malformed WAV, size limits and real Vietnamese recognition')
