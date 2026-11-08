// How well the microphone hears and follows a propeller changing speed, on a running phone.
//   node tools/prop-sweep.mjs <host:port> <token> <schedule.json> [lead-seconds]
// Make the sound and schedule with tools/prop-sweep.py, start this, then play the sound to the phone. It records every
// propeller reading (about two a second), lines them up with the schedule, and reports accuracy at each speed, the
// lag after each jump, how closely it follows ramps, and how fast it notices the sound stopping.
import { readFileSync } from 'node:fs';
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';

const [host, token, schedFile, leadArg = '0'] = process.argv.slice(2);
// Seconds of room sound before the test sound starts: steady tones heard then (a fan) become background.
const lead = Number(leadArg);
const sched = JSON.parse(readFileSync(schedFile, 'utf8'));
const segs = [];
let at = 0;
for (const s of sched.segments) { segs.push({ ...s, t0: at, t1: at + s.s }); at += s.s; }
const total = at;
const expected = t => { const s = segs.find(g => t >= g.t0 && t < g.t1); return s ? s.from + (s.to - s.from) * (t - s.t0) / s.s : 0; };

const sleep = ms => new Promise(r => setTimeout(r, ms));
const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
let params;
const cfg = [];
const readings = [];
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') params = m.params;
  if (m.type === 'cfgAck') cfg.push(m);
  if (m.type === 'fcs' && m.prop) readings.push({ t: m.t / 1000, rpm: m.prop.rpm ?? null, snr: m.prop.snr ?? null, h: m.prop.harmonics, bg: m.prop.background });
});
await new Promise(r => ws.on('open', r));
await sleep(1200);
const set = async (name, value) => {
  const n = cfg.length;
  ws.send(JSON.stringify({ type: 'param_set', name, value }));
  for (let i = 0; i < 300 && cfg.length === n; i++) await sleep(10);
};
const param = name => params?.find(p => p.name === name)?.value;
const before = { MIC_PROP: param('MIC_PROP') ?? 0, PROP_BLADES: param('PROP_BLADES') ?? 2 };
await set('MIC_PROP', 1);
await set('PROP_BLADES', sched.blades);
console.log(`recording for ${lead + total + 12} s: play the sound in ${lead} s`);
readings.length = 0;
await sleep((lead + total + 12) * 1000);
await set('MIC_PROP', before.MIC_PROP);
await set('PROP_BLADES', before.PROP_BLADES);
ws.close();

// Line the readings up with the schedule: the playback start isn't known, so take the offset that fits best,
// ignoring the second after every change.
const changes = segs.slice(1).filter((g, i) => g.from !== segs[i].to || g.from !== g.to).map(g => g.t0);
const settled = t => !changes.some(c => t >= c && t < c + 1);
const start = readings[0]?.t ?? 0;
let best = { off: 0, n: -1 };
for (let off = lead - 2; off <= lead + 14; off += 0.05) {
  let n = 0;
  for (const r of readings) {
    const t = r.t - start - off, e = expected(t);
    if (e > 0 && r.rpm != null && settled(t) && Math.abs(r.rpm - e) / e < 0.03) n++;
  }
  if (n > best.n) best = { off, n };
}
const rel = readings.map(r => ({ ...r, t: r.t - start - best.off }));
const pct = (r, e) => (r - e) / e * 100;
const med = a => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : NaN; };
const f1 = x => Number.isFinite(x) ? x.toFixed(1) : '-';
const worst = a => a.length ? Math.max(...a) : NaN;

console.log(`\n${readings.length} readings, aligned with ${best.n} in agreement\n`);
console.log('Holding a speed (after the first second):');
console.log('  rpm     readings  heard  median err  worst err  within 1%  within 3%  snr    harmonics of the revolution heard');
let holdsOk = 0, holds = 0;
for (const g of segs.filter(g => g.from === g.to && g.from > 0)) {
  const rs = rel.filter(r => r.t >= g.t0 + 1 && r.t < g.t1);
  const got = rs.filter(r => r.rpm != null);
  const errs = got.map(r => Math.abs(pct(r.rpm, g.from)));
  const w1 = errs.filter(e => e < 1).length, w3 = errs.filter(e => e < 3).length;
  const hs = [...new Set(got.map(r => r.h).filter(Boolean))].slice(0, 2).join(' | ');
  holds++; if (rs.length && w3 >= rs.length * 0.8) holdsOk++;
  console.log(`  ${String(g.from).padEnd(7)} ${String(rs.length).padEnd(9)} ${String(got.length).padEnd(6)} ${(f1(med(errs)) + '%').padEnd(11)} ${(f1(worst(errs)) + '%').padEnd(10)} ${String(w1).padEnd(10)} ${String(w3).padEnd(10)} ${f1(med(got.map(r => r.snr))).padEnd(5)}  ${hs}`);
}

console.log('\nAfter a jump: time until it reads within 3% and stays there:');
for (let i = 1; i < segs.length; i++) {
  const g = segs[i], p = segs[i - 1];
  if (g.from === p.to || g.from === 0) continue;
  const rs = rel.filter(r => r.t >= g.t0 - 0.3 && r.t < g.t1);
  let lag = null;
  for (let k = 0; k + 1 < rs.length; k++) {
    const ok = r => r.rpm != null && Math.abs(pct(r.rpm, expected(r.t))) < 3;
    if (rs[k].t >= g.t0 && ok(rs[k]) && ok(rs[k + 1])) { lag = rs[k].t - g.t0; break; }
  }
  console.log(`  ${p.to} -> ${g.from} rpm: ${lag == null ? 'never settled' : `${lag.toFixed(2)} s`}`);
}

console.log('\nFollowing a ramp:');
for (const g of segs.filter(g => g.from !== g.to)) {
  const rs = rel.filter(r => r.t >= g.t0 + 0.5 && r.t < g.t1 && r.rpm != null);
  const errs = rs.map(r => Math.abs(pct(r.rpm, expected(r.t))));
  // The steady lag that best explains the readings.
  let lagBest = { l: 0, e: Infinity };
  for (let l = 0; l <= 1.5; l += 0.02) {
    const e = rs.reduce((a, r) => a + Math.abs(r.rpm - expected(r.t - l)), 0);
    if (e < lagBest.e) lagBest = { l, e };
  }
  const rate = Math.abs(g.to - g.from) / g.s;
  console.log(`  ${g.from} -> ${g.to} rpm over ${g.s} s (${Math.round(rate)} rpm/s): ${rs.length} readings, median err ${f1(med(errs))}%, worst ${f1(worst(errs))}%, lag about ${lagBest.l.toFixed(2)} s`);
}

const last = segs.at(-1);
if (last.from === 0) {
  const after = rel.filter(r => r.t >= last.t0);
  const gone = after.find(r => r.rpm == null);
  console.log(`\nSound stopped: ${gone ? `reported gone after ${(gone.t - last.t0).toFixed(2)} s` : 'still reporting a tone'}`);
}
const bgs = [...new Set(readings.map(r => r.bg).filter(Boolean))];
console.log(`Background tones being ignored: ${bgs.length ? bgs.at(-1) + ' Hz' : 'none'}`);
const wild = rel.filter(r => r.rpm != null && expected(r.t) > 0 && settled(r.t) && Math.abs(pct(r.rpm, expected(r.t))) > 10).length;
const ghosts = rel.filter(r => r.rpm != null && expected(r.t) === 0 && r.t > 0.5 && r.t < total).length;
console.log(`Wrong by over 10% while steady: ${wild}. Tones reported during silence: ${ghosts}.`);
console.log(`\n${holdsOk === holds ? 'PASS' : 'FAIL'}  ${holdsOk} of ${holds} speeds held within 3% for 80% of readings`);
process.exit(holdsOk === holds ? 0 : 1);
