"""Integration checks using actual ONNX inference, no mocked transcripts."""
import pathlib
import subprocess
import tempfile
import wave

root = pathlib.Path(__file__).resolve().parents[1]
model = root / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10'

def decode(path):
    result = subprocess.run([str(root / 'scripts/run.sh'), str(path)], capture_output=True, text=True, check=True)
    finals = [line[8:] for line in result.stdout.splitlines() if line.startswith('FINAL   ')]
    assert finals, result.stdout
    assert 'rtf=' in result.stderr
    return finals

assert 'AFTER EARLY' in ' '.join(decode(model / 'test_wavs/en.wav'))
vi = decode(root / 'models/vi.wav')
assert any(ord(c) > 127 for c in ' '.join(vi)), vi
# Separate utterances with silence to exercise endpoint/reset and language changes.
with tempfile.TemporaryDirectory() as tmp:
    path = pathlib.Path(tmp) / 'conversation.wav'
    with wave.open(str(path), 'wb') as out:
        out.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        for source in [model / 'test_wavs/en.wav', root / 'models/vi.wav']:
            with wave.open(str(source), 'rb') as audio:
                assert audio.getparams()[:3] == (1, 2, 16000)
                out.writeframes(audio.readframes(audio.getnframes()))
            out.writeframes(bytes(16000 * 2 * 3))
    finals = decode(path)
    assert len(finals) >= 2, finals
    assert 'AFTER EARLY' in finals[0], finals
    assert any(ord(c) > 127 for c in ' '.join(finals[1:])), finals
print('PASS: English, Vietnamese, endpoint reset and language transition')
