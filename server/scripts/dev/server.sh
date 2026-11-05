#!/bin/sh
# Streaming WebSocket server on ws://127.0.0.1:8766 (Rust). Also starts the translation
# router on 5002 unless one is already listening or ONPLAY_TRANSLATION=0; it stops with the server.
# ONPLAY_LOG=1 prints a line per connection; ONPLAY_DEBUG=1 also prints frames and timings.
set -eu
cd "$(dirname "$0")/../.."
if [ ! -x build/onplay-server ]; then ./scripts/dev/build.sh; fi
# PID and command of the process listening on TCP port $1, one per line.
listener() {
  for pid in $(lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null); do
    echo "$pid $(ps -o command= -p "$pid")"
  done
}
busy=$(listener 8766)
if [ -n "$busy" ]; then
  echo "Port 8766 busy: $(echo "$busy" | paste -sd';' -). Run: kill $(echo "$busy" | cut -d' ' -f1 | paste -sd' ' -)" >&2
  exit 1
fi
router=$(listener 5002)
if [ -n "$router" ]; then
  echo "Using the translation router already on 5002: PID $router" >&2
fi
if [ "${ONPLAY_TRANSLATION:-1}" != 0 ] && [ -z "$router" ]; then
  ./scripts/dev/translation.sh &
  router=$!
  trap 'kill "$router" 2>/dev/null || true' EXIT
  trap 'exit 130' INT TERM HUP
  echo 'Waiting for translation router on 127.0.0.1:5002...' >&2
  # The router listens only after loading its models.
  while ! nc -z 127.0.0.1 5002 2>/dev/null; do
    if ! kill -0 "$router" 2>/dev/null; then
      echo 'Translation router exited; continuing without translation.' >&2
      break
    fi
    sleep 1
  done
fi
build/onplay-server
