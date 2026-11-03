"""Bounded client for the local translation router; no model code in the STT process."""
import json
import os
import math
from urllib.request import Request, urlopen
from urllib.error import URLError
from urllib.parse import urlsplit

TARGETS = {'vi', 'en', 'zh', 'ja', 'ko', 'th', 'id', 'ru', 'ar', 'fr', 'de', 'es'}


class TranslationError(Exception):
    pass


def translate_result(text, target, source='auto', mode='standard', context=None):
    if not isinstance(text, str) or not text.strip() or len(text) > 10000:
        raise ValueError('Invalid text')
    if not isinstance(target, str) or target not in TARGETS:
        raise ValueError('Invalid target')
    if not isinstance(source, str) or source not in TARGETS | {'auto'} or mode not in ('standard', 'context'):
        raise ValueError('Invalid source or translation mode')
    if context is None:
        context = []
    if not isinstance(context, list) or len(context) > 5 or any(not isinstance(t, str) or not t.strip() or len(t) > 10000 for t in context):
        raise ValueError('Invalid context')
    endpoint = os.environ.get('ONPLAY_TRANSLATE_URL', 'http://127.0.0.1:5002').rstrip('/')
    if urlsplit(endpoint).scheme not in ('http', 'https') or not urlsplit(endpoint).hostname:
        raise TranslationError('ONPLAY_TRANSLATE_URL phải trỏ tới translation router HTTP/HTTPS.')
    payload = {'text': text, 'source': source, 'target': target, 'mode': mode, 'context': context}
    body = json.dumps(payload).encode()
    if len(body) > 131072:
        raise ValueError('Translation request too large')
    request = Request(endpoint + '/translate', body, {'Content-Type': 'application/json'})
    try:
        with urlopen(request, timeout=60) as response:
            raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ValueError('Response too large')
        result = json.loads(raw)
        if not isinstance(result, dict) or not isinstance(result.get('text'), str) or not result['text'].strip():
            raise ValueError('Missing translation')
        if (not isinstance(result.get('source'), str) or result['source'] not in TARGETS or result.get('target') != target
                or not isinstance(result.get('model'), str) or not result['model']
                or not isinstance(result.get('latency_ms'), (int, float))
                or not math.isfinite(result['latency_ms']) or result['latency_ms'] < 0):
            raise ValueError('Invalid translation metadata')
        return result
    except (URLError, OSError, ValueError, UnicodeError):
        raise TranslationError('Không dịch được. Kiểm tra router local: ./scripts/translation.sh và model đã tải.') from None


def translate(text, target):
    return translate_result(text, target)['text']
