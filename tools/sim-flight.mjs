// Flies the phone's simulated plane through a plan, end to end, in real time, and checks what it does when things go
// wrong: the motor stops far from home, and the link is lost. Everything runs on the phone: the real autopilot,
// mission engine and contingency rules, with simulated sensors.
//   node tools/sim-flight.mjs <host:port> <token>
// It switches to the "Simulated plane" profile and back, and leaves no plan behind.
const { default: WebSocket } = await import('../relay/node_modules/ws/wrapper.mjs');
const [host, token] = process.argv.slice(2);
if (!token) { console.log('usage: node tools/sim-flight.mjs <host:port> <token>'); process.exit(2); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
let seq = Date.now(), ops, fcs, profile, params;
const acks = new Map();
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') { profile = m.activeProfile; params = m.params; }
  if (m.type === 'ops') ops = m;
  if (m.type === 'fcs') fcs = m;
  if (m.type === 'ack') acks.set(m.seq, m);
});
await new Promise((r, j) => { ws.on('open', r); ws.on('error', j); });
// The link stays alive while this talks to the phone.
const keepAlive = setInterval(() => ws.send(JSON.stringify({ type: 'ping', c: 0, w: Date.now() })), 500);
await sleep(1500);
const cmd = async (name, body = {}) => {
  const s = ++seq;
  ws.send(JSON.stringify({ type: 'cmd', seq: s, sentAt: Date.now(), cmd: name, ...body }));
  for (let i = 0; i < 500 && !acks.has(s); i++) await sleep(10);
  return acks.get(s) ?? { accepted: false, message: 'no answer' };
};
let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };
const until = async (fn, ms) => { const end = Date.now() + ms; while (Date.now() < end) { if (fn()) return true; await sleep(200); } return false; };
const status = () => {
  const s = ops?.sim ?? {}, m = ops?.mission ?? {};
  return `${m.state ?? '-'} point ${Math.min((m.index ?? 0) + 1, m.total ?? 0)}/${m.total ?? 0} ${m.step ?? ''}${m.lateS !== undefined ? ` late ${m.lateS}s` : ''} | ${ops?.flightMode} | ${s.airspeed} m/s ${s.heightM} m${s.onGround ? ' ground' : ''}${ops?.divert ? ` | to ${ops.divert.zone}${ops.divert.glide ? ' gliding' : ''}` : ''}`;
};
const before = profile;
const metres = () => { const g = fcs?.gps, h = fcs?.home; if (!g || !h) return null; return { e: (g.lon - h.lon) * 111320 * Math.cos(h.lat * Math.PI / 180), n: (g.lat - h.lat) * 111320 }; };

const sel = await cmd('select_profile', { id: 'plane-sim' });
check('The simulated plane is selected', sel.accepted, sel.message);
await sleep(3000);
// Disarming is refused for a few seconds after landing, while the plane may still be airborne: keep asking.
const reset = async () => {
  for (let i = 0; i < 30 && ops?.flightMode !== undefined; i++) { const d = await cmd('set_mode', { mode: 'DISARMED' }); if (d.accepted) break; await sleep(500); }
  await sleep(300);
  const r = await cmd('sim_set', { reset: true, windMps: 0, motorFailed: false, linkCut: false });
  if (!r.accepted) console.log('    reset refused:', r.message);
  await sleep(2500);
};
await reset();
check('Home is set from the simulated GPS', (await cmd('home_set')).accepted && await until(() => !!fcs?.home, 3000));
// The microphone hears the simulated propeller: without it a stopped motor can't be noticed.
await sleep(1000);
const micBefore = params?.find(x => x.name === 'MIC_PROP')?.value ?? 0;
ws.send(JSON.stringify({ type: 'param_set', name: 'MIC_PROP', value: 1 }));
await sleep(500);
let h = fcs.home;
const at = (e, n) => [h.lat + n / 111320, h.lon + e / (111320 * Math.cos(h.lat * Math.PI / 180))];
const makePlan = () => ({
  format: 'smartphonex.plan/1', name: 'Sim check', onLinkLoss: 'home', onEnd: 'home',
  zones: [{ id: 'field', kind: 'landing', at: at(150, 900), radius: 40 }, { id: 'scrub', kind: 'ditch', at: at(-300, 500), radius: 30 }],
  steps: [
    { do: 'takeoff', id: 'up', heading: 0, alt: 60 },
    { do: 'goto', id: 'out', at: at(0, 1100), alt: 80 },
    { do: 'home', id: 'back', alt: 80 },
  ],
});
let plan = makePlan();
const fly = async () => {
  let up = await cmd('plan_upload', { plan });
  // Advisory notices are read and acknowledged, as a person would: they never stop the plan, and the phone logs it.
  if (up.code === 'needs_ack') {
    const adv = up.data?.advisories ?? [];
    for (const a of adv) console.log(`    notice: ${a.text}`);
    up = await cmd('plan_upload', { plan, acknowledged: adv.map(a => a.id) });
  }
  check('The plan is sent, checked against home', up.accepted, up.message);
  // Just after a reset the position is still settling: give it a few seconds.
  let start;
  for (let i = 0; i < 20; i++) { start = await cmd('mission_start', { hash: up.data?.hash }); if (start.accepted || !/settl|jump/.test(start.message)) break; await sleep(500); }
  check('It starts', start.accepted, start.message);
  check('It launches and climbs', await until(() => (ops?.sim?.heightM ?? 0) > 50, 60000), status());
};

// 1. The motor stops 700 m out at 80 m: home is beyond gliding reach (about 640 m), the field is about 250 m away.
console.log('Flight 1: the motor stops far from home');
await fly();
check('It flies out along the plan', await until(() => (metres()?.n ?? 0) > 700, 120000), status());
console.log('   ', status());
await cmd('sim_set', { motorFailed: true });
check('The phone hears the motor stop', await until(() => !!ops?.motorOut, 6000), ops?.motorOut ?? status());
check('It glides to the field, not home', await until(() => ops?.divert?.zone === 'field' && ops?.divert?.glide, 3000), status());
const landed = await until(() => ops?.sim?.onGround, 120000);
const p = metres();
const fromField = p ? Math.hypot(p.e - 150, p.n - 900) : NaN;
check('It comes down by the field', landed && fromField < 150, `${Math.round(fromField)} m from its centre, ${ops?.sim?.crashed ? 'hard landing' : 'landed'}: ${ops?.sim?.touchdown}`);

// 2. The link is lost 400 m out: the plan's rule is home, and home is in reach on the time left.
console.log('Flight 2: the link is lost');
await reset();
await cmd('home_set');
await until(() => fcs?.home && Math.hypot(fcs.home.lat - h.lat, fcs.home.lon - h.lon) < 1e-4, 3000);
h = fcs.home; plan = makePlan();
await fly();
check('It flies out', await until(() => (metres()?.n ?? 0) > 400, 120000), status());
await cmd('sim_set', { linkCut: true });
check('Losing the link sends it home', await until(() => ops?.divert?.zone === 'home' && ops?.flightMode === 'RETURN_HOME', 6000), status());
const d0 = metres()?.n ?? 0;
await sleep(12000);
check('And it heads home', (metres()?.n ?? 1e9) < d0 - 50, `${Math.round(d0)} m out, then ${Math.round(metres()?.n ?? NaN)} m`);
console.log('   ', status());

// What the flights taught it about planning.
const learned = await cmd('plan_learning');
const m = learned.data?.measured ?? {};
check('The flights measured its climbs', (m.climbs ?? 0) > 0, JSON.stringify(m));
for (const sg of learned.data?.suggestions ?? []) console.log(`    suggests ${sg.param} ${sg.current} -> ${sg.suggested}: ${sg.basis}`);
await cmd('plan_learning_forget');

// Leave things as they were.
await cmd('sim_set', { linkCut: false });
await cmd('stop');
await sleep(500);
await reset();
const cleared = await cmd('mission_clear');
if (!cleared.accepted) console.log('    plan not cleared:', cleared.message);
ws.send(JSON.stringify({ type: 'param_set', name: 'MIC_PROP', value: micBefore }));
await sleep(500);
const back = await cmd('select_profile', { id: before });
console.log(`back to ${before}: ${back.accepted}`);
clearInterval(keepAlive);
ws.close();
console.log(failed ? `\n${failed} FAILED` : '\nALL PASSED');
process.exit(failed ? 1 : 0);
