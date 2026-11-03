"""TTS HTTP validation, failure handling and optional real KorvaTTS check."""
import io
import json
import sys
import threading
import unittest
import wave
import numpy as np
from pathlib import Path
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ui'))
import tts
from server import Handler


class SpeechTests(unittest.TestCase):
    def test_engine_cached(self):
        tts.engine.cache_clear()
        try:
            with patch.object(tts, 'TTS') as model:
                self.assertIs(tts.engine(), model.return_value)
                self.assertIs(tts.engine(), model.return_value)
                model.assert_called_once()
        finally:
            tts.engine.cache_clear()

    def test_validation_and_lock(self):
        for text, language in [('', 'vi'), ('x'*3001, 'vi'), ('x', []), ('x\x00', 'en')]:
            with self.assertRaises(ValueError):
                tts.synthesize(text, language)
        tts.LOCK.acquire()
        try:
            with self.assertRaises(tts.SpeechError):
                tts.synthesize('hello', 'en')
        finally:
            tts.LOCK.release()
        with patch.object(tts, 'engine', side_effect=RuntimeError):
            with self.assertRaises(tts.SpeechError):
                tts.synthesize('hello', 'en')
        self.assertFalse(tts.LOCK.locked())

    def test_synthesis_returns_korva_wav(self):
        with patch.object(tts, 'engine') as model:
            model.return_value.synthesize.return_value = (np.zeros(100, dtype=np.float32), 0.01)
            with wave.open(io.BytesIO(tts.synthesize('Xin chào', 'vi'))) as audio:
                self.assertEqual((audio.getnchannels(), audio.getsampwidth(), audio.getframerate()), (1, 2, 44100))
                self.assertEqual(audio.getnframes(), 100)
            model.return_value.synthesize.assert_called_once_with(
                'Xin chào', voice='khanh_vy', lang='vi', total_steps=16)

    def test_http(self):
        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as app:
            threading.Thread(target=app.serve_forever, daemon=True).start()
            def post(data, kind='application/json'):
                req = Request(f'http://127.0.0.1:{app.server_port}/api/tts', json.dumps(data).encode(), {'Content-Type': kind})
                try:
                    response = urlopen(req, timeout=5)
                except HTTPError as error:
                    response = error
                with response:
                    return response.status, response.headers['Content-Type'], response.read()
            try:
                with patch('server.synthesize', return_value=b'RIFF-test') as synth:
                    self.assertEqual(post({'text':'Xin chào', 'language':'vi'}), (200, 'audio/wav', b'RIFF-test'))
                    synth.assert_called_once_with('Xin chào', 'vi')
                    self.assertEqual(post([])[0], 400)
                    self.assertEqual(post({}, 'text/plain')[0], 415)
                    self.assertEqual(post({'text':'x'*33000})[0], 413)
                with patch('server.synthesize', side_effect=tts.SpeechError('unavailable')):
                    self.assertEqual(post({'text':'hello','language':'en'})[0], 503)
                self.assertEqual(post({'text':'','language':'en'})[0], 400)
            finally:
                app.shutdown()

    @unittest.skipUnless(__import__('os').environ.get('ONPLAY_TEST_REAL_TTS') == '1', 'requires downloaded KorvaTTS assets')
    def test_real_vietnamese_and_english(self):
        for text, language in [('Xin chào, đây là bản dịch.', 'vi'), ('Hello, this is the translation.', 'en')]:
            with wave.open(io.BytesIO(tts.synthesize(text, language))) as audio:
                self.assertEqual((audio.getnchannels(), audio.getsampwidth(), audio.getframerate()), (1, 2, 44100))
                self.assertGreater(audio.getnframes(), 0)


if __name__ == '__main__':
    unittest.main()
