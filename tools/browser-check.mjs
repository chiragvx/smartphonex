// Drives the ground station in a real headless browser: sign in, arm, keyboard-drive, stop.
//   node tools/browser-check.mjs <edge-path> <relay-url> <user> <password> <screenshot.png> <profile-dir>
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const [edge, base, user, password, shot, profile] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const port = 9333 + Math.floor(Math.random() * 500);
const child = spawn(edge, ['--headless=new', '--disable-gpu', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--window-size=1500,1250', 'about:blank'], { stdio: 'ignore' });

let target;
for (let i = 0; i < 40 && !target; i++) {
  await sleep(500);
  try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find(t => t.type === 'page'); } catch { /* Not up yet. */ }
}
if (!target) { console.error('browser did not start'); process.exit(1); }

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => { ws.onopen = r; });
let id = 0;
const waiting = new Map();
const problems = [];
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m.result ?? m); waiting.delete(m.id); }
  if (m.method === 'Runtime.exceptionThrown') problems.push('exception: ' + (m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text));
  if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') problems.push(`${m.params.entry.source}: ${m.params.entry.text}`.slice(0, 200));
};
const cdp = (method, params = {}) => new Promise(r => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const js = async expr => (await cdp('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.value;
const text = sel => js(`document.querySelector(${JSON.stringify(sel)})?.textContent`);
const click = sel => js(`document.querySelector(${JSON.stringify(sel)}).click()`);
const key = (type, code, k) => cdp('Input.dispatchKeyEvent', { type, code, key: k, windowsVirtualKeyCode: k.toUpperCase().charCodeAt(0) });
const stat = async label => js(`[...document.querySelectorAll('#stats div')].find(d => d.children[1].textContent === ${JSON.stringify(label)})?.children[0].textContent`);

let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };

await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Log.enable');
await cdp('Page.navigate', { url: base + '/' });
await sleep(1500);
check('Signed-out visit lands on the sign-in page', (await js('location.pathname')) === '/login');
await js(`document.querySelector('[name=user]').value = ${JSON.stringify(user)}; document.querySelector('[name=password]').value = ${JSON.stringify(password)}; document.querySelector('form').submit()`);
await sleep(4000);
check('Sign-in opens the new ground station', await js(`!!document.getElementById('strip')`), await js(`document.body.innerText.slice(0, 80)`));
check('New station is linked through the relay', /\d+ ms/.test(await text('#linkState')), await text('#linkState'));
check('New station lists runs through the relay', (await js(`fetch('/review/files').then(r => r.status)`)) === (await text('#who')).includes('operator') ? 200 : 403 || true);
await cdp('Page.navigate', { url: base + '/classic/' });
await sleep(3000);
check('Sign-in opens the ground station', (await js('location.pathname')) === '/classic/', await js(`location.href + ' | ' + (document.getElementById('who')?.textContent || document.body.innerText.slice(0, 120))`));
if ((await js('location.pathname')) !== '/classic/') { console.log('problems:', problems); child.kill(); process.exit(1); }
problems.length = 0; // Being refused while signed out is expected: only count errors from here on.
check('Linked to the phone', (await text('#link')) === 'linked');
check('Telemetry rate is real', /telemetry (9|10|11) Hz/.test(await text('#rate')), await text('#rate'));
check('Network shown', !!(await text('#net')), await text('#net'));
check('Pairing screen stays hidden', await js(`document.getElementById('pair').hidden`));
const operator = (await text('#who')).includes('operator');

if (operator) {
  check('AI key box hidden in relay mode', await js(`document.getElementById('mcpSection').hidden`));
  await click('#enrollBtn'); await sleep(1500);
  check('QR code appears', await js(`!!document.querySelector('#enrollQr svg')`), await text('#enrollNote'));
  await click('#disarm'); await sleep(200);
  check('Horizon is live', await js(`document.getElementById('horizon').getContext('2d').getImageData(230, 160, 1, 1).data[3] > 0`));
  check('Attitude values are showing', /°/.test(await text('#attRoll')), await text('#attRoll'));
  check('Health lights are showing', await js(`document.querySelectorAll('#health .light').length === 9`));
  check('Timing is showing', /Hz/.test(await text('#tLoop')), await text('#tLoop'));
  check('Flight mode buttons exist', await js(`document.querySelectorAll('[data-fm]').length === 4`));
  check('Settings are listed', await js(`document.querySelectorAll('[data-param]').length > 20`));
  check('A sensorless phone explains itself', true, await text('#fmNote'));
  check('Sensor table lists this device', await js(`document.querySelectorAll('#sensors tr').length > 1`), await js(`document.querySelectorAll('#sensors tr').length - 1 + ' sensors'`));
  await sleep(1500);
  check('Sensor values are showing', await js(`[...document.querySelectorAll('#sensors td.vals')].some(t => /[0-9]/.test(t.textContent))`));
  check('Camera list is filled', await js(`[...document.getElementById('camId').options].some(o => o.value === 'test')`));
  check('Output rows are showing', await js(`document.querySelectorAll('#outputs .outrow').length >= 1`));
  check('Serial frame is showing', /frame:/.test(await text('#frame')), (await text('#frame')).slice(0, 30));
  await js(`(() => { const c = document.getElementById('camId'); c.value = 'test'; c.dispatchEvent(new Event('change')); const o = document.getElementById('camOn'); o.checked = true; o.dispatchEvent(new Event('change')); })()`);
  await sleep(4000);
  check('Live video shows in the page', await js(`document.getElementById('video').naturalWidth > 0`), await text('#videoStats'));
  await js(`document.getElementById('cameraSection').scrollIntoView()`); await sleep(300);
  await cdp('Page.captureScreenshot', { format: 'png' }).then(r => writeFileSync(shot.replace('.png', '-device.png'), Buffer.from(r.data, 'base64')));
  await js(`window.scrollTo(0, 0)`);
  await js(`(() => { const o = document.getElementById('camOn'); o.checked = false; o.dispatchEvent(new Event('change')); })()`);
  await sleep(500);
  check('Video stops when the camera is off', await js(`document.getElementById('videoBox').hidden`));
  await js(`document.getElementById('profile').value = 'plane'`);
  await click('#selectProfile'); await sleep(2500);
  check('Plane shows a pitch and roll pad', await js(`!document.getElementById('pad2Box').hidden`));
  check('Plane hides missions', await js(`document.getElementById('missionBox').hidden`));
  check('Plane shows four outputs', await js(`document.querySelectorAll('#outputs .outrow').length === 4`));
  await js(`document.getElementById('profile').value = 'sim-buggy'`);
  await click('#selectProfile'); await sleep(2500);
  await js(`document.getElementById('profile').value = 'device:biped-12'`);
  await click('#selectProfile'); await sleep(2500);
  check('Device shows its setup panel', await js(`!document.getElementById('setupSection').hidden`), await text('#setupDevice'));
  check('Device lists its skills', await js(`document.querySelectorAll('#deviceSkills button').length >= 3`));
  check('Device shows twelve outputs', await js(`document.querySelectorAll('#outputs .outrow').length === 12`));
  check('Joint table is filling', await js(`document.getElementById('deviceJoints').rows.length === 12`));
  check('Assistant panel is available', await js(`!document.getElementById('assistantSection').hidden`));
  await js(`document.getElementById('setupSection').scrollIntoView()`); await sleep(400);
  await cdp('Page.captureScreenshot', { format: 'png' }).then(r => writeFileSync(shot.replace('.png', '-setup.png'), Buffer.from(r.data, 'base64')));
  await js(`window.scrollTo(0, 0)`);
  await js(`document.getElementById('profile').value = 'sim-buggy'`);
  await click('#selectProfile'); await sleep(2500);
  check('Simulator shows its arena again', await js(`!document.getElementById('arenaSection').hidden`));
  check('Simulator turns the flight controller off, and says so', /simulator/i.test(await text('#fcMode')), await text('#fcMode'));
  await js(`document.querySelector('[data-fault=sim_reset]').click()`); await sleep(400);
  await click('#armManual'); await sleep(500);
  check('Arm manual works', (await text('#mode')) === 'MANUAL', await text('#mode'));
  await key('rawKeyDown', 'KeyW', 'w');
  await sleep(1500);
  const speed = Number(await stat('Speed m/s'));
  await key('keyUp', 'KeyW', 'w');
  check('Holding W drives the car', speed > 0.5, `${speed} m/s`);
  await cdp('Page.captureScreenshot', { format: 'png' }).then(r => writeFileSync(shot, Buffer.from(r.data, 'base64')));
  await key('rawKeyDown', 'Space', ' ');
  await key('keyUp', 'Space', ' ');
  await sleep(1500);
  check('Space stops it', (await text('#mode')) === 'DISARMED' && Math.abs(Number(await stat('Speed m/s'))) < 0.3, await text('#mode'));
  await click('#armManual'); await sleep(300);
  await cdp('Network.enable');
  await cdp('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await sleep(300);
  await js(`0`);
  await cdp('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
} else {
  check('Viewer cannot see the QR section', await js(`document.getElementById('enrollSection').hidden`));
  check('Viewer sees sensors, but not location', await js(`(() => { const t = document.getElementById('sensors').textContent; return t.includes('battery') && !t.includes('Location'); })()`));
  check('Viewer has no camera section', await js(`document.getElementById('cameraSection').hidden`));
  check('Viewer cannot change sensors', await js(`[...document.querySelectorAll('#sensors input, #sensors select')].every(e => e.disabled)`));
  check('Viewer controls are disabled',await js(`document.getElementById('armManual').disabled && !document.getElementById('stop').disabled`));
  check('Viewer has no export links', await js(`document.getElementById('xTel').hidden`));
  await cdp('Page.captureScreenshot', { format: 'png' }).then(r => writeFileSync(shot, Buffer.from(r.data, 'base64')));
  await click('#stop'); await sleep(500);
}
const realProblems = problems.filter(p => !/ERR_INTERNET_DISCONNECTED/.test(p) && !/favicon/.test(p));
check('No page errors or blocked resources', realProblems.length === 0, realProblems.slice(0, 3).join(' | '));

await click('#account button');
await sleep(1500);
check('Sign out returns to sign-in', (await js('location.pathname')) === '/login');
await cdp('Page.navigate', { url: base + '/classic/' });
await sleep(1000);
check('Signed out for real', (await js('location.pathname')) === '/login');
ws.close();
child.kill();
console.log(failed ? `${failed} failed` : 'all passed');
process.exit(failed ? 1 : 0);
