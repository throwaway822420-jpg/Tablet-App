// Tests Slate's Termux bridge with Claude Code inside a (fake) proot-distro Ubuntu:  node android/termux-test/proot-test.js
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'slate-proot-'));
const bin = path.join(tmp, 'bin');
const rootfs = path.join(tmp, 'rootfs');
fs.mkdirSync(bin, { recursive: true });
fs.mkdirSync(path.join(rootfs, 'ubuntu/root/.local/bin'), { recursive: true });
fs.copyFileSync(path.join(__dirname, 'fake-proot-distro'), path.join(bin, 'proot-distro'));
fs.chmodSync(path.join(bin, 'proot-distro'), 0o755);
fs.copyFileSync(path.join(__dirname, 'fake-claude'), path.join(rootfs, 'ubuntu/root/.local/bin/claude'));
fs.chmodSync(path.join(rootfs, 'ubuntu/root/.local/bin/claude'), 0o755);

// No claude in plain "Termux": only the fake proot-distro, node and the basics.
fs.symlinkSync(process.execPath, path.join(bin, 'node')); // node alone, not whatever else lives next to it
process.env.PATH = [bin, '/usr/bin', '/bin'].join(':');
process.env.SLATE_ROOTFS_DIR = rootfs;
process.env.SLATE_TOKEN = 'secret';
delete process.env.CLAUDE_BIN;

const bridge = require(path.join(__dirname, '../app/src/main/assets/termux/slate-bridge.js'));
const found = bridge.findClaude();
assert.ok(found, 'should find Claude Code in the distro');
assert.equal(found.where, 'proot-distro ubuntu');
bridge.useClaude(found);

bridge.server.listen(0, '127.0.0.1', () => {
  const system = 'You help a student.\nUse $maths$ and "quotes"; never `rm -rf`.';
  const body = JSON.stringify({ askId: 'p1', new: true, title: 'Poles & zeros', system, text: 'What is a pole?', images: [{ data: 'AAAA' }] });
  const req = http.request({ host: '127.0.0.1', port: bridge.server.address().port, path: '/ask', method: 'POST', headers: { 'x-slate-token': 'secret' } }, res => {
    let d = '';
    res.on('data', c => d += c);
    res.on('end', () => {
      try {
        const done = d.trim().split('\n').map(JSON.parse).at(-1);
        assert.equal(done.t, 'ask.done', d);
        assert.ok(done.markdown.startsWith('You sent 1 image(s): What is a pole? [tools off, mcp off]'), done.markdown);
        assert.ok(done.markdown.endsWith('system=' + JSON.stringify(system)), 'system prompt must arrive intact: ' + done.markdown);
        assert.deepEqual(fs.readdirSync(path.join(rootfs, 'ubuntu/tmp')), []); // argument files cleaned up
        console.log('proot tests passed');
        fs.rmSync(tmp, { recursive: true, force: true });
        process.exit(0);
      } catch (e) { console.error(e); process.exit(1); }
    });
  });
  req.end(body);
});
