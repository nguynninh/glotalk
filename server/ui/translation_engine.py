"""Local translation router. Models and cached text stay in this process."""
import collections
import json
import pathlib
import re
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

ROOT = pathlib.Path(__file__).resolve().parents[1]
LANGUAGES = {
    'en': ('eng_Latn', 'English'), 'vi': ('vie_Latn', 'Vietnamese'),
    'zh': ('zho_Hans', 'Chinese'), 'ja': ('jpn_Jpan', 'Japanese'),
    'ko': ('kor_Hang', 'Korean'), 'th': ('tha_Thai', 'Thai'),
    'id': ('ind_Latn', 'Indonesian'), 'ru': ('rus_Cyrl', 'Russian'),
    'ar': ('arb_Arab', 'Arabic'), 'fr': ('fra_Latn', 'French'),
    'de': ('deu_Latn', 'German'), 'es': ('spa_Latn', 'Spanish'),
}
PLACEHOLDERS = re.compile(r'\{\{[^{}\n]+\}\}|\$\{[^{}\n]+\}|\{[A-Za-z_][\w.]*\}|%(?:\d+\$)?[sdif]')


def normalize(text):
    if not isinstance(text, str) or len(text) > 10000:
        raise ValueError('Text must be a string of at most 10000 characters')
    text = unicodedata.normalize('NFC', text).replace('\r\n', '\n').replace('\r', '\n')
    text = '\n'.join(re.sub(r'[^\S\n]+', ' ', line).strip() for line in text.split('\n')).strip()
    if not text or any(unicodedata.category(c) == 'Cc' and c != '\n' for c in text):
        raise ValueError('Empty text or unsupported control characters')
    return text


class Engine:
    def __init__(self, config):
        self.config = config
        self.models = {}
        self.cache = collections.OrderedDict()
        self.lock = threading.Lock()
        self.metrics = {'requests': 0, 'cache_hits': 0, 'errors': 0, 'fallbacks': 0,
                        'latency_ms_total': 0, 'by_model': {}, 'placeholder_failures': 0, 'over_500_ms': 0}
        registered = config['models']
        chosen = [config['default_model'], config.get('fallback_model')]
        context = config['context']
        if context['enabled']:
            chosen.extend((context['model'], context.get('fallback')))
        for route in config['routes'].values():
            chosen.extend((route['primary'], route.get('fallback')))
        if any(name and name not in registered for name in chosen):
            raise ValueError('A route references an unregistered model')
        if not 1 <= context['max_segments'] <= 5 or not 0 <= config['cache_entries'] <= 4096:
            raise ValueError('Invalid context/cache limit')
        if any(pair.count('-') != 1 or any(code not in LANGUAGES for code in pair.split('-'))
               for pair in config['routes']):
            raise ValueError('Unsupported route language pair')
        if any(spec['backend'] not in ('nllb', 'qwen', 'libretranslate', 'ct2_nllb', 'ct2_opus') for spec in registered.values()):
            raise ValueError('Unknown model backend')
        self.detector = None

    def detect(self, text):
        if self.detector is None:
            from lingua import Language, LanguageDetectorBuilder
            self.detector = LanguageDetectorBuilder.from_languages(*[
                Language.from_iso_code_639_1(getattr(__import__('lingua').IsoCode639_1, code.upper()))
                for code in LANGUAGES]).with_preloaded_language_models().build()
        language = self.detector.detect_language_of(text)
        if language is None:
            raise ValueError('Cannot identify source language; supply source explicitly')
        return language.iso_code_639_1.name.lower()

    def run_model(self, name, text, source, target, context):
        spec = self.config['models'][name]
        backend = spec['backend']
        if backend in ('ct2_nllb', 'ct2_opus'):
            import ctranslate2
            if backend == 'ct2_opus':
                if source not in ('en', 'vi', 'zh') or target not in ('en', 'vi', 'zh'):
                    raise RuntimeError('Native OPUS does not support this pair')
                pairs = [source + '_' + target] if 'en' in (source, target) else [source + '_en', 'en_' + target]
                for pair in pairs:
                    key = name + ':' + pair
                    if key not in self.models:
                        import sentencepiece
                        path = ROOT / spec['path'] / pair
                        self.models[key] = (ctranslate2.Translator(str(path / 'model'), compute_type='int8', intra_threads=2),
                                            sentencepiece.SentencePieceProcessor(model_file=str(path / 'sentencepiece.model')))
                    model, tokenizer = self.models[key]
                    tokens = tokenizer.encode(text, out_type=str)
                    if len(tokens) > 512:
                        raise RuntimeError('Segment exceeds 512 tokens')
                    result = model.translate_batch([tokens], beam_size=1, return_scores=False, return_end_token=True,
                                                   max_input_length=0, max_decoding_length=512)[0].hypotheses[0]
                    if not result or result[-1] != '</s>':
                        raise RuntimeError('Translation output reached its token limit')
                    text = tokenizer.decode_pieces(result[:-1]).replace('\u2581', ' ').strip()
                return text
            if name not in self.models:
                from transformers import AutoTokenizer
                self.models[name] = (ctranslate2.Translator(str(ROOT / spec['path']), compute_type='int8', intra_threads=2),
                                     AutoTokenizer.from_pretrained(str(ROOT / spec['tokenizer']), local_files_only=True))
            model, tokenizer = self.models[name]
            tokenizer.src_lang = LANGUAGES[source][0]
            tokens = tokenizer.convert_ids_to_tokens(tokenizer.encode(text))
            if len(tokens) > 512:
                raise RuntimeError('NLLB segment exceeds 512 tokens')
            result = model.translate_batch([tokens], target_prefix=[[LANGUAGES[target][0]]], beam_size=1,
                                           return_scores=False, return_end_token=True,
                                           max_input_length=0, max_decoding_length=512)[0].hypotheses[0]
            if not result or result[-1] != '</s>':
                raise RuntimeError('Translation output reached its token limit')
            return tokenizer.decode(tokenizer.convert_tokens_to_ids(result), skip_special_tokens=True).strip()
        if backend == 'libretranslate':
            if source not in spec['languages'] or target not in spec['languages']:
                raise RuntimeError('Fallback does not support this language pair')
            request = Request(spec['url'].rstrip('/') + '/translate', json.dumps({
                'q': text, 'source': source, 'target': target, 'format': 'text'
            }).encode(), {'Content-Type': 'application/json'})
            with urlopen(request, timeout=45) as response:
                raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise RuntimeError('Provider response too large')
            return json.loads(raw)['translatedText']
        import torch
        from transformers import AutoTokenizer, AutoModelForSeq2SeqLM, AutoModelForCausalLM
        if name not in self.models:
            path = str(ROOT / spec['path'])
            tokenizer = AutoTokenizer.from_pretrained(path, local_files_only=True)
            cls = AutoModelForSeq2SeqLM if backend == 'nllb' else AutoModelForCausalLM
            device = 'mps' if torch.backends.mps.is_available() else 'cpu'
            dtype = torch.float16 if device == 'mps' and backend == 'qwen' else torch.float32
            model = cls.from_pretrained(path, local_files_only=True, dtype=dtype)
            model.to(device).eval()
            self.models[name] = tokenizer, model
        tokenizer, model = self.models[name]
        if backend == 'nllb':
            tokenizer.src_lang = LANGUAGES[source][0]
            inputs = tokenizer(text, return_tensors='pt')
            if inputs.input_ids.shape[1] > 512:
                raise RuntimeError('NLLB segment exceeds 512 tokens; split the segment')
            kwargs = {'forced_bos_token_id': tokenizer.convert_tokens_to_ids(LANGUAGES[target][0])}
        else:
            prompt = ('Translate from ' + LANGUAGES[source][1] + ' to ' + LANGUAGES[target][1] +
                      '. Output only the translation.\n')
            if context:
                prompt += 'Previous context (do not translate):\n' + '\n'.join(context) + '\n'
            prompt += 'Text to translate:\n' + text
            formatted = tokenizer.apply_chat_template([
                {'role': 'system', 'content': 'You are a translator. Return only translated text, without explanation.'},
                {'role': 'user', 'content': prompt}], tokenize=False, add_generation_prompt=True, enable_thinking=False)
            inputs = tokenizer(formatted, return_tensors='pt')
            if inputs.input_ids.shape[1] > 2048:
                raise RuntimeError('Context exceeds 2048 tokens; shorten the segments')
            kwargs = {}
        inputs = inputs.to(model.device)
        with torch.inference_mode():
            output = model.generate(**inputs, **kwargs, max_new_tokens=512, do_sample=False)
        tokens = output[0] if backend == 'nllb' else output[0][inputs.input_ids.shape[1]:]
        if len(tokens) and int(tokens[-1]) not in (tokenizer.eos_token_id, tokenizer.pad_token_id):
            raise RuntimeError('Translation output reached its token limit')
        return tokenizer.decode(tokens, skip_special_tokens=True).strip()

    def warmup(self):
        # Startup cost must not be charged to the first audio segment.
        self.detect('Hello, how are you today?')
        for name, spec in self.config['models'].items():
            if spec['backend'] == 'ct2_nllb':
                self.run_model(name, 'Hello.', 'en', 'vi', [])
            elif spec['backend'] == 'ct2_opus':
                for source, target in [('en', 'vi'), ('vi', 'en'), ('zh', 'en'), ('en', 'zh')]:
                    self.run_model(name, 'Hello.', source, target, [])

    def translate(self, data):
        if not isinstance(data, dict):
            raise ValueError('Expected a JSON object')
        text = normalize(data.get('text'))
        source, target = data.get('source', 'auto'), data.get('target')
        mode = data.get('mode', 'standard')
        if not isinstance(source, str) or source not in set(LANGUAGES) | {'auto'} or not isinstance(target, str) or target not in LANGUAGES:
            raise ValueError('Unsupported source or target language')
        if mode not in ('standard', 'context'):
            raise ValueError('Mode must be standard or context')
        context = data.get('context', [])
        if not isinstance(context, list) or len(context) > 5:
            raise ValueError('Context must contain at most five previous text segments')
        context = [normalize(item) for item in context][-self.config['context']['max_segments']:]
        started = time.monotonic()
        # One worker owns tokenizer language state, models, cache and counters.
        if not self.lock.acquire(blocking=False):
            raise BlockingIOError('Translation engine busy; retry later')
        self.metrics['requests'] += 1
        try:
            if source == 'auto':
                source = self.detect(text)
            route = self.config['routes'].get(source + '-' + target, {})
            primary = route.get('primary', self.config['default_model'])
            fallback = route.get('fallback', self.config.get('fallback_model'))
            if mode == 'context':
                if not self.config['context']['enabled']:
                    raise ValueError('Context translation is disabled')
                primary = self.config['context']['model']
                fallback = self.config['context'].get('fallback', fallback)
            if source == target:
                primary, fallback = 'identity', None
            key = (text, source, target, primary, fallback, mode, tuple(context))
            cached = self.cache.get(key)
            if cached:
                self.cache.move_to_end(key)
                self.metrics['cache_hits'] += 1
                elapsed = round((time.monotonic()-started)*1000, 2)
                return {**cached, 'cached': True, 'latency_ms': elapsed, 'within_budget': elapsed <= 500}
            # Translate lines separately so blank lines and subtitle boundaries survive.
            errors = []
            for name in dict.fromkeys([primary, fallback]):
                if not name:
                    continue
                try:
                    lines = []
                    for line in text.split('\n'):
                        if not line or name == 'identity':
                            lines.append(line)
                            continue
                        # shortcut: translate around literal placeholders; use model-aware glossary constraints if benchmarks require more context.
                        pieces = re.split('(' + PLACEHOLDERS.pattern + ')', line)
                        translated = []
                        for piece in pieces:
                            if not piece.strip() or not any(c.isalpha() for c in piece) or PLACEHOLDERS.fullmatch(piece):
                                translated.append(piece)
                            else:
                                value = self.run_model(name, piece.strip(), source, target, context if name == primary else [])
                                if not isinstance(value, str) or not value.strip():
                                    raise RuntimeError('Empty translation')
                                if value.lstrip().startswith(('{"previous_segments"', 'Text to translate:', 'Previous context')):
                                    raise RuntimeError('Model echoed the prompt')
                                translated.append((' ' if piece.startswith(' ') else '') + value.strip() + (' ' if piece.endswith(' ') else ''))
                        result = ''.join(translated)
                        if not isinstance(result, str) or not result.strip() or any(unicodedata.category(c) == 'Cc' and c not in '\n\t' for c in result):
                            raise RuntimeError('Empty translation')
                        # Retaining literal placeholders avoids unreliable marker recovery.
                        if collections.Counter(PLACEHOLDERS.findall(line)) != collections.Counter(PLACEHOLDERS.findall(result)):
                            self.metrics['placeholder_failures'] += 1
                            raise RuntimeError('Model changed placeholders')
                        lines.append(' '.join(result.split()))
                    result = {'text': '\n'.join(lines), 'source': source, 'target': target,
                              'model': name, 'fallback': name != primary, 'cached': False,
                              'latency_ms': round((time.monotonic()-started)*1000, 2)}
                    result['within_budget'] = result['latency_ms'] <= 500
                    if name != primary:
                        self.metrics['fallbacks'] += 1
                    self.metrics['by_model'][name] = self.metrics['by_model'].get(name, 0) + 1
                    if self.config['cache_entries']:
                        self.cache[key] = result
                        while len(self.cache) > self.config['cache_entries']:
                            self.cache.popitem(last=False)
                    return result
                except Exception:
                    errors.append(name)
            raise RuntimeError('Translation failed for models: ' + ', '.join(errors) + '. Check models, segment length and placeholders.')
        except Exception:
            self.metrics['errors'] += 1
            raise
        finally:
            elapsed = (time.monotonic()-started)*1000
            self.metrics['latency_ms_total'] += round(elapsed, 2)
            self.metrics['over_500_ms'] += int(elapsed > 500)
            self.lock.release()


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == '/health':
            return self.reply(200, {'status': 'ok', 'models': list(self.server.engine.config['models'])})
        if self.path == '/metrics':
            with self.server.engine.lock:
                return self.reply(200, self.server.engine.metrics)
        return self.reply(404, {'error': 'Unknown endpoint'})

    def do_POST(self):
        self.close_connection = True
        if self.path != '/translate':
            return self.reply(404, {'error': 'Unknown endpoint'})
        if self.headers.get('Transfer-Encoding') or self.headers.get('Content-Type','').split(';')[0].strip() != 'application/json':
            return self.reply(400, {'error': 'Expected bounded application/json body'})
        try:
            length = int(self.headers.get('Content-Length', 0))
            if not 0 < length <= 131072:
                return self.reply(413, {'error': 'Body must be 1 byte to 128 KiB'})
            self.connection.settimeout(30)
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError('Incomplete body')
            self.reply(200, self.server.engine.translate(json.loads(raw)))
        except (ValueError, UnicodeError) as error:
            self.reply(400, {'error': str(error)})
        except BlockingIOError as error:
            self.reply(409, {'error': str(error)})
        except Exception:
            self.reply(503, {'error': 'Local translation failed; check engine and model setup'})


if __name__ == '__main__':
    import yaml
    config = yaml.safe_load((ROOT / 'config/translation.yaml').read_text())['translation']
    engine = Engine(config)
    engine.warmup()
    server = ThreadingHTTPServer(('127.0.0.1', 5002), Handler)
    server.engine = engine
    print('ONPlay translation router: http://127.0.0.1:5002', flush=True)
    server.serve_forever()
