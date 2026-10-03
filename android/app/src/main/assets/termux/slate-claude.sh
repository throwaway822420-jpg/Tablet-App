#!/data/data/com.termux/files/usr/bin/sh
# Slate: starts the bridge next to Claude Code. In Termux if Claude Code runs there; otherwise inside
# the proot-distro Linux where it's installed. Everything goes through proot-distro itself (no guessing
# where distros keep their files): it's asked whether Claude Code is there, and the bridge is sent in
# on stdin, so nothing depends on quoting (proot-distro login joins a command's words with spaces).
termux-wake-lock 2>/dev/null || true
pkill -f 'node .*/[.]slate/slate-bridge[.]js$' 2>/dev/null && sleep 1
if claude --version >/dev/null 2>&1; then
  exec node "$HOME/.slate/slate-bridge.js" "$@"
fi

if command -v proot-distro >/dev/null 2>&1; then
  ROOTS="${SLATE_ROOTFS_DIR:-$PREFIX/var/lib/proot-distro/installed-rootfs}"
  distros="${SLATE_DISTRO:-$(ls "$ROOTS" 2>/dev/null) ubuntu debian}"
  tried=
  for d in $distros; do
    case " $tried " in *" $d "*) continue ;; esac
    tried="$tried $d"
    echo "Looking for Claude Code in $d…"
    if proot-distro login "$d" -- test -e /root/.local/bin/claude -o -e /usr/local/bin/claude -o -e /usr/bin/claude </dev/null >/dev/null 2>&1; then
      echo "Starting Slate's bridge inside $d, next to Claude Code…"
      {
        echo 'set -e'
        echo 'mkdir -p "$HOME/.slate"'
        echo "cat > \"\$HOME/.slate/slate-bridge.js\" <<'SLATE_JS'"
        cat "$HOME/.slate/slate-bridge.js"
        echo 'SLATE_JS'
        echo "printf '%s' '$(cat "$HOME/.slate/token")' > \"\$HOME/.slate/token\""
        cat "$HOME/.slate/slate-bridge-start"
      } | proot-distro login "$d" -- sh -s
      exit $?
    fi
  done
  [ -n "$tried" ] && echo "No Claude Code found in:$tried"
fi

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
