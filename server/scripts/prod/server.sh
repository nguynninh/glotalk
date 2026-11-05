#!/bin/sh
# Public server behind the stt.qiverse.top tunnel, which routes the hostname to 127.0.0.1:8766.
# Same as dev/server.sh plus the public Origin; override with ONPLAY_PUBLIC_ORIGIN.
set -eu
ONPLAY_PUBLIC_ORIGIN="${ONPLAY_PUBLIC_ORIGIN:-https://stt.qiverse.top}"
export ONPLAY_PUBLIC_ORIGIN
exec "$(dirname "$0")/../dev/server.sh"
