"""Real-model checks for offline speaker labels and the local socket lifecycle."""
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import wave
from websockets.sync.client import connect
from websockets.sync.server import serve

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ui'))
import ws_stream


def pcm(path):
    with wave.open(str(path)) as audio:
        assert audio.getparams()[:3] == (1, 2, 16000)
        return audio.readframes(audio.getnframes())


lock = threading.Lock()
with serve(ws_stream.make_handler(lock), '127.0.0.1', 0, max_size=32000) as server:
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    url = 'ws://127.0.0.1:' + str(server.socket.getsockname()[1])

    def run(audio, speakers=True, expect_live=False):
        events = []
        with connect(url, proxy=None) as socket:
            socket.send(json.dumps({'speakers':speakers}))
            while True:
                event = json.loads(socket.recv(timeout=20))
                events.append(event)
                if event['type'] in ('ready', 'error'):
                    break
            assert events[-1]['type'] == 'ready', events
            for pos in range(0, len(audio), 3200):
                socket.send(audio[pos:pos+3200])
            if expect_live:
                while True:
                    event = json.loads(socket.recv(timeout=20))
                    events.append(event)
                    assert event['type'] not in ('done', 'error'), events
                    if event['type'] == 'partial' and event.get('speaker', 0) > 0:
                        break
            socket.send(json.dumps({'type':'end'}))
            for message in socket:
                events.append(json.loads(message))
            assert events[-1]['type'] == 'done', events
        assert lock.acquire(timeout=10), 'Session lock leaked'
        lock.release()
        return events

    try:
        vi = pcm(ROOT / 'models/vi.wav')
        en = pcm(ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/test_wavs/en.wav')
        events = run(vi + bytes(32000 * 3) + en + bytes(32000 * 3) + vi, expect_live=True)
        segments = next(e['segments'] for e in events if e['type'] == 'speakers')
        assert len({s['speaker'] for s in segments}) == 2, segments
        vietnamese = [s for s in segments if 'đất nước' in s['text']]
        english = [s for s in segments if 'AFTER EARLY' in s['text']]
        assert len(vietnamese) == 2 and len(english) == 1, segments
        assert vietnamese[0]['speaker'] == vietnamese[1]['speaker'] != english[0]['speaker'], segments
        assert all(0 <= s['start'] < s['end'] for s in segments), segments
        assert any(e['type'] == 'final' for e in events), events
        assert events[-1]['metrics']['speaker_s'] > 0
        live_vi = [e for e in events if e['type'] == 'final' and 'đất nước' in e['text']]
        assert len(live_vi) == 2 and live_vi[0]['speaker'] == live_vi[1]['speaker'] > 0, events
        # The existing ASR handles English -> Vietnamese; Vietnamese -> English can lose text.
        live_events = run(en + bytes(32000 * 3) + vi, expect_live=True)
        live_finals = [e for e in live_events if e['type'] == 'final']
        assert len(live_finals) == 2 and live_finals[0]['speaker'] != live_finals[1]['speaker'], live_events
        assert all(e['speaker'] > 0 for e in live_finals), live_events
        print('PASS: real Vietnamese/English/Vietnamese voices -> two speakers, returning voice keeps its label')

        events = run(vi, speakers=False)
        assert not any(e['type'] == 'speakers' for e in events), events
        assert any(e['type'] == 'final' and 'đất nước' in e['text'] for e in events)
        events = run(bytes(32000 * 3))
        assert not any(e['type'] == 'speakers' for e in events), events
        print('PASS: disabled mode keeps normal STT; silence produces no invented speaker transcript')

        original_model = ws_stream.EMBEDDING
        ws_stream.EMBEDDING = ROOT / 'models/missing-speaker.onnx'
        events = run(vi)
        assert any(e['type'] == 'warning' for e in events)
        assert any(e['type'] == 'final' for e in events)
        ws_stream.EMBEDDING = original_model
        ws_stream.EMBEDDING = ROOT / 'models/vi.wav'
        events = run(vi)
        assert any(e['type'] == 'warning' and 'thất bại' in e['text'] for e in events)
        assert any(e['type'] == 'final' for e in events)
        assert not any(e['type'] == 'speakers' for e in events)
        ws_stream.EMBEDDING = original_model
        original_limit = ws_stream.MAX_SPEAKER_BYTES
        ws_stream.MAX_SPEAKER_BYTES = 3200
        events = run(vi, expect_live=True)
        assert any(e['type'] == 'warning' and '10 phút' in e['text'] for e in events)
        assert any(e['type'] == 'final' for e in events)
        assert not any(e['type'] == 'speakers' for e in events)
        ws_stream.MAX_SPEAKER_BYTES = original_limit
        print('PASS: missing/invalid models and preview limit preserve the original transcript')

        with connect(url, proxy=None) as socket:
            socket.send(json.dumps({'speakers':True}))
            while json.loads(socket.recv(timeout=20))['type'] != 'ready':
                pass
            for pos in range(0, len(vi), 3200):
                socket.send(vi[pos:pos+3200])
            socket.send(json.dumps({'type':'end'}))
            while True:
                event = json.loads(socket.recv(timeout=20))
                if event['type'] == 'status' and 'bản chữ gốc' in event['text']:
                    break
        assert lock.acquire(timeout=20), 'Cancellation did not release the engine'
        lock.release()
        events = run(vi, speakers=False)
        assert events[-1]['type'] == 'done'
        print('PASS: cancellation during diarization releases engine for the next session')

        model = ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10'
        args = [str(model / name) for name in (
            'encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx',
            'decoder-epoch-75-avg-11-chunk-16-left-128.onnx',
            'joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'tokens.txt')]
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'long.wav'
            with wave.open(str(path), 'wb') as audio:
                audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                audio.writeframes(bytes(32000 * 601))
            result = subprocess.run([str(ROOT / 'build/onplay-speakers'), *args,
                str(ws_stream.SEGMENTATION), str(ws_stream.EMBEDDING), str(path), '2'],
                capture_output=True, text=True, timeout=10)
            assert result.returncode != 0 and '10 minutes' in result.stderr, result.stderr
        print('PASS: native duration bound rejects before offline model inference')
    finally:
        server.shutdown()
        thread.join()
