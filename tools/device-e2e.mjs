// Sensors, camera, data budget and vehicle outputs on a running device, directly or via the relay.
//   node tools/device-e2e.mjs local <host:port> <token>
//   node tools/device-e2e.mjs relay <relay-url> <user> <password> <ai-key>
// Leaves camera, location and AI access off, and restores the selected vehicle and budget.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';

const [mode, a, b, c, d] = process.argv.slice(2);
const local = mode === 'local';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => { ok ? passed++ : failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };

const base = local ? `http://${a}` : a;
const token = local ? b : '';
let cookie = '';
const aiKey = local ? b : d;
if (!local) {
  const res = await fetch(`${base}/login`, { method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded', origin: base }, body: new URLSearchParams({ user: b, password: c }) });
  cookie = res.headers.get('set-cookie').split(';')[0];
}
const wsUrl = path => `${base.replace(/^http/, 'ws')}${path}${local ? `?token=${token}` : ''}`;
const wsOpts = local ? {} : { headers: { cookie, origin: base } };

class Link {
  constructor() { this.seq = 0; this.acks = new Map(); this.values = {}; this.sensorMsgs = 0; this.cfgAcks = []; this.drops = 0; }
  open() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(wsUrl('/ws'), wsOpts);
      this.ws.on('message', raw => this.on(JSON.parse(raw.toString())));
      this.ws.on('error', reject);
      const t = setInterval(() => { if (this.hello) { clearInterval(t); resolve(this); } }, 20);
    });
  }
  on(m) {
    if (m.type === 'hello') { this.hello = m; this.offset = m.t - Date.now(); this.config = m; }
    if (m.type === 'config') this.config = m;
    if (m.type === 'telemetry') this.tel = m.data;
    if (m.type === 'ack') this.acks.set(m.seq, m);
    if (m.type === 'cfgAck') this.cfgAcks.push(m);
    if (m.type === 'sensorStats') this.stats = m;
    if (m.type === 'sensors') { this.sensorMsgs++; Object.assign(this.values, m.d); this.drops += m.drop; }
  }
  raw(o) { this.ws.send(JSON.stringify(o)); }
  async cmd(cmd, args = {}) {
    this.seq = Math.max(this.seq + 1, Date.now() * 1000);
    const seq = this.seq;
    this.raw({ type: 'cmd', seq, sentAt: Date.now() + this.offset, cmd, ...args });
    for (let i = 0; i < 500 && !this.acks.has(seq); i++) await sleep(10);
    return this.acks.get(seq) ?? { accepted: false, code: 'no_ack' };
  }
  async cfg(o) {
    const n = this.cfgAcks.length;
    this.raw(o);
    for (let i = 0; i < 500 && this.cfgAcks.length === n; i++) await sleep(10);
    return this.cfgAcks.at(-1) ?? {};
  }
  close() { this.ws.close(); }
}

async function mcp(method, params) {
  const res = await fetch(`${base}/mcp`, { method: 'POST', headers: { authorization: `Bearer ${aiKey}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
  return res.json();
}
const tool = async (name, args = {}) => (await mcp('tools/call', { name, arguments: args })).result;
const toolJson = async (name, args) => JSON.parse((await tool(name, args)).content[0].text);

function video(seconds) {
  return new Promise(resolve => {
    const w = new WebSocket(wsUrl('/video'), wsOpts);
    const r = { frames: 0, bytes: 0, first: null, jpeg: false, rot: null };
    w.on('message', (data, isBinary) => {
      if (!isBinary) return;
      r.frames++;
      r.bytes += data.length;
      if (!r.first) r.first = data;
      r.jpeg = data[9] === 0xFF && data[10] === 0xD8;
      r.rot = data[8];
      if (local) w.send('a');
    });
    w.on('error', () => resolve(r));
    w.on('open', () => setTimeout(() => { w.close(); resolve(r); }, seconds * 1000));
  });
}

let l = await new Link().open();
await sleep(1500);
const originalProfile = l.hello.activeProfile;
const originalBudget = l.config.budgetKbps;
const originalCfg = Object.fromEntries(l.config.sensors.map(s => [s.id, s]));

// --- Discovery: whatever this device has, with nothing assumed.
const sensors = l.config.sensors;
check('Device sensors are discovered', sensors.length > 0, `${sensors.length}: ${sensors.map(s => s.name).slice(0, 8).join(', ')}...`);
check('Phone state sensors are always present', sensors.some(s => s.id === 'phone.battery') && sensors.some(s => s.id === 'phone.thermal'));
check('Location is marked private', sensors.find(s => s.id === 'phone.location')?.private === true);
check('Location is off by default', sensors.find(s => s.id === 'phone.location')?.enabled === false);
check('Camera list is available', Array.isArray(l.config.cameras) && l.config.cameras.length > 0, l.config.cameras?.map(x => x.name).join(', '));
check('Test pattern always offered', l.config.cameras.some(x => x.id === 'test'));
check('Camera is off by default', l.config.camera.enabled === false);
check('AI access is off by default', l.config.aiAccess === false);

// --- Streaming
await sleep(3000);
check('Sensor values are streaming', Object.keys(l.values).length > 0, `${Object.keys(l.values).length} sensors, ${l.sensorMsgs} messages`);
check('Battery value arrives', Array.isArray(l.values['phone.battery']), JSON.stringify(l.values['phone.battery']));
check('Stats report rates', !!l.stats?.s, `${l.stats?.sensorKbps?.toFixed(1)} kbps`);

// --- Per-sensor control. Uses the battery sensor because every device has it.
const id = 'phone.battery';
check('Rate can be changed', (await l.cfg({ type: 'sensor_cfg', id, rateHz: 1 })).ok);
check('Bad rate refused', (await l.cfg({ type: 'sensor_cfg', id, rateHz: 9999 })).ok === false);
check('Unknown sensor refused', (await l.cfg({ type: 'sensor_cfg', id: 'no.such', enabled: true })).ok === false);
await l.cfg({ type: 'sensor_cfg', id, enabled: false });
await sleep(400);
delete l.values[id];
await sleep(2500);
check('Disabled sensor stops', l.values[id] === undefined);
await l.cfg({ type: 'sensor_cfg', id, enabled: true });
await sleep(2500);
check('Enabled sensor resumes', Array.isArray(l.values[id]));

// --- Budget and priority
const moving = sensors.filter(s => !s.unavailable && s.enabled && !s.id.startsWith('phone.'));
if (moving.length >= 2) {
  const hi = moving[0].id, lo = moving[1].id;
  await l.cfg({ type: 'sensor_cfg', id: hi, priority: 1, rateHz: 20 });
  await l.cfg({ type: 'sensor_cfg', id: lo, priority: 3, rateHz: 20 });
  const before = l.drops;
  await l.cfg({ type: 'budget', kbps: 1 });
  await sleep(4000);
  check('A tight budget drops data', l.drops > before, `${l.drops - before} dropped`);
  await l.cfg({ type: 'budget', kbps: 0 });
  await l.cfg({ type: 'sensor_cfg', id: hi, priority: originalCfg[hi].priority, rateHz: originalCfg[hi].rateHz });
  await l.cfg({ type: 'sensor_cfg', id: lo, priority: originalCfg[lo].priority, rateHz: originalCfg[lo].rateHz });
} else {
  console.log('SKIP  budget priority: this device has too few moving sensors');
}
check('Commands still work', (await l.cmd('stop')).accepted);

// --- Camera, using the test pattern so it works on any device and shows nothing private.
check('No video while the camera is off', (await video(1.5)).frames === 0);
check('Camera can be configured', (await l.cfg({ type: 'camera_cfg', enabled: true, cameraId: 'test', width: 320, height: 240, fps: 10, quality: 50 })).ok);
check('Bad frame rate refused', (await l.cfg({ type: 'camera_cfg', fps: 999 })).ok === false);
const v = await video(5);
check('Video frames arrive as JPEG', v.frames > 5 && v.jpeg, `${v.frames} frames, ${(v.bytes * 8 / 5000).toFixed(0)} kbps`);
await sleep(2500);
check('Camera reports status', /waiting|live/.test(l.stats ? (await waitCam()) : ''), lastCam);

await l.cfg({ type: 'budget', kbps: 50 });
const limited = await video(5);
const limitedKbps = limited.bytes * 8 / 5000;
const unlimitedKbps = v.bytes * 8 / 5000;
check('Budget limits video, without stopping it', limitedKbps > 0 && limitedKbps <= 60, `${limitedKbps.toFixed(0)} kbps at a 50 kbps budget, ${unlimitedKbps.toFixed(0)} kbps without`);
await l.cfg({ type: 'budget', kbps: 0 });

// --- Every real camera this device offers. Frames only come to this test, and aren't kept.
for (const cam of l.config.cameras.filter(x => x.id !== 'test')) {
  const size = cam.sizes.includes('320x240') ? '320x240' : cam.sizes[0];
  const [width, height] = size.split('x').map(Number);
  await l.cfg({ type: 'camera_cfg', enabled: true, cameraId: cam.id, width, height, fps: 5, quality: 50 });
  const f = await video(6);
  check(`Real camera streams: ${cam.name}`, f.frames > 3 && f.jpeg, `${f.frames} frames at ${size}, ${(f.bytes * 8 / 6000).toFixed(0)} kbps, rotation ${f.rot}`);
}
await l.cfg({ type: 'camera_cfg', enabled: true, cameraId: 'test', width: 320, height: 240, fps: 10, quality: 50 });
await sleep(500);

// --- AI access to private data
const denied = await tool('vehicle_get_camera_frame');
check('AI is refused the camera without permission', denied.isError === true && denied.content[0].text.includes('not_permitted'));
check('AI sees sensors', Object.keys(await toolJson('vehicle_get_sensors')).length > 0);
await l.cfg({ type: 'ai_access', value: true });
const still = await tool('vehicle_get_camera_frame');
check('AI gets a still once granted', still.content?.[0]?.type === 'image' && still.content[0].mimeType === 'image/jpeg', `${still.content?.[0]?.data?.length} base64 chars`);
await l.cfg({ type: 'camera_cfg', enabled: false });
await l.cfg({ type: 'ai_access', value: false });
check('Camera off again', (await video(1.5)).frames === 0);

// --- Vehicle types
await l.cmd('stop');
await l.cmd('set_mode', { mode: 'DISARMED' });
for (const want of ['car', 'skid', 'boat', 'boat-twin', 'plane', 'wing', 'custom']) {
  check(`Vehicle type available: ${want}`, l.hello.profiles.some(p => p.id === want));
}
check('Plane can be selected', (await l.cmd('select_profile', { id: 'plane' })).accepted);
await sleep(300);
check('Telemetry shows four plane outputs', l.tel.out.length === 4, JSON.stringify(l.tel.out));
check('Safe state: motor off, surfaces neutral', JSON.stringify(l.tel.out.map(o => o[1])) === '[1000,1500,1500,1500]');
check('AI cannot arm a plane', (await toolJson('vehicle_set_mode', { mode: 'AUTO' })).code === 'not_permitted');
check('Capabilities describe the plane', (await toolJson('vehicle_get_capabilities')).vehicleType === 'plane');
await l.cmd('set_mode', { mode: 'MANUAL' });
let driving = true;
const stream = (async () => { while (driving) { await l.cmd('drive', { throttle: 0.5, steering: 0.2, pitch: -0.4, roll: 0.6 }); await sleep(50); } })();
await sleep(800);
check('Pilot inputs reach the plane outputs', JSON.stringify(l.tel.out.map(o => o[1])) === '[1500,1800,1300,1600]', JSON.stringify(l.tel.out.map(o => o[1])));
check('Serial frame shown', l.tel.frame.startsWith('C8 18 16'), l.tel.frame.slice(0, 40));
driving = false;
await stream;
await sleep(1000);
check('Link loss: plane goes to failsafe values', l.tel.mode === 'FAILSAFE' && JSON.stringify(l.tel.out.map(o => o[1])) === '[1000,1500,1500,1500]', `${l.tel.mode} ${JSON.stringify(l.tel.out.map(o => o[1]))}`);

await l.cmd('set_mode', { mode: 'DISARMED' });
check('Custom vehicle can be selected', (await l.cmd('select_profile', { id: 'custom' })).accepted);
check('Custom outputs saved', (await l.cmd('set_channels', { channels: [{ name: 'Winch', kind: 'MOTOR_FORWARD', throttle: 1 }, { name: 'Gate', kind: 'SERVO', yaw: 1, failsafe: 0.5 }], frameFormat: 'ibus' })).accepted);
check('Invalid outputs refused', (await l.cmd('set_channels', { channels: [] })).code === 'out_of_range');
await sleep(300);
check('Custom failsafe value is held', JSON.stringify(l.tel.out.map(o => o[1])) === '[1000,1750]', JSON.stringify(l.tel.out.map(o => o[1])));
check('iBus frame shown', l.tel.frame.startsWith('20 40'), l.tel.frame.slice(0, 30));
await l.cmd('set_channels', { channels: [{ name: 'Output 1', throttle: 1 }, { name: 'Output 2', yaw: 1 }], frameFormat: 'crsf' });

await l.cmd('select_profile', { id: originalProfile });
await l.cfg({ type: 'budget', kbps: originalBudget });
l.close();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);

var lastCam;
async function waitCam() {
  // Stats carry the camera state; read the next one.
  const seen = await new Promise(resolve => {
    const prev = l.on.bind(l);
    l.on = m => { prev(m); if (m.type === 'sensorStats') { l.on = prev; resolve(m.camera); } };
  });
  lastCam = seen;
  return seen ?? '';
}
