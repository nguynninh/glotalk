"""Local KorvaTTS speech synthesis returning bounded PCM WAV files."""
import io
import threading
import wave
from functools import lru_cache
import numpy as np
from korvatts import TTS
from translation import TARGETS

LOCK = threading.Lock()
VOICES = {'vi': 'khanh_vy', 'en': 'khanh_vy'}


class SpeechError(Exception):
    pass


@lru_cache(maxsize=1)
def engine():
    return TTS()


def synthesize(text, language):
    if not isinstance(text, str) or not text.strip() or len(text) > 3000 or '\x00' in text:
        raise ValueError('Expected non-empty text, maximum 3000 characters')
    if not isinstance(language, str) or language not in TARGETS:
        raise ValueError('Unsupported speech language')
    if language not in VOICES:
        raise SpeechError('KorvaTTS currently supports Vietnamese and English speech only')
    if not LOCK.acquire(blocking=False):
        raise SpeechError('Speech engine busy; retry after the current request')
    try:
        samples, _ = engine().synthesize(text, voice=VOICES[language], lang=language,
                                         total_steps=16)
        pcm = np.clip(samples * 32767, -32768, 32767).astype('<i2').tobytes()
        output = io.BytesIO()
        with wave.open(output, 'wb') as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(44100)
            audio.writeframes(pcm)
        if len(output.getvalue()) > 32 * 1024 * 1024:
            raise SpeechError('Generated audio exceeds 32 MiB')
        return output.getvalue()
    except SpeechError:
        raise
    except Exception:
        raise SpeechError('KorvaTTS speech synthesis unavailable') from None
    finally:
        LOCK.release()
