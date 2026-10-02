#!/usr/bin/env node
// Slate ↔ Claude Code bridge for Termux.
//
// Runs on the tablet next to Claude Code (signed in with your Claude subscription) and lets the
// Slate app use it: questions about the screen, chat, and optionally handwriting and calculator.
// Listens on 127.0.0.1 only and needs the token Slate generated during setup, so other apps
// can't use your subscription through it. No dependencies beyond Node.
//
//   slate-claude            (installed by Slate's setup command)
//   node slate-bridge.js    (directly; SLATE_PORT, SLATE_TOKEN, CLAUDE_BIN override the defaults)

'use strict';
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const VERSION = 1;
const HOME = path.join(os.homedir(), '.slate');
const PORT = Number(process.env.SLATE_PORT || 47820);
const PREFIX_DIR = process.env.PREFIX || '/data/data/com.termux/files/usr';
const TOKEN = (process.env.SLATE_TOKEN || readFile(path.join(HOME, 'token')) || '').trim();
const WORK = path.join(HOME, 'work');
const IDLE_MS = 5 * 60 * 1000;

fs.mkdirSync(WORK, { recursive: true });

function readFile(p) {
  try { return fs.readFileSync(p, 'utf8'); } catch { return null; }
}

// --- Finding Claude Code: plain Termux, or inside a proot-distro Linux (where its native build runs) ---

const { spawnSync } = require('child_process');

/** How to start Claude Code: a command plus the arguments that go before Claude Code's own. */
function findClaude() {
  if (process.env.CLAUDE_BIN) return { cmd: process.env.CLAUDE_BIN, pre: [], where: process.env.CLAUDE_BIN };
  const works = (cmd, pre) => {
    const r = spawnSync(cmd, [...pre, '--version'], { encoding: 'utf8', timeout: 60000 });
    return r.status === 0 && /claude/i.test(r.stdout || '');
  };
  if (works('claude', [])) return { cmd: 'claude', pre: [], where: 'Termux' };
  // Claude Code's native build needs a regular Linux: look in proot-distro installs (Ubuntu, Debian, …).
  const rootfs = path.join(PREFIX_DIR, 'var/lib/proot-distro/installed-rootfs');
  let distros = [];
  try { distros = fs.readdirSync(rootfs); } catch {}
  if (process.env.SLATE_DISTRO) distros = [process.env.SLATE_DISTRO];
  for (const d of distros) {
    const pre = ['login', d, '--', 'sh', '-c', 'PATH="$HOME/.local/bin:$PATH"; exec claude "$@"', 'claude'];
    if (works('proot-distro', pre)) return { cmd: 'proot-distro', pre, where: `proot-distro ${d}` };
  }
  return null;
}

let CLAUDE = null;

function log(...a) {
  console.log(new Date().toTimeString().slice(0, 8), ...a);
}

// --- Claude Code's stream-json output → Slate events (same meaning as the PC app's parser) ---

function parseLine(line) {
  if (!line || line[0] !== '{') return null;
  let o;
  try { o = JSON.parse(line); } catch { return null; }
  if (o.type === 'system' && o.subtype === 'init') return { kind: 'started', session: o.session_id || '' };
  if (o.type === 'stream_event' && (o.parent_tool_use_id == null)) {
    const ev = o.event || {};
    if (ev.type === 'content_block_delta' && ev.delta && ev.delta.type === 'text_delta') return { kind: 'text', text: ev.delta.text || '' };
    if (ev.type === 'content_block_start' && ev.content_block && ev.content_block.type === 'tool_use') return { kind: 'tool', name: ev.content_block.name };
    return null;
  }
  if (o.type === 'result') {
    return {
      kind: 'done',
      result: o.result || '',
      structured: o.structured_output,
      session: o.session_id || '',
      cost: Number(o.total_cost_usd || 0),
      error: Boolean(o.is_error) || o.subtype !== 'success',
    };
  }
  return null;
}

/** The single user message, with images inline (no tool round trip to read them). */
function userMessage(images, text) {
  const content = (images || []).map(img => ({
    type: 'image',
    source: { type: 'base64', media_type: img.type || 'image/jpeg', data: img.data },
  }));
  content.push({ type: 'text', text: text || '(see the image)' });
  return JSON.stringify({ type: 'user', message: { role: 'user', content } }) + '\n';
}

function startClaude(args) {
  const child = spawn(CLAUDE.cmd, [...CLAUDE.pre, '-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', ...args], {
    cwd: WORK,
    stdio: ['pipe', 'pipe', 'pipe'],
    env: process.env,
  });
  child.stderrText = '';
  child.stderr.on('data', d => { child.stderrText = (child.stderrText + d).slice(-4000); });
  child.on('error', e => { child.stderrText += String(e.message || e); });
  return child;
}

/** Feeds one message to a started process and reports events until it finishes. */
function run(child, message, onEvent) {
  return new Promise(resolve => {
    let buf = '';
    let done = null;
    let streamed = '';
    child.stdout.on('data', d => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const ev = parseLine(buf.slice(0, i).trim());
        buf = buf.slice(i + 1);
        if (!ev) continue;
        if (ev.kind === 'text') streamed += ev.text;
        if (ev.kind === 'done') done = ev;
        onEvent && onEvent(ev);
      }
    });
    child.on('close', code => resolve({ done, streamed, errors: child.stderrText.trim(), code }));
    child.stdin.on('error', () => {});
    child.stdin.end(message);
  });
}

// --- Warm processes for handwriting / calculator: Claude Code starts while you're still writing ---

const warm = new Map(); // key → { child, args, timer }

function warmKey(args) { return JSON.stringify(args); }

function takeWarm(args) {
  const key = warmKey(args);
  const w = warm.get(key);
  warm.delete(key);
  if (w) clearTimeout(w.timer);
  const child = w && w.child.exitCode === null ? w.child : startClaude(args);
  // Start the next one now, so the following conversion doesn't wait for start-up either.
  const next = startClaude(args);
  const timer = setTimeout(() => { if (warm.get(key) && warm.get(key).child === next) { warm.delete(key); next.kill(); } }, IDLE_MS);
  next.on('close', () => { if (warm.get(key) && warm.get(key).child === next) warm.delete(key); });
  warm.set(key, { child: next, timer });
  return child;
}

// --- HTTP ---

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', c => {
      size += c.length;
      if (size > 40 * 1024 * 1024) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

function authorised(req) {
  const got = String(req.headers['x-slate-token'] || '');
  if (!TOKEN || got.length !== TOKEN.length) return false;
  return crypto.timingSafeEqual(Buffer.from(got), Buffer.from(TOKEN));
}

function json(res, status, obj) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(obj));
}

/** A question or chat turn: streams Slate's ask.status / ask.delta / ask.done messages as NDJSON. */
async function ask(req, res) {
  const b = await readBody(req);
  const askId = String(b.askId || '');
  const fresh = !b.session || b.new === true;
  const session = fresh ? crypto.randomUUID() : String(b.session);
  const args = ['--tools', '', '--permission-mode', 'dontAsk'];
  if (b.system) args.push('--system-prompt', String(b.system));
  if (b.model) args.push('--model', String(b.model));
  if (fresh) args.push('--session-id', session, '--name', 'Slate: ' + (b.title || 'Study'));
  else args.push('--resume', session);

  res.writeHead(200, { 'content-type': 'application/x-ndjson', 'cache-control': 'no-store' });
  const send = o => res.write(JSON.stringify(o) + '\n');
  send({ t: 'ask.status', askId, state: 'thinking', message: 'Claude is thinking…', session });
  log('ask', askId, fresh ? 'new session' : 'session ' + session);

  const child = startClaude(args);
  res.on('close', () => { if (child.exitCode === null) child.kill(); });
  const out = await run(child, userMessage(b.images, b.text), ev => {
    if (ev.kind === 'text') send({ t: 'ask.delta', askId, text: ev.text });
  });
  const d = out.done;
  if (!d || d.error) {
    let why = (d && d.result) || out.errors || `Claude Code exited with code ${out.code}.`;
    if (/login|auth|credential/i.test(why)) why += ' — run `claude` in Termux once and sign in.';
    if (/ENOENT|native binary/.test(why)) why = 'Claude Code didn\'t start in Termux. Run slate-claude again to see why.';
    send({ t: 'ask.status', askId, state: 'error', message: why, session });
  } else {
    send({ t: 'ask.done', askId, session: d.session || session, backend: 'termux', title: b.title || '', markdown: d.result || out.streamed, cost: d.cost });
  }
  res.end();
}

/** Handwriting or calculator: one image in, structured JSON out. */
async function convert(req, res) {
  const b = await readBody(req);
  const args = ['--tools', '', '--permission-mode', 'dontAsk', '--no-session-persistence'];
  if (b.system) args.push('--system-prompt', String(b.system));
  if (b.model) args.push('--model', String(b.model));
  if (b.effort) args.push('--effort', String(b.effort));
  if (b.schema) args.push('--json-schema', typeof b.schema === 'string' ? b.schema : JSON.stringify(b.schema));
  const started = Date.now();
  const child = takeWarm(args);
  const out = await run(child, userMessage(b.image ? [{ data: b.image, type: b.type || 'image/png' }] : [], b.prompt));
  const d = out.done;
  log('convert', b.model || '', `${Date.now() - started} ms`);
  if (!d || d.error) return json(res, 502, { ok: false, error: (d && d.result) || out.errors || `Claude Code exited with code ${out.code}.` });
  let value = d.structured;
  if (value === undefined || value === null) {
    const text = d.result || out.streamed;
    const m = text.match(/\{[\s\S]*\}/);
    try { value = JSON.parse(m ? m[0] : text); } catch { return json(res, 502, { ok: false, error: 'Unexpected reply: ' + text.slice(0, 200) }); }
  }
  json(res, 200, { ok: true, json: value, cost: d.cost });
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') return json(res, 200, { ok: true, version: VERSION, authorised: authorised(req) });
    if (!authorised(req)) return json(res, 401, { ok: false, error: 'Wrong or missing token: run Slate\'s Termux setup again.' });
    if (req.method === 'POST' && req.url === '/ask') return await ask(req, res);
    if (req.method === 'POST' && req.url === '/convert') return await convert(req, res);
    json(res, 404, { ok: false, error: 'not found' });
  } catch (e) {
    log('error', e && e.message);
    if (!res.headersSent) json(res, 500, { ok: false, error: String(e && e.message || e) });
    else res.end();
  }
});

if (require.main === module) {
  if (!TOKEN) {
    console.error('No token: run the setup command from Slate (main screen › Claude Code on this tablet).');
    process.exit(1);
  }
  log('Looking for Claude Code…');
  CLAUDE = findClaude();
  if (!CLAUDE) {
    console.error([
      'Claude Code doesn\'t run here. On Android it needs a Linux distro inside Termux (one-time):',
      '  pkg install proot-distro',
      '  proot-distro install ubuntu',
      '  proot-distro login ubuntu',
      '    apt update && apt install -y curl',
      '    curl -fsSL https://claude.ai/install.sh | bash',
      '    ~/.local/bin/claude          (sign in, then /exit)',
      '    exit',
      'Then run slate-claude again.',
    ].join('\n'));
    process.exit(1);
  }
  server.on('error', e => {
    if (e.code === 'EADDRINUSE') console.error(`Another Slate bridge is already running on port ${PORT}. Stop it (Ctrl+C in its Termux session, or: pkill -f slate-bridge.js) and run slate-claude again.`);
    else console.error(e.message);
    process.exit(1);
  });
  server.listen(PORT, '127.0.0.1', () => log(`Slate bridge ready on 127.0.0.1:${PORT}, using Claude Code in ${CLAUDE.where}. Leave this running; Ctrl+C stops it.`));
}

module.exports = { parseLine, userMessage, server, findClaude, useClaude: c => { CLAUDE = c; } };
