// Relay security and forwarding tests, using a stand-in vehicle. Run: npm test
import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';
import { createRelay } from '../server.mjs';

const dir = mkdtempSync(join(tmpdir(), 'relay-test-'));
let relay, base, host, vehicleKey, aiKey, vehicle;
const sleep = ms => new Promise(r => setTimeout(r, ms));

/** Acts like the phone: echoes channel messages and answers RPCs. */
function connectVehicle(key = vehicleKey) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${host}/vehicle`, { headers: { authorization: `Bearer ${key}` } });
    ws.opened = []; ws.got = []; ws.closedCh = [];
    ws.on('message', raw => {
      const m = JSON.parse(raw.toString());
      if (m.rpc !== undefined) {
        const body = m.kind === 'mcp' ? JSON.stringify({ jsonrpc: '2.0', id: 1, result: { echo: m.body } }) : 'a,b\n1,2\n';
        if (!ws.mute) ws.send(JSON.stringify({ rpc: m.rpc, status: 200, body }));
      } else if (m.op === 'open') {
        ws.opened.push(m.ch);
        ws.send(JSON.stringify({ ch: m.ch, op: 'msg', data: '{"type":"hello"}' }));
      } else if (m.op === 'msg') {
        ws.got.push(m.data);
        ws.send(JSON.stringify({ ch: m.ch, op: 'msg', data: `{"type":"echo","data":${JSON.stringify(m.data)}}` }));
      } else if (m.op === 'close') ws.closedCh.push(m.ch);
    });
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
    ws.on('unexpected-response', (_, res) => reject(new Error(String(res.statusCode))));
  });
}

let ipCounter = 1;
async function login(user, password, headers = {}) {
  return fetch(`${base}/login`, {
    method: 'POST', redirect: 'manual',
    // Each call appears to come from its own address unless a test says otherwise.
    headers: { 'content-type': 'application/x-www-form-urlencoded', 'x-forwarded-for': `203.0.113.${ipCounter++}`, ...headers },
    body: new URLSearchParams({ user, password }),
  });
}
const cookieOf = res => res.headers.get('set-cookie').split(';')[0];

function browser(cookie, origin) {
  return new Promise(resolve => {
    const headers = {};
    if (cookie) headers.cookie = cookie;
    if (origin) headers.origin = origin;
    const ws = new WebSocket(`ws://${host}/ws`, { headers });
    ws.msgs = [];
    ws.on('message', raw => ws.msgs.push(JSON.parse(raw.toString())));
    ws.on('open', () => resolve(ws));
    ws.on('close', code => { ws.closeCode = code; });
    ws.on('unexpected-response', (_, res) => { ws.refused = res.statusCode; resolve(ws); });
    ws.on('error', () => {});
  });
}

const mcp = (key, body = { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'x' } }) =>
  fetch(`${base}/mcp`, { method: 'POST', headers: key ? { authorization: `Bearer ${key}` } : {}, body: JSON.stringify(body) });

before(async () => {
  relay = createRelay({ dataDir: dir, trustProxy: true });
  await new Promise(r => relay.server.listen(0, '127.0.0.1', r));
  host = `127.0.0.1:${relay.server.address().port}`;
  base = `http://${host}`;
  vehicleKey = relay.store.newVehicleKey();
  aiKey = relay.store.newAiKey('claude');
  await relay.store.addUser('op', 'operator', 'operator-password');
  await relay.store.addUser('watcher', 'viewer', 'viewer-password');
  await relay.store.addUser('lockme', 'operator', 'lockme-password');
  vehicle = await connectVehicle();
});

after(async () => {
  vehicle?.terminate();
  await relay.close();
  rmSync(dir, { recursive: true, force: true });
});

test('secrets are never stored in plain text', () => {
  const stored = readFileSync(join(dir, 'auth.json'), 'utf8');
  for (const secret of [vehicleKey, aiKey, 'operator-password']) assert.ok(!stored.includes(secret));
});

test('signed-out visitors get no vehicle access', async () => {
  const home = await fetch(`${base}/`, { redirect: 'manual' });
  assert.equal(home.status, 303);
  assert.equal(home.headers.get('location'), '/login');
  assert.equal((await fetch(`${base}/assets/app.js`)).status, 401);
  assert.equal((await fetch(`${base}/review/files`)).status, 401);
  assert.equal((await fetch(`${base}/session`)).status, 401);
  assert.equal((await fetch(`${base}/export/telemetry.csv`)).status, 401);
  const ws = await browser();
  await sleep(100);
  assert.equal(ws.closeCode, 1008);
  assert.deepEqual(ws.msgs, []);
});

test('wrong password is refused, right password sets a protected cookie', async () => {
  assert.equal((await login('op', 'not-the-password')).status, 401);
  assert.equal((await login('nobody', 'not-the-password')).status, 401);
  const ok = await login('op', 'operator-password');
  assert.equal(ok.status, 303);
  const c = ok.headers.get('set-cookie');
  assert.match(c, /HttpOnly/);
  assert.match(c, /SameSite=Strict/);
});

test('repeated wrong passwords lock the account, even for the right password', async () => {
  for (let i = 0; i < 5; i++) await login('lockme', 'wrong-password');
  assert.equal((await login('lockme', 'lockme-password')).status, 429);
});

test('one address guessing across many accounts gets locked out, without affecting others', async () => {
  const attacker = { 'x-forwarded-for': '198.51.100.9' };
  for (let i = 0; i < 5; i++) await login(`guess${i}`, 'wrong-password', attacker);
  assert.equal((await login('op', 'operator-password', attacker)).status, 429);
  assert.equal((await login('op', 'operator-password')).status, 303);
});

test('wrong password lockout cannot be used on __proto__ style names', async () => {
  assert.equal((await login('__proto__', 'not-the-password')).status, 401);
  assert.equal((await login('constructor', 'not-the-password')).status, 401);
});

test('logins and connections from another site are refused', async () => {
  const res = await login('op', 'operator-password', { origin: 'https://evil.example' });
  assert.equal(res.status, 403);
  const cookie = cookieOf(await login('op', 'operator-password'));
  const ws = await browser(cookie, 'https://evil.example');
  assert.equal(ws.refused, 403);
});

test('pages never set a referrer policy that would break browser sign-in', async () => {
  const page = await fetch(`${base}/login`);
  assert.equal(page.headers.get('referrer-policy'), 'same-origin');
  const res = await login('op', 'operator-password', { origin: base });
  assert.equal(res.status, 303);
});

test('vehicle connection needs the vehicle key', async () => {
  await assert.rejects(connectVehicle('wrong-key'), /401/);
  await assert.rejects(connectVehicle(aiKey), /401/);
});

test('operator traffic reaches the vehicle and answers come back', async () => {
  const cookie = cookieOf(await login('op', 'operator-password'));
  const session = await (await fetch(`${base}/session`, { headers: { cookie } })).json();
  assert.deepEqual([session.user, session.role, session.vehicleOnline], ['op', 'operator', true]);
  const ws = await browser(cookie);
  await sleep(100);
  ws.send('{"type":"cmd","seq":1,"cmd":"drive"}');
  await sleep(100);
  assert.ok(vehicle.got.includes('{"type":"cmd","seq":1,"cmd":"drive"}'));
  assert.ok(ws.msgs.some(m => m.type === 'echo'));
  assert.ok(ws.msgs.some(m => m.type === 'hello'));
  ws.close();
  await sleep(50);
  assert.ok(vehicle.closedCh.length >= 1);
});

test('viewers can watch and stop, but nothing else', async () => {
  const cookie = cookieOf(await login('watcher', 'viewer-password'));
  const ws = await browser(cookie);
  await sleep(50);
  const before = vehicle.got.length;
  for (const m of [
    '{"type":"cmd","seq":2,"cmd":"drive","throttle":1,"steering":0}',
    '{"type":"cmd","seq":3,"cmd":"set_mode","mode":"MANUAL"}',
    '{"type":"fault","fault":"disconnect","value":5}',
    'not json',
  ]) ws.send(m);
  await sleep(100);
  assert.equal(vehicle.got.length, before, 'nothing forwarded');
  assert.equal(ws.msgs.filter(m => m.type === 'ack' && m.code === 'not_permitted').length, 4);
  ws.send('{"type":"cmd","seq":4,"cmd":"stop"}');
  ws.send('{"type":"ping","c":1}');
  await sleep(100);
  assert.equal(vehicle.got.length, before + 2);
  assert.equal((await fetch(`${base}/export/telemetry.csv`, { headers: { cookie } })).status, 403);
  ws.close();
});

test('a browser flooding commands is limited, but stop still gets through', async () => {
  const cookie = cookieOf(await login('op', 'operator-password'));
  const ws = await browser(cookie);
  await sleep(50);
  const start = vehicle.got.length;
  for (let i = 0; i < 500; i++) ws.send(`{"type":"cmd","seq":${i},"cmd":"drive"}`);
  ws.send('{"type":"cmd","seq":9999,"cmd":"stop"}');
  await sleep(200);
  const forwarded = vehicle.got.slice(start);
  assert.ok(forwarded.length < 100, `forwarded ${forwarded.length}`);
  assert.ok(forwarded.some(m => m.includes('"stop"')));
  ws.close();
});

test('MCP needs an AI key, and login passwords or vehicle keys do not work', async () => {
  for (const bad of [null, 'wrong', vehicleKey, 'operator-password']) assert.equal((await mcp(bad)).status, 401);
  const res = await mcp(aiKey);
  assert.equal(res.status, 200);
  assert.ok((await res.json()).result.echo);
  assert.ok(res.headers.get('x-spx-vehicle-ms'));
});

test('an AI key cannot open the ground station', async () => {
  const res = await fetch(`${base}/session`, { headers: { authorization: `Bearer ${aiKey}` } });
  assert.equal(res.status, 401);
});

test('MCP flood is limited', async () => {
  const results = await Promise.all(Array.from({ length: 60 }, () => mcp(aiKey).then(r => r.status)));
  assert.ok(results.includes(429));
  await sleep(1100);
});

test('removed AI keys stop working at once', async () => {
  const temp = relay.store.newAiKey('temp');
  assert.equal((await mcp(temp)).status, 200);
  relay.store.removeAiKey('temp');
  assert.equal((await mcp(temp)).status, 401);
});

test('oversized requests are refused', async () => {
  const res = await fetch(`${base}/mcp`, { method: 'POST', headers: { authorization: `Bearer ${aiKey}` }, body: 'x'.repeat(300 * 1024) }).catch(() => null);
  assert.ok(!res || res.status === 413 || res.status === 500);
  await sleep(1100);
});

test('signing out ends the session and live connection', async () => {
  const cookie = cookieOf(await login('op', 'operator-password'));
  const ws = await browser(cookie);
  await sleep(50);
  await fetch(`${base}/logout`, { method: 'POST', headers: { cookie }, redirect: 'manual' });
  await sleep(100);
  assert.equal(ws.closeCode, 1008);
  assert.equal((await fetch(`${base}/session`, { headers: { cookie } })).status, 401);
});

test('vehicle going offline is reported, and channels reopen when it returns', async () => {
  const cookie = cookieOf(await login('op', 'operator-password'));
  const ws = await browser(cookie);
  await sleep(50);
  vehicle.terminate();
  await sleep(150);
  const off = ws.msgs.filter(m => m.type === 'vehicle').at(-1);
  assert.equal(off.online, false);
  ws.send('{"type":"cmd","seq":77,"cmd":"drive"}');
  await sleep(50);
  assert.equal(ws.msgs.find(m => m.seq === 77).code, 'vehicle_offline');
  const r = await mcp(aiKey);
  assert.equal(r.status, 503);
  assert.equal((await r.json()).error.message, 'Vehicle is offline');
  vehicle = await connectVehicle();
  await sleep(150);
  assert.equal(ws.msgs.filter(m => m.type === 'vehicle').at(-1).online, true);
  assert.equal(vehicle.opened.length, 1);
  ws.close();
});

test('a newer vehicle connection replaces a stale one', async () => {
  const old = vehicle;
  vehicle = await connectVehicle();
  await sleep(100);
  assert.equal(old.readyState, WebSocket.CLOSED);
  assert.equal((await mcp(aiKey)).status, 200);
  await sleep(100);
});

const enrollNew = (cookie, headers = {}) => fetch(`${base}/enroll/new`, { method: 'POST', headers: { ...(cookie ? { cookie } : {}), ...headers } });
const claim = (code, ip) => fetch(`${base}/enroll/claim`, { method: 'POST', headers: { 'x-forwarded-for': ip }, body: JSON.stringify({ code }) });
const codeOf = async res => new URL((await res.json()).url).searchParams.get('code');

test('only a signed-in operator can create a QR code', async () => {
  assert.equal((await enrollNew()).status, 401);
  const viewer = cookieOf(await login('watcher', 'viewer-password'));
  assert.equal((await enrollNew(viewer)).status, 403);
  const op = cookieOf(await login('op', 'operator-password'));
  assert.equal((await enrollNew(op, { origin: 'https://evil.example' })).status, 403);
  const res = await enrollNew(op);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.match(body.svg, /^<svg/);
  assert.equal(body.encrypted, false);
  assert.match(body.url, /\/enroll\/open\?code=[A-Za-z0-9_-]{32}$/);
});

test('the scanned page links to the app, and viewing it does not use the code', async () => {
  const op = cookieOf(await login('op', 'operator-password'));
  const code = await codeOf(await enrollNew(op));
  const page = await (await fetch(`${base}/enroll/open?code=${code}`)).text();
  assert.ok(page.includes(`smartphonex://enroll?relay=${encodeURIComponent(base)}&#38;code=${code}`));
  assert.equal((await fetch(`${base}/enroll/open?code=<script>`)).status, 400);
  assert.equal((await claim(code, '203.0.113.200')).status, 200);
  vehicle = await connectVehicleWithLatestKey();
});

let latestKey = null;
const latestKeyNow = () => currentVehicleKey;
let currentVehicleKey = null;
async function connectVehicleWithLatestKey() {
  const op = cookieOf(await login('op', 'operator-password'));
  const code = await codeOf(await enrollNew(op));
  const res = await claim(code, '203.0.113.201');
  latestKey = (await res.json()).vehicleKey;
  return connectVehicle(latestKey);
}

test('a claimed code gives a new key, disconnects the old phone, and works only once', async () => {
  const op = cookieOf(await login('op', 'operator-password'));
  const code = await codeOf(await enrollNew(op));
  const oldVehicle = vehicle;
  const oldKey = latestKey;
  const res = await claim(code, '203.0.113.202');
  assert.equal(res.status, 200);
  const { vehicleKey } = await res.json();
  currentVehicleKey = vehicleKey;
  await sleep(100);
  assert.equal(oldVehicle.readyState, WebSocket.CLOSED);
  await assert.rejects(connectVehicle(oldKey), /401/);
  vehicle = await connectVehicle(vehicleKey);
  assert.equal((await claim(code, '203.0.113.203')).status, 401, 'one use only');
});

test('wrong codes are refused and locked out, without replacing the key', async () => {
  const before = relay.store.data.vehicleKeyHash;
  const ip = '198.51.100.77';
  for (let i = 0; i < 5; i++) assert.equal((await claim('A'.repeat(32), ip)).status, 401);
  assert.equal((await claim('A'.repeat(32), ip)).status, 429);
  assert.equal(relay.store.data.vehicleKeyHash, before);
  const op = cookieOf(await login('op', 'operator-password'));
  const good = await codeOf(await enrollNew(op));
  assert.equal((await claim(good, ip)).status, 429, 'even a real code, while locked out');
});

test('an expired code is refused', async t => {
  const op = cookieOf(await login('op', 'operator-password'));
  const code = await codeOf(await enrollNew(op));
  const realNow = Date.now;
  t.after(() => { Date.now = realNow; });
  Date.now = () => realNow() + 5 * 60_000 + 1000;
  assert.equal((await claim(code, '203.0.113.210')).status, 401);
});

test('video reaches operators only, is acknowledged, and the phone knows who is watching', async () => {
  const op = cookieOf(await login('op', 'operator-password'));
  const key = latestKeyNow();
  const seen = [];
  vehicle.removeAllListeners('message');
  vehicle.on('message', raw => { const m = JSON.parse(raw.toString()); if (m.op === 'video') seen.push(m.viewers); });

  const open = (cookie, origin) => new Promise(resolve => {
    const headers = {};
    if (cookie) headers.cookie = cookie;
    if (origin) headers.origin = origin;
    const ws = new WebSocket(`ws://${host}/video`, { headers });
    ws.frames = [];
    ws.on('message', (d, bin) => { if (bin) ws.frames.push(d); });
    ws.on('open', () => resolve(ws));
    ws.on('unexpected-response', (_, res) => { ws.refused = res.statusCode; resolve(ws); });
    ws.on('error', () => {});
  });

  assert.equal((await open()).refused, 403, 'signed out');
  const viewerCookie = cookieOf(await login('watcher', 'viewer-password'));
  assert.equal((await open(viewerCookie)).refused, 403, 'viewer');
  assert.equal((await open(op, 'https://evil.example')).refused, 403, 'other site');

  const badPhone = new WebSocket(`ws://${host}/vehicle/video`, { headers: { authorization: 'Bearer wrong' } });
  assert.equal(await new Promise(r => { badPhone.on('unexpected-response', (_, res) => r(res.statusCode)); badPhone.on('error', () => {}); }), 401);

  const phone = new WebSocket(`ws://${host}/vehicle/video`, { headers: { authorization: `Bearer ${key}` } });
  const acks = [];
  phone.on('message', d => acks.push(d.toString()));
  await new Promise(r => phone.on('open', r));

  const v = await open(op);
  await sleep(100);
  assert.deepEqual(seen, [1]);
  phone.send(Buffer.from([1, 2, 3]), { binary: true });
  await sleep(100);
  assert.deepEqual(acks, ['a']);
  assert.equal(v.frames.length, 1);
  assert.deepEqual([...v.frames[0]], [1, 2, 3]);

  await fetch(`${base}/logout`, { method: 'POST', headers: { cookie: op }, redirect: 'manual' });
  await sleep(100);
  assert.equal(v.readyState, WebSocket.CLOSED, 'signing out ends video');
  assert.deepEqual(seen, [1, 0]);
  phone.terminate();
});

test('channels are opened with the role, so the phone can hide private data from viewers', async () => {
  const roles = [];
  vehicle.removeAllListeners('message');
  vehicle.on('message', raw => { const m = JSON.parse(raw.toString()); if (m.op === 'open') roles.push(m.role); });
  const a = await browser(cookieOf(await login('op', 'operator-password')));
  const b = await browser(cookieOf(await login('watcher', 'viewer-password')));
  await sleep(100);
  assert.deepEqual(roles.sort(), ['operator', 'viewer']);
  a.close(); b.close();
});

test('viewers cannot change sensors, budget, camera or AI access', async () => {
  const b = await browser(cookieOf(await login('watcher', 'viewer-password')));
  await sleep(50);
  for (const m of ['{"type":"sensor_cfg","id":"x","enabled":true}', '{"type":"budget","kbps":1}', '{"type":"camera_cfg","enabled":true}', '{"type":"ai_access","value":true}']) b.send(m);
  await sleep(100);
  assert.equal(b.msgs.filter(m => m.code === 'not_permitted').length, 4);
  b.close();
});

test('security events are audited without secrets', () => {
  const log = readFileSync(join(dir, 'audit.log'), 'utf8');
  for (const e of ['login_failed', 'login_blocked', 'browser_forbidden', 'mcp_denied', 'vehicle_denied', 'ws_bad_origin', 'enroll_created', 'enroll_claimed', 'enroll_denied']) assert.ok(log.includes(e), e);
  assert.ok(!log.includes(latestKey));
  for (const secret of [vehicleKey, aiKey, 'operator-password', 'not-the-password']) assert.ok(!log.includes(secret));
  assert.ok(existsSync(join(dir, 'audit.log')));
});
