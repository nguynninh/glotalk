#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec .deps/translation-venv/bin/python ui/translation_engine.py
