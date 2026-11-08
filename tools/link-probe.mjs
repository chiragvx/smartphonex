// Drives at 20 Hz while recording telemetry, then tells a phone that stopped producing from a link that held messages.
//   node tools/link-probe.mjs <host:port> <token> [seconds] [profile]
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';
const [host, token, secs = 20, profile = 'sim-buggy'] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
let off = 0, seq = 0, tel; const acks = new Map(); const rows = []; const ackLat = [];
const sentAt = new Map();
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') off = m.t - Date.now();
  if (m.type === 'telemetry') { tel = m.data; rows.push([performance.now(), m.data.t, m.data.mode, m.data.failsafeReason]); }
  if (m.type === 'ack') { acks.set(m.seq, m); const s = sentAt.get(m.seq); if (s) ackLat.push(performance.now() - s); }
});
await new Promise(r => ws.on('open', r)); await sleep(1500);
const send = (cmd, a = {}) => { seq = Math.max(seq + 1, Date.now() * 1000); sentAt.set(seq, performance.now()); ws.send(JSON.stringify({ type: 'cmd', seq, sentAt: Date.now() + off, cmd, ...a })); return seq; };
const ask = async (c, a) => { const s = send(c, a); for (let i = 0; i < 100 && !acks.has(s); i++) await sleep(10); return acks.get(s); };
const start = tel.profile;
await ask('stop'); await ask('set_mode', { mode: 'DISARMED' }); await ask('select_profile', { id: profile }); await sleep(600);
console.log('arm:', (await ask('set_mode', { mode: 'MANUAL' }))?.code);
rows.length = 0; ackLat.length = 0;
const t0 = performance.now();
let k = 0;
while (performance.now() - t0 < secs * 1000) { send('drive', { throttle: 0.2 * Math.sin(k / 20), steering: 0.3 }); k++; await sleep(50); }
await ask('stop'); await ask('set_mode', { mode: 'DISARMED' }); await ask('select_profile', { id: start });
const q = (a, p) => [...a].sort((x, y) => x - y)[Math.floor(a.length * p)];
console.log(`drive acks: p50 ${q(ackLat, .5).toFixed(0)} p99 ${q(ackLat, .99).toFixed(0)} max ${Math.max(...ackLat).toFixed(0)} ms, ${ackLat.length} of ${k}`);
const fs = rows.find(r => r[2] === 'FAILSAFE');
console.log(fs ? `FAILSAFE: ${fs[3]}` : 'no failsafe');
let shown = 0;
for (let i = 1; i < rows.length && shown < 8; i++) {
  const arrive = rows[i][0] - rows[i - 1][0], made = rows[i][1] - rows[i - 1][1];
  if (arrive > 250 || made > 250) { shown++; console.log(`gap: arrived ${arrive.toFixed(0)} ms apart, made ${made} ms apart (${made > 250 ? 'phone stalled' : 'link held it'})`); }
}
console.log(`telemetry ${rows.length} in ${secs}s`);
process.exit(0);
