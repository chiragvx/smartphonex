// A dead link with no disconnect, while being driven: packets vanish, and nothing closes.
//   node tools/stall-test.mjs <relay-url> <user> <password> <proxy-control-port> <stall-ms>
import { login, RemoteLink, sleep } from './remote-lib.mjs';

const [base, user, password, control, stallMs] = process.argv.slice(2);
const l = await new RemoteLink(base, await login(base, user, password)).open();
await sleep(1500);
await l.send('stop');
l.fault('sim_reset', 0);
await sleep(400);
await l.send('set_mode', { mode: 'MANUAL' });
let driving = true;
const stream = (async () => { while (driving) { l.fire('drive', { throttle: 0, steering: 0 }); await sleep(50); } })();
await sleep(1500);
console.log(`before: mode ${l.tel.mode}, vehicle online ${l.vehicleOnline}`);

const t0 = Date.now();
await fetch(`http://127.0.0.1:${control}/stall?ms=${stallMs}`, { method: 'POST' });
let lastTel = l.tel.t, telStopped = null, offlineAt = null, onlineAt = null;
const end = t0 + Number(stallMs) + 40000;
while (Date.now() < end && !onlineAt) {
  await sleep(100);
  if (l.tel.t !== lastTel) lastTel = l.tel.t;
  else if (telStopped === null && Date.now() + l.offset - lastTel > 1000) telStopped = Date.now() - t0;
  if (offlineAt === null && l.vehicleOnline === false) offlineAt = Date.now() - t0;
  if (offlineAt !== null && l.vehicleOnline === true) onlineAt = Date.now() - t0;
}
driving = false;
await stream;
await sleep(1500);
console.log(`dead link for ${stallMs} ms:`);
console.log(`  telemetry stopped arriving after ${telStopped} ms`);
console.log(`  relay marked the phone offline after ${offlineAt} ms`);
console.log(`  phone back online after ${onlineAt} ms (${onlineAt === null ? 'never' : onlineAt - Number(stallMs)} ms after the link healed)`);
console.log(`after: mode ${l.tel.mode}, reason ${l.tel.failsafeReason}, loop ${l.tel.loopHz} Hz, overruns ${l.tel.loopOverruns}`);
const r = await l.send('drive', { throttle: 0.2, steering: 0 });
console.log(`drive after recovery: ${r.code}`);
const stop = await l.send('stop');
console.log(`stop: ${stop.code}`);
l.close();
process.exit(0);
