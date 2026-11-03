"""Hardware check against the running local API: 500 ms includes HTTP, no cache hits."""
import json
import pathlib
import statistics
import time
import uuid
from urllib.request import Request, urlopen

samples = [
 ('en', 'vi', 'Hello, how are you today?'),
 ('vi', 'en', 'Xin ch\u00e0o, h\u00f4m nay b\u1ea1n kh\u1ecfe kh\u00f4ng?'),
 ('zh', 'vi', '\u4f60\u597d\uff0c\u6700\u8fd1\u600e\u4e48\u6837\uff1f'),
 ('ja', 'vi', '\u3053\u3093\u306b\u3061\u306f\u3002\u4eca\u65e5\u306f\u3044\u3044\u5929\u6c17\u3067\u3059\u306d\u3002'),
 ('en', 'vi', 'Today we are discussing how to improve the speed of speech recognition and translation in our mobile application.'),
]
rows=[]
run=uuid.uuid4().hex[:4]
for source,target,text in samples:
    for index in range(5):
        # A unique suffix forces actual inference rather than reusing a cached result.
        payload={'text':text+' '+run+str(index),'source':source,'target':target,'mode':'standard'}
        request=Request('http://127.0.0.1:8765/api/translate',json.dumps(payload).encode(),{'Content-Type':'application/json'})
        started=time.monotonic()
        with urlopen(request,timeout=20) as response:result=json.load(response)
        elapsed=(time.monotonic()-started)*1000
        assert not result['cached'],result
        assert result['text'].strip() and '\u2581' not in result['text'],result
        rows.append({'pair':source+'-'+target,'chars':len(payload['text']), 'http_ms':round(elapsed,2),
                     'engine_ms':result['latency_ms'],'model':result['model'],'cached':result['cached']})
values=sorted(row['http_ms'] for row in rows)
report={'samples':rows,'count':len(rows),'p50_ms':statistics.median(values),
        'p95_ms':values[int((len(values)-1)*.95)],'max_ms':max(values),'budget_ms':500}
output=pathlib.Path(__file__).resolve().parents[1]/'build/translation-latency.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='samples'}))
assert max(values)<=500, '500 ms budget exceeded; inspect build/translation-latency.json'
print('PASS: uncached HTTP translation within 500 ms on all benchmark samples')
