// Design preview: serves the real, built ground station with sample data standing in for a phone, so the screens can
// be looked at and clicked without hardware. Nothing here controls anything; commands are answered "done".
//   node tools/preview-station.mjs [port]      then open http://127.0.0.1:<port>/#token=preview
// The device is named "Car (preview)" so a screenshot of this can't be mistaken for a real vehicle.
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const { WebSocketServer } = createRequire(join(root, 'relay', 'package.json'))('ws');
const web = join(root, 'app', 'src', 'main', 'assets', 'web');
const port = Number(process.argv[2] ?? 8095);
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.webmanifest': 'application/manifest+json' };
const TILES = {
  satellite: (z, x, y) => `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`,
  street: (z, x, y) => `https://tile.openstreetmap.org/${z}/${x}/${y}.png`,
};

const home = { lat: 18.5793, lon: 73.9089 };
const t0 = Date.now();
const now = () => Date.now();
let armed = false, mode = 'MANUAL', flightMode = 'MANUAL', armedAt = 0;
let mission = { state: 'IDLE', index: 0 };
let camera = false;
const camCfg = () => ({ enabled: camera, cameraId: '0', width: 1280, height: 720, fps: 10, quality: 70 });
// Stand-in camera: a few aerial tiles round home, shown in turn, as if looking down from a moving vehicle.
const frames = [];
(async () => {
  const z = 19, n = 2 ** z;
  const x = Math.floor((home.lon + 180) / 360 * n), y = Math.floor((1 - Math.log(Math.tan(home.lat * Math.PI / 180) + 1 / Math.cos(home.lat * Math.PI / 180)) / Math.PI) / 2 * n);
  for (const [dx, dy] of [[0, 0], [1, 0], [1, 1], [0, 1]]) {
    try { frames.push(Buffer.from(await (await fetch(TILES.satellite(z, x + dx, y + dy))).arrayBuffer())); } catch { /* Offline: no picture. */ }
  }
})();
const route = [[0, 0], [40, 0], [40, 40], [0, 40]].map(([n, e]) => ({ lat: home.lat + n / 111320, lon: home.lon + e / (111320 * Math.cos(home.lat * Math.PI / 180)) }));
const ground = { how: 'walk', done: {} };

const params = [
  ['Navigation', 'WP_THROTTLE', 0.4, 'Throttle on the way to a waypoint', 0, 1], ['Navigation', 'WP_RADIUS', 5, 'Arrival radius, m', 1, 50],
  ['Failsafe', 'FS_BATT_MIN', 20, 'Lowest phone battery to arm, %', 0, 90], ['Failsafe', 'FENCE_MARGIN', 2, 'Extra margin at the fence, m', 0, 20],
  ['Planning', 'PLAN_SPEED', 2, 'Planned speed, m/s', 0.2, 40], ['Face', 'FACE_MODE', 0, 'When the face shows', 0, 2],
].map(([group, name, value, desc, min, max]) => ({ group, name, value, desc, min, max }));

const profile = { id: 'car', name: 'Car (preview)', commandTimeoutMs: 500, channels: [{ name: 'Motor', kind: 'MOTOR' }, { name: 'Steering', kind: 'SERVO' }] };
const hello = () => ({
  type: 'hello', t: now(), protocol: 3, params,
  caps: { map: true, vehicleType: 'car', modeNoun: 'Drive mode', skills: [], behaviors: [] },
  profiles: [profile, { id: 'plane-sim', name: 'Simulated plane', channels: [] }],
  device: { name: 'Car (preview)', class: 'car', hazard: 'low', signedOff: true },
  camera: camCfg(), cameras: [{ id: '0', name: 'Back camera', sizes: ['640x480', '1280x720', '1920x1080'] }], net: { transport: 'wifi' },
  sensors: [{ id: 'phone.accel', name: 'Accelerometer', unit: 'm/s²', enabled: true, rateHz: 10, priority: 1 }, { id: 'phone.location', name: 'Location', unit: '', enabled: true, rateHz: 1, priority: 2 }],
});
const check = (id, label, status, detail = '', fix = '') => ({ id, label, status, detail, fix });
const stage = (id, label, how, status, detail = '') => ({ id, label, how, status, detail });

function pos(t) {
  if (mission.state !== 'RUNNING') return { ...home, speed: 0 };
  const s = ((t - mission.since) / 1000) * 1.4, leg = 40, k = Math.floor(s / leg) % 4, f = (s % leg) / leg;
  const a = k === 0 ? { lat: home.lat, lon: home.lon } : route[k - 1], b = route[k];
  mission.index = k;
  return { lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f, speed: 1.4 };
}

function ops(t) {
  const p = pos(t);
  return {
    type: 'ops', t, flightMode, owner: armed ? 'GROUND' : null, killed: false, armProblem: null,
    readiness: [check('gyro', 'Gyroscope', 'ok', '211 Hz'), check('gps', 'GPS', 'ok', '±4 m, 14 satellites'), check('battery', 'Battery', 'ok', '86%'), check('fence', 'Fence', 'warn', 'No fence set', 'Draw one in Plan')],
    mission: { state: mission.state, name: 'Ground test, walked', index: mission.index, total: 4, dist: mission.state === 'RUNNING' ? 18 : null, bearing: null, onLinkLoss: 'hold', hash: 'a41f09c2', step: `corner-${mission.index + 1}`, elapsedS: mission.state === 'RUNNING' ? (t - mission.since) / 1000 : 0, lateS: 1 },
    missionData: { name: 'Ground test, walked', onEnd: 'hold', onLinkLoss: 'hold', items: route.map((r, i) => ({ ...r, radiusM: 6, expectAt: 30 * (i + 1) })) },
    fence: { set: true, breach: null, data: { polygon: [[-12, -12], [52, -12], [52, 52], [-12, 52]].map(([n, e]) => [home.lat + n / 111320, home.lon + e / (111320 * Math.cos(home.lat * Math.PI / 180))]), keepOut: [], maxDistM: 0, maxAltM: 0, action: 'hold' } },
    triggers: [{ id: 'photo', label: 'Take photo', danger: false, armedOnly: false, count: 3, cooldownMs: 500, maxCount: 0, aiAllowed: false, everyMeters: 0, everySeconds: 0, sensor: null, op: null, compare: 0, action: { type: 'photo', output: null, value: 0, ms: 500, skill: null, text: null } }, { id: 'marker', label: 'Mark in log', danger: false, armedOnly: false, count: 0, cooldownMs: 500, maxCount: 0, aiAllowed: false, everyMeters: 0, everySeconds: 0, sensor: null, op: null, compare: 0, action: { type: 'marker', output: null, value: 0, ms: 500, skill: null, text: 'Mark' } }],
    diag: null, ground: 1, checklist: [{ item: 'Wheels on, nuts tight', done: true }, { item: 'Area clear', done: false }],
    trims: { Motor: { trim: 0, reverse: false, lo: -1, hi: 1 }, Steering: { trim: 0.02, reverse: false, lo: -1, hi: 1 } },
    connections: [check('wifi', 'Wi-Fi', 'ok', 'Same network'), check('usb', 'USB output', 'off', 'Not connected')],
    report: { t: t0 - 3600_000, device: 'car', name: 'Car', passed: true, results: [{ test: 'Accelerometer', status: 'pass', detail: '9.79 m/s² at rest' }, { test: 'Gyroscope', status: 'pass', detail: '211 Hz' }, { test: 'Link-loss failsafe', status: 'pass', detail: 'Went to FAILSAFE 14 ms after the 500 ms link timeout' }] },
    timeLeftS: armed ? 1500 - (t - armedAt) / 1000 : undefined,
    face: { shown: false, mode: 'off', requested: null, held: false },
    groundTest: {
      how: ground.how, passed: 3, needed: 6, done: false,
      stages: [
        stage('selftest', 'Phone self-test', 'Run the automatic tests below, with the phone still.', 'pass', 'Passed'),
        stage('link', 'Link', 'Run the link test below, from where you\'ll stand.', 'pass', 'Round trip 12 ms, jitter 3 ms, worst 31 ms, 0% lost.'),
        stage('outputs', 'Each output moves the right way', 'Wheels off the ground. Run each output both ways below, then say whether each moved the right way.', 'pass', '2 output(s) run each way; ground saw them move the right way'),
        stage('stop', 'STOP disarms it', 'Arm it, then press STOP.', 'todo'),
        stage('route', 'It follows a plan', 'Make a ground-test plan, send it, and start it.', 'todo'),
        stage('fence', 'The fence catches it', 'Walk out of the fence, armed.', 'todo'),
        stage('stop-moving', 'It stops when moving', 'Driving only.', 'skip', 'Needs the vehicle driving itself: switch to Drive it.'),
      ],
    },
  };
}

function fcs(t) {
  const p = pos(t);
  return {
    type: 'fcs', t, mode: flightMode, battery: 86,
    gps: { lat: p.lat, lon: p.lon, alt: 562, speed: p.speed, course: 90, acc: 4, sats: 14, signal: 38, nav: { health: 'good', radius: 4, lat: p.lat, lon: p.lon, rejected: 0 } },
    home: { lat: home.lat, lon: home.lon, height: 0.4, dist: Math.hypot((p.lat - home.lat) * 111320, (p.lon - home.lon) * 105600), bearing: 225 },
    modes: { RETURN_HOME: armed ? null : 'arm first' }, timing: { delayAvgMs: 6 }, vib: 0.2,
  };
}

function plan_check(plan) {
  const pts = (plan?.steps ?? []).map(s => s.at ?? s.center ?? s.area?.[0] ?? s.path?.[0]).filter(Boolean);
  let d = 0, t = 0;
  const timeline = [{ t: 0, lo: 0, hi: 0, step: null, kind: 'start', text: 'start', lat: home.lat, lon: home.lon }];
  let prev = [home.lat, home.lon];
  pts.forEach((p, i) => { const leg = Math.hypot((p[0] - prev[0]) * 111320, (p[1] - prev[1]) * 105600); d += leg; t += leg / (plan.defaults?.speed ?? 2); timeline.push({ t, lo: t * 0.9, hi: t * 1.1, step: plan.steps[i].id, kind: 'arrive', text: 'arrives (goto)', lat: p[0], lon: p[1] }); prev = p; });
  return { ok: true, problems: [], warnings: [], hash: 'a41f09c2', timeline, gaps: [], summary: { distanceM: d, timeS: t, timeLoS: t * 0.9, timeHiS: t * 1.1, batteryPct: 4, points: pts.length }, assumptions: { speed: 2, reservePct: 20 }, path: [[home.lat, home.lon, 0, 0], ...pts.map((p, i) => [p[0], p[1], 0, timeline[i + 1].t])], advisories: [] };
}

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/review/files') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({
      logs: [3, 26, 50].map((h, i) => ({ name: `run-${i}`, t: t0 - h * 3600_000, bytes: [182000, 64000, 410000][i], frames: i === 2 ? 40 : 0 })),
      photos: [],
      reports: [{ t: t0 - 3600_000, name: 'Car', passed: true, results: [{ test: 'Accelerometer', status: 'pass', detail: '9.79 m/s² at rest' }, { test: 'Link-loss failsafe', status: 'pass', detail: 'Went to FAILSAFE 14 ms after the 500 ms link timeout' }] }],
    }));
  }
  const tile = url.pathname.match(/^\/tile\/(\w+)\/(\d+)\/(\d+)\/(\d+)/);
  if (tile && TILES[tile[1]]) { res.writeHead(302, { Location: TILES[tile[1]](tile[2], tile[3], tile[4]) }); return res.end(); }
  let f = join(web, url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname));
  if (!f.startsWith(web) || !existsSync(f)) f = join(web, 'index.html');
  res.writeHead(200, { 'Content-Type': TYPES[extname(f)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
  res.end(readFileSync(f));
});
const wss = new WebSocketServer({ noServer: true });
const videoWss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, sock, head) => {
  const path = new URL(req.url, 'http://x').pathname;
  const target = path === '/ws' ? wss : path === '/video' ? videoWss : null;
  if (!target) return sock.destroy();
  target.handleUpgrade(req, sock, head, ws => target.emit('connection', ws));
});
videoWss.on('connection', ws => {
  let i = 0;
  const next = () => setTimeout(() => {
    if (ws.readyState !== 1 || !camera || !frames.length) return;
    const img = frames[Math.floor(i++ / 5) % frames.length];
    const head = Buffer.alloc(9); head.writeBigInt64BE(BigInt(now() - 60)); head.writeUInt8(0, 8);
    ws.send(Buffer.concat([head, img]));
  }, 100);
  ws.on('message', () => next());
  next();
});
wss.on('connection', ws => {
  const send = o => ws.readyState === 1 && ws.send(JSON.stringify(o));
  send(hello());
  const timer = setInterval(() => {
    const t = now();
    const tel = { t, mode: armed ? mode : 'DISARMED', profile: 'car', loopHz: 100, loopDtMaxMs: 10.4, out: [[0, armed ? 1560 : 1500], [1, 1500]] };
    send({ type: 'telemetry', data: tel, events: [] });
    send({ type: 'fc', a: [0.4, -0.3, mission.state === 'RUNNING' ? 90 * mission.index : 255] });
    send(fcs(t));
    send(ops(t));
  }, 200);
  ws.on('message', buf => {
    const m = JSON.parse(buf);
    if (m.type === 'ping') return send({ type: 'pong', c: m.c, t: now() });
    if (m.type === 'camera_cfg') { if ('enabled' in m) camera = !!m.enabled; return send({ type: 'config', camera: camCfg() }); }
    if (m.type !== 'cmd') return;
    let data;
    if (m.cmd === 'set_mode') { armed = m.mode !== 'DISARMED'; if (armed) armedAt = now(); else mission.state = 'IDLE'; }
    if (m.cmd === 'stop') { armed = false; mission.state = 'IDLE'; }
    if (m.cmd === 'set_flight_mode') flightMode = m.mode;
    if (m.cmd === 'mission_start') { mission = { state: 'RUNNING', index: 0, since: now() }; armed = true; mode = 'AUTO'; flightMode = 'AUTO'; armedAt = now(); }
    if (m.cmd === 'mission_abort' || m.cmd === 'mission_pause') mission.state = m.cmd === 'mission_pause' ? 'PAUSED' : 'ABORTED';
    if (m.cmd === 'mission_resume') mission.state = 'RUNNING';
    if (m.cmd === 'ground_test_how') ground.how = m.how;
    if (m.cmd === 'plan_check') data = plan_check(m.plan);
    send({ type: 'ack', seq: m.seq, accepted: true, code: 'ok', message: 'done (preview)', data });
  });
  ws.on('close', () => clearInterval(timer));
});
server.listen(port, '127.0.0.1', () => console.log(`Preview: http://127.0.0.1:${port}/#token=preview`));
