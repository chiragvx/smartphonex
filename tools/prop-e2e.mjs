// The experimental propeller listener (MIC_PROP) on a running phone: turns it on, prints what it hears, puts it back.
//   node tools/prop-e2e.mjs <host:port> <token> [seconds] [expected-rpm]
// Play a propeller, or a test tone through the phone's speaker, while it runs. With an expected rpm it checks the
// heard rpm is within 3% of it.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';

const [host, token, secs = '15', expected] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
let params, fcs;
const cfg = [];
ws.on('message', raw => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'hello') params = m.params;
  if (m.type === 'params') params = m.list;
  if (m.type === 'fcs') fcs = m;
  if (m.type === 'cfgAck') cfg.push(m);
});
await new Promise(r => ws.on('open', r));
await sleep(1200);
const set = async (name, value) => {
  const n = cfg.length;
  ws.send(JSON.stringify({ type: 'param_set', name, value }));
  for (let i = 0; i < 300 && cfg.length === n; i++) await sleep(10);
  return cfg.at(-1) ?? {};
};
const param = name => params?.find(p => p.name === name)?.value;

const before = { MIC_PROP: param('MIC_PROP'), PROP_BLADES: param('PROP_BLADES') };
console.log('before', before);
if ((await set('MIC_PROP', 1)).ok !== true) { console.log('FAIL  could not turn the listener on'); process.exit(1); }
await set('PROP_BLADES', 2);
const heard = [];
for (let i = 0; i < Number(secs); i++) {
  await sleep(1000);
  const p = fcs?.prop;
  console.log(`${String(i + 1).padStart(3)}s  ${p ? `${p.status}  hz=${p.hz ?? '-'}  rpm=${p.rpm ?? '-'}  snr=${p.snr ?? '-'} dB` : 'no prop block yet'}`);
  if (p?.rpm != null) heard.push(p.rpm);
}
await set('MIC_PROP', before.MIC_PROP ?? 0);
await set('PROP_BLADES', before.PROP_BLADES ?? 2);
ws.close();
if (expected) {
  const e = Number(expected);
  const good = heard.filter(r => Math.abs(r - e) / e < 0.03).length;
  const ok = heard.length > 0 && good >= heard.length * 0.8;
  console.log(`${ok ? 'PASS' : 'FAIL'}  heard ${heard.length} readings, ${good} within 3% of ${e} rpm`);
  process.exit(ok ? 0 : 1);
}
