import urllib.error
import urllib.request
from websockets.sync.client import connect
from websockets.exceptions import InvalidStatus
base = 'http://127.0.0.1:8765'
assert 'STT Lab' in urllib.request.urlopen(base).read().decode()
for route in ('/metrics', '/metrics?full=1', '/api/metrics'):
    assert urllib.request.urlopen(base + route).status == 200
for script in ('spectrum.mjs','live.mjs','pcm-worklet.js'):
    assert urllib.request.urlopen(base + '/' + script).status == 200
try:
    urllib.request.urlopen(urllib.request.Request(base + '/api/unknown',data=b'x'))
except urllib.error.HTTPError as error:
    assert error.code == 404
else:
    raise AssertionError('Unknown endpoint must be rejected')
try:
    connect('ws://127.0.0.1:8766',origin='https://example.com',proxy=None)
except InvalidStatus as error:
    assert error.response.status_code == 403
else:
    raise AssertionError('Unexpected cross-origin websocket access')
print('PASS: UI assets, unknown POST rejected, websocket origin validation')
