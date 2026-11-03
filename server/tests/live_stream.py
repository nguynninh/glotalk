"""No upload or full-file conversion: feed PCM incrementally and expect partials."""
import json
import pathlib
import threading
import time
import wave
from websockets.sync.client import connect
root = pathlib.Path(__file__).resolve().parents[1]
with connect('ws://127.0.0.1:8766', origin='http://127.0.0.1:8765', proxy=None) as socket:
    socket.send(json.dumps({'threads':2,'chunk':100}))
    while json.loads(socket.recv())['type'] != 'ready':
        pass
    events=[]
    start=time.monotonic()
    def receive():
        for message in socket:
            event=json.loads(message)
            events.append((time.monotonic()-start,event))
            if event['type'] in ('done','error'):
                break
    reader=threading.Thread(target=receive)
    reader.start()
    with wave.open(str(root/'models/vi.wav')) as audio:
        while True:
            chunk=audio.readframes(1600)
            if not chunk:break
            socket.send(chunk)
            time.sleep(len(chunk)/32000)
    # Partials must exist while the PCM source remains open, before end is sent.
    assert any(e['type']=='partial' for _,e in events), events
    socket.send(json.dumps({'type':'end'}))
    reader.join(timeout=10)
    assert not reader.is_alive()
    assert events[-1][1]['type']=='done',events
    assert any(e['type']=='final' and 'đất nước' in e['text'] for _,e in events),events
    first=next(t for t,e in events if e['type']=='partial')
    assert first<2,first
    print('PASS: incremental stdin/socket PCM, first partial %.2f s after ready, final after end' % first)
