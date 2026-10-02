#!/bin/sh
# Slate: runs inside the proot-distro Linux. Starts the bridge with Claude Code on PATH,
# installing Node.js there the first time.
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null 2>&1; then
  echo "Installing Node.js in this Linux (one time)…"
  (apt-get update && apt-get install -y nodejs) || { echo "Couldn't install Node.js. Inside the distro run: apt install nodejs"; exit 1; }
fi
exec node "$HOME/.slate/slate-bridge.js"
