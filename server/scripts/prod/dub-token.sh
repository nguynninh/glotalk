#!/bin/sh
# Create a dub job token for one tester: prints the token once and appends
# "NAME sha256-of-token" to the token file (ONPLAY_DUB_TOKENS, default dub-tokens in the
# checkout), which never holds the token itself. A running onplay-server picks up the new
# line, and a deleted line (revocation), on the next request; no restart is needed.
set -eu
cd "$(dirname "$0")/../.."
name="${1:-}"
case "$name" in
  '' | *[!A-Za-z0-9._-]*) echo "Usage: $0 NAME  (letters, digits, . _ -)" >&2; exit 2 ;;
esac
file="${ONPLAY_DUB_TOKENS:-$PWD/dub-tokens}"
if [ -f "$file" ] && grep -q "^$name " "$file"; then
  echo "$name already has a token in $file; remove its line first." >&2
  exit 1
fi
token=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')
hash=$(printf %s "$token" | shasum -a 256 | cut -d' ' -f1)
umask 077
printf '%s %s\n' "$name" "$hash" >> "$file"
echo "Token for $name (shown once; give it privately):"
echo "$token"
