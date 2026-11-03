"""Real WebSocket lifecycle with deterministic recognition and translation stubs."""
import base64
import io
import json
import pathlib
import sys
import threading
import unittest
from unittest.mock import patch
from websockets.sync.client import connect
from websockets.sync.server import serve

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'ui'))
import ws_stream
from translation import TranslationError


EXPECT_LOW_LATENCY = True

class Process:
    def __init__(self, args, **kwargs):
        assert ('--low-latency' in args) == EXPECT_LOW_LATENCY
        self.finished = threading.Event()
        self.stdin = self.Input(self.finished)
        self.stdout = self.output()
        kwargs['stderr'].write('audio_s=1 processing_s=1 rtf=1 wall_s=1')
        kwargs['stderr'].flush()

    class Input(io.BytesIO):
        def __init__(self, finished):
            super().__init__()
            self.finished = finished

        def close(self):
            self.finished.set()
            super().close()

    def output(self):
        yield b'READY\n'
        yield b'PARTIAL tentative\n'
        yield b'FINAL   first\n'
        yield b'FINAL   second\n'
        self.finished.wait(5)

    def wait(self):
        self.finished.set()
        return 0

    def poll(self):
        return 0 if self.finished.is_set() else None

    def kill(self):
        self.finished.set()


class SocketTranslationTests(unittest.TestCase):
    def test_speech_does_not_block_translation(self):
        entered, release = threading.Event(), threading.Event()
        lock = threading.Lock()
        def translate(text, target, *args):
            return {'text':text, 'source':'en', 'model':'stub', 'latency_ms':1}
        def speech(text, target):
            entered.set()
            assert release.wait(5)
            return b'RIFF-' + text.encode()
        with patch.object(ws_stream.subprocess, 'Popen', Process), patch.object(ws_stream, 'translate_result', translate), patch.object(ws_stream, 'synthesize', speech):
            with serve(ws_stream.make_handler(lock), '127.0.0.1', 0) as server:
                threading.Thread(target=server.serve_forever, daemon=True).start()
                try:
                    with connect(f'ws://127.0.0.1:{server.socket.getsockname()[1]}', proxy=None) as socket:
                        socket.send(json.dumps({'target_language':'vi', 'speech':True}))
                        events=[]
                        while len([e for e in events if e['type']=='translation']) < 2:
                            events.append(json.loads(socket.recv(timeout=5)))
                        self.assertTrue(entered.wait(2))
                        self.assertFalse(any(e['type']=='audio' for e in events))
                        socket.send(json.dumps({'type':'end'}))
                        release.set()
                        audio=[]
                        while len(audio)<2:
                            event=json.loads(socket.recv(timeout=5))
                            if event['type']=='audio': audio.append(event)
                        self.assertEqual([e['id'] for e in audio], [0,1])
                        self.assertEqual([base64.b64decode(e['data']) for e in audio], [b'RIFF-first',b'RIFF-second'])
                        self.assertEqual(json.loads(socket.recv(timeout=5))['type'],'done')
                    self.assertTrue(lock.acquire(timeout=2)); lock.release()
                    with patch.object(ws_stream, 'synthesize', side_effect=ws_stream.SpeechError('Unavailable')):
                        with connect(f'ws://127.0.0.1:{server.socket.getsockname()[1]}', proxy=None) as socket:
                            socket.send(json.dumps({'target_language':'vi', 'speech':True}))
                            socket.send(json.dumps({'type':'end'}))
                            events=[json.loads(message) for message in socket]
                            self.assertEqual(events[-1]['type'],'done')
                            self.assertEqual(len([e for e in events if e['type']=='translation']),2)
                            self.assertTrue(any(e['type']=='audio_error' for e in events))
                finally:
                    release.set()
                    server.shutdown()

    def test_context_is_per_connection(self):
        calls=[]
        def translate(text,target,source,mode,context):
            calls.append((text,source,mode,list(context)))
            return {'text':'translated '+text,'source':'en','target':target,'model':'qwen','latency_ms':1}
        lock=threading.Lock()
        with patch.object(ws_stream.subprocess,'Popen',Process), patch.object(ws_stream,'translate_result',translate):
            with serve(ws_stream.make_handler(lock),'127.0.0.1',0) as server:
                threading.Thread(target=server.serve_forever,daemon=True).start()
                try:
                    for _ in range(2):
                        with connect(f'ws://127.0.0.1:{server.socket.getsockname()[1]}',proxy=None) as socket:
                            socket.send(json.dumps({'target_language':'vi','source_language':'en','translation_mode':'context'}))
                            socket.send(json.dumps({'type':'end'}))
                            events=[json.loads(message) for message in socket]
                            self.assertEqual(events[-1]['type'],'done')
                        self.assertTrue(lock.acquire(timeout=2));lock.release()
                    self.assertEqual(calls,[('first','en','context',[]),('second','en','context',['first'])]*2)
                finally:server.shutdown()

    def test_lifecycle(self):
        entered, release = threading.Event(), threading.Event()
        calls = []
        lock = threading.Lock()

        def translate(text, target, source='auto', mode='standard', context=None):
            calls.append((text, target))
            if text == 'first':
                entered.set()
                assert release.wait(5)
            return {'text':'translated ' + text, 'source':'en', 'model':'stub', 'latency_ms':1}

        with patch.object(ws_stream.subprocess, 'Popen', Process), patch.object(ws_stream, 'translate_result', translate):
            with serve(ws_stream.make_handler(lock), '127.0.0.1', 0) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                port = server.socket.getsockname()[1]
                try:
                    with connect(f'ws://127.0.0.1:{port}', proxy=None) as socket:
                        socket.send(json.dumps({'target_language':'en'}))
                        events=[]
                        while len([e for e in events if e['type']=='final']) < 2:
                            events.append(json.loads(socket.recv(timeout=5)))
                        self.assertTrue(entered.wait(2))
                        self.assertEqual([e['id'] for e in events if e['type']=='final'], [0,1])
                        self.assertFalse(any(e['type']=='translation' for e in events))
                        release.set()
                        for index in range(2):
                            event=json.loads(socket.recv(timeout=5))
                            self.assertEqual((event['type'],event['id'],event['revision']), ('translation',index,'live'))
                            self.assertEqual(event['source']['text'], ['first','second'][index])
                            self.assertEqual(event['translation']['language'],'en')
                        # Translation arrives while PCM input is still open, before end/done.
                        socket.send(json.dumps({'type':'end'}))
                        self.assertEqual(json.loads(socket.recv(timeout=5))['type'],'done')
                    self.assertEqual(calls,[('first','en'),('second','en')])
                    self.assertTrue(lock.acquire(timeout=2)); lock.release()
                    # No target keeps the existing recognition-only behavior.
                    global EXPECT_LOW_LATENCY
                    EXPECT_LOW_LATENCY = False
                    with connect(f'ws://127.0.0.1:{port}', proxy=None) as socket:
                        socket.send('{}')
                        socket.send(json.dumps({'type':'end'}))
                        events=[json.loads(message) for message in socket]
                        self.assertEqual(events[-1]['type'],'done')
                        self.assertFalse(any(e['type'].startswith('translation') for e in events))
                    self.assertEqual(len(calls),2)
                    self.assertTrue(lock.acquire(timeout=2)); lock.release()
                    EXPECT_LOW_LATENCY = True
                    # Provider failure preserves recognition and terminates normally.
                    with patch.object(ws_stream, 'translate_result', side_effect=TranslationError('Unavailable')):
                        with connect(f'ws://127.0.0.1:{port}', proxy=None) as socket:
                            socket.send(json.dumps({'target_language':'en'}))
                            socket.send(json.dumps({'type':'end'}))
                            events=[json.loads(message) for message in socket]
                            self.assertEqual(events[-1]['type'],'done')
                            self.assertEqual(len([e for e in events if e['type']=='final']),2)
                            self.assertTrue(any(e['type']=='translation_error' for e in events))
                    self.assertTrue(lock.acquire(timeout=2)); lock.release()
                    # Disconnect while the provider is blocked: release the session,
                    # skip queued translations, and discard the late provider result.
                    entered.clear(); release.clear(); calls.clear()
                    with connect(f'ws://127.0.0.1:{port}', proxy=None) as socket:
                        socket.send(json.dumps({'target_language':'en'}))
                        while json.loads(socket.recv(timeout=5))['type']!='final':
                            pass
                        self.assertTrue(entered.wait(2))
                    self.assertTrue(lock.acquire(timeout=2)); lock.release()
                    release.set()
                    self.assertEqual(calls,[('first','en')])
                finally:
                    release.set()
                    server.shutdown()


if __name__ == '__main__':
    unittest.main()
