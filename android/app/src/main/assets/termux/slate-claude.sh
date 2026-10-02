#!/data/data/com.termux/files/usr/bin/sh
# Slate: starts the bridge next to Claude Code. In Termux if Claude Code runs there; otherwise inside
# the proot-distro Linux where it's installed (the bridge then runs in there as well, so no command
# has to cross into the distro for each question).
termux-wake-lock 2>/dev/null || true
pkill -f 'node .*/[.]slate/slate-bridge[.]js$' 2>/dev/null && sleep 1
if claude --version >/dev/null 2>&1; then
  exec node "$HOME/.slate/slate-bridge.js" "$@"
fi
ROOTS="${SLATE_ROOTFS_DIR:-$PREFIX/var/lib/proot-distro/installed-rootfs}"
for d in "$ROOTS"/*; do
  [ -d "$d" ] || continue
  found=
  # The installer's claude is a symlink with an absolute target inside the distro: test the link itself.
  for f in "$d/root/.local/bin/claude" "$d/usr/local/bin/claude" "$d/usr/bin/claude"; do
    if [ -L "$f" ] || [ -x "$f" ]; then found=1; fi
  done
  [ -n "$found" ] || continue
  name=$(basename "$d")
  mkdir -p "$d/root/.slate" "$d/usr/local/bin"
  cp "$HOME/.slate/slate-bridge.js" "$HOME/.slate/token" "$d/root/.slate/"
  cp "$HOME/.slate/slate-bridge-start" "$d/usr/local/bin/slate-bridge-start"
  chmod +x "$d/usr/local/bin/slate-bridge-start"
  echo "Starting Slate's bridge inside $name, next to Claude Code…"
  exec proot-distro login "$name" -- /usr/local/bin/slate-bridge-start
done
cat <<'MSG'
Claude Code isn't installed. On Android it needs a Linux distro inside Termux (one-time):
  pkg install proot-distro
  proot-distro install ubuntu
  proot-distro login ubuntu
    apt update && apt install -y curl
    curl -fsSL https://claude.ai/install.sh | bash
    ~/.local/bin/claude          (sign in, then /exit)
    exit
Then run slate-claude again (in Termux, not inside Ubuntu).
MSG
exit 1
