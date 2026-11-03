"""Translation HTTP contract checks using a local fake provider; no external text."""
import json
import os
import sys
import threading
import unittest
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ui'))
from server import Handler
import translation


class Provider(BaseHTTPRequestHandler):
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert self.path == '/translate'
        assert data == {'text': 'Xin chào', 'source': 'auto', 'target': 'en', 'mode': 'standard', 'context': []}
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps({'text': 'Hello', 'source':'vi', 'target':'en', 'model':'stub', 'latency_ms':1}).encode())

    def log_message(self, *args):
        pass


class TranslationTests(unittest.TestCase):
    def test_default_is_local(self):
        with patch.dict(os.environ):
            os.environ.pop('ONPLAY_TRANSLATE_URL', None)
            os.environ.pop('ONPLAY_TRANSLATE_API_KEY', None)
            with patch.object(translation, 'urlopen', return_value=io.BytesIO(b'{"text":"Hello","source":"vi","target":"en","model":"stub","latency_ms":1}')) as send:
                self.assertEqual(translation.translate('Xin chào', 'en'), 'Hello')
                request = send.call_args.args[0]
                self.assertEqual(request.full_url, 'http://127.0.0.1:5002/translate')
                self.assertNotIn('api_key', json.loads(request.data))

    def test_http(self):
        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as app, ThreadingHTTPServer(('127.0.0.1', 0), Provider) as provider:
            for server in (app, provider):
                threading.Thread(target=server.serve_forever, daemon=True).start()
            def post(data, kind='application/json'):
                body = json.dumps(data).encode()
                request = Request(f'http://127.0.0.1:{app.server_port}/api/translate', body, {'Content-Type': kind})
                try:
                    response = urlopen(request, timeout=5)
                except HTTPError as error:
                    response = error
                with response:
                    return response.status, json.loads(response.read())
            try:
                with patch.dict(os.environ, {'ONPLAY_TRANSLATE_URL': '', 'ONPLAY_TRANSLATE_API_KEY': 'test'}):
                    self.assertEqual(post({'text': 'Xin chào', 'target': 'en'})[0], 503)
                    for invalid in ({}, [], {'text': '', 'target': 'en'}, {'text': 'x', 'target': []}, {'text': 'x'*10001, 'target': 'en'}):
                        self.assertEqual(post(invalid)[0], 400)
                    self.assertEqual(post({}, 'text/plain')[0], 415)
                    self.assertEqual(post({'text': 'x'*66000})[0], 413)
                    os.environ['ONPLAY_TRANSLATE_URL'] = f'http://127.0.0.1:{provider.server_port}'
                    self.assertEqual(post({'text': 'Xin chào', 'target': 'en'}), (200, {'text': 'Hello', 'source':'vi', 'target':'en', 'model':'stub', 'latency_ms':1}))
                    with patch.object(Provider, 'do_POST', lambda self: (self.send_response(200), self.end_headers(), self.wfile.write(b'{}'))):
                        self.assertEqual(post({'text': 'Xin chào', 'target': 'en'})[0], 503)
            finally:
                app.shutdown()
                provider.shutdown()


if __name__ == '__main__':
    unittest.main()
