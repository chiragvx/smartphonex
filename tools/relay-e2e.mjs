// Phase 2 acceptance test: the real app, through the real relay.
//   node tools/relay-e2e.mjs <relay-url> <operator-user> <password> <ai-key>
import { login, Mcp, RemoteLink, sleep } from './remote-lib.mjs';

const [base, user, password, aiKey] = process.argv.slice(2);
if (!aiKey) { console.error('usage: node tools/relay-e2e.mjs <relay-url> <user> <password> <ai-key>'); process.exit(2); }

let passed = 0, failed = 0;
function check(name, ok, detail = '') {
  ok ? passed++ : failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  [' + detail + ']' : ''}`);
}

const cookie = await login(base, user, password);
const session = await (await fetch(`${base}/session`, { headers: { cookie } })).json();
check('Signed in through the relay', session.role === 'operator' && session.vehicleOnline === true, JSON.stringify(session));

// These checks use the simulated car. The selected vehicle is restored at the end.
const setup = await new RemoteLink(base, cookie).open();
await sleep(800);
const originalProfile = setup.hello?.activeProfile;
await setup.send('stop');
await setup.send('set_mode', { mode: 'DISARMED' });
await setup.send('select_profile', { id: 'sim-buggy' });
setup.close();
await sleep(300);

const l = await new RemoteLink(base, cookie).open();
await sleep(800);
check('Phone answers through the relay', !!l.hello && !!l.tel, l.hello?.vehicle);
check('Network state is reported', !!l.net?.transport, JSON.stringify(l.net));

await l.send('stop');
l.fault('sim_reset', 0);
await sleep(300);
await l.send('set_mode', { mode: 'MANUAL' });
let driving = true;
const stream = (async () => { while (driving) { l.fire('drive', { throttle: 0.4, steering: 0 }); await sleep(50); } })();
await sleep(1500);
check('Remote manual driving moves the car', l.tel.mode === 'MANUAL' && l.tel.speed > 0.5, `speed ${l.tel.speed}`);
const mcp = new Mcp(base, aiKey);
const init = await mcp.rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } });
check('MCP initialize through the relay', init.result?.protocolVersion === '2025-06-18');
check('MCP tools listed through the relay', (await mcp.rpc('tools/list', {})).result?.tools?.length >= 8);
check('AI is refused while a human drives', (await mcp.tool('vehicle_set_speed', { mps: 1 })).code === 'not_permitted');
driving = false;
await stream;

await sleep(900);
check('Lost command stream triggers the failsafe', l.tel.mode === 'FAILSAFE', l.tel.failsafeReason);

await l.send('set_mode', { mode: 'MANUAL' });
await l.send('set_mode', { mode: 'DISARMED' });
l.fault('sim_reset', 0); // Back to the start, clear of the wall the manual test drove toward.
await sleep(300);
check('AI arms through the relay', (await mcp.tool('vehicle_set_mode', { mode: 'AUTO' })).accepted === true);
await mcp.tool('vehicle_set_speed', { mps: 1.5, durationMs: 4000 });
await sleep(2500);
const held = await mcp.tool('vehicle_get_status');
check('AI holds speed remotely', Math.abs(held.speedMps - 1.5) < 0.25, `speed ${held.speedMps}`);
check('AI limits are still enforced by the phone', (await mcp.tool('vehicle_set_speed', { mps: 99 })).code === 'out_of_range');
check('AI stop works remotely', (await mcp.tool('vehicle_stop')).accepted === true);

// Internet loss, as the phone sees it: its relay connection is dropped while AUTO is running.
await mcp.tool('vehicle_set_mode', { mode: 'AUTO' });
await mcp.tool('vehicle_set_speed', { mps: 1, durationMs: 1500 });
const connectsBefore = l.relay?.connects ?? 0;
l.fault('disconnect', 3);
await sleep(600);
check('Relay reports the phone offline', l.vehicleOnline === false);
check('MCP reports the vehicle offline', (await mcp.tool('vehicle_get_status')).httpStatus === 503);
const offAck = await l.send('drive', { throttle: 0.2, steering: 0 });
check('Commands during an outage are refused, not queued', offAck.code === 'vehicle_offline', offAck.code);
const preReconnectSeq = l.seq;
const staleAt = l.now();

const t0 = Date.now();
while (l.vehicleOnline !== true && Date.now() - t0 < 30000) await sleep(100);
check('Phone reconnects by itself', l.vehicleOnline === true, `${l.outages.at(-1)} ms outage seen by the relay`);
await sleep(1000);
const after = await mcp.tool('vehicle_get_status');
check('AI request expired and the car stopped during the outage', Math.abs(after.speedMps) < 0.15, `speed ${after.speedMps}, mode ${after.mode}`);

await l.send('set_mode', { mode: 'MANUAL' });
check('Old command from before the outage is rejected', (await l.send('drive', { throttle: 0.2, steering: 0 }, { sentAt: staleAt - 5000 })).code === 'stale');
check('Duplicate after reconnecting is rejected', (await l.send('drive', { throttle: 0.2, steering: 0 }, { seq: preReconnectSeq })).code === 'duplicate');
await l.send('stop');

// Manual driving when the relay link drops: the phone must stop on its own.
await l.send('set_mode', { mode: 'MANUAL' });
driving = true;
const stream2 = (async () => { while (driving) { l.fire('drive', { throttle: 0.3, steering: 0 }); await sleep(50); } })();
await sleep(500);
l.fault('disconnect', 2);
await sleep(300);
driving = false;
await stream2;
const t1 = Date.now();
while (l.vehicleOnline !== true && Date.now() - t1 < 30000) await sleep(100);
await sleep(1000);
check('Link loss while driving trips the failsafe, which stays latched', l.tel?.mode === 'FAILSAFE', `${l.tel?.mode}: ${l.tel?.failsafeReason}`);
check('Latched failsafe refuses driving after reconnect', (await l.send('drive', { throttle: 0.2, steering: 0 })).code === 'not_permitted');
check('Human can re-arm', (await l.send('set_mode', { mode: 'MANUAL' })).accepted);
await l.send('stop');

for (let i = 0; i < 20; i++) { l.ping(); await sleep(50); }
l.raw({ type: 'probe' });
const t2 = Date.now();
while (!l.lastProbe && Date.now() - t2 < 25000) await sleep(200);
console.log(`\nProbe: ${JSON.stringify(l.lastProbe)}`);
check('Packet-loss probe returns a result', !!l.lastProbe);

const csv = await (await fetch(`${base}/export/commands.csv`, { headers: { cookie } })).text();
check('Logs export through the relay', csv.includes('stale') && csv.includes('duplicate'));
const rtt = [...l.rtts].sort((a, b) => a - b);
console.log(`RTT p50 ${rtt[rtt.length >> 1]?.toFixed(0)} ms, max ${rtt.at(-1)?.toFixed(0)} ms; MCP avg ${(mcp.durations.reduce((a, b) => a + b, 0) / mcp.durations.length).toFixed(0)} ms`);
await l.send('stop');
await l.send('set_mode', { mode: 'DISARMED' });
if (originalProfile) await l.send('select_profile', { id: originalProfile });
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
