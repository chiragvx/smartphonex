// The ground run on a running phone: holds the button, prints each step, then what was stored.
//   node tools/ground-run.mjs <host:port> <token> [--forget]
//   node tools/ground-run.mjs --sound <out.wav> [strength] [bend]
// With a real motor: restrain the plane first. Ctrl+C lets go, which stops the motor.
// Without one, --sound writes a test sound that steps the way a motor does at the default throttles (30, 45, 60%):
// [strength] scales every speed (0.93 is a weaker pack), [bend] changes the curve's shape (a different propeller).
// Start the run, then play the sound to the phone. --forget clears the stored runs afterwards, so test sounds
// don't become the reference for a real motor.
import { writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const motor = (u, strength, bend, i) => (40 + 220 * u - 60 * u * u) * strength * (1 + bend * i);

if (args[0] === '--sound') {
  const [, out, s = '1', b = '0'] = args;
  const rate = 44100, steps = [0.3, 0.45, 0.6], secs = [7, 6, 6], tail = 2;
  const n = (secs.reduce((a, x) => a + x, 0) + tail) * rate;
  const pcm = Buffer.alloc(n * 2);
  let phase = 0, k = 0, seed = 1;
  const noise = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 * 2 - 1; };
  steps.forEach((u, i) => {
    const rev = motor(u, Number(s), Number(b), i);
    console.log(`step ${i + 1}: ${Math.round(u * 100)}% throttle, ${Math.round(rev * 60)} rpm for ${secs[i]} s`);
    for (let j = 0; j < secs[i] * rate; j++, k++) {
      phase += rev / rate;
      const p = 2 * Math.PI * phase;
      const v = 0.02 * noise() + 0.2 * Math.sin(2 * p) + 0.12 * Math.sin(4 * p + 1) + 0.05 * Math.sin(6 * p + 2) + 0.06 * Math.sin(7 * p + 0.5);
      pcm.writeInt16LE(Math.round(Math.max(-1, Math.min(1, v)) * 32000), k * 2);
    }
  });
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  writeFileSync(out, Buffer.concat([h, pcm]));
  process.exit(0);
}

const { default: WebSocket } = await import('../relay/node_modules/ws/wrapper.mjs');
const [host, token] = args;
const forget = args.includes('--forget');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
// The phone refuses a sequence number it has already seen from this link, so start from the clock.
let params, ops, fcs, profile, seq = Date.now();
const acks = new Map();
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') { params = m.params; profile = m.activeProfile; }
  if (m.type === 'ops') ops = m;
  if (m.type === 'fcs') fcs = m;
  if (m.type === 'ack') acks.set(m.seq, m);
});
await new Promise(r => ws.on('open', r));
await sleep(1500);
const cmd = async (name, body = {}) => {
  const s = ++seq;
  ws.send(JSON.stringify({ type: 'cmd', seq: s, sentAt: Date.now(), cmd: name, ...body }));
  for (let i = 0; i < 300 && !acks.has(s); i++) await sleep(10);
  return acks.get(s) ?? { accepted: false, message: 'no answer' };
};
// --vehicle <id>: run on that vehicle profile, and switch back afterwards (for testing on a phone set up as another).
const vi = args.indexOf('--vehicle');
const vehicleBefore = profile;
if (vi >= 0 && args[vi + 1] !== profile) {
  const r = await cmd('select_profile', { id: args[vi + 1] });
  if (!r.accepted) { console.log(`couldn't choose ${args[vi + 1]}: ${r.message}`); process.exit(1); }
  profile = args[vi + 1];
  await sleep(1500);
}
const param = n => params?.find(p => p.name === n)?.value;
const micBefore = param('MIC_PROP') ?? 0;
console.log(`vehicle ${profile}, MIC_PROP ${micBefore}, steps ${param('PROP_GR_LO')} to ${param('PROP_GR_HI')}`);
// The microphone must be listening. Turned on for the run if it was off, and put back after.
if (micBefore < 1) { ws.send(JSON.stringify({ type: 'param_set', name: 'MIC_PROP', value: 1 })); await sleep(1500); }
const restore = async () => {
  if (micBefore < 1) { ws.send(JSON.stringify({ type: 'param_set', name: 'MIC_PROP', value: micBefore })); await sleep(500); }
  if (profile !== vehicleBefore) { const r = await cmd('select_profile', { id: vehicleBefore }); console.log(`back to ${vehicleBefore}: ${r.accepted}`); }
};

let stop = false;
process.on('SIGINT', () => { stop = true; });
const first = await cmd('ground_run', { confirmed: true });
if (!first.accepted) { console.log(`refused: ${first.message}`); await restore(); process.exit(1); }
let last = '';
const t0 = Date.now();
while (!stop && Date.now() - t0 < 40_000) {
  ws.send(JSON.stringify({ type: 'cmd', seq: ++seq, sentAt: Date.now(), cmd: 'ground_run', confirmed: true }));
  const g = ops?.groundRun;
  const line = g ? `${g.message}${g.state === 'RUNNING' && fcs?.prop?.rpm ? ` (hearing ${fcs.prop.rpm} rpm)` : ''}` : '';
  if (g && g.message !== last) { console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(5)} s  ${line}`); last = g.message; }
  if (g && g.state !== 'RUNNING' && Date.now() - t0 > 1500) break;
  await sleep(100);
}
await cmd('ground_run_stop');
await sleep(1200);
const g = fcs?.prop?.groundRun;
if (g) {
  console.log(`\nthrottle   ${g.throttles.split(',').map(x => x + '%').join(', ')}`);
  console.log(`reference  ${g.referenceRpm} rpm`);
  console.log(`this run   ${g.lastRpm} rpm`);
  console.log(`pack       ${g.strength}x the reference${g.changed ? `\nchanged    ${g.changed}` : ''}`);
}
const check = ops?.readiness?.find(c => c.id === 'groundrun');
if (check) console.log(`arm check  ${check.status}: ${check.detail}`);
if (forget) { const r = await cmd('ground_run_forget'); console.log(`forgotten: ${r.accepted}`); }
await restore();
ws.close();
process.exit(ops?.groundRun?.state === 'DONE' ? 0 : 1);
