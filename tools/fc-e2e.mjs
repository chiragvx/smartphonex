// The flight controller on a running phone, directly over USB or Wi-Fi.
//   node tools/fc-e2e.mjs <host:port> <token>
// Works whether or not the device has a gyroscope, and restores the vehicle it found.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';

const [host, token] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => { ok ? passed++ : failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };

const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
let hello, tel, fc, fcs, fcCount = 0, seq = 0, offset = 0, params;
const acks = new Map(), cfg = [];
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') { hello = m; offset = m.t - Date.now(); params = m.params; }
  if (m.type === 'telemetry') tel = m.data;
  if (m.type === 'fc') { fc = m; fcCount++; }
  if (m.type === 'fcs') fcs = m;
  if (m.type === 'ack') acks.set(m.seq, m);
  if (m.type === 'cfgAck') cfg.push(m);
  if (m.type === 'params') params = m.list;
});
await new Promise(r => ws.on('open', r));
const cmd = async (name, args = {}) => {
  seq = Math.max(seq + 1, Date.now() * 1000);
  const s = seq;
  ws.send(JSON.stringify({ type: 'cmd', seq: s, sentAt: Date.now() + offset, cmd: name, ...args }));
  for (let i = 0; i < 300 && !acks.has(s); i++) await sleep(10);
  return acks.get(s) ?? { accepted: false, code: 'no_ack' };
};
const config = async o => {
  const n = cfg.length;
  ws.send(JSON.stringify(o));
  for (let i = 0; i < 300 && cfg.length === n; i++) await sleep(10);
  return cfg.at(-1) ?? {};
};
const param = name => params.find(p => p.name === name)?.value;

await sleep(1500);
const originalProfile = hello.activeProfile;
// Some checks need no position. If Location is on, turn it off for the run and let the fix go stale.
const locationWasOn = hello.sensors?.find(x => x.id === 'phone.location')?.enabled === true;
if (locationWasOn) { ws.send(JSON.stringify({ type: 'sensor_cfg', id: 'phone.location', enabled: false })); await sleep(6000); }
await cmd('stop');
await cmd('set_mode', { mode: 'DISARMED' });
await cmd('select_profile', { id: 'plane' });
await sleep(800);

fcCount = 0;
await sleep(3000);
const rate = fcCount / 3;
check('Flight state streams', rate > 10, `${rate.toFixed(1)} Hz`);
check('Slow state streams', !!fcs?.health, fcs?.mode);
check('Flight controller is active', fcs.active === true);
check('Settings are published', params?.length > 20, `${params?.length} settings`);
check('Rates reported', fcs.rates.loop > 40, `loop ${fcs.rates.loop} Hz, gyro ${fcs.rates.gyro}, accel ${fcs.rates.accel}, compass ${fcs.rates.mag}`);
console.log(`      Timing: sensor to output ${fcs.timing.delayAvgMs} ms avg, ${fcs.timing.delayMaxMs} ms max, longest step ${fcs.timing.dtMaxMs} ms, control task ${fcs.timing.taskMaxUs} us`);
console.log(`      Health: ${Object.entries(fcs.health).map(([k, v]) => `${k} ${v}`).join(', ')}`);

// --- Settings
check('Out-of-range setting refused', (await config({ type: 'param_set', name: 'ROLL_P', value: 999 })).ok === false);
check('Unknown setting refused', (await config({ type: 'param_set', name: 'NOPE', value: 1 })).ok === false);
const oldP = param('HDG_P');
const saved = await config({ type: 'param_set', name: 'HDG_P', value: 2 });
await sleep(200); // The updated list follows the acknowledgement.
check('Setting saved', saved.ok && param('HDG_P') === 2, `HDG_P ${param('HDG_P')}`);
await config({ type: 'param_set', name: 'HDG_P', value: oldP });
await sleep(200);

const hasGyro = fcs.health.gyro === 'ok';
if (!hasGyro) {
  check('No gyroscope: Stabilize refused with a reason', /gyroscope/.test((await cmd('set_flight_mode', { mode: 'STABILIZE' })).message));
  check('No gyroscope: Manual still works', (await cmd('set_flight_mode', { mode: 'MANUAL' })).accepted);
  check('No gyroscope: arming Manual still works', (await cmd('set_mode', { mode: 'MANUAL' })).accepted);
  await cmd('stop');
} else {
  await sleep(2500);
  check('Attitude has settled', fcs.settled === true);
  check('Attitude values are sane', Math.abs(fc.a[0]) <= 180 && Math.abs(fc.a[1]) <= 90 && fc.a[2] >= 0 && fc.a[2] < 360, fc.a.join(', '));
  if (fcs.diff) {
    check('Our roll and pitch agree with Android', Math.abs(fcs.diff[0]) < 5 && Math.abs(fcs.diff[1]) < 5, `difference ${fcs.diff.join(', ')} (roll, pitch, heading)`);
    console.log(`      Heading difference ${fcs.diff[2]} degrees. Android's reference is true north only if it had a position.`);
  }

  check('Return Home refused without a position or home', (await cmd('set_flight_mode', { mode: 'RETURN_HOME' })).accepted === false);

  // Stabilize on the plane: the wanted angle comes from the stick, and outputs follow attitude.
  check('Stabilize accepted', (await cmd('set_flight_mode', { mode: 'STABILIZE' })).accepted);
  await sleep(1000);
  check('Disarmed: outputs held at failsafe', JSON.stringify(tel.out.map(o => o[1])) === '[1000,1500,1500,1500]', JSON.stringify(tel.out.map(o => o[1])));
  check('Disarmed: what it would command is reported', tel.would?.length === 4, JSON.stringify(tel.would));
  check('Armed Stabilize accepted', (await cmd('set_mode', { mode: 'MANUAL' })).accepted);
  let driving = true;
  const stream = (async () => { while (driving) { await cmd('drive', { throttle: 0, steering: 0, pitch: 0, roll: 0 }); await sleep(50); } })();
  await sleep(1500);
  check('Armed: outputs now follow the autopilot', tel.mode === 'MANUAL' && JSON.stringify(tel.out.map(o => o[1])) === JSON.stringify(tel.would), `${tel.out.map(o => o[1])} (roll ${fc.a[0]}, pitch ${fc.a[1]})`);
  const expectSign = (out, att) => att > 3 ? out[1] > 1500 : att < -3 ? out[1] < 1500 : true;
  check('Aileron corrects against the phone\'s roll', expectSign(tel.out[1], fc.a[0]), `roll ${fc.a[0]}, aileron ${tel.out[1][1]} us`);
  driving = false;
  await stream;
  await sleep(1000);
  check('Link loss without GPS is a normal failsafe', tel.mode === 'FAILSAFE', `${tel.mode}: ${tel.failsafeReason}`);
  check('Failsafe holds safe outputs', JSON.stringify(tel.out.map(o => o[1])) === '[1000,1500,1500,1500]');
  await cmd('set_mode', { mode: 'DISARMED' });
  await cmd('set_flight_mode', { mode: 'MANUAL' });

  check('Gyro calibration runs', (await config({ type: 'fc_action', action: 'calibrate_gyro' })).ok);
  await sleep(2600);
  console.log(`      Gyro offsets after calibration: ${param('GYRO_BX')}, ${param('GYRO_BY')}, ${param('GYRO_BZ')} (0 means the phone moved, or it hasn't refreshed yet)`);
}

// --- Flight log. It only records while armed, and the armed runs above wrote one.
const log = await (await fetch(`http://${host}/export/flight.csv?token=${token}`)).text();
const rows = log.trim().split('\n');
if (hasGyro) {
  check('Flight log was written', rows.length > 50 && rows[0].startsWith('t,mode,roll'), `${rows.length} rows`);
  const ts = rows.slice(1).map(l => Number(l.split(',')[0]));
  const secs = (ts.at(-1) - ts[0]) / 1000;
  check('Log keeps full rate', rows.length / secs > 40, `${(rows.length / secs).toFixed(0)} rows/s`);
} else {
  console.log('SKIP  flight log rate: too little armed time on this device');
}

await cmd('stop');
await cmd('set_mode', { mode: 'DISARMED' });
await cmd('select_profile', { id: originalProfile });
ws.close();
console.log(`\n${passed} passed, ${failed} failed`);
if (locationWasOn) { ws.send(JSON.stringify({ type: 'sensor_cfg', id: 'phone.location', enabled: true })); await sleep(300); }
process.exit(failed ? 1 : 0);
