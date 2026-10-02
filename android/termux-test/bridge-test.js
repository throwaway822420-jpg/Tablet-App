// Tests Slate's Termux bridge end to end against a fake Claude Code:  node android/termux-test/bridge-test.js
const assert = require('assert');
process.env.SLATE_TOKEN = 'secret123';
process.env.CLAUDE_BIN = __dirname + '/fake-claude';
const { server, parseLine } = require(__dirname + "/../app/src/main/assets/termux/slate-bridge.js");
const http = require('http');
function req(path, body, token) {
  return new Promise((res, rej) => {
    const r = http.request({ host: '127.0.0.1', port: server.address().port, path, method: body ? 'POST' : 'GET', headers: token ? { 'x-slate-token': token } : {} }, resp => {
      let d = ''; resp.on('data', c => d += c); resp.on('end', () => res({ status: resp.statusCode, body: d }));
    });
    r.on('error', rej); if (body) r.write(JSON.stringify(body)); r.end();
  });
}
server.listen(0, '127.0.0.1', async () => {
  try {
    assert.equal(JSON.parse((await req('/health')).body).ok, true);
    assert.equal((await req('/ask', { askId: 'a' }, 'wrong')).status, 401);
    assert.equal((await req('/ask', { askId: 'a' })).status, 401);
    const a = await req('/ask', { askId: 'a1', new: true, title: 'T', system: 'sys', text: 'What is a pole?', images: [{ name: 's.jpg', data: 'AAAA' }] }, 'secret123');
    const lines = a.body.trim().split('\n').map(JSON.parse);
    assert.equal(lines[0].t, 'ask.status');
    const sid = lines[0].session;
    assert.ok(/^[0-9a-f-]{36}$/.test(sid));
    const deltas = lines.filter(l => l.t === 'ask.delta').map(l => l.text).join('');
    assert.equal(deltas, 'You sent 1 image(s): What is a pole?');
    const done = lines.at(-1);
    assert.equal(done.t, 'ask.done'); assert.equal(done.session, sid); assert.equal(done.backend, 'termux');
    assert.ok(done.markdown.includes('[tools off]'));
    const b = await req('/ask', { askId: 'a2', session: sid, text: 'and a zero?' }, 'secret123');
    const bl = b.body.trim().split('\n').map(JSON.parse);
    assert.equal(bl.at(-1).session, sid); // resumed the same session
    const c = await req('/convert', { kind: 'handwriting', model: 'sonnet', system: 's', prompt: 'Transcribe', schema: { type: 'object' }, image: 'AAAA' }, 'secret123');
    assert.equal(c.status, 200);
    assert.equal(JSON.parse(c.body).json.segments[0].text, 'hello');
    const c2 = await req('/convert', { kind: 'handwriting', model: 'sonnet', system: 's', prompt: 'Transcribe', schema: { type: 'object' }, image: 'AAAA' }, 'secret123');
    assert.equal(JSON.parse(c2.body).ok, true); // served by the warm process
    assert.equal(parseLine('{"type":"result","subtype":"error_max_turns","is_error":false}').error, true);
    console.log('bridge tests passed');
    process.exit(0);
  } catch (e) { console.error(e); process.exit(1); }
});
