"""Install checksum-verified native OPUS models for latency evaluation."""
import hashlib
import pathlib
import stat
import tempfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
HASHES = {
 'en_vi': '32e13c96db538f77fc7773596de75b1b4ca5e3f933a26c2cf67311f4c16ff9c8',
 'vi_en': '63aa5bdb275126e62d27ca43ed8c9021df0ae6b94383ca8be4d99c6d73fbcb96',
 'zh_en': 'edd8c8a6863d36959613ff291074627a1635fab2f51b872ef437e924d238921a',
 'en_zh': '1a039114d9456b6528fabb65b455b6f156319634a0f984b1f6018f7737d67598',
}
base = ROOT / 'models/translation/opus'
base.mkdir(parents=True, exist_ok=True)
for pair, expected in HASHES.items():
    destination = base / pair
    if (destination / 'model/model.bin').is_file():
        assert hashlib.sha256((destination / 'model/model.bin').read_bytes()).hexdigest() == expected
        continue
    with tempfile.TemporaryDirectory(dir=base) as temporary:
        folder = pathlib.Path(temporary)
        archive = folder / 'model.zip'
        url = f'https://argos-net.com/v1/translate-{pair}-1_9.argosmodel'
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'curl/8.7.1'}), timeout=60) as response, archive.open('wb') as output:
            size = 0
            while block := response.read(1024*1024):
                size += len(block)
                if size > 256*1024*1024:
                    raise ValueError('Archive too large')
                output.write(block)
        with zipfile.ZipFile(archive) as package:
            if sum(info.file_size for info in package.infolist()) > 1024*1024*1024:
                raise ValueError('Expanded model too large')
            for info in package.infolist():
                if pathlib.PurePosixPath(info.filename).is_absolute() or '..' in pathlib.PurePosixPath(info.filename).parts or stat.S_ISLNK(info.external_attr >> 16):
                    raise ValueError('Unsafe model archive')
            package.extractall(folder / 'extracted')
        weights = list((folder / 'extracted').glob('*/model/model.bin'))
        assert len(weights) == 1, weights
        assert hashlib.sha256(weights[0].read_bytes()).hexdigest() == expected, pair
        weights[0].parent.parent.rename(destination)
        print('Installed', pair, expected, flush=True)
