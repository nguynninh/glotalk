"""Read-only local resource monitor, independent of running STT sessions."""
import json
import pathlib
import threading
import time
from urllib.parse import urlparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import psutil

ROOT = pathlib.Path(__file__).resolve().parents[1]

class Monitor:
    def __init__(self):
        self.processes = {}
        self.peak = {}
        self.previous_io = None
        self.model_bytes = sum(p.stat().st_size for p in (ROOT / 'models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10').glob('*.onnx'))
        self.snapshot = {}
        psutil.cpu_percent(None)

    def sample(self):
        roots = []
        for p in psutil.process_iter(['pid','name']):
            if 'python' not in (p.info['name'] or '').lower():
                continue
            try:
                if any(arg.endswith('ui/server.py') for arg in p.cmdline()) and pathlib.Path(p.cwd()) == ROOT:
                    roots.append(p)
            except (psutil.Error, OSError):
                continue
        candidates = {}
        for root in roots:
            try:
                candidates[root.pid] = (root, 'Bridge Python')
                for child in root.children(recursive=True):
                    if child.name() in ('onplay-stt', 'onplay-recognize', 'onplay-speakers'):
                        candidates[child.pid] = (child, 'Engine C++')
            except psutil.Error:
                pass
        rows = []
        for pid, (process, label) in candidates.items():
            try:
                key = (pid, process.create_time())
                process = self.processes.setdefault(key, process)
                with process.oneshot():
                    memory = process.memory_info().rss
                    self.peak[key] = max(self.peak.get(key, 0), memory)
                    cpu = process.cpu_percent(None)
                    times = process.cpu_times()
                    command = process.cmdline()
                    threads = next((int(arg.split('=')[1]) for arg in command if arg.startswith('--threads=')), None)
                    if process.name() == 'onplay-recognize':
                        threads = 2
                    rows.append(dict(pid=pid, label=label, cpu_percent=cpu, rss_bytes=memory,
                                     peak_rss_bytes=self.peak[key], os_threads=process.num_threads(),
                                     inference_threads=threads, cpu_seconds=times.user+times.system))
            except (psutil.Error, ValueError):
                continue
        alive = {(p.pid, p.create_time()) for p, _ in candidates.values() if p.is_running()}
        self.processes = {k:v for k,v in self.processes.items() if k in alive}
        self.peak = {k:v for k,v in self.peak.items() if k in alive}
        ram, swap, disk = psutil.virtual_memory(), psutil.swap_memory(), psutil.disk_usage(ROOT)
        now = time.monotonic()
        disk_read = disk_write = None
        io = psutil.disk_io_counters()
        if io:
            if self.previous_io:
                previous, before = self.previous_io
                elapsed = now-before
                disk_read = max(0,(io.read_bytes-previous.read_bytes)/elapsed)
                disk_write = max(0,(io.write_bytes-previous.write_bytes)/elapsed)
            self.previous_io = (io,now)
        self.snapshot = dict(timestamp=time.time(), cpu_percent=psutil.cpu_percent(None),
            logical_cpus=psutil.cpu_count(), physical_cpus=psutil.cpu_count(logical=False),
            ram_total=ram.total, ram_available=ram.available, ram_percent=ram.percent,
            swap_used=swap.used, disk_total=disk.total, disk_used=disk.used, disk_free=disk.free,
            disk_percent=disk.percent, disk_read_bps=disk_read, disk_write_bps=disk_write,
            model_bytes=self.model_bytes, processes=rows,
            engine_running=any(row['label']=='Engine C++' for row in rows))
        return self.snapshot

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if urlparse(self.path).path == '/api/metrics':
            body = json.dumps(self.server.monitor.snapshot).encode()
            kind = 'application/json'
        elif urlparse(self.path).path == '/':
            body = (ROOT / 'ui/metrics.html').read_bytes()
            kind = 'text/html; charset=utf-8'
        else:
            self.send_error(404);return
        self.send_response(200)
        self.send_header('Content-Type',kind)
        self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.end_headers();self.wfile.write(body)
    def log_message(self, *args):
        pass

def start_monitor():
    server = ThreadingHTTPServer(('127.0.0.1',8767),Handler)
    server.monitor = Monitor()
    server.monitor.sample()
    def update():
        while True:
            time.sleep(2)
            try:
                server.monitor.sample()
            except (psutil.Error,OSError):
                pass
    threading.Thread(target=update,daemon=True).start()
    threading.Thread(target=server.serve_forever,daemon=True).start()
    return server

if __name__ == '__main__':
    server=start_monitor()
    print('ONPlay resources: http://127.0.0.1:8767',flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        server.shutdown()
