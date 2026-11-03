"""One serial native worker; model stays loaded, each WAV gets a fresh stream."""
import atexit
import os
from pathlib import Path
import select
import struct
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


class RecognitionWorker:
    def __init__(self):
        self.process = None
        self.lock = threading.Lock()

    def close(self):
        if self.process is not None:
            self.process.kill()
            self.process.wait()
            self.process.stdin.close()
            self.process.stdout.close()
            self.process = None

    def _read(self, size, deadline):
        result = bytearray()
        while len(result) < size:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                raise subprocess.TimeoutExpired('onplay-recognize', 90)
            block = os.read(self.process.stdout.fileno(), size - len(result))
            if not block:
                raise OSError('Recognition worker stopped')
            result.extend(block)
        return bytes(result)

    def _start(self, deadline):
        if self.process is not None:
            return
        model = ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10'
        args = [str(model / name) for name in (
            'encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx',
            'decoder-epoch-75-avg-11-chunk-16-left-128.onnx',
            'joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'tokens.txt')]
        self.process = subprocess.Popen([str(ROOT / 'build/onplay-recognize'), *args],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, bufsize=0)
        os.set_blocking(self.process.stdin.fileno(), False)
        if self._read(6, deadline) != b'READY\n':
            raise OSError('Invalid recognition worker handshake')

    def recognize(self, pcm):
        if not pcm or len(pcm) % 2 or len(pcm) > 16000 * 2 * 60:
            raise ValueError('Expected up to 60 seconds of PCM16 at 16 kHz')
        with self.lock:
            deadline = time.monotonic() + 90
            try:
                self._start(deadline)
                data = memoryview(struct.pack('<I', len(pcm)) + pcm)
                while data:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not select.select([], [self.process.stdin], [], remaining)[1]:
                        raise subprocess.TimeoutExpired('onplay-recognize', 90)
                    try:
                        written = os.write(self.process.stdin.fileno(), data[:4096])
                    except BlockingIOError:
                        continue
                    if not written:
                        raise OSError('Recognition worker input closed')
                    data = data[written:]
                header = bytearray()
                while len(header) < 6:
                    byte = self._read(1, deadline)
                    if byte == b'\n':
                        break
                    header.extend(byte)
                else:
                    raise OSError('Invalid recognition worker response header')
                if not header.isdigit() or int(header) > 65536:
                    raise OSError('Invalid recognition worker response')
                return self._read(int(header), deadline).decode('utf-8').strip()
            except (OSError, ValueError, subprocess.TimeoutExpired):
                self.close()
                raise


WORKER = RecognitionWorker()
atexit.register(WORKER.close)
recognize = WORKER.recognize
