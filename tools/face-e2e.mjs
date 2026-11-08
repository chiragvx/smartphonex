// The face on a running phone: settings, expressions from the ground station and AI, and safety states winning.
//   node tools/face-e2e.mjs <host:port> <token> [screenshot-command]
// With a screenshot command (given the file name as its last argument), it saves a picture of each look.
// Puts the face settings back as it found them.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';
import { execFileSync } from 'node:child_process';

const [host, token, ...shot] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => { ok ? passed++ : failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };
const snap = name => { if (shot.length) { try { execFileSync(shot[0], [...shot.slice(1), name], { stdio: 'ignore' }); console.log(`      saved ${name}`); } catch (e) { console.log(`      no picture: ${e.message}`); } } };

const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
let hello, params, ops, offset = 0, seq = 0;
const acks = new Map(), cfg = [];
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') { hello = m; offset = m.t - Date.now(); params = m.params; }
  if (m.type === 'params') params = m.list;
  if (m.type === 'ops') ops = m;
  if (m.type === 'ack') acks.set(m.seq, m);
  if (m.type === 'cfgAck') cfg.push(m);
});
await new Promise(r => ws.on('open', r));
await sleep(1200);
const cmd = async (name, args = {}) => {
  seq = Math.max(seq + 1, Date.now() * 1000);
  const s = seq;
  ws.send(JSON.stringify({ type: 'cmd', seq: s, sentAt: Date.now() + offset, cmd: name, ...args }));
  for (let i = 0; i < 300 && !acks.has(s); i++) await sleep(10);
  return acks.get(s) ?? { accepted: false, code: 'no_ack' };
};
const set = async (name, value) => {
  const n = cfg.length;
  ws.send(JSON.stringify({ type: 'param_set', name, value }));
  for (let i = 0; i < 300 && cfg.length === n; i++) await sleep(10);
  return cfg.at(-1) ?? {};
};
const param = name => params?.find(p => p.name === name)?.value;
const mcp = async (name, args) => {
  const r = await fetch(`http://${host}/mcp`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } }) });
  return JSON.parse((await r.text()).replace(/^data: /m, '').trim().split('\n').find(l => l.startsWith('{')) ?? '{}');
};

const names = ['FACE_MODE', 'FACE_IDLE_S', 'FACE_STYLE', 'FACE_HUE', 'FACE_BRIGHT'];
check('Face settings are published', names.every(n => param(n) !== undefined), names.map(n => `${n}=${param(n)}`).join(' '));
const before = Object.fromEntries(names.map(n => [n, param(n)]));
check('Bad setting refused', (await set('FACE_STYLE', 7)).ok === false);

await cmd('stop'); await cmd('set_mode', { mode: 'DISARMED' });
check('Robot mode set', (await set('FACE_MODE', 2)).ok === true);
await set('FACE_IDLE_S', 5);
await set('FACE_STYLE', 0); await set('FACE_HUE', 190);
// Nobody touches the phone during this test, so the face comes up by itself within the robot-mode wait.
await sleep(6500);
snap('face-eyes.png');

for (const [label, args, ok] of [['happy', { expression: 'happy', seconds: 8 }, true], ['dead (not requestable)', { expression: 'dead' }, false],
  ['too long', { expression: 'sad', seconds: 4000 }, false], ['nonsense', { expression: 'pizza' }, false]]) {
  const r = await cmd('face_express', args);
  check(`Expression ${label} ${ok ? 'accepted' : 'refused'}`, r.accepted === ok, r.message || r.code);
}
await sleep(1200);
snap('face-happy.png');

const viaAi = await mcp('vehicle_set_face', { expression: 'love', seconds: 6 });
check('AI can set an expression over MCP', viaAi.result?.isError === false, viaAi.result?.content?.[0]?.text?.slice(0, 80) ?? JSON.stringify(viaAi).slice(0, 120));
await sleep(1200);
snap('face-love.png');

await set('FACE_STYLE', 1);
await cmd('face_express', { expression: 'surprised', seconds: 5 });
await sleep(1200);
snap('face-mouth-surprised.png');

await set('FACE_STYLE', 2); await set('FACE_HUE', 45);
await sleep(1500);
snap('face-visor.png');

// Full control from the ground station, with the face set to off.
await set('FACE_STYLE', 0); await set('FACE_HUE', 190); await set('FACE_MODE', 0);
await cmd('face_show', { show: false });
await sleep(900);
check('Hidden on request', ops?.face?.shown === false, JSON.stringify(ops?.face));
check('Show accepted with the face set to off', (await cmd('face_show', { show: true })).accepted === true);
await sleep(900);
check('Ground station sees it showing', ops?.face?.shown === true, JSON.stringify(ops?.face));
for (const e of ['excited', 'confused', 'scared']) {
  check(`Expression ${e} accepted`, (await cmd('face_express', { expression: e, seconds: 5 })).accepted === true);
  await sleep(1000);
  snap(`face-${e}.png`);
}
await cmd('face_express', { expression: 'sad', seconds: 0 });
await sleep(900);
check('Held expression reported', ops?.face?.requested === 'sad' && ops?.face?.held === true, JSON.stringify(ops?.face));
snap('face-sad-held.png');
await cmd('face_express', { expression: 'auto' });
await sleep(900);
check('Automatic clears it', ops?.face?.requested === null, JSON.stringify(ops?.face));
const hideAi = await mcp('vehicle_show_face', { show: false });
await sleep(900);
check('AI can hide it over MCP', hideAi.result?.isError === false && ops?.face?.shown === false, JSON.stringify(ops?.face));
check('Show needs true or false', (await cmd('face_show', { show: 'yes' })).accepted === false);
await cmd('face_show', { show: true });
await sleep(600);

const killed = await cmd('kill');
await sleep(1200);
snap('face-killed.png');
check('Kill shows on the face even with an expression asked for', killed.accepted === true, killed.message || killed.code);
// A kill only clears when a person arms again. Arm for a moment and disarm, as the other suites do.
const rearm = await cmd('set_mode', { mode: 'MANUAL' });
await cmd('stop'); await cmd('set_mode', { mode: 'DISARMED' });
await cmd('face_show', { show: false });
check('Kill cleared by arming again', rearm.accepted === true, rearm.message || rearm.code);

for (const [n, v] of Object.entries(before)) if (v !== undefined) await set(n, v);
await sleep(300);
check('Settings put back', names.every(n => param(n) === before[n]), names.map(n => `${n}=${param(n)}`).join(' '));
ws.close();
console.log(`\n${failed ? `FAILED (${failed} of ${passed + failed})` : `ALL PASSED (${passed})`}`);
process.exit(failed ? 1 : 0);
