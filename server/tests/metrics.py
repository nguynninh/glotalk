import importlib.util
import pathlib
import urllib.request
import json
root=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('stt_metrics',root/'ui/metrics.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
monitor=module.Monitor()
first=monitor.sample();second=monitor.sample()
assert 0<=second['cpu_percent']<=100
assert 0<second['ram_available']<=second['ram_total']
assert second['model_bytes']>0 and second['disk_free']>0
assert second['disk_read_bps']>=0 and second['disk_write_bps']>=0
for process in second['processes']:
 assert process['rss_bytes']>0
 assert process['peak_rss_bytes']>=process['rss_bytes']
 assert process['cpu_percent']>=0
assert urllib.request.urlopen('http://127.0.0.1:8767/?full=1').status==200
metrics=json.load(urllib.request.urlopen('http://127.0.0.1:8767/api/metrics'))
assert metrics['ram_total']==second['ram_total']
print('PASS: OS/process metrics, bounded values, full embedded page and live API')
