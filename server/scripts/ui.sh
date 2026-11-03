#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ ! -x build/onplay-stt ] || [ ! -x build/onplay-speakers ] || [ ! -x build/onplay-recognize ]; then ./scripts/build.sh; fi
if [ -x .venv/bin/python ] && ! .venv/bin/python -c 'import sys; exit(sys.version_info < (3, 10))'; then
  if [ -e .venv-python39 ]; then
    echo 'Incompatible .venv exists; move it aside, then retry.' >&2
    exit 1
  fi
  mv .venv .venv-python39
fi
if [ ! -x .venv/bin/python ]; then
  python_bin=
  for candidate in python3.12 python3.11 python3.10; do
    if command -v "$candidate" >/dev/null 2>&1; then python_bin=$candidate; break; fi
  done
  if [ -z "$python_bin" ]; then echo 'KorvaTTS requires Python 3.10 or newer.' >&2; exit 1; fi
  "$python_bin" -m venv .venv
fi
if ! .venv/bin/python -c 'import websockets, psutil, korvatts' 2>/dev/null; then
  .venv/bin/python -m pip install -r ui/requirements.txt
fi
exec .venv/bin/python ui/server.py
