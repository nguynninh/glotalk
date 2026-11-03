import pathlib
import struct
import subprocess
import tempfile
root = pathlib.Path(__file__).resolve().parents[1]
subprocess.run(['clang++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined', '-Iinclude', 'tests/wave_reader.cpp', '-o', 'build/wave-reader-test'], cwd=root, check=True)
with tempfile.TemporaryDirectory() as tmp:
    path = pathlib.Path(tmp) / 'large.wav'
    with path.open('wb') as wav:
        wav.write(b'RIFF' + struct.pack('<I', 80000036) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, 16000, 32000, 2, 16))
        wav.write(b'data' + struct.pack('<Ihhh', 80000000, 0, -32768, 32767))
        wav.truncate(80000044)
    subprocess.run([str(root / 'build/wave-reader-test'), str(path)], check=True)
    with path.open('r+b') as wav:
        wav.truncate(44)
    assert subprocess.run([str(root / 'build/onplay-stt'), 'missing', 'missing', 'missing', 'missing', str(path)], capture_output=True).returncode == 1
print('PASS: bounded WAV reads, signed PCM and truncated file rejection')
