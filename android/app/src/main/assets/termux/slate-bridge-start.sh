#!/bin/sh
# Slate: runs inside the proot-distro Linux (sent in by slate-claude on stdin, after the bridge files).
# Starts the bridge with Claude Code on PATH, installing Node.js there the first time. apt reads from
# /dev/null so it can't swallow the rest of this script from stdin.
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null 2>&1; then
  echo "Installing Node.js in this Linux (one time, a minute or two)…"
  (export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y nodejs) </dev/null \
    || { echo "Couldn't install Node.js. Inside the distro run: apt install nodejs"; exit 1; }
fi
exec node "$HOME/.slate/slate-bridge.js"
