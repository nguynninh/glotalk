"""Download configured model revisions only; keep assets in ignored models/."""
import hashlib
import json
import pathlib
import yaml
from huggingface_hub import snapshot_download

root = pathlib.Path(__file__).resolve().parents[1]
config = yaml.safe_load((root / 'config/translation.yaml').read_text())['translation']
for name, spec in config['models'].items():
    if spec['backend'] not in ('nllb', 'qwen'):
        continue
    destination = root / spec['path']
    snapshot_download(spec['repository'], revision=spec['revision'], local_dir=destination,
                      allow_patterns=['*.json', '*.model', '*.safetensors', 'pytorch_model.bin',
                                      'merges.txt', 'vocab.json', 'README.md', 'LICENSE*'])
    files = {}
    for file in sorted(destination.iterdir()):
        if file.is_file() and file.name != 'onplay-manifest.json':
            digest = hashlib.sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(1024*1024), b''):
                    digest.update(block)
            files[file.name] = digest.hexdigest()
    (destination / 'onplay-manifest.json').write_text(json.dumps({
        'repository': spec['repository'], 'revision': spec['revision'], 'sha256': files}, indent=2))
    print('Installed', name, spec['revision'], flush=True)
