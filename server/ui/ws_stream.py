"""Bounded PCM bridge: browser decoder -> stdin -> C++ -> partial transcripts."""
import base64
import contextlib
import os
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import threading
import time
import wave
import queue
from translation import translate_result, TranslationError, TARGETS
from tts import synthesize, SpeechError
from websockets.sync.server import serve
from websockets.exceptions import ConnectionClosed

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEGMENTATION = ROOT / 'models/speakers/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx'
EMBEDDING = ROOT / 'models/speakers/embedding.onnx'
MAX_SPEAKER_BYTES = 16000 * 2 * 600

def valid_embedding():
    if not EMBEDDING.is_file():
        return False
    digest = hashlib.sha256()
    with EMBEDDING.open('rb') as model:
        for block in iter(lambda: model.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest() == 'f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11'

def make_handler(lock):
    def stream(connection):
        process = None
        acquired = False
        pump = None
        recording = None
        resources = contextlib.ExitStack()
        input_finished = False
        cancelled = threading.Event()
        translations = queue.Queue(maxsize=64)
        translator = None
        translation_failed = threading.Event()
        speech_jobs = queue.Queue(maxsize=4)
        speech_worker = None
        speech_failed = threading.Event()
        segment_id = 0
        def emit(event):
            try:
                connection.send(json.dumps(event, ensure_ascii=False))
            except (ConnectionClosed, OSError):
                cancelled.set()
                raise
        def enqueue(event, revision='live'):
            if not target or not event['text'].strip() or translation_failed.is_set():
                return
            try:
                translations.put_nowait((event.copy(), revision, time.monotonic()))
            except queue.Full:
                translation_failed.set()
                emit({'type':'translation_error', 'text':'Dịch không theo kịp audio (64 đoạn đang chờ). Hãy dịch lại bản chữ sau khi kết thúc.'})
        def speech_output():
            while not cancelled.is_set():
                try:
                    event_id, text = speech_jobs.get(timeout=0.2)
                except queue.Empty:
                    continue
                try:
                    if cancelled.is_set() or speech_failed.is_set():
                        continue
                    started = time.monotonic()
                    audio = synthesize(text, target)
                    if not cancelled.is_set():
                        emit({'type':'audio', 'id':event_id, 'revision':'live',
                              'language':target, 'content_type':'audio/wav',
                              'encoding':'base64', 'data':base64.b64encode(audio).decode('ascii'),
                              'tts_ms':round((time.monotonic() - started) * 1000, 2)})
                except (SpeechError, ValueError) as error:
                    speech_failed.set()
                    if not cancelled.is_set():
                        emit({'type':'audio_error', 'text':str(error)})
                except (ConnectionClosed, OSError):
                    cancelled.set()
                finally:
                    speech_jobs.task_done()
        def translation_output():
            history = {'live': [], 'refined': []}
            while not cancelled.is_set():
                try:
                    event, revision, queued_at = translations.get(timeout=0.2)
                except queue.Empty:
                    continue
                try:
                    if translation_failed.is_set():
                        continue
                    queue_ms = (time.monotonic() - queued_at) * 1000
                    result = translate_result(event['text'], target, source, mode,
                                              history[revision][-5:] if mode == 'context' else [])
                    result['queue_ms'] = round(queue_ms, 2)
                    result['total_ms'] = round((time.monotonic() - queued_at) * 1000, 2)
                    result['within_budget'] = result['total_ms'] <= 500
                    history[revision].append(event['text'])
                    history[revision] = history[revision][-5:]
                    if not cancelled.is_set():
                        emit({'type':'translation', 'id':event['id'], 'revision':revision,
                              **{key:event[key] for key in ('speaker', 'start', 'end') if key in event},
                              'source':{'text':event['text'], 'language':result['source']},
                              'translation':{**result, 'language':target}})
                        if speech and revision == 'live' and not speech_failed.is_set():
                            try:
                                speech_jobs.put_nowait((event['id'], result['text']))
                            except queue.Full:
                                speech_failed.set()
                                emit({'type':'audio_error', 'text':'Speech cannot keep up (4 segments waiting); start a new session.'})
                except (TranslationError, ValueError) as error:
                    translation_failed.set()
                    if not cancelled.is_set():
                        emit({'type':'translation_error', 'text':str(error)})
                except (ConnectionClosed, OSError):
                    cancelled.set()
                finally:
                    translations.task_done()
        try:
            config = json.loads(connection.recv(timeout=10))
            if not isinstance(config, dict):
                raise ValueError('Cấu hình không hợp lệ.')
            target = config.get('target_language', '')
            if not isinstance(target, str) or target and target not in TARGETS:
                raise ValueError('Ngôn ngữ dịch không hợp lệ.')
            source, mode = config.get('source_language', 'auto'), config.get('translation_mode', 'standard')
            if not isinstance(source, str) or source not in TARGETS | {'auto'} or mode not in ('standard', 'context'):
                raise ValueError('Cấu hình dịch không hợp lệ.')
            speech = config.get('speech', False)
            if not isinstance(speech, bool) or speech and not target:
                raise ValueError('speech requires a target_language and a boolean value.')
            speakers = config.get('speakers', False)
            if not isinstance(speakers, bool):
                raise ValueError('Cấu hình người nói không hợp lệ.')
            live_speakers = speakers and valid_embedding()
            if speakers and EMBEDDING.is_file() and not live_speakers:
                speakers = False
                emit({'type':'warning', 'text':'Kiểm tra model người nói thất bại. Chạy ./scripts/setup-speakers.sh. Vẫn nhận dạng chữ.'})
            threads, chunk = int(config.get('threads', 2)), int(config.get('chunk', 100))
            if not 1 <= threads <= 16 or not 10 <= chunk <= 1000:
                raise ValueError('Cấu hình không hợp lệ.')
            acquired = lock.acquire(blocking=False)
            if not acquired:
                raise ValueError('Engine đang chạy phiên khác. Hãy dừng phiên đó trước.')
            if speech:
                speech_worker = threading.Thread(target=speech_output, daemon=True)
                speech_worker.start()
            if target:
                translator = threading.Thread(target=translation_output, daemon=True)
                translator.start()
            emit({'type':'status', 'text':'Đang nạp model · không tải video lên server…'})
            directory = resources.enter_context(tempfile.TemporaryDirectory(prefix='onplay-speakers-'))
            errors = resources.enter_context(tempfile.TemporaryFile(mode='w+'))
            audio_path = pathlib.Path(directory) / 'audio.wav'
            recording = None
            recorded = 0
            if speakers:
                if not all(path.is_file() for path in (SEGMENTATION, EMBEDDING, ROOT / 'build/onplay-speakers')):
                    speakers = False
                    emit({'type':'warning', 'text':'Thiếu model người nói. Chạy ./scripts/setup-speakers.sh và ./scripts/build.sh. Vẫn nhận dạng chữ.'})
                else:
                    recording = wave.open(str(audio_path), 'wb')
                    recording.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            speaker_args = ['--speaker-model=' + str(EMBEDDING)] if live_speakers else []
            if target:
                speaker_args.append('--low-latency')
            process = subprocess.Popen([str(ROOT / 'scripts/run.sh'), '-', '--threads=' + str(threads), '--chunk-ms=' + str(chunk), *speaker_args],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors)
            def output():
                nonlocal process, segment_id
                try:
                    for raw in process.stdout:
                        line = raw.decode('utf-8').strip()
                        if line == 'READY':
                            emit({'type':'ready'})
                        elif line.startswith(('PARTIAL_SPEAKER ', 'FINAL_SPEAKER ')):
                            kind, speaker, text = line.split(' ', 2)
                            event = {'type':'final' if kind == 'FINAL_SPEAKER' else 'partial',
                                     'text':text, 'speaker':int(speaker)}
                            if kind == 'FINAL_SPEAKER':
                                event['id'] = segment_id
                                segment_id += 1
                            emit(event)
                            if kind == 'FINAL_SPEAKER':
                                enqueue(event)
                        elif line.startswith('PARTIAL '):
                            emit({'type':'partial','text':line[8:].strip()})
                        elif line.startswith('FINAL   '):
                            event = {'type':'final', 'id':segment_id, 'text':line[8:].strip()}
                            segment_id += 1
                            emit(event)
                            enqueue(event)
                        elif line.startswith('PROGRESS '):
                            emit({'type':'progress','seconds':float(line[9:])})
                    code = process.wait()
                    errors.seek(0)
                    logs = errors.read()
                    if code:
                        emit({'type':'error','text':'Engine C++ thất bại: ' + logs[-400:]})
                    else:
                        metrics = {key:float(value) for key,value in re.findall(r'(model_load_s|audio_s|processing_s|rtf|wall_s)=([0-9.eE+-]+)', logs)}
                        if speakers and recorded and not cancelled.is_set():
                            speaker_start = time.monotonic()
                            emit({'type':'status', 'text':'Đang phân biệt người nói · bản chữ gốc được giữ để so sánh…'})
                            model = ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10'
                            args = [str(model / name) for name in (
                                'encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx',
                                'decoder-epoch-75-avg-11-chunk-16-left-128.onnx',
                                'joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'tokens.txt')]
                            with tempfile.TemporaryFile(mode='w+') as speaker_errors:
                                process = subprocess.Popen([str(ROOT / 'build/onplay-speakers'), *args,
                                    str(SEGMENTATION), str(EMBEDDING), str(audio_path), str(threads)],
                                    stdout=subprocess.PIPE, stderr=speaker_errors)
                                if cancelled.is_set():
                                    process.kill()
                                segments = []
                                for raw in process.stdout:
                                    parts = raw.decode('utf-8').rstrip('\n').split(' ', 4)
                                    if len(parts) == 5 and parts[0] == 'SPEAKER':
                                        segments.append({'start':float(parts[1]), 'end':float(parts[2]),
                                                         'speaker':int(parts[3]), 'text':parts[4]})
                                        emit({'type':'status', 'text':f'Đang phân biệt người nói · đã xử lý {len(segments)} đoạn…'})
                                if process.wait():
                                    emit({'type':'warning', 'text':'Phân biệt người nói thất bại. Đã giữ bản chữ gốc.'})
                                elif segments:
                                    for index, segment in enumerate(segments):
                                        segment['id'] = index
                                    emit({'type':'speakers', 'segments':segments})
                                    for segment in segments:
                                        enqueue(segment, 'refined')
                                else:
                                    emit({'type':'warning', 'text':'Không tìm thấy đoạn giọng nói đủ rõ. Đã giữ bản chữ gốc.'})
                            metrics['speaker_s'] = time.monotonic() - speaker_start
                            metrics['processing_s'] += metrics['speaker_s']
                            metrics['wall_s'] += metrics['speaker_s']
                            metrics['rtf'] = metrics['processing_s'] / metrics['audio_s'] if metrics['audio_s'] else 0
                        while (translations.unfinished_tasks or speech_jobs.unfinished_tasks) and not cancelled.is_set():
                            time.sleep(0.05)
                        if not cancelled.is_set():
                            emit({'type':'done','metrics':metrics})
                except (ConnectionClosed, OSError, ValueError) as error:
                    if process.poll() is None:
                        process.kill()
                    if not cancelled.is_set():
                        try:
                            emit({'type':'error', 'text':str(error)})
                        except ConnectionClosed:
                            pass
                finally:
                    connection.close()
            pump = threading.Thread(target=output, daemon=True)
            pump.start()
            for message in connection:
                if input_finished:
                    raise ValueError('PCM đã kết thúc.')
                if isinstance(message, bytes):
                    if not message or len(message) % 2 or len(message) > 32000:
                        raise ValueError('PCM16 chunk không hợp lệ.')
                    if recording:
                        if recorded + len(message) <= MAX_SPEAKER_BYTES:
                            recording.writeframesraw(message)
                            recorded += len(message)
                        else:
                            recording.close()
                            recording = None
                            speakers = False
                            audio_path.unlink()
                            emit({'type':'warning', 'text':'Audio dài hơn 10 phút: vẫn hiển thị nhãn người nói tạm, bỏ bước phân đoạn lại cuối file.'})
                    process.stdin.write(message)
                    process.stdin.flush()
                elif json.loads(message).get('type') == 'end':
                    input_finished = True
                    if recording:
                        recording.close()
                        recording = None
                    process.stdin.close()
                else:
                    raise ValueError('Thông điệp streaming không hợp lệ.')
        except ConnectionClosed:
            pass
        except Exception as error:
            try:
                emit({'type':'error','text':str(error)})
            except ConnectionClosed:
                pass
        finally:
            cancelled.set()
            if translator:
                translator.join(timeout=0.3)
            if speech_worker:
                speech_worker.join(timeout=0.3)
            if process:
                if process.poll() is None:
                    process.kill()
                process.wait()
            if pump:
                pump.join()
            if recording:
                recording.close()
            resources.close()
            if acquired:
                lock.release()
    return stream

def start_stream_server(lock):
    server = serve(make_handler(lock), '127.0.0.1', 8766,
                   origins=['http://127.0.0.1:8765', *filter(None, os.environ.get('ONPLAY_PUBLIC_ORIGIN', '').split(','))], max_size=32000, max_queue=8, compression=None)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server
