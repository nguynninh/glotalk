"""One-request audio-to-audio HTTP contract, reusing the WAV recognition path."""
import io
import json
import sys
import threading
import unittest
import wave
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ui'))
import server


def wav():
    body = io.BytesIO()
    with wave.open(body, 'wb') as audio:
        audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        audio.writeframes(bytes(3200))
    return body.getvalue()


class AudioTranslationTests(unittest.TestCase):
    def test_pipeline(self):
        with ThreadingHTTPServer(('127.0.0.1', 0), server.Handler) as app:
            threading.Thread(target=app.serve_forever, daemon=True).start()
            def post(path='/api/translate-audio?target=en', body=None, kind='audio/wav'):
                request = Request(f'http://127.0.0.1:{app.server_port}'+path,
                                  wav() if body is None else body, {'Content-Type':kind})
                try:
                    response = urlopen(request, timeout=5)
                except HTTPError as error:
                    response = error
                with response:
                    if response.status in (200, 204) and path.startswith('/api/translate-audio?'):
                        timing = response.headers['Server-Timing']
                        self.assertIn('stt;dur=', timing)
                        if response.status == 200:
                            self.assertIn('translate;dur=', timing)
                            self.assertIn('tts;dur=', timing)
                    return response.status, response.headers['Content-Type'], response.read()
            result = SimpleNamespace(returncode=0, stdout='SPEAKER 0.0 1.0 1 Xin chào\nSPEAKER 1.0 2.0 2 Bạn khỏe không?\n')
            try:
                with patch.object(server, 'SEGMENTATION') as model, patch.object(server, 'valid_embedding', return_value=True), patch.object(server.subprocess, 'run', return_value=result) as offline, patch.object(server, 'recognize', return_value='Xin chào\nBạn khỏe không?') as recognize, patch.object(server, 'translate_result', return_value={'text':'Hello. How are you?'}) as translate, patch.object(server, 'synthesize', return_value=b'RIFF-output') as speech:
                    model.is_file.return_value=True
                    self.assertEqual(post(), (200, 'audio/wav', b'RIFF-output'))
                    recognize.assert_called_once_with(bytes(3200))
                    offline.assert_not_called()
                    translate.assert_called_once_with('Xin chào\nBạn khỏe không?', 'en')
                    speech.assert_called_once_with('Hello. How are you?', 'en')
                    status, _, data = post('/api/transcribe')
                    self.assertEqual(status, 200)
                    self.assertEqual(len(json.loads(data)['segments']), 2)
                    translate.assert_called_once()
                    for query in ('', '?target=no', '?target=en&target=vi', '?target='):
                        self.assertEqual(post('/api/translate-audio'+query)[0], 400)
                    self.assertEqual(post(body=b'bad')[0], 422)
                    self.assertEqual(post(kind='audio/mpeg')[0], 415)
                    server.LOCK.acquire()
                    try:
                        self.assertEqual(post()[0],409)
                    finally:
                        server.LOCK.release()
                    recognize.return_value=''
                    self.assertEqual(post(),(204,'audio/wav',b''))
                    speech.assert_called_once()
                    recognize.return_value='Xin chào'
                    for failure, status in [(OSError('worker stopped'), 500), (subprocess.TimeoutExpired('worker', 90), 504)]:
                        recognize.side_effect=failure
                        self.assertEqual(post()[0], status)
                        self.assertFalse(server.LOCK.locked())
                    recognize.side_effect=None
                    recognize.return_value='x'*10001
                    self.assertEqual(post()[0],422)
                    recognize.return_value='Xin chào'
                    translate.side_effect=server.TranslationError('Translation unavailable')
                    self.assertEqual(post()[0],503)
                    self.assertFalse(server.LOCK.locked())
                    translate.side_effect=None
                    translate.return_value={'text':'x'*3001}
                    self.assertEqual(post()[0],422)
                    translate.return_value={'text':'Hello'}
                    speech.side_effect=server.SpeechError('Speech unavailable')
                    self.assertEqual(post()[0],503)
                    self.assertFalse(server.LOCK.locked())
            finally:
                app.shutdown()


if __name__ == '__main__':
    unittest.main()
