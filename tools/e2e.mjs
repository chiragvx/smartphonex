// Phase 1 acceptance test, run live against the phone.
//   adb forward tcp:8080 tcp:8080
//   node tools/e2e.mjs <token> [host:port]
const token = process.argv[2];
const host = process.argv[3] || 'localhost:8080';
if (!token) { console.error('usage: node tools/e2e.mjs <token> [host:port]'); process.exit(2); }

const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
function check(name, ok, detail = '') {
  ok ? passed++ : failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  [' + detail + ']' : ''}`);
}

let rpcId = 0;
async function mcp(method, params, auth = token) {
  const res = await fetch(`http://${host}/mcp`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', authorization: `Bearer ${auth}` },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }),
  });
  if (res.status !== 200) return { status: res.status };
  return { status: 200, ...(await res.json()) };
}
async function tool(name, args = {}) {
  const r = await mcp('tools/call', { name, arguments: args });
  if (r.status !== 200) return { httpStatus: r.status };
  return { ...JSON.parse(r.result.content[0].text), isError: r.result.isError };
}

class Link {
  constructor() { this.tel = null; this.acks = new Map(); this.offset = 0; this.telCount = 0; this.rtts = []; this.seq = 0; }
  open() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(`ws://${host}/ws?token=${token}`);
      this.ws.onmessage = e => this.on(JSON.parse(e.data));
      this.ws.onerror = () => reject(new Error('ws error'));
      this.closed = new Promise(r => { this.ws.onclose = r; });
      const t = setInterval(() => { if (this.hello) { clearInterval(t); resolve(this); } }, 10);
    });
  }
  on(m) {
    if (m.type === 'hello') { this.hello = m; this.offset = m.t - Date.now(); }
    if (m.type === 'telemetry' && (!this.tel || m.data.t >= this.tel.t)) { this.tel = m.data; this.telCount++; }
    if (m.type === 'ack') this.acks.set(m.seq, m);
    if (m.type === 'pong') { const rtt = performance.now() - m.c; this.rtts.push(rtt); this.offset = m.t + rtt / 2 - Date.now(); }
  }
  nextSeq() { this.seq = Math.max(this.seq + 1, Date.now() * 1000); return this.seq; }
  now() { return Date.now() + this.offset; }
  raw(o) { this.ws.send(typeof o === 'string' ? o : JSON.stringify(o)); }
  async send(cmd, args = {}, over = {}) {
    const seq = over.seq ?? this.nextSeq();
    this.acks.delete(seq);
    this.raw({ type: 'cmd', seq, sentAt: over.sentAt ?? this.now(), cmd, ...args });
    for (let i = 0; i < 300; i++) {
      if (this.acks.has(seq)) return this.acks.get(seq);
      await sleep(10);
    }
    return { accepted: false, code: 'no_ack' };
  }
  fault(fault, value) { this.raw({ type: 'fault', fault, value }); }
  close() { this.ws.close(); }
}

async function settleStopped(l, ms = 5000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (l.tel && Math.abs(l.tel.speed) < 0.1) return true; await sleep(50); }
  return false;
}

async function main() {
  // --- Auth
  check('MCP rejects a wrong token', (await mcp('ping', {}, 'wrong')).status === 401);
  const badWs = new WebSocket(`ws://${host}/ws?token=wrong`);
  check('Ground link rejects a wrong token', await new Promise(r => { badWs.onmessage = () => r(false); badWs.onclose = () => r(true); setTimeout(() => r(false), 3000); }));

  // --- Pairing
  const pairTry = code => fetch(`http://${host}/pair`, { method: 'POST', body: JSON.stringify({ code }) });
  const getCode = async () => (await (await fetch(`http://${host}/pair/code`, { headers: { authorization: `Bearer ${token}` } })).json()).code;
  check('Pairing code is not readable without the token', (await fetch(`http://${host}/pair/code`)).status === 401);
  const code1 = await getCode();
  check('Wrong pairing code refused', (await pairTry(code1 === '000000' ? '000001' : '000000')).status === 401);
  const paired = await pairTry(code1);
  check('Right pairing code returns the token', paired.status === 200 && (await paired.json()).token === token);
  const code2 = await getCode();
  check('Pairing code changes after use', code2 !== code1 && (await pairTry(code1)).status === 401);
  await pairTry(code2); // Success clears the failure count.

  // These checks use the simulated car. The selected vehicle is restored at the end.
  const setup = await new Link().open();
  const originalProfile = setup.hello.activeProfile;
  await setup.send('stop');
  await setup.send('set_mode', { mode: 'DISARMED' });
  await setup.send('select_profile', { id: 'sim-buggy' });
  setup.close();
  await sleep(200);

  const l = await new Link().open();
  await sleep(300);
  check('Phone and ground station communicate both ways', !!l.tel && l.hello.vehicle === 'sim-rc-car');
  await l.send('stop');
  l.fault('sim_reset', 0);
  await sleep(200);
  await l.send('set_mode', { mode: 'DISARMED' });

  // --- MCP handshake
  const init = await mcp('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } });
  check('MCP initialize', init.result?.protocolVersion === '2025-06-18');
  const list = await mcp('tools/list', {});
  const names = list.result.tools.map(t => t.name);
  check('MCP lists vehicle tools with valid names', names.length >= 8 && names.every(n => /^[a-zA-Z0-9_-]{1,64}$/.test(n)), names.join(' '));
  check('MCP unknown method errors', (await mcp('nope', {})).error?.code === -32601);
  const caps = await tool('vehicle_get_capabilities');
  check('AI can read capabilities and limits', caps.limits?.maxSpeedMps > 0 && caps.sensorsAndActuators.includes('speed'));

  // --- Manual path
  check('Drive refused when disarmed', (await l.send('drive', { throttle: 0.3, steering: 0 })).code === 'not_permitted');
  await l.send('set_mode', { mode: 'MANUAL' });
  let driving = true;
  const stream = (async () => { while (driving) { l.raw({ type: 'cmd', seq: l.nextSeq(), sentAt: l.now(), cmd: 'drive', throttle: 0.4, steering: 0 }); await sleep(50); } })();
  await sleep(1500);
  check('Manual browser commands move the car', l.tel.mode === 'MANUAL' && l.tel.speed > 0.5, `speed ${l.tel.speed}`);
  const mcpTel = await tool('vehicle_get_telemetry', { samples: 5 });
  check('Same telemetry reaches the AI tools', mcpTel.latest.speed > 0.5 && mcpTel.recent.length === 5);
  check('AI is refused while a human has control', (await tool('vehicle_set_speed', { mps: 1 })).code === 'not_permitted');
  driving = false; await stream;

  // --- Failsafe on lost manual link
  await sleep(900);
  check('Manual command loss triggers failsafe', l.tel.mode === 'FAILSAFE', l.tel.failsafeReason);
  check('Car stops in failsafe', await settleStopped(l));
  check('AI cannot clear a failsafe', (await tool('vehicle_set_mode', { mode: 'AUTO' })).code === 'not_permitted');

  // --- Validation
  await l.send('set_mode', { mode: 'MANUAL' });
  check('Out-of-range rejected', (await l.send('drive', { throttle: 9, steering: 0 })).code === 'out_of_range');
  const dupSeq = l.nextSeq();
  await l.send('drive', { throttle: 0, steering: 0 }, { seq: dupSeq });
  l.acks.delete(dupSeq);
  check('Duplicate rejected', (await l.send('drive', { throttle: 0, steering: 0 }, { seq: dupSeq })).code === 'duplicate');
  check('Stale rejected', (await l.send('drive', { throttle: 0, steering: 0 }, { sentAt: l.now() - 30000 })).code === 'stale');
  const badSeq = l.nextSeq();
  l.raw({ type: 'cmd', seq: badSeq, sentAt: l.now(), cmd: 'drive', throttle: 'lots', steering: 0 });
  await sleep(200);
  check('Malformed rejected', l.acks.get(badSeq)?.code === 'malformed');
  const rejBefore = l.tel.rejected;
  l.raw('{not json');
  await sleep(300);
  check('Non-JSON rejected and link survives', l.tel.rejected > rejBefore && l.ws.readyState === 1);
  check('Rejections reach the MCP path too', (await tool('vehicle_set_throttle', { value: 'x' })).code === 'malformed');
  await l.send('stop');

  // --- The PRD's AI scenario: forward, turn, hold speed, report, stop
  await l.send('set_mode', { mode: 'DISARMED' });
  await sleep(100);
  l.fault('sim_reset', 0);
  await l.send('set_mode', { mode: 'MANUAL' }); // A human lifts the stop latch, then hands over.
  await l.send('set_mode', { mode: 'DISARMED' });
  await sleep(100);
  check('AI can arm AUTO', (await tool('vehicle_set_mode', { mode: 'AUTO' })).accepted === true);
  await tool('vehicle_set_speed', { mps: 1.5, durationMs: 5000 });
  await sleep(2500);
  const held = await tool('vehicle_get_status');
  check('AI holds target speed through local PID', Math.abs(held.speedMps - 1.5) < 0.2, `speed ${held.speedMps}`);
  const hBefore = held.headingRad;
  await tool('vehicle_set_steering', { value: 0.6, durationMs: 1500 });
  await sleep(1400);
  const turned = await tool('vehicle_get_status');
  check('AI steers the car', Math.abs(turned.headingRad - hBefore) > 0.5, `heading ${hBefore} -> ${turned.headingRad}`);
  check('AI request over the limit rejected', (await tool('vehicle_set_speed', { mps: 99 })).code === 'out_of_range');
  check('AI stop is honoured', (await tool('vehicle_stop')).accepted === true);
  check('Car stops on AI stop', await settleStopped(l));

  // --- Lease expiry: AI goes silent
  await tool('vehicle_set_mode', { mode: 'AUTO' });
  await tool('vehicle_set_speed', { mps: -1, durationMs: 800 });
  await sleep(600);
  const moving = Math.abs(l.tel.speed) > 0.2;
  await sleep(2500);
  check('Silent AI: request expires and the car stops', moving && Math.abs(l.tel.speed) < 0.1 && l.tel.mode === 'AUTO', `speed ${l.tel.speed}`);

  // --- MCP outage
  l.fault('mcp_down', 1);
  await sleep(100);
  check('MCP outage is visible to clients', (await mcp('ping', {})).status === 503);
  l.fault('mcp_down', 0);
  await sleep(100);
  check('MCP recovers', (await mcp('ping', {})).status === 200);

  // --- Mission
  check('AI runs an approved mission', (await tool('vehicle_run_mission', { name: 'straight_and_stop' })).accepted === true);
  await sleep(1500);
  check('Mission is reported', (await tool('vehicle_get_status')).mission === 'straight_and_stop');
  check('Unknown mission rejected', (await tool('vehicle_run_mission', { name: 'ram_the_cat' })).isError === true);
  await sleep(4000);
  check('Mission completes', (await tool('vehicle_get_status')).mission === null);

  // --- Human override and latch
  await tool('vehicle_set_speed', { mps: 1, durationMs: 5000 });
  check('Human override takes control from AI', (await l.send('drive', { throttle: 0, steering: 0 })).accepted && (await sleep(150), l.tel.mode === 'MANUAL'));
  await l.send('stop');
  check('AI cannot undo a human stop', (await tool('vehicle_set_mode', { mode: 'AUTO' })).code === 'not_permitted');

  // --- Profiles
  const kp = 0.5 + Math.round(Math.random() * 100) / 1000;
  check('PID update accepted', (await tool('vehicle_configure_pid', { kp })).accepted === true);
  check('PID out of bounds rejected', (await tool('vehicle_configure_pid', { kp: 99 })).code === 'out_of_range');
  const l2 = await new Link().open();
  check('Saved settings visible to a new session', l2.hello.profiles.find(p => p.id === l2.hello.activeProfile).kp === kp, `kp ${kp}`);
  l2.close();

  // --- Faults
  await l.send('set_mode', { mode: 'MANUAL' });
  l.fault('delay', 300);
  await sleep(100);
  const t0 = performance.now();
  await l.send('drive', { throttle: 0, steering: 0 });
  const lat = performance.now() - t0;
  check('Injected 300 ms delay is observable', lat > 280, `${lat.toFixed(0)} ms`);
  l.fault('delay', 2000);
  await sleep(100);
  const late = await l.send('drive', { throttle: 0, steering: 0 });
  check('Commands delayed past the limit are rejected as stale', late.code === 'stale', late.code);
  l.fault('delay', 0);
  await l.send('stop');

  await sleep(2200); // Let telemetry delayed by the previous test drain, and stats refresh.
  const before = l.tel.loopOverruns;
  l.fault('stall', 800);
  await sleep(2500); // Stats publish once a second; this also moves past the window containing the stall.
  check('App stall is detected by the loop', l.tel.loopOverruns > before, `overruns ${before} -> ${l.tel.loopOverruns}`);

  // Drop the ground link while driving.
  await l.send('set_mode', { mode: 'MANUAL' });
  await l.send('drive', { throttle: 0.3, steering: 0 });
  l.fault('disconnect', 3);
  await l.closed;
  const start = Date.now();
  const refused = await new Promise(r => { const w = new WebSocket(`ws://${host}/ws?token=${token}`); w.onmessage = () => { w.close(); r(false); }; w.onclose = () => r(true); w.onerror = () => {}; });
  check('Link stays down during the injected outage', refused);
  const during = await tool('vehicle_get_status');
  check('Controller keeps running without the ground station', during.loopHz > 40, `mode ${during.mode}, ${during.loopHz} Hz`);
  await sleep(700);
  check('Dropping the link while driving triggers failsafe', (await tool('vehicle_get_status')).mode === 'FAILSAFE');
  await sleep(Math.max(0, 3200 - (Date.now() - start)));
  const r = await new Link().open();
  await sleep(300);
  check('Failsafe survives reconnect, still latched', r.tel.mode === 'FAILSAFE', r.tel.mode);
  check('Old commands rejected after reconnect', (await r.send('drive', { throttle: 0.3, steering: 0 }, { sentAt: r.now() - 5000 })).code === 'stale');
  check('Human can re-arm after recovery', (await r.send('set_mode', { mode: 'MANUAL' })).accepted);
  await r.send('stop');

  // --- Measurements
  for (let i = 0; i < 40; i++) { r.raw({ type: 'ping', c: performance.now() }); await sleep(50); }
  await sleep(200);
  const rtts = [...r.rtts].sort((a, b) => a - b);
  const q = p => rtts[Math.floor(rtts.length * p)]?.toFixed(1);
  r.telCount = 0;
  await sleep(2000);
  console.log(`\nMeasured over this link: RTT p50 ${q(0.5)} ms, p95 ${q(0.95)} ms, telemetry ${(r.telCount / 2).toFixed(1)} Hz`);
  console.log(`Control loop: ${r.tel.loopHz} Hz, avg dt ${r.tel.loopDtAvgMs} ms, max dt ${r.tel.loopDtMaxMs} ms, overruns ${r.tel.loopOverruns}, command processing ${r.tel.cmdProcAvgUs} us`);

  const csv = await (await fetch(`http://${host}/export/commands.csv?token=${token}`)).text();
  check('Commands are logged and exportable', csv.includes('duplicate') && csv.includes('stale') && csv.includes('malformed') && csv.includes('out_of_range'));
  await tool('vehicle_configure_pid', { kp: 0.4 }); // Put back the default changed by the settings test.
  await r.send('stop');
  await r.send('set_mode', { mode: 'DISARMED' });
  await r.send('select_profile', { id: originalProfile });
  r.close();

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}
main().catch(e => { console.error(e); process.exit(1); });
