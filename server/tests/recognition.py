"""Real-model reuse, stream isolation, worker restart and bounded pipe reads."""
import subprocess
import sys
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ui'))
from recognition import RecognitionWorker


class RecognitionTests(unittest.TestCase):
    def test_reuse_and_fresh_streams(self):
        worker = RecognitionWorker()
        try:
            with wave.open(str(ROOT / 'models/vi.wav')) as audio:
                pcm = audio.readframes(audio.getnframes())
            first = worker.recognize(pcm)
            self.assertIn('đất nước', first)
            process = worker.process
            self.assertEqual(worker.recognize(bytes(32000)), '')
            self.assertEqual(worker.recognize(bytes(1920000)), '')
            self.assertEqual(worker.recognize(pcm), first)
            self.assertIs(worker.process, process)
            with wave.open(str(ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/test_wavs/en.wav')) as audio:
                self.assertIn('AFTER EARLY', worker.recognize(audio.readframes(audio.getnframes())))
            process.kill()
            process.wait()
            with self.assertRaises(OSError):
                worker.recognize(pcm)
            self.assertIsNone(worker.process)
            self.assertEqual(worker.recognize(pcm), first)
        finally:
            worker.close()

    def test_validation_and_timeout(self):
        worker = RecognitionWorker()
        for pcm in (b'', b'x', bytes(1920002)):
            with self.assertRaises(ValueError):
                worker.recognize(pcm)
        try:
            with patch('recognition.select.select', return_value=([], [], [])):
                with self.assertRaises(subprocess.TimeoutExpired):
                    worker.recognize(bytes(3200))
            self.assertIsNone(worker.process)
        finally:
            worker.close()

    def test_native_rejects_truncated_or_oversized_frames(self):
        model = ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10'
        args = [str(model / name) for name in (
            'encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx',
            'decoder-epoch-75-avg-11-chunk-16-left-128.onnx',
            'joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'tokens.txt')]
        for data in (b'\x02', b'\xff'*4, b'\x02\x00\x00\x00\x00'):
            result = subprocess.run([str(ROOT / 'build/onplay-recognize'), *args],
                                    input=data, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 1)


if __name__ == '__main__':
    unittest.main()
