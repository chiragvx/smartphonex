// Drives the ground station in a real headless browser against a real phone's server.
//   node tools/gs-check.mjs <edge-path> <host:port> <token> <shots-dir> <profile-dir>
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

const [edge, host, token, shots, profile] = process.argv.slice(2);
mkdirSync(shots, { recursive: true });
const base = `http://${host}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const port = 9333 + Math.floor(Math.random() * 500);
const child = spawn(edge, ['--headless=new', '--disable-gpu', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--window-size=1400,1000', 'about:blank'], { stdio: 'ignore' });
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
const dialogs = [];
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m.result ?? m); waiting.delete(m.id); }
  // Confirmations (advisory notices before sending, deleting) are answered yes, as a person reading them would.
  if (m.method === 'Page.javascriptDialogOpening') {
    dialogs.push(m.params.message);
    ws.send(JSON.stringify({ id: ++id, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
  }
  if (m.method === 'Runtime.exceptionThrown') problems.push('exception: ' + (m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text));
  if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error' && !/tile|arcgis|openstreetmap|ERR_INTERNET|\/session$/.test(m.params.entry.text + (m.params.entry.url ?? ''))) problems.push(`${m.params.entry.source}: ${m.params.entry.text} ${m.params.entry.url ?? ''}`.slice(0, 220));
};
const cdp = (method, params = {}) => new Promise(r => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const js = async expr => {
  const r = await cdp('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(`${expr}: ${r.exceptionDetails.exception?.description}`);
  return r.result?.value;
};
const text = sel => js(`document.querySelector(${JSON.stringify(sel)})?.textContent ?? ''`);
const has = sel => js(`!!document.querySelector(${JSON.stringify(sel)})`);
const click = sel => js(`document.querySelector(${JSON.stringify(sel)}).click()`);
const rect = sel => js(`(() => { const el = document.querySelector(${JSON.stringify(sel)}); el.scrollIntoView({ block: 'center' }); const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; })()`);
const mouse = (type, x, y, buttons = 1) => cdp('Input.dispatchMouseEvent', { type, x, y, button: type === 'mouseMoved' && !buttons ? 'none' : 'left', buttons, clickCount: type === 'mouseMoved' ? 0 : 1 });
const page = async name => { await click(`[data-page=${name}]`); await sleep(300); };
const shot = async name => { const r = await cdp('Page.captureScreenshot', { format: 'png' }); writeFileSync(`${shots}/${name}.png`, Buffer.from(r.data, 'base64')); };
const clickButton = t => js(`(() => { const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === ${JSON.stringify(t)}); if (b) b.click(); return !!b; })()`);
const until = async (fn, ms = 4000) => { const end = Date.now() + ms; while (Date.now() < end) { if (await fn()) return true; await sleep(100); } return false; };

let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };
const arm = () => text('#armState');
const slide = async () => {
  await sleep(100);
  const r = await rect('#slideArm');
  await mouse('mousePressed', r.x + 25, r.y + r.h / 2);
  for (let x = 25; x <= r.w; x += 30) { await mouse('mouseMoved', r.x + x, r.y + r.h / 2); await sleep(15); }
  await mouse('mouseMoved', r.x + r.w, r.y + r.h / 2);
  await sleep(50);
  await mouse('mouseReleased', r.x + r.w, r.y + r.h / 2);
};
const hold = async (sel, ms) => {
  const r = await rect(sel);
  await mouse('mousePressed', r.x + r.w / 2, r.y + r.h / 2);
  await sleep(ms);
  await mouse('mouseReleased', r.x + r.w / 2, r.y + r.h / 2);
};
// Every request goes through the page's own socket path, with its own token: as any client.
const send = async msg => js(`new Promise(res => { const w = new WebSocket('ws://${host}/ws?token=${token}'); w.onopen = () => { w.send(JSON.stringify(${JSON.stringify(msg)})); setTimeout(() => { w.close(); res(true); }, 300); }; })`);
const setParam = (name, value) => send({ type: 'param_set', name, value });
const selectDevice = async wanted => {
  await page('setup');
  await js(`(() => { const s = document.querySelector('#profileSelect'); s.value = ${JSON.stringify(wanted)}; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await sleep(1500);
  await until(() => has('#strip'));
  await sleep(1500);
};

await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Log.enable');
await cdp('Page.navigate', { url: base + '/' });
await sleep(2000);
check('With no token, it asks to pair', await has('#pairCode'));
await cdp('Page.navigate', { url: 'about:blank' });
await sleep(200);
await cdp('Page.navigate', { url: `${base}/#pair=000000` });
await sleep(1500);
check('A wrong code from a QR link is refused', /Wrong code/.test(await js('document.body.innerText')) , (await js('document.body.innerText')).slice(0, 80));
problems.length = 0; // Being refused here is expected.
await cdp('Page.navigate', { url: 'about:blank' });
await cdp('Page.navigate', { url: `${base}/?x=1#token=${token}` });
await sleep(3000);
check('Token link opens the station and clears the address', (await js('location.hash')) === '' && await has('#strip'));
check('Armed, Mode and In control are separate', !!(await arm()) && !!(await text('#modeState')) && !!(await text('#ownerState')), `${await arm()} / ${await text('#modeState')} / ${await text('#ownerState')}`);
check('Link time is measured', /\d+ ms/.test(await text('#linkState')), await text('#linkState'));
check('STOP is on screen', await has('#stop'));
const startProfile = await js(`document.querySelector('#deviceName').textContent`);

await selectDevice('car');
check('A car is selected', (await text('#deviceName')) === 'Car', await text('#deviceName'));
await page('operate');
check('Horizon draws', await js(`document.getElementById('horizon').getContext('2d').getImageData(80, 60, 1, 1).data[3] > 0`));
check('Map exists for a vehicle', await has('#map'));
check('Stats are live', /\d/.test(await text('#stats')), (await text('#stats')).slice(0, 60));
await shot('operate');

// Arming.
await js(`document.querySelector('#slideArm .thumb').click()`);
await sleep(400);
check('A plain click does not arm', /Disarmed|DISARMED/.test(await arm()), await arm());
await slide();
check('Sliding arms', await until(async () => (await arm()) === 'ARMED'), await arm());
check('Sticks appear while armed', await has('.stick'));
const r0 = await rect('.stick');
await mouse('mousePressed', r0.x + r0.w / 2, r0.y + 10);
await sleep(500);
const thr = await js(`document.querySelector('#outputs .joint b').textContent`);
check('On-screen stick moves an output', parseInt(thr) > 1500, thr);
check('Control shows a person', await until(async () => /ground/i.test(await text('#ownerState')), 3000), await text('#ownerState'));
await mouse('mouseReleased', r0.x + r0.w / 2, r0.y + 10);
await click('#stop');
check('STOP disarms in one tap', await until(async () => (await arm()) === 'Disarmed'), await arm());
check('The command result is shown', (await text('#toasts')).includes('Stop'), await text('#toasts'));

// Keyboard.
await slide();
await until(async () => (await arm()) === 'ARMED');
await cdp('Input.dispatchKeyEvent', { type: 'keyDown', code: 'Space', key: ' ', windowsVirtualKeyCode: 32 });
await cdp('Input.dispatchKeyEvent', { type: 'keyUp', code: 'Space', key: ' ', windowsVirtualKeyCode: 32 });
check('Space stops', await until(async () => (await arm()) === 'Disarmed'));

// Kill.
await slide();
await until(async () => (await arm()) === 'ARMED');
await js(`document.querySelector('#killBox summary').click()`);
await sleep(100);
await hold('#kill', 500);
check('A short press does not kill', (await arm()) === 'ARMED', await arm());
await hold('#kill', 2300);
check('A long press kills', await until(async () => (await arm()) === 'KILLED'), await arm());
const pulses = () => js(`document.querySelector('#outputs .joint b').textContent`);
check('Kill shows outputs off', await until(async () => parseInt(await pulses()) === 0, 1500), await pulses());
await slide();
check('A person arming clears a kill', await until(async () => (await arm()) === 'ARMED'), await arm());
await click('#stop');
await until(async () => (await arm()) === 'Disarmed');

// A failing check really blocks arming.
await setParam('ARM_REQ_CHECK', 1);
await sleep(900);
check('A required checklist removes the slider', !(await has('#slideArm')));
check('And says why', /Checklist/.test(await js(`document.querySelector('.side').innerText`)));
await page('test');
const boxes = await js(`document.querySelectorAll('#checklist input').length`);
check('Checklist listed', boxes >= 3, boxes);
for (let i = 0; i < boxes; i++) { await js(`(() => { const b = document.querySelectorAll('#checklist input')[${i}]; if (!b.checked) b.click(); })()`); await sleep(100); }
await sleep(800);
await page('operate');
check('Finishing it brings the slider back', await until(() => has('#slideArm')));
await setParam('ARM_REQ_CHECK', 0);

// Test page.
await page('test');
check('Readiness panel is listed', await js(`document.querySelectorAll('#readiness .check').length >= 3`));
await click('#runTests');
check('Automatic tests start', await until(async () => /Testing/.test(await text('#runTests')), 2000), await text('#runTests'));
check('Automatic tests produce a report', await until(async () => (await has('#report')) && !/Testing/.test(await text('#runTests')), 15000), (await text('#report')).slice(0, 200));
const stopLine = await js(`[...document.querySelectorAll('#report .check')].map(x => x.innerText).join(' | ')`);
console.log('      report:', stopLine.replace(/\n/g, ' '));
check('Stop test measured', /Outputs safe in \d+ ms/.test(stopLine));
check('Failsafe test measured', /\d+ ms after the \d+ ms link timeout/.test(stopLine));
check('A test raises no false alarm', !/link lost/.test(await js(`document.querySelector('.banners').innerText`)), await js(`document.querySelector('.banners').innerText`));
check('Tests leave it disarmed', (await arm()) === 'Disarmed', await arm());
await click('#linkTest');
check('Link test measures', await until(() => has('#linkResult'), 8000), await text('#linkResult'));
const runBtn = `#outputTests .testRow:nth-child(1) .run:last-child`;
await hold(runBtn, 500);
check('A motor test without confirming is refused', await until(async () => /Confirm props/.test(await text('#toasts')), 1500), await text('#toasts'));
await click('#propsOff');
await sleep(100);
const r1 = await rect(runBtn);
await mouse('mousePressed', r1.x + r1.w / 2, r1.y + r1.h / 2);
check('Hold to run is shown running', await until(async () => /Running/.test(await js('document.body.innerText')), 2000));
const during = await js(`document.querySelector('#outputTests .testRow:nth-child(1) .dim').textContent`);
check('Hold to run moves the output', parseInt(during) > 1500, during);
await shot('test');
await mouse('mouseReleased', r1.x + r1.w / 2, r1.y + r1.h / 2);
await sleep(700);
const after = await js(`document.querySelector('#outputTests .testRow:nth-child(1) .dim').textContent`);
check('Release stops it', parseInt(after) === 1500 || parseInt(after) === 1000, after);
check('Banner ends', !/Output test running/.test(await js('document.body.innerText')));

// Trim.
await page('setup');
check('Trim rows are listed', await has('[data-trim]'));
const row = '[data-trim]:nth-child(2)';
await js(`[...document.querySelectorAll('${row} button')].find(b => b.textContent === '+').click()`);
await sleep(800);
check('Trim changes', parseFloat(await text(`${row} .mono`)) === 0.005, await text(`${row} .mono`));
await js(`[...document.querySelectorAll('${row} button')].find(b => b.textContent === 'Undo').click()`);
await sleep(800);
check('Trim undoes', parseFloat(await text(`${row} .mono`)) === 0, await text(`${row} .mono`));

// Triggers.
await click('#addTrigger');
await sleep(150);
await js(`(() => { const i = document.querySelector('#trigLabel'); i.value = 'Test mark'; i.dispatchEvent(new Event('input', { bubbles: true })); })()`);
await sleep(150);
await js(`(() => { const s = document.querySelector('#trigType'); s.value = 'marker'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
await sleep(150);
await click('#trigSave');
await sleep(1000);
await page('operate');
check('A saved trigger gets a button', await until(() => has('[data-trigger=test-mark]')));
await sleep(500);
await js(`document.querySelector('[data-trigger=test-mark]').click()`);
await sleep(600);
check('Unarmed, an armed-only trigger is refused with the reason', /arm first/.test(await text('#toasts')), await text('#toasts'));
await shot('operate2');

// Settings.
await page('setup');
await js(`(() => { const i = document.querySelector('#paramSearch'); i.value = 'WP_THROTTLE'; i.dispatchEvent(new Event('input', { bubbles: true })); })()`);
await sleep(200);
const param = '[data-param=WP_THROTTLE]';
check('Settings search works', await has(param));
const before = await js(`document.querySelector('${param}').value`);
await js(`(() => { const i = document.querySelector('${param}'); i.value = 0.33; i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
check('Settings history records it', await until(() => has('#paramHistory'), 2000));
await js(`document.querySelector('#paramHistory summary').click(); [...document.querySelectorAll('#paramHistory button')][0].click()`);
await sleep(600);
check('Restore puts it back', String(await js(`document.querySelector('${param}').value`)) === String(before), await js(`document.querySelector('${param}').value`));
await shot('setup');
check('Backup offers a download', await has('#backupBtn') && (await js(`document.querySelector('#backupBtn').getAttribute('href')`)).startsWith('/backup?token='));
check('App health loads', await until(() => has('#phoneReport'), 4000));
check('App health lists how the app stopped', await until(() => js(`document.querySelectorAll('#exitList .item').length > 0`), 3000));
await js(`document.querySelector('#phoneReport').scrollIntoView()`);
await sleep(300);
await shot('setup-health');

// Plan: a plan file, checked live by the phone.
await page('plan');
await sleep(500);
// Measured each time: once checked, the profile and timeline appear under the map and it shrinks.
const clickMap = async (fx, fy) => { const m = await rect('#planMap'); const x = m.x + m.w * fx, y = m.y + m.h * fy; await mouse('mousePressed', x, y); await mouse('mouseReleased', x, y); await sleep(150); };
const steps = () => js(`document.querySelectorAll('#steps .item').length`);
// Zoomed in to a field's scale, whether or not the phone has a position to centre on yet.
{ const m = await rect('#planMap'); for (let i = 0; i < 16; i++) { await cdp('Input.dispatchMouseEvent', { type: 'mouseWheel', x: m.x + m.w / 2, y: m.y + m.h / 2, deltaX: 0, deltaY: -100 }); await sleep(20); } }
await js(`document.querySelector('[data-tool=goto]').click()`);
const before2 = await steps();
for (let i = 0; i < 4; i++) await clickMap(0.3 + i * 0.1, 0.3);
check('Clicking the map adds steps', (await steps()) === before2 + 4, await steps());
await clickButton('Undo');
await sleep(100);
check('Undo removes one', (await steps()) === before2 + 3);
await clickButton('Redo');
await sleep(100);
check('Redo restores it', (await steps()) === before2 + 4);
check('The phone checks it as it changes', await until(async () => /Checks out|problem/.test(await text('#checkState')), 5000), await text('#checkState'));
check('It becomes a timeline', await until(() => js(`document.querySelectorAll('#timeline .item').length >= 4`), 3000), await js(`document.querySelectorAll('#timeline .item').length`));
await click('#steps .item:last-child');
await sleep(150);
check('A step can be edited', await has('#stepEdit'));
const setField = async (label, v) => js(`(() => { const l = [...document.querySelectorAll('#stepEdit label')].find(l => l.textContent.startsWith(${JSON.stringify(label)})); const i = l.querySelector('input'); i.value = ${JSON.stringify(String(v))}; i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
await setField('Arrival radius', 0);
check('A bad step is refused with a reason, as it is made', await until(async () => /radius/.test(await text('#problems')), 4000), await text('#problems'));
await setField('Arrival radius', 5);
check('Fixed, it checks out again', await until(async () => /Checks out/.test(await text('#checkState')), 4000), await text('#checkState') + ' ' + await text('#problems'));
// A survey: four corners, then Finish.
await js(`document.querySelector('[data-tool=survey]').click()`);
for (const [fx, fy] of [[0.35, 0.5], [0.6, 0.5], [0.6, 0.7], [0.35, 0.7]]) await clickMap(fx, fy);
await click('#finishDrawing');
await sleep(150);
check('A survey is drawn as one step', (await steps()) === before2 + 5 && /Survey/.test(await text('#steps')), await text('#steps'));
check('Its turns and lines show in the timeline', await until(async () => /survey/.test(await text('#timeline')), 4000), (await text('#timeline')).slice(0, 120));
await js(`document.querySelector('[data-tool=zone-landing]').click()`);
await clickMap(0.2, 0.8);
check('A landing zone can be placed', /Safe zones \(1\)/.test(await js(`document.body.textContent`)));
// Narrow survey lines take a slow vehicle longer than its battery: the check says so. Wider ones fit.
await click('#steps .item:last-child');
await sleep(150);
await setField('Line spacing', 60);
check('Wider survey lines fit the battery', await until(async () => /Checks out/.test(await text('#checkState')), 4000), await text('#problems'));
check('The profile shows over time', await has('.profile svg'));
// Points marked for the AI, the airport bands, and what flights measured.
await js(`document.querySelector('[data-tool=anchor]').click()`);
await clickMap(0.8, 0.2);
check('Anchors are marked for the AI', await until(async () => /Marked for the AI: A/.test(await text('#anchors')), 2000), await text('#anchors'));
await clickButton('Clear anchors');
await js(`document.querySelector('[data-tool=goto]').click()`);
await js(`document.querySelector('#airportBands').click()`);
await sleep(600);
check('The airport bands layer turns on', await js(`document.querySelector('#airportBands').checked`));
await js(`document.querySelector('#airportBands').click()`);
await js(`[...document.querySelectorAll('a')].find(a => a.textContent === 'What flights measured')?.click()`);
check('What flights measured shows', await until(() => has('#learned'), 3000));
// The plan in 3D: MapLibre, loaded only now.
await click('#view3d');
check('The 3D view opens', await until(() => has('#map3d canvas'), 15000), await text('#map3d'));
await sleep(2500);
await shot('plan-3d');
await clickButton('Close 3D');
await sleep(200);
check('And closes', !(await has('#map3d')));
await shot('plan');
// Kept on the phone by name, then opened again.
await js(`(() => { const i = document.querySelector('#planName'); i.value = 'gs-check plan'; i.dispatchEvent(new Event('input', { bubbles: true })); i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
await sleep(100);
await clickButton('Save as "gs-check plan"');
await sleep(600);
await clickButton('Show');
check('Saved plans are listed', await until(async () => /gs-check plan/.test(await text('#library')), 3000), await text('#library'));
await click('#upload');
check('Sending answers: on the phone, or why not', await until(() => js(`!!document.querySelector('#uploaded, #sendProblem')`), 4000), await text('#sendProblem'));
const sent = await has('#uploaded');
await page('operate');
await sleep(800);
if (sent) check('The plan shows in Operate', /point \d+ of \d+/.test(await text('#missionState')), await text('#missionState'));
else console.log("SKIP  Plan in Operate: sending needs the phone's home, and there's no good GPS position indoors");
const gpsGood = await js(`[...document.querySelectorAll('.side .check')].every(x => !/^.\\s*GPS/.test(x.innerText))`);
if (gpsGood) {
  await hold('#missionStart', 1500);
  const ok = await until(async () => /running/.test(await text('#missionState')), 2500);
  // Indoors GPS can be good enough to try but rougher than the plan's arrival radius: the phone rightly refuses.
  const rough = /tightest arrival radius/.test(await text('#toasts'));
  if (!ok && rough) console.log(`SKIP  Mission start: GPS is rougher than the plan's arrival radius, and the phone said so: ${(await text('#toasts')).slice(0, 120)}`);
  else check('Mission starts, with a good GPS position', ok, `${await text('#missionState')} | ${await text('#toasts')}`);
  if (ok) {
    check('Mode shows it', (await text('#modeState')) === 'Mission', await text('#modeState'));
    await click('#stop');
    await until(async () => (await arm()) === 'Disarmed');
    check('Stop ends the mission', await until(async () => /aborted/.test(await text('#missionState'))), await text('#missionState'));
    check('And leaves normal arming', await until(async () => (await text('#modeState')) === 'Manual'), await text('#modeState'));
  }
} else console.log('SKIP  Mission start: no good GPS position indoors');

// Review.
await page('review');
check('Runs are listed', await until(() => has('[data-run]'), 3000));
await click('[data-run]');
check('A run opens', await until(() => has('#runView'), 6000));
await sleep(500);
check('Charts draw', await js(`(() => { const c = document.querySelector('.chart canvas'); const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; for (let i = 3; i < d.length; i += 4) if (d[i]) return true; return false; })()`));
check('Three charts, each with its own axis', (await js(`document.querySelectorAll('.chart').length`)) === 3);
await click('#play');
const s1 = await js(`document.querySelector('#scrub').value`);
await sleep(900);
check('Replay moves', (await js(`document.querySelector('#scrub').value`)) !== s1);
await click('#play');
await shot('review');
await clickButton('All runs');
await sleep(200);

// Video.
await page('operate');
await clickButton('Video');
await sleep(200);
const camWas = await js(`document.querySelector('#camToggle').classList.contains('sel')`);
if (!camWas) await click('#camToggle');
check('Video arrives', await until(() => has('#video'), 8000));
await shot('video');
await sleep(300);
await click('#camToggle');
await sleep(300);
await clickButton('Map');

// A device with joints gets different screens.
await selectDevice('device:biped-12');
await sleep(500);
await page('operate');
check('A biped has no map', !(await has('#map')), await text('#deviceName'));
check('A biped shows joints', await until(() => has('#joints'), 2000), await js(`document.querySelectorAll('#joints .joint').length`));
check('12 joints', (await js(`document.querySelectorAll('#joints .joint').length`)) === 12);
await shot('biped');
await page('plan');
check('Plan becomes a timeline', await has('#keyframes') && !(await has('#planMap')));
await click('#capture'); await sleep(50); await click('#capture'); await sleep(100);
check('Capture adds poses', (await js(`document.querySelectorAll('#keyframes .item').length`)) === 2);
await click('#saveSeq');
await sleep(1200);
check('The sequence saves', /done/.test(await text('#toasts')), await text('#toasts'));
await page('operate');
await sleep(800);
await slide();
check('Biped arms in preview', await until(async () => (await arm()) === 'ARMED'), `${await arm()} ${await text('#toasts')}`);
await clickButton('sequence1');
check('The saved sequence runs', await until(async () => /sequence1/.test(await text('#toasts')), 1000), await text('#toasts'));
await sleep(500);
await click('#stop');
await until(async () => (await arm()) === 'Disarmed');

// A plane plans with height: take off, a profile of height over time, and landing.
await selectDevice('plane');
await page('plan');
await sleep(500);
await js(`document.querySelector('#newPlan')?.click()`);
await sleep(200);
check('New plan starts empty', (await js(`document.querySelectorAll('#steps .item').length`)) === 0);
check('A plane can take off', await clickButton('+ Take off'));
await js(`document.querySelector('[data-tool=land]').click()`);
await clickMap(0.2, 0.3);
check('And land', /Land/.test(await text('#steps')), await text('#steps'));
check('Its plan is checked with height', await until(() => js(`!!document.querySelector('.profile svg path')`), 5000), await text('#checkState'));
check('Climbs and the landing are in the timeline', await until(async () => /touches down/.test(await text('#timeline')), 3000), (await text('#timeline')).slice(-160));
await sleep(300);
await shot('plan-plane');
await click('#view3d');
check('A plane plan in 3D', await until(() => has('#map3d canvas'), 15000));
await sleep(3000);
await shot('plan-plane-3d');
await clickButton('Close 3D');

// Narrow layout.
await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await selectDevice('car');
await page('operate');
await sleep(600);
check('No sideways scroll at phone width', await js(`document.documentElement.scrollWidth <= innerWidth + 1`), await js(`document.documentElement.scrollWidth`));
await shot('phone-operate');
await page('plan');
await sleep(300);
await shot('phone-plan');
await page('test');
await sleep(300);
check('Test fits at phone width', await js(`document.documentElement.scrollWidth <= innerWidth + 1`));
await shot('phone-test');
await cdp('Emulation.clearDeviceMetricsOverride');

// Ground test: the parts that can be shown indoors. Results are cleared before and after, so the check's own STOP
// isn't left looking like a person's ground test.
await selectDevice('car');
await page('test');
check('Ground test is listed', await until(async () => (await js(`document.querySelectorAll('#groundStages [data-stage]').length`)) === 7), await js(`document.querySelectorAll('#groundStages [data-stage]').length`));
await js(`document.querySelector('#groundTest details summary').click()`);
await clickButton('Clear the results');
await until(async () => /^0 of/.test(await text('#groundTestScore')));
await click('#outputsRight');
check('Outputs can only be confirmed after each was run', await until(async () => /run each output each way first/.test(await text('#toasts'))), (await text('#toasts')).slice(-120));
await page('operate');
await slide();
const armedForStop = await until(async () => (await arm()) === 'ARMED');
if (armedForStop) {
  await click('#stop');
  await page('test');
  check('A STOP while armed is recorded', await until(async () => js(`!!document.querySelector('[data-stage=stop] .mark.good')`)), await text('[data-stage=stop]'));
} else console.log(`SKIP  Ground test STOP: it didn't arm (${await arm()})`);
await page('test');
await click('[data-how=drive]');
check('Driving asks for a moving stop too', await until(async () => js(`!document.querySelector('[data-stage=stop-moving]').classList.contains('skip')`)));
await click('[data-how=walk]');
await until(async () => js(`document.querySelector('[data-stage=stop-moving]').classList.contains('skip')`));
await click('#makeTestPlan');
await sleep(800);
if (await has('#groundTestMsg')) console.log(`SKIP  Ground test plan: ${(await text('#groundTestMsg')).slice(0, 120)}`);
else {
  check('A ground test plan opens in Plan', await until(async () => /corner-4/.test(await text('#steps'))), (await text('#steps')).slice(0, 120));
  check('It checks out on the phone', await until(async () => /Checks out/.test(await text('#checkState')), 8000), await text('#checkState'));
  await shot('ground-test-plan');
  await clickButton('Undo');
}
await page('test');
await shot('ground-test');
await js(`document.querySelector('#groundTest details').open = true`);
await clickButton('Clear the results');
check('Ground test results cleared', await until(async () => /^0 of/.test(await text('#groundTestScore'))), await text('#groundTestScore'));

await cdp('Page.navigate', { url: `${base}/classic/` });
await sleep(2500);
check('The classic page still loads', await js(`!!document.getElementById('link')`));
// Clean up. Commands carry a time on the phone's clock, which can differ from this computer's, so read it from hello first.
const cleaned = await js(`new Promise(res => {
  const w = new WebSocket('ws://${host}/ws?token=${token}');
  w.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.type === 'hello') w.send(JSON.stringify({ type: 'cmd', seq: Date.now() * 1000, sentAt: Date.now() + (m.t - Date.now()), cmd: 'trigger_delete', id: 'test-mark' }));
    if (m.type === 'ack') { w.close(); res(m.accepted); }
  };
  setTimeout(() => res(false), 4000);
})`);
check('Test trigger cleaned up', cleaned === true);
const planGone = await js(`new Promise(res => {
  const w = new WebSocket('ws://${host}/ws?token=${token}');
  w.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.type === 'hello') w.send(JSON.stringify({ type: 'cmd', seq: Date.now() * 1000 + 1, sentAt: Date.now() + (m.t - Date.now()), cmd: 'plan_delete', name: 'gs-check plan' }));
    if (m.type === 'ack') { w.close(); res(m.accepted); }
  };
  setTimeout(() => res(false), 4000);
})`);
check('Test plan cleaned up', planGone === true);
// A plan sent during the check comes off the phone again.
const missionGone = await js(`new Promise(res => {
  const w = new WebSocket('ws://${host}/ws?token=${token}');
  w.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.type === 'hello') w.send(JSON.stringify({ type: 'cmd', seq: Date.now() * 1000 + 2, sentAt: Date.now() + (m.t - Date.now()), cmd: 'mission_clear' }));
    if (m.type === 'ack') { w.close(); res(m.accepted); }
  };
  setTimeout(() => res(false), 4000);
})`);
check('Sent plan cleared', missionGone === true);

for (const d of dialogs) console.log(`DIALOG  ${d.replace(/\s+/g, ' ').slice(0, 220)}`);
check('No script errors or blocked requests', problems.length === 0, problems.slice(0, 5).join(' || '));
console.log(`\n${failed ? `${failed} FAILED` : 'ALL PASSED'} (started on ${startProfile})`);
child.kill();
process.exit(failed ? 1 : 0);
