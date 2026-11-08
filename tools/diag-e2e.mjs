// App health, the phone report, crash recording and the update check, on a running phone over USB.
//   node tools/diag-e2e.mjs <host:port> <token> <adb> <serial>
// Needs a build made with -Pspx.updateUrl=http://127.0.0.1:8799/latest.json. This script serves that feed and
// reverses the port to the phone. It also crashes the app once on purpose (adb's "am crash") to check it's recorded.
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';

const [host, token, adb, serial] = process.argv.slice(2);
const base = `http://${host}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => { ok ? passed++ : failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };
const sh = (...a) => execFileSync(adb, ['-s', serial, ...a], { encoding: 'utf8' });

let feed = { tag_name: 'v9.9.9', html_url: 'https://github.com/example/smartphonex/releases/tag/v9.9.9', assets: [{ browser_download_url: 'https://github.com/example/smartphonex/releases/download/v9.9.9/SmartPhoneX-v9.9.9.apk' }] };
let hits = 0;
const server = createServer((req, res) => { hits++; res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(feed)); }).listen(8799, '127.0.0.1');
sh('reverse', 'tcp:8799', 'tcp:8799');

const get = path => fetch(`${base}${path}${path.includes('?') ? '&' : '?'}token=${token}`);
check('Health needs the token', (await fetch(`${base}/diagnostics`)).status === 401);
let h = await (await get('/diagnostics')).json();
check('Health lists how the app stopped', Array.isArray(h.exits) && h.supported === true, `${h.exits.length} recorded, latest: ${h.exits[0]?.text}`);

const rep = await get('/diagnostics/phone');
const text = await rep.text();
const r = JSON.parse(text);
check('Phone report downloads as a file', /attachment; filename="phone-report-[A-Za-z0-9-]+\.json"/.test(rep.headers.get('content-disposition') ?? ''), rep.headers.get('content-disposition'));
check('Report names the phone', !!r.phone?.model && !!r.phone?.android, `${r.phone?.manufacturer} ${r.phone?.model}, Android ${r.phone?.android}, ${r.phone?.ramMb} MB`);
check('Report lists sensors with rates', r.sensors?.length > 3 && r.sensors.some(s => s.maxHz > 0), `${r.sensors?.length} sensors`);
check('Report has timing', r.timing?.loopHz > 0, `loop ${r.timing?.loopHz} Hz, worst step ${r.timing?.stepMaxMs} ms`);
check('Report has no location or IDs', !/"(lat|lon|latitude|longitude|serial|imei|androidId)"/i.test(text));
check('Crash files only by their own names', (await get('/diagnostics/crash/..%2Ftoken.txt')).status === 404 && (await get('/diagnostics/crash/token.txt')).status === 404);

const up = await (await fetch(`${base}/diagnostics/update`, { method: 'POST', headers: { authorization: `Bearer ${token}` } })).json();
check('Update check reaches the feed', hits > 0 && up.enabled === true, `${hits} request(s)`);
check('Newer version found', up.newer === true && up.latest === '9.9.9' && up.apk?.endsWith('.apk'), JSON.stringify(up).slice(0, 160));
check('Update check needs the token', (await fetch(`${base}/diagnostics/update`, { method: 'POST' })).status === 401);
feed = { tag_name: 'v0.1.0', html_url: 'https://github.com/example/smartphonex/releases/tag/v0.1.0' };
const same = await (await fetch(`${base}/diagnostics/update`, { method: 'POST', headers: { authorization: `Bearer ${token}` } })).json();
check('Older release is not offered', same.newer === false, same.latest);
feed = { tag_name: 'v9.9.9', html_url: 'http://insecure.example/' };
const bad = await (await fetch(`${base}/diagnostics/update`, { method: 'POST', headers: { authorization: `Bearer ${token}` } })).json();
check('A feed with an http link is refused', !!bad.error, bad.error);

// Crash it on purpose. Android restarts the service, and the crash and its reason should be on record.
const before = h.crashes.length;
try { sh('shell', 'am', 'crash', 'com.smartphonex.app'); } catch (e) { console.log(`      am crash: ${e.message.split('\n')[0]}`); }
let back = false;
for (let i = 0; i < 40 && !back; i++) {
  await sleep(1000);
  try { const x = await get('/diagnostics'); if (x.ok) { h = await x.json(); back = true; } } catch { /* restarting */ }
}
if (!back) { sh('shell', 'am', 'start', '-n', 'com.smartphonex.app/.MainActivity'); await sleep(10000); h = await (await get('/diagnostics')).json(); }
check('Crash written to disk', h.crashes.length > before, `${before} to ${h.crashes.length}: ${h.crashes[0]?.summary}`);
check('Exit reason recorded as a crash', h.exits.some(e => /crash/.test(e.text)), h.exits.slice(0, 3).map(e => e.text).join('; '));
if (h.crashes[0]) {
  const c = await (await get(`/diagnostics/crash/${h.crashes[0].name}`)).text();
  check('Crash file opens, with version and stack', c.includes('SmartPhoneX') && /\n\s+at /.test(c), c.split('\n')[0]);
}

server.close();
try { sh('reverse', '--remove', 'tcp:8799'); } catch { /* fine */ }
console.log(`\n${failed ? `FAILED (${failed} of ${passed + failed})` : `ALL PASSED (${passed})`}`);
process.exit(failed ? 1 : 0);
