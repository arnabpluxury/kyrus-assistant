#!/data/data/com.termux/files/usr/bin/bash
# Termux / Unix launcher
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
export PYTHONPATH="$ROOT${PYTHONPATH:+:$PYTHONPATH}"
exec python -m kyrus "$@"
