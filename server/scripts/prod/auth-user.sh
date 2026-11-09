#!/bin/sh
# Add or replace an editor account: asks for the password twice and writes
# "NAME argon2-hash" to the accounts file (ONPLAY_AUTH_USERS, default auth-users in the
# checkout), which never holds the password itself. A running onplay-server reads the file
# on every sign-in; no restart is needed. Needs build/onplay-server.
set -eu
cd "$(dirname "$0")/../.."
name="${1:-}"
case "$name" in
  '' | *[!A-Za-z0-9._-]*) echo "Usage: $0 NAME  (letters, digits, . _ -)" >&2; exit 2 ;;
esac
file="${ONPLAY_AUTH_USERS:-$PWD/auth-users}"
if [ -t 0 ]; then
  stty -echo
  trap 'stty echo' EXIT
  printf 'Password for %s: ' "$name" >&2; read -r password; echo >&2
  printf 'Again: ' >&2; read -r again; echo >&2
  stty echo
  trap - EXIT
  [ "$password" = "$again" ] || { echo "Passwords differ." >&2; exit 1; }
else
  read -r password
fi
hash=$(printf '%s\n' "$password" | build/onplay-server --hash-password)
umask 077
touch "$file"
grep -v "^$name " "$file" > "$file.tmp" || true
printf '%s %s\n' "$name" "$hash" >> "$file.tmp"
mv "$file.tmp" "$file"
echo "Account $name saved in $file."
