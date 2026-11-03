"""Streaming UI and bounded WAV upload API."""
import json
import io
import socket
import subprocess
import tempfile
import wave
import pathlib
import threading
import errno
import time
from recognition import recognize
from translation import translate_result, TranslationError, TARGETS
from tts import synthesize, SpeechError, engine
from urllib.request import urlopen
from urllib.parse import urlsplit, parse_qs
from metrics import start_monitor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ws_stream import start_stream_server, SEGMENTATION, EMBEDDING, valid_embedding

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def reply(self, status, body, kind='application/json', timings=None):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        if timings:
            self.send_header('Server-Timing', ', '.join(f'{name};dur={duration:.2f}' for name, duration in timings.items()))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        if self.path in ('/metrics', '/metrics?full=1', '/api/metrics'):
            target = '/api/metrics' if self.path == '/api/metrics' else '/' + self.path.removeprefix('/metrics')
            try:
                with urlopen('http://127.0.0.1:8767' + target, timeout=5) as response:
                    self.reply(200, response.read(), response.headers['Content-Type'])
            except OSError:
                self.reply(502, {'error': 'Monitor unavailable'})
        elif self.path == '/':
            self.reply(200, (ROOT / 'ui/index.html').read_bytes(), 'text/html; charset=utf-8')
        elif self.path in ('/spectrum.mjs', '/live.mjs', '/pcm-worklet.js'):
            self.reply(200, (ROOT / 'ui' / self.path[1:]).read_bytes(), 'text/javascript; charset=utf-8')
        elif self.path == '/api/sample':
            self.reply(200, (ROOT / 'models/vi.wav').read_bytes(), 'audio/wav')
        else:
            self.reply(404, {'error':'Không tìm thấy trang.'})
    def do_POST(self):
        self.close_connection = True
        if self.path == '/api/tts':
            return self.speech()
        if self.path == '/api/translate':
            return self.translate()
        route = urlsplit(self.path)
        translated_audio = route.path == '/api/translate-audio'
        if self.path != '/api/transcribe' and not translated_audio:
            return self.reply(404, {'error': 'Unknown endpoint'})
        if translated_audio:
            query = parse_qs(route.query, keep_blank_values=True)
            targets = query.get('target', [])
            if set(query) != {'target'} or len(targets) != 1 or targets[0] not in TARGETS:
                return self.reply(400, {'error': 'Provide one supported target language: ?target=vi'})
            target = targets[0]
        if self.headers.get('Transfer-Encoding'):
            return self.reply(400, {'error': 'Content-Length required; chunked uploads unsupported'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            return self.reply(400, {'error': 'Invalid Content-Length'})
        if not 0 < length <= 2 * 1024 * 1024:
            return self.reply(413, {'error': 'Body must be 1 byte to 2 MiB'})
        if self.headers.get('Content-Type', '').split(';')[0].strip().lower() != 'audio/wav':
            return self.reply(415, {'error': 'Send raw WAV bytes with Content-Type: audio/wav'})
        if not LOCK.acquire(blocking=False):
            return self.reply(409, {'error': 'Engine busy; retry after the current session finishes'})
        try:
            self.connection.settimeout(30)
            body = self.rfile.read(length)
            if len(body) != length:
                return self.reply(400, {'error': 'Incomplete upload'})
            try:
                with wave.open(io.BytesIO(body), 'rb') as audio:
                    frames = audio.getnframes()
                    if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 16000, 'NONE') or not 0 < frames <= 960000:
                        raise ValueError()
                    pcm = audio.readframes(frames)
                    if len(pcm) != frames * 2:
                        raise ValueError()
            except (wave.Error, EOFError, ValueError):
                return self.reply(422, {'error': 'Expected valid PCM16 mono 16000 Hz WAV, maximum 60 seconds'})
            if translated_audio:
                started = time.monotonic()
                text = recognize(pcm)
                timings = {'stt': (time.monotonic() - started) * 1000}
                if not text:
                    return self.reply(204, b'', 'audio/wav', timings)
                if len(text) > 10000:
                    return self.reply(422, {'error': 'Transcript exceeds translation limit; send shorter audio'})
                started = time.monotonic()
                translated = translate_result(text, target)['text']
                timings['translate'] = (time.monotonic() - started) * 1000
                if len(translated) > 3000:
                    return self.reply(422, {'error': 'Translation exceeds speech limit; send shorter audio'})
                started = time.monotonic()
                output = synthesize(translated, target)
                timings['tts'] = (time.monotonic() - started) * 1000
                return self.reply(200, output, 'audio/wav', timings)
            with tempfile.TemporaryDirectory(prefix='onplay-post-') as directory:
                path = pathlib.Path(directory) / 'audio.wav'
                path.write_bytes(body)
                if not SEGMENTATION.is_file() or not valid_embedding():
                    return self.reply(503, {'error': 'Speaker models unavailable or invalid'})
                model = ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10'
                args = [str(model / name) for name in (
                    'encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx',
                    'decoder-epoch-75-avg-11-chunk-16-left-128.onnx',
                    'joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'tokens.txt')]
                result = subprocess.run([str(ROOT / 'build/onplay-speakers'), *args,
                                         str(SEGMENTATION), str(EMBEDDING), str(path), '2'],
                                        capture_output=True, text=True, timeout=90)
            if result.returncode:
                return self.reply(500, {'error': 'Recognition failed'})
            segments = []
            for line in result.stdout.splitlines():
                parts = line.split(' ', 4)
                if len(parts) == 5 and parts[0] == 'SPEAKER':
                    segments.append({'start': float(parts[1]), 'end': float(parts[2]),
                                     'speaker': int(parts[3]) - 1, 'text': parts[4]})
            self.reply(200, {'type': 'speakers', 'segments': segments})
        except ValueError:
            self.reply(422, {'error': 'Invalid recognition or translated speech result'})
        except (TranslationError, SpeechError) as error:
            self.reply(503, {'error': str(error)})
        except socket.timeout:
            self.reply(408, {'error': 'Upload timed out'})
        except subprocess.TimeoutExpired:
            self.reply(504, {'error': 'Recognition timed out'})
        except OSError:
            self.reply(500, {'error': 'Recognition service unavailable'})
        finally:
            LOCK.release()

    def speech(self):
        if self.headers.get('Transfer-Encoding'):
            return self.reply(400, {'error': 'Content-Length required'})
        if self.headers.get('Content-Type', '').split(';')[0].strip().lower() != 'application/json':
            return self.reply(415, {'error': 'Expected application/json'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 32768:
                return self.reply(413, {'error': 'Speech body must be 1 byte to 32 KiB'})
            self.connection.settimeout(30)
            body = self.rfile.read(length)
            if len(body) != length:
                return self.reply(400, {'error': 'Incomplete request'})
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError('Expected a JSON object')
            audio = synthesize(data.get('text'), data.get('language'))
        except (ValueError, UnicodeError) as error:
            return self.reply(400, {'error': str(error)})
        except SpeechError as error:
            return self.reply(503, {'error': str(error)})
        except (socket.timeout, OSError):
            return self.reply(408, {'error': 'Speech request interrupted or timed out'})
        self.reply(200, audio, 'audio/wav')

    def translate(self):
        if self.headers.get('Transfer-Encoding'):
            return self.reply(400, {'error': 'Content-Length required'})
        if self.headers.get('Content-Type', '').split(';')[0].strip().lower() != 'application/json':
            return self.reply(415, {'error': 'Expected application/json'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 65536:
                return self.reply(413, {'error': 'Translation body must be 1 byte to 64 KiB'})
            self.connection.settimeout(30)
            body = self.rfile.read(length)
            if len(body) != length:
                return self.reply(400, {'error': 'Incomplete request'})
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError()
            result = translate_result(data.get('text'), data.get('target'), data.get('source', 'auto'),
                                      data.get('mode', 'standard'), data.get('context', []))
            self.reply(200, result)
        except (ValueError, UnicodeError):
            self.reply(400, {'error': 'Expected non-empty text (maximum 10000 characters) and a supported target'})
        except TranslationError as error:
            self.reply(503, {'error': str(error)})
        except (socket.timeout, OSError):
            self.reply(408, {'error': 'Translation request interrupted or timed out'})

if __name__ == '__main__':
    try:
        resource_server = start_monitor()
    except OSError as error:
        if error.errno != errno.EADDRINUSE:
            raise
    streaming_server = start_stream_server(LOCK)
    try:
        recognize(bytes(3200))
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print('Audio recognition warmup failed; check runtime and model files.', flush=True)
    try:
        engine()
    except Exception:
        print('KorvaTTS initialization failed; check model assets and dependencies.', flush=True)
    print('ONPlay STT: http://127.0.0.1:8765', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
