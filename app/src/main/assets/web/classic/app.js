'use strict';
const $ = id => document.getElementById(id);

function loadToken() {
  const fromUrl = new URLSearchParams(location.search).get('token');
  if (fromUrl) history.replaceState(null, '', location.pathname);
  try {
    if (fromUrl) localStorage.setItem('spx-token', fromUrl);
    return fromUrl || localStorage.getItem('spx-token') || '';
  } catch (e) { return fromUrl || ''; }
}
function saveToken(t) {
  token = t;
  try {
    if (t) localStorage.setItem('spx-token', t); else localStorage.removeItem('spx-token');
  } catch (e) { /* This browser just won't remember it next time. */ }
  applyToken();
}
function applyToken() {
  for (const [id, name] of [['xTel', 'telemetry'], ['xCmd', 'commands'], ['xEvt', 'events'], ['xSen', 'sensors'], ['xFlt', 'flight']]) {
    $(id).href = `/export/${name}.csv?token=${encodeURIComponent(token)}`;
  }
  $('mcpCmd').value = `claude mcp add --transport http smartphonex ${location.origin}/mcp --header "Authorization: Bearer ${token}"`;
}
let token = loadToken();
applyToken();
$('mcpCmd').onclick = () => $('mcpCmd').select();

function showPair(message) {
  $('pairMsg').textContent = message;
  $('pair').hidden = false;
  $('pairCode').value = '';
  $('pairCode').focus();
}
async function submitPair() {
  const code = $('pairCode').value.replace(/\D/g, '');
  $('pairMsg').textContent = 'Checking...';
  try {
    const res = await fetch('/pair', { method: 'POST', body: JSON.stringify({ code }) });
    const body = await res.json();
    if (res.ok) {
      saveToken(body.token);
      $('pair').hidden = true;
      connect();
      return;
    }
    showPair(res.status === 429 ? `Too many wrong codes. Try again in ${body.retryAfterSeconds} s, using the new code.` : 'Wrong code. Check the phone: it changes every few minutes.');
  } catch (e) {
    showPair('Could not reach the phone. Check that it is on and on this network.');
  }
}
$('pairForm').onsubmit = e => { e.preventDefault(); submitPair(); };
$('pairCode').oninput = () => { if ($('pairCode').value.replace(/\D/g, '').length === 6) submitPair(); };

let ws = null, offset = 0, lastSeq = 0, tel = null, hello = null, retry = null;
let pendingChannels = null;
let telCount = 0, drive = { t: 0, s: 0, p: 0, r: 0 }, mcpDown = false, relayInfo = null, lastTelAt = 0;
const trail = [], series = [];
const keys = new Set();
let pointer = null;

function nextSeq() {
  lastSeq = Math.max(lastSeq + 1, Date.now() * 1000);
  return lastSeq;
}
const raw = obj => { if (ws && ws.readyState === 1) ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj)); };
const cmd = (name, args = {}) => { const seq = nextSeq(); raw({ type: 'cmd', seq, sentAt: Date.now() + offset, cmd: name, ...args }); return seq; };
const fault = (name, value) => raw({ type: 'fault', fault: name, value });

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`);
  ws.onopen = () => { setLink(true); ping(); };
  ws.onmessage = e => onMessage(JSON.parse(e.data));
  ws.onclose = e => {
    setLink(false);
    clearTimeout(retry);
    if (e.code === 1008) { // The phone or relay refused these credentials.
      if (relayInfo) { location.href = '/login'; return; }
      saveToken('');
      showPair('This browser needs pairing again. Enter the code on the phone.');
      return;
    }
    retry = setTimeout(connect, 1000);
  };
  ws.onerror = () => ws.close();
}
function setLink(up) {
  $('link').textContent = up ? 'linked' : 'offline';
  $('link').className = 'pill ' + (up ? 'up' : 'down');
}
function ping() { raw({ type: 'ping', c: performance.now(), w: Date.now() }); }

function showNet(net) {
  if (!net || !net.transport) return;
  const bars = net.signal === null || net.signal === undefined || net.transport !== 'cellular' ? '' : ` · signal ${net.signal}/4`;
  $('net').textContent = (net.radio || net.transport) + bars;
}

function onMessage(m) {
  switch (m.type) {
    case 'hello': hello = m; offset = m.t - Date.now(); buildConfig(); drawMap(); showNet(m.net); applyVehicle(); applyDeviceConfig(m); if (m.params) renderParams(m.params); applyDevicePanel(m); break;
    case 'config': applyDeviceConfig(m); break;
    case 'fc': onFcFast(m); break;
    case 'fcs': onFcSlow(m); break;
    case 'params': renderParams(m.list); break;
    case 'setup': onSetup(m); break;
    case 'device': onDevice(m); break;
    case 'assistant': aiLine(m.role, m.text); break;
    case 'assistantIdle': $('aiSend').disabled = false; break;
    case 'assistantCfg': applyAssistantCfg(m.cfg); break;
    case 'sensors': showSensorValues(m); break;
    case 'sensorStats': showSensorStats(m); break;
    case 'cfgAck': if (!m.ok) addLog({ t: Date.now(), source: 'phone', kind: 'rejected', code: m.what, message: m.message }); break;
    case 'vehicle':
      $('vehicleState').hidden = m.online;
      if (!m.online) { lastTelAt = lastTelAt || Date.now(); }
      else if (m.lastOutageMs && m.connects > 1) addLog({ t: Date.now(), source: 'relay', kind: 'link', code: 'reconnected', message: `phone back after ${(m.lastOutageMs / 1000).toFixed(1)} s` });
      break;
    case 'pong': {
      const rtt = performance.now() - m.c;
      offset = m.t + rtt / 2 - Date.now();
      $('rtt').textContent = `RTT ${rtt.toFixed(0)} ms`;
      break;
    }
    case 'telemetry':
      (m.events || []).forEach(addLog);
      if (m.net) showNet(m.net);
      if (tel && m.data.t < tel.t) break; // A late packet must not rewind the display.
      tel = m.data; telCount++; lastTelAt = Date.now();
      $('age').textContent = `age ${Math.max(0, Date.now() + offset - tel.t).toFixed(0)} ms`;
      onTelemetry();
      break;
    case 'ack':
      if (pendingChannels && m.seq === pendingChannels.seq) {
        $('channelsState').textContent = m.accepted ? 'Saved' : m.message;
        if (m.accepted) {
          const p = profileNow();
          p.channels = pendingChannels.channels;
          p.frameFormat = pendingChannels.frameFormat;
          applyVehicle();
        }
        pendingChannels = null;
      }
      if (!m.accepted) { $('saveState').textContent = m.cmd ? `${m.cmd}: ${m.code}` : m.code; }
      else if (m.cmd === 'update_profile' || m.cmd === 'select_profile') { $('saveState').textContent = 'Saved'; }
      break;
  }
}

function onTelemetry() {
  $('mode').textContent = tel.mode;
  $('mode').className = 'pill ' + tel.mode;
  const notes = [];
  if (tel.failsafeReason) notes.push(tel.failsafeReason);
  if (tel.mission) notes.push('mission: ' + tel.mission);
  if (tel.obstacleHold) notes.push('obstacle hold');
  if (tel.aiLocked) notes.push('AI locked until human arms');
  $('reason').textContent = notes.join(' · ');
  if (tel.x !== null) { trail.push([tel.x, tel.y]); if (trail.length > 1500) trail.shift(); }
  series.push({ speed: tel.speed, target: tel.targetSpeed, thr: tel.throttle, dt: tel.loopDtMaxMs });
  if (series.length > 600) series.shift();
  renderStats();
  drawMap(); drawChart(); drawPad($('pad'), -drive.s, drive.t); drawPad($('pad2'), -drive.r, drive.p);
  showOutputs();
}

const fmt = (v, d = 2) => (v === null || v === undefined ? '--' : Number(v).toFixed(d));
function renderStats() {
  const rows = [
    ['Speed m/s', fmt(tel.speed)], ['Target', fmt(tel.targetSpeed)], ['Throttle', fmt(tel.throttle)], ['Steering', fmt(tel.steering)],
    ['X', fmt(tel.x)], ['Y', fmt(tel.y)], ['Range m', fmt(tel.range)], ['Yaw rate', fmt(tel.yawRate)],
    ['Loop Hz', fmt(tel.loopHz, 1)], ['Max dt ms', fmt(tel.loopDtMaxMs, 1)], ['Overruns', tel.loopOverruns], ['Cmd proc us', fmt(tel.cmdProcAvgUs, 0)],
    ['Accepted', tel.accepted], ['Rejected', tel.rejected],
  ];
  const box = $('stats');
  if (box.children.length !== rows.length) {
    box.replaceChildren(...rows.map(() => { const d = document.createElement('div'); d.append(document.createElement('b'), document.createElement('span')); return d; }));
  }
  rows.forEach(([k, v], i) => {
    box.children[i].children[0].textContent = v;
    box.children[i].children[1].textContent = k;
  });
}

function drawMap() {
  const c = $('map'), g = c.getContext('2d');
  g.clearRect(0, 0, c.width, c.height);
  if (!hello || hello.arenaHalf === undefined) return;
  const h = hello.arenaHalf, sc = c.width / (2 * h);
  const px = x => (x + h) * sc, py = y => (h - y) * sc;
  g.strokeStyle = '#1f2733';
  for (let i = -h; i <= h; i += 5) {
    g.beginPath(); g.moveTo(px(i), 0); g.lineTo(px(i), c.height); g.moveTo(0, py(i)); g.lineTo(c.width, py(i)); g.stroke();
  }
  g.fillStyle = '#5c6b80';
  (hello.obstacles || []).forEach(b => g.fillRect(px(b[0]), py(b[3]), (b[2] - b[0]) * sc, (b[3] - b[1]) * sc));
  g.strokeStyle = '#4aa3ff'; g.beginPath();
  trail.forEach((p, i) => i ? g.lineTo(px(p[0]), py(p[1])) : g.moveTo(px(p[0]), py(p[1])));
  g.stroke();
  if (!tel || tel.x === null) return;
  g.save(); g.translate(px(tel.x), py(tel.y)); g.rotate(-tel.heading);
  if (tel.range !== null) { g.strokeStyle = tel.obstacleHold ? '#f5b342' : '#3a4656'; g.beginPath(); g.moveTo(0, 0); g.lineTo(tel.range * sc, 0); g.stroke(); }
  g.fillStyle = tel.collided ? '#ff5c5c' : '#3ecf8e';
  g.beginPath(); g.moveTo(11, 0); g.lineTo(-7, 7); g.lineTo(-7, -7); g.closePath(); g.fill();
  g.restore();
}

function drawChart() {
  const c = $('chart'), g = c.getContext('2d'), W = c.width, H = c.height;
  g.clearRect(0, 0, W, H);
  const panels = [
    { name: 'speed m/s (green), target (blue)', lo: -1, hi: 4, lines: [['speed', '#3ecf8e'], ['target', '#4aa3ff']] },
    { name: 'throttle', lo: -1, hi: 1, lines: [['thr', '#f5b342']] },
    { name: 'loop max dt ms', lo: 0, hi: 60, lines: [['dt', '#c58bff']] },
  ];
  const w = W / panels.length;
  panels.forEach((p, i) => {
    const x0 = i * w + 6, pw = w - 12;
    g.strokeStyle = '#2d3644'; g.strokeRect(x0, 4, pw, H - 8);
    g.fillStyle = '#93a0b2'; g.font = '14px system-ui'; g.fillText(p.name, x0 + 8, 22);
    p.lines.forEach(([k, col]) => {
      g.strokeStyle = col; g.beginPath(); let pen = false;
      series.forEach((s, j) => {
        const v = s[k];
        if (v === null || v === undefined) { pen = false; return; }
        const x = x0 + j / 600 * pw;
        const y = 4 + (1 - (Math.min(p.hi, Math.max(p.lo, v)) - p.lo) / (p.hi - p.lo)) * (H - 8);
        pen ? g.lineTo(x, y) : g.moveTo(x, y); pen = true;
      });
      g.stroke();
    });
  });
}

function drawPad(c, x, y) {
  const g = c.getContext('2d'), W = c.width, H = c.height;
  g.clearRect(0, 0, W, H);
  g.strokeStyle = '#2d3644'; g.beginPath(); g.moveTo(W / 2, 0); g.lineTo(W / 2, H); g.moveTo(0, H / 2); g.lineTo(W, H / 2); g.stroke();
  g.fillStyle = '#4aa3ff'; g.beginPath(); g.arc(W / 2 + x * W / 2, H / 2 - y * H / 2, 12, 0, 7); g.fill();
}

const clampUnit = v => Math.max(-1, Math.min(1, v));
function padAt(pad, e) {
  const r = pad.getBoundingClientRect();
  return { h: clampUnit(-((e.clientX - r.left) / r.width * 2 - 1)), v: clampUnit(-((e.clientY - r.top) / r.height * 2 - 1)) };
}
let pointer2 = null;
function bindPad(pad, set) {
  let active = false;
  pad.addEventListener('pointerdown', e => { pad.setPointerCapture(e.pointerId); active = true; set(padAt(pad, e)); });
  pad.addEventListener('pointermove', e => { if (active) set(padAt(pad, e)); });
  for (const ev of ['pointerup', 'pointercancel']) pad.addEventListener(ev, () => { active = false; set(null); });
}
bindPad($('pad'), a => { pointer = a && { t: a.v, s: a.h }; });
bindPad($('pad2'), a => { pointer2 = a && { p: a.v, r: a.h }; });

const typing = e => ['INPUT', 'SELECT', 'TEXTAREA'].includes(e.target.tagName);
addEventListener('keydown', e => {
  if (typing(e)) return;
  if (e.code === 'Space') { e.preventDefault(); cmd('stop'); return; }
  if (e.code.startsWith('Arrow')) e.preventDefault();
  keys.add(e.code);
});
addEventListener('keyup', e => keys.delete(e.code));
addEventListener('blur', () => { keys.clear(); pointer = null; pointer2 = null; });

/** While armed for manual, stream at 20 Hz: this stream is the heartbeat the failsafe watches. */
setInterval(() => {
  let t = 0, s = 0;
  if (pointer) { t = pointer.t; s = pointer.s; }
  if (keys.has('KeyW')) t = 1;
  if (keys.has('KeyS')) t = -1;
  if (keys.has('KeyA')) s = 1;
  if (keys.has('KeyD')) s = -1;
  let dp = 0, dr = 0;
  if (pointer2) { dp = pointer2.p; dr = pointer2.r; }
  if (keys.has('ArrowUp')) dp = 1;
  if (keys.has('ArrowDown')) dp = -1;
  if (keys.has('ArrowLeft')) dr = 1;
  if (keys.has('ArrowRight')) dr = -1;
  drive = { t, s, p: dp, r: dr };
  if (!tel || !hello || relayInfo?.role === 'viewer') return;
  const p = activeProfile();
  const moved = t !== 0 || s !== 0 || dp !== 0 || dr !== 0;
  // Touching the controls during AUTO is a deliberate human takeover.
  if (tel.mode === 'MANUAL' || (tel.mode === 'AUTO' && moved)) {
    cmd('drive', { throttle: t * p.maxThrottle, steering: s * p.maxSteering, pitch: dp, roll: dr });
  }
}, 50);
setInterval(ping, 1000);
setInterval(() => { $('rate').textContent = `telemetry ${telCount} Hz`; telCount = 0; }, 1000);

const activeProfile = () => hello.profiles.find(p => p.id === tel?.profile) || hello.profiles[0];

const FIELDS = [
  ['kp', 'Kp'], ['ki', 'Ki'], ['kd', 'Kd'], ['ff', 'Feed-forward'],
  ['maxThrottle', 'Max throttle'], ['maxSteering', 'Max steering'], ['maxSpeedMps', 'Max speed m/s'],
  ['commandTimeoutMs', 'Link timeout ms'], ['staleCommandMs', 'Stale after ms'], ['maxAiLeaseMs', 'AI lease max ms'], ['obstacleStopM', 'Obstacle stop m'],
];
function buildConfig() {
  $('mission').replaceChildren(...hello.missions.map(m => new Option(m, m)));
  $('profile').replaceChildren(...hello.profiles.map(p => new Option(p.name, p.id, false, p.id === hello.activeProfile)));
  const p = hello.profiles.find(x => x.id === hello.activeProfile) || hello.profiles[0];
  $('fields').replaceChildren(...FIELDS.map(([k, label]) => {
    const l = document.createElement('label');
    const i = document.createElement('input');
    i.type = 'number'; i.step = 'any'; i.id = 'f_' + k; i.value = p[k];
    l.append(label, i);
    return l;
  }));
}
$('saveProfile').onclick = () => {
  const fields = {};
  for (const [k] of FIELDS) fields[k] = Number($('f_' + k).value);
  $('saveState').textContent = 'Saving...';
  cmd('update_profile', { fields });
  const p = activeProfile();
  Object.assign(p, fields);
};
$('selectProfile').onclick = () => {
  cmd('select_profile', { id: $('profile').value });
  setTimeout(() => { if (ws) ws.close(); }, 200); // Reconnect to load that profile's settings.
};

$('stop').onclick = () => cmd('stop');
$('armManual').onclick = () => cmd('set_mode', { mode: 'MANUAL' });
$('armAuto').onclick = () => cmd('set_mode', { mode: 'AUTO' });
$('disarm').onclick = () => cmd('set_mode', { mode: 'DISARMED' });
$('runMission').onclick = () => cmd('run_mission', { name: $('mission').value });

$('fApply').onclick = () => { fault('delay', Number($('fDelay').value)); fault('loss', Number($('fLoss').value)); };
$('fMcp').onclick = () => { mcpDown = !mcpDown; fault('mcp_down', mcpDown ? 1 : 0); $('fMcp').textContent = mcpDown ? 'MCP is down: restore' : 'Toggle MCP down'; };
document.querySelectorAll('[data-fault]').forEach(b => { b.onclick = () => fault(b.dataset.fault, Number(b.dataset.value)); });

document.querySelectorAll('[data-bad]').forEach(b => {
  b.onclick = () => {
    const now = Date.now() + offset;
    switch (b.dataset.bad) {
      case 'malformed': raw('{"type":"cmd","cmd":"drive","throttle":"lots"'); break;
      case 'duplicate': raw({ type: 'cmd', seq: lastSeq, sentAt: now, cmd: 'set_mode', mode: 'DISARMED' }); break;
      case 'stale': raw({ type: 'cmd', seq: nextSeq(), sentAt: now - 30000, cmd: 'set_mode', mode: 'DISARMED' }); break;
      case 'range': cmd('drive', { throttle: 9, steering: 0 }); break;
    }
  };
});

const logEl = $('log');
function addLog(e) {
  const d = document.createElement('div');
  d.className = e.kind;
  const time = new Date(e.t).toLocaleTimeString();
  d.textContent = `${time} ${e.source.padEnd(10)} ${e.kind}/${e.code} ${e.message}`;
  if ($('hideTel').checked) logEl.prepend(d); else logEl.append(d);
  while (logEl.children.length > 500) ($('hideTel').checked ? logEl.lastChild : logEl.firstChild).remove();
}

let enrollTimer = null;
async function showEnrollQr() {
  clearInterval(enrollTimer);
  $('enrollNote').textContent = 'Creating code...';
  try {
    const res = await fetch('/enroll/new', { method: 'POST' });
    if (!res.ok) throw new Error(String(res.status));
    const body = await res.json();
    $('enrollQr').innerHTML = body.svg; // Built by our own server from its own URL.
    let left = body.expiresInSeconds;
    const warning = body.encrypted ? '' : ' This address is not encrypted, so a phone on another network will not accept it.';
    const tick = () => {
      if (left <= 0) {
        clearInterval(enrollTimer);
        $('enrollQr').replaceChildren();
        $('enrollNote').textContent = 'Code expired. Show a new one.';
        return;
      }
      $('enrollNote').textContent = `Works once. Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}.${warning}`;
      left--;
    };
    tick();
    enrollTimer = setInterval(tick, 1000);
  } catch (e) {
    $('enrollNote').textContent = 'Could not create a code. Check that you are still signed in.';
  }
}

/* ---------- Vehicle outputs ---------- */
const profileNow = () => hello && (hello.profiles.find(p => p.id === (tel?.profile ?? hello.activeProfile)) || hello.profiles[0]);
let outputRows = [], outputsKey = '';

function applyVehicle() {
  const p = profileNow();
  if (!p) return;
  const sim = hello.simulated;
  // The arena belongs to the simulator. Real position is on the Map panel.
  $('arenaSection').hidden = !sim;
  const uses2 = p.channels.some(c => c.pitch !== 0 || c.roll !== 0);
  $('pad2Box').hidden = !uses2;
  $('arrowHint').hidden = !uses2;
  $('missionBox').hidden = !p.missionsAllowed;
  $('outputsNote').textContent = sim
    ? '· simulated car'
    : `· ${p.name}, no hardware attached: these are the values it would receive`;
  $('frameFormat').value = p.frameFormat;
  buildChannelEditor(p.channels);
}

function showOutputs() {
  const p = profileNow();
  if (!p || !tel.out) return;
  const key = p.channels.map(c => c.name).join('|');
  if (key !== outputsKey || outputRows.length !== tel.out.length) {
    outputsKey = key;
    outputRows = p.channels.map(c => {
      const row = document.createElement('div');
      row.className = 'outrow';
      const name = document.createElement('span');
      name.textContent = c.name;
      const bar = document.createElement('div');
      bar.className = 'bar';
      const fill = document.createElement('i');
      const mid = document.createElement('b');
      bar.append(fill, mid);
      const us = document.createElement('span');
      row.append(name, bar, us);
      return { row, fill, us };
    });
    $('outputs').replaceChildren(...outputRows.map(r => r.row));
  }
  tel.out.forEach(([, pulse], i) => {
    const r = outputRows[i];
    if (!r) return;
    r.fill.style.width = `${Math.max(0, Math.min(100, (pulse - 1000) / 10))}%`;
    const would = tel.would?.[i];
    r.us.textContent = would !== undefined && would !== pulse ? `${pulse} us (would ${would})` : `${pulse} us`;
  });
  $('frame').textContent = tel.frame ? `${p.frameFormat.toUpperCase()} frame: ${tel.frame}` : '';
}

const CHANNEL_FIELDS = ['throttle', 'yaw', 'pitch', 'roll', 'trim', 'failsafe'];
const CHANNEL_KINDS = [['SERVO', 'Servo'], ['MOTOR_REVERSIBLE', 'Motor, reverses'], ['MOTOR_FORWARD', 'Motor, forward only']];

function channelRow(c) {
  const tr = document.createElement('tr');
  const cell = el => { const td = document.createElement('td'); td.append(el); tr.append(td); return el; };
  const name = cell(Object.assign(document.createElement('input'), { type: 'text', value: c.name, maxLength: 24, className: 'cName' }));
  name.setAttribute('aria-label', 'Output name');
  const kind = document.createElement('select');
  kind.className = 'cKind';
  kind.setAttribute('aria-label', 'Output type');
  CHANNEL_KINDS.forEach(([v, l]) => kind.append(new Option(l, v, false, v === c.kind)));
  cell(kind);
  for (const f of CHANNEL_FIELDS) {
    const i = cell(Object.assign(document.createElement('input'), { type: 'number', step: '0.1', value: c[f] ?? 0 }));
    i.dataset.f = f;
    i.setAttribute('aria-label', f);
  }
  const rm = Object.assign(document.createElement('button'), { textContent: 'Remove' });
  rm.onclick = () => tr.remove();
  cell(rm);
  return tr;
}

function buildChannelEditor(channels) {
  const head = document.createElement('tr');
  for (const h of ['Output', 'Type', 'Throttle', 'Yaw', 'Pitch', 'Roll', 'Trim', 'Failsafe', '']) {
    head.append(Object.assign(document.createElement('th'), { textContent: h }));
  }
  $('channels').replaceChildren(head, ...channels.map(channelRow));
}
$('addChannel').onclick = () => $('channels').append(channelRow({ name: `Output ${$('channels').rows.length}`, kind: 'SERVO' }));
$('saveChannels').onclick = () => {
  const channels = [...$('channels').rows].slice(1).map(tr => {
    const c = { name: tr.querySelector('.cName').value, kind: tr.querySelector('.cKind').value };
    tr.querySelectorAll('[data-f]').forEach(i => { c[i.dataset.f] = Number(i.value); });
    return c;
  });
  const seq = cmd('set_channels', { channels, frameFormat: $('frameFormat').value });
  pendingChannels = { seq, channels, frameFormat: $('frameFormat').value };
  $('channelsState').textContent = 'Saving...';
};

/* ---------- Sensors ---------- */
let sensorCells = new Map(), catalogKey = '';
const PRIORITIES = [[1, 'High'], [2, 'Normal'], [3, 'Low']];

function applyDeviceConfig(m) {
  if (m.budgetKbps !== undefined) $('budget').value = String(m.budgetKbps);
  if (m.sensors) renderSensors(m.sensors);
  if (m.cameras) renderCameras(m.cameras, m.camera);
  if (m.aiAccess !== undefined) $('aiAccess').checked = m.aiAccess;
}

function renderSensors(list) {
  const key = list.map(s => s.id + (s.unavailable ?? '')).join('|');
  if (key === catalogKey) {
    for (const s of list) {
      const c = sensorCells.get(s.id);
      if (!c) continue;
      c.on.checked = s.enabled;
      c.rate.value = String(s.rateHz);
      c.prio.value = String(s.priority);
    }
    return;
  }
  catalogKey = key;
  sensorCells = new Map();
  const head = document.createElement('tr');
  for (const h of ['On', 'Sensor', 'Latest', 'Rate', 'Priority', 'Sent/s', 'Dropped']) head.append(Object.assign(document.createElement('th'), { textContent: h }));
  const viewer = relayInfo?.role === 'viewer';
  const rows = list.map(s => {
    const tr = document.createElement('tr');
    const td = el => { const t = document.createElement('td'); if (el) t.append(el); tr.append(t); return t; };
    const on = Object.assign(document.createElement('input'), { type: 'checkbox', checked: s.enabled, disabled: viewer || !!s.unavailable });
    on.setAttribute('aria-label', `${s.name} on`);
    on.onchange = () => raw({ type: 'sensor_cfg', id: s.id, enabled: on.checked });
    td(on);
    const label = td();
    label.textContent = s.name + (s.unit ? ` (${s.unit})` : '');
    if (s.private) label.textContent += ' · private';
    if (s.vendor) label.title = s.vendor;
    const vals = td();
    vals.className = 'vals';
    if (s.unavailable) vals.textContent = s.unavailable;
    const rate = document.createElement('select');
    rate.setAttribute('aria-label', `${s.name} rate`);
    for (const r of [0.2, 0.5, 1, 2, 5, 10, 20, 50]) rate.append(new Option(`${r} Hz`, String(r)));
    if (![...rate.options].some(o => o.value === String(s.rateHz))) rate.append(new Option(`${s.rateHz} Hz`, String(s.rateHz)));
    rate.value = String(s.rateHz);
    rate.disabled = viewer || !!s.unavailable;
    rate.onchange = () => raw({ type: 'sensor_cfg', id: s.id, rateHz: Number(rate.value) });
    td(rate);
    const prio = document.createElement('select');
    prio.setAttribute('aria-label', `${s.name} priority`);
    PRIORITIES.forEach(([v, l]) => prio.append(new Option(l, String(v))));
    prio.value = String(s.priority);
    prio.disabled = viewer || !!s.unavailable;
    prio.onchange = () => raw({ type: 'sensor_cfg', id: s.id, priority: Number(prio.value) });
    td(prio);
    const sent = td();
    const dropped = td();
    sensorCells.set(s.id, { on, rate, prio, vals, sent, dropped, info: s });
    return tr;
  });
  $('sensors').replaceChildren(head, ...rows);
}

function showSensorValues(m) {
  for (const [id, values] of Object.entries(m.d)) {
    const c = sensorCells.get(id);
    if (!c) continue;
    const axes = c.info.axes;
    c.vals.textContent = values.map((v, i) => (axes[i] ? `${axes[i]} ` : '') + (v === null ? '--' : v)).join('  ');
  }
}

function showSensorStats(m) {
  $('dataRates').textContent = `Sensors ${m.sensorKbps.toFixed(1)} kbps · video ${m.videoKbps.toFixed(0)} kbps`;
  $('camStatus').textContent = m.camera ?? '';
  for (const [id, [sent, , dropped]] of Object.entries(m.s)) {
    const c = sensorCells.get(id);
    if (!c) continue;
    c.sent.textContent = sent.toFixed(1);
    c.dropped.textContent = dropped || '';
  }
}
$('budget').onchange = () => raw({ type: 'budget', kbps: Number($('budget').value) });
$('aiAccess').onchange = () => raw({ type: 'ai_access', value: $('aiAccess').checked });

/* ---------- Camera ---------- */
let cameras = [], videoWs = null, videoFrames = 0, videoBytes = 0, videoAge = 0, videoUrl = null;

function renderCameras(list, cfg) {
  cameras = list;
  const chosen = list.some(c => c.id === cfg.cameraId) ? cfg.cameraId : (list[0] && list[0].id) || '';
  $('camId').replaceChildren(...list.map(c => new Option(c.name, c.id, false, c.id === chosen)));
  $('camId').value = chosen;
  renderSizes(chosen, `${cfg.width}x${cfg.height}`);
  $('camOn').checked = cfg.enabled;
  $('camFps').value = String(Math.round(cfg.fps));
  $('camQuality').value = String(cfg.quality);
  $('camPriority').value = String(cfg.priority);
  syncVideo(cfg.enabled);
}

function renderSizes(id, wanted) {
  const cam = cameras.find(c => c.id === id);
  const sizes = cam ? cam.sizes : [];
  $('camSize').replaceChildren(...sizes.map(s => new Option(s, s)));
  if (sizes.includes(wanted)) $('camSize').value = wanted;
  else if (sizes.length) {
    const area = s => { const [w, h] = s.split('x').map(Number); return w * h; };
    const target = area(wanted);
    $('camSize').value = sizes.reduce((a, b) => (Math.abs(area(b) - target) < Math.abs(area(a) - target) ? b : a));
  }
}

function sendCamera() {
  const [width, height] = $('camSize').value.split('x').map(Number);
  raw({
    type: 'camera_cfg', enabled: $('camOn').checked, cameraId: $('camId').value, width, height,
    fps: Number($('camFps').value), quality: Number($('camQuality').value), priority: Number($('camPriority').value),
  });
}
$('camOn').onchange = sendCamera;
$('camId').onchange = () => { renderSizes($('camId').value, $('camSize').value); sendCamera(); };
for (const id of ['camSize', 'camFps', 'camQuality', 'camPriority']) $(id).onchange = sendCamera;

/** Video has its own connection, so frames in flight can't hold up commands on the main link. */
function syncVideo(wanted) {
  if (relayInfo?.role === 'viewer') return;
  $('videoBox').hidden = !wanted;
  if (wanted && !videoWs) openVideo();
  if (!wanted && videoWs) { const w = videoWs; videoWs = null; w.close(); $('videoStats').textContent = ''; }
}

function openVideo() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const w = new WebSocket(`${proto}://${location.host}/video?token=${encodeURIComponent(token)}`);
  w.binaryType = 'arraybuffer';
  videoWs = w;
  w.onmessage = e => {
    if (typeof e.data === 'string') return;
    const view = new DataView(e.data);
    const capturedAt = Number(view.getBigInt64(0));
    const quarter = view.getUint8(8);
    const url = URL.createObjectURL(new Blob([new Uint8Array(e.data, 9)], { type: 'image/jpeg' }));
    const img = $('video');
    img.onload = () => { if (videoUrl) URL.revokeObjectURL(videoUrl); videoUrl = url; };
    img.src = url;
    img.style.transform = quarter ? `rotate(${quarter * 90}deg)` : '';
    videoFrames++;
    videoBytes += e.data.byteLength;
    videoAge = Date.now() + offset - capturedAt;
    // Directly on the phone there's no relay, so this browser tells it to send the next frame.
    if (!relayInfo && w.readyState === 1) w.send('a');
  };
  w.onclose = () => {
    if (videoWs !== w) return;
    videoWs = null;
    // The camera setting is still on: the link dropped, so try again.
    setTimeout(() => { if ($('camOn').checked) syncVideo(true); }, 2000);
  };
  w.onerror = () => w.close();
}

setInterval(() => {
  if (!videoWs) return;
  $('videoStats').textContent = videoFrames
    ? `${videoFrames} frames/s · ${(videoBytes * 8 / 1000).toFixed(0)} kbps · ${Math.max(0, videoAge).toFixed(0)} ms old`
    : 'Waiting for frames...';
  videoFrames = 0;
  videoBytes = 0;
}, 1000);

/* ---------- Flight controller panels ---------- */
let fcNow = null, fcSlow = null, fcFastAt = 0, fcSlowAt = 0, replay = null;
const fcSeries = [];

function setText(id, v) { const e = $(id); if (e.textContent !== v) e.textContent = v; }
const deg = (v, d = 1) => (v === null || v === undefined ? '--' : `${Number(v).toFixed(d)}°`);

function onFcFast(m) {
  fcFastAt = Date.now();
  if (replay) return;
  fcNow = m;
  fcSeries.push({ roll: m.a[0], pitch: m.a[1], rRate: m.r[0], tRoll: m.ta[0], tPitch: m.ta[1], tRRate: m.tr[0], yRate: m.r[2], tYRate: m.tr[2] });
  if (fcSeries.length > 400) fcSeries.shift();
  drawHorizon(m.a[0], m.a[1], m.a[2], m.ta[2]);
  setText('attRoll', deg(m.a[0])); setText('attPitch', deg(m.a[1])); setText('attYaw', deg(m.a[2], 0));
  setText('rateRoll', `${m.r[0].toFixed(0)}°/s`); setText('ratePitch', `${m.r[1].toFixed(0)}°/s`); setText('rateYaw', `${m.r[2].toFixed(0)}°/s`);
  setText('tgtRoll', deg(m.ta[0])); setText('tgtPitch', deg(m.ta[1])); setText('tgtYaw', deg(m.ta[2], 0));
  drawFcChart();
  setText('fcAge', `${Math.max(0, Date.now() + offset - m.t).toFixed(0)} ms old`);
  drawMapView();
}

function onFcSlow(m) {
  fcSlowAt = Date.now();
  fcSlow = m;
  const viewer = relayInfo?.role === 'viewer';
  setText('fcMode', m.active ? m.mode.replace('_', ' ') : 'OFF: simulator selected');
  const modes = m.modes || {};
  document.querySelectorAll('[data-fm]').forEach(b => {
    const problem = modes[b.dataset.fm];
    b.disabled = viewer || !!problem;
    b.title = problem || '';
    b.className = b.dataset.fm === m.mode ? 'on' : '';
  });
  const blocked = Object.entries(modes).filter(([, p]) => p).map(([k, p]) => `${k.replace('_', ' ')}: ${p}`);
  setText('fmNote', blocked.join(' · '));
  setText('armNote', m.armProblem ? `Can't arm: ${m.armProblem}` : (m.settled ? 'Ready to arm' : 'Attitude settling...'));
  if (m.diff) { setText('refDiff', `${m.diff[0].toFixed(1)}° / ${m.diff[1].toFixed(1)}° / ${m.diff[2].toFixed(1)}°`); setText('refVal', `${m.ref[0].toFixed(1)}° / ${m.ref[1].toFixed(1)}° / ${m.ref[2].toFixed(0)}°`); }
  else { setText('refDiff', 'no reference sensor'); setText('refVal', '--'); }
  setText('vib', `${m.vib.toFixed(2)} m/s²`);
  setText('alt', m.gps ? `${m.gps.alt.toFixed(1)} m (GPS)` : '--');
  setText('baro', m.baro ? `${m.baro.toFixed(1)} hPa` : 'none on this phone');
  renderHealth(m.health);
  const r = m.rates, t = m.timing;
  setText('tGyro', `${r.gyro.toFixed(0)} Hz`); setText('tAccel', `${r.accel.toFixed(0)} Hz`); setText('tMag', `${r.mag.toFixed(0)} Hz`);
  setText('tLoop', `${r.loop.toFixed(0)} Hz`); setText('tGps', `${r.gps.toFixed(1)} Hz`);
  setText('tDelay', `${t.delayAvgMs.toFixed(1)} ms avg, ${t.delayMaxMs.toFixed(1)} ms max`);
  setText('tDt', `${t.dtMaxMs.toFixed(1)} ms (worst ever ${t.stallMaxMs.toFixed(0)} ms)`);
  setText('tTask', `${t.taskMaxUs.toFixed(0)} µs`); setText('tOver', String(t.overruns));
  if (m.pid) setText('pidTerms', ['roll', 'pitch', 'yaw'].map(a => `${a} P ${m.pid[a][0].toFixed(3)} I ${m.pid[a][1].toFixed(3)} D ${m.pid[a][2].toFixed(3)}`).join('  ·  '));
  showGps(m);
}

const HEALTH_LABELS = { gyro: 'Gyro', accel: 'Accel', compass: 'Compass', baro: 'Barometer', gps: 'GPS', attitude: 'Attitude', timing: 'Timing', battery: 'Battery', heat: 'Heat' };
function renderHealth(h) {
  const box = $('health');
  if (box.children.length !== Object.keys(HEALTH_LABELS).length) {
    box.replaceChildren(...Object.entries(HEALTH_LABELS).map(([k, l]) => {
      const s = document.createElement('span');
      s.dataset.h = k;
      s.textContent = l;
      return s;
    }));
  }
  [...box.children].forEach(s => { s.className = `light ${h[s.dataset.h]}`; });
}

function drawHorizon(roll, pitch, yaw, targetHeading) {
  const c = $('horizon'), g = c.getContext('2d'), W = c.width, H = c.height;
  const ppd = H / 90; // Pixels per degree of pitch.
  g.save();
  g.translate(W / 2, H / 2);
  g.rotate(-roll * Math.PI / 180);
  const y = pitch * ppd;
  g.fillStyle = '#2f7fd1'; g.fillRect(-W * 2, -H * 3 + y, W * 4, H * 3);
  g.fillStyle = '#7a4f22'; g.fillRect(-W * 2, y, W * 4, H * 3);
  g.strokeStyle = '#fff'; g.lineWidth = 2;
  g.beginPath(); g.moveTo(-W * 2, y); g.lineTo(W * 2, y); g.stroke();
  g.font = '13px system-ui'; g.fillStyle = '#fff'; g.lineWidth = 1;
  for (let d = -80; d <= 80; d += 10) {
    if (d === 0) continue;
    const ly = y - d * ppd, w = d % 20 === 0 ? 50 : 25;
    g.beginPath(); g.moveTo(-w, ly); g.lineTo(w, ly); g.stroke();
    if (d % 20 === 0) { g.fillText(String(d), w + 6, ly + 4); g.fillText(String(d), -w - 26, ly + 4); }
  }
  g.restore();
  // Bank pointer, fixed to the vehicle.
  g.strokeStyle = '#ffd24a'; g.fillStyle = '#ffd24a'; g.lineWidth = 3;
  g.beginPath(); g.moveTo(W / 2 - 70, H / 2); g.lineTo(W / 2 - 22, H / 2); g.lineTo(W / 2 - 12, H / 2 + 12); g.stroke();
  g.beginPath(); g.moveTo(W / 2 + 70, H / 2); g.lineTo(W / 2 + 22, H / 2); g.lineTo(W / 2 + 12, H / 2 + 12); g.stroke();
  g.beginPath(); g.arc(W / 2, H / 2, 3, 0, 7); g.fill();
  // Heading tape.
  g.fillStyle = 'rgba(0,0,0,.65)'; g.fillRect(0, 0, W, 34);
  g.fillStyle = '#fff'; g.strokeStyle = '#fff'; g.lineWidth = 1; g.font = '13px system-ui'; g.textAlign = 'center';
  const pxPerDeg = W / 90;
  const names = { 0: 'N', 45: 'NE', 90: 'E', 135: 'SE', 180: 'S', 225: 'SW', 270: 'W', 315: 'NW' };
  for (let d = Math.floor(yaw / 10) * 10 - 50; d <= yaw + 50; d += 10) {
    const x = W / 2 + (d - yaw) * pxPerDeg, nd = ((d % 360) + 360) % 360;
    g.beginPath(); g.moveTo(x, 34); g.lineTo(x, nd % 30 === 0 ? 24 : 29); g.stroke();
    if (nd % 30 === 0) g.fillText(names[nd] || String(nd), x, 19);
  }
  if (targetHeading !== null && targetHeading !== undefined) {
    const diff = ((targetHeading - yaw + 540) % 360) - 180;
    const x = Math.max(6, Math.min(W - 6, W / 2 + diff * pxPerDeg));
    g.fillStyle = '#ff8ad8'; g.beginPath(); g.moveTo(x, 34); g.lineTo(x - 6, 44); g.lineTo(x + 6, 44); g.fill();
  }
  g.fillStyle = '#ffd24a'; g.beginPath(); g.moveTo(W / 2, 34); g.lineTo(W / 2 - 6, 22); g.lineTo(W / 2 + 6, 22); g.fill();
  g.textAlign = 'left';
}

function drawFcChart() {
  const c = $('fcChart'), g = c.getContext('2d'), W = c.width, H = c.height;
  g.clearRect(0, 0, W, H);
  const panels = [
    { name: 'Roll: actual (green), wanted (pink)', lim: 90, a: 'roll', b: 'tRoll' },
    { name: 'Pitch: actual (green), wanted (pink)', lim: 60, a: 'pitch', b: 'tPitch' },
    { name: 'Yaw rate: actual (green), wanted (pink)', lim: 180, a: 'yRate', b: 'tYRate' },
  ];
  const w = W / panels.length;
  panels.forEach((p, i) => {
    const x0 = i * w + 6, pw = w - 12;
    g.strokeStyle = '#2d3644'; g.strokeRect(x0, 4, pw, H - 8);
    g.fillStyle = '#93a0b2'; g.font = '13px system-ui'; g.fillText(p.name, x0 + 8, 20);
    g.beginPath(); g.moveTo(x0, H / 2); g.lineTo(x0 + pw, H / 2); g.stroke();
    for (const [k, col] of [[p.a, '#3ecf8e'], [p.b, '#ff8ad8']]) {
      g.strokeStyle = col; g.beginPath();
      let pen = false;
      fcSeries.forEach((s, j) => {
        const v = s[k];
        if (v === null || v === undefined) { pen = false; return; }
        const x = x0 + j / 400 * pw, y = H / 2 - Math.max(-1, Math.min(1, v / p.lim)) * (H / 2 - 8);
        pen ? g.lineTo(x, y) : g.moveTo(x, y);
        pen = true;
      });
      g.stroke();
    }
  });
}

document.querySelectorAll('[data-fm]').forEach(b => { b.onclick = () => cmd('set_flight_mode', { mode: b.dataset.fm }); });
const fcAction = a => raw({ type: 'fc_action', action: a });
$('calGyro').onclick = () => fcAction('calibrate_gyro');
$('setLevel').onclick = () => fcAction('set_level');
$('setHome').onclick = () => fcAction('set_home');

setInterval(() => {
  // Old data must never look current.
  const now = Date.now();
  $('flightPanel').classList.toggle('stale', !replay && (!fcFastAt || now - fcFastAt > 1000));
  $('fcStale').hidden = !!replay || (fcFastAt && now - fcFastAt <= 1000);
  $('healthPanel').classList.toggle('stale', !fcSlowAt || now - fcSlowAt > 2500);
}, 300);

/* ---------- Map: real GPS position over satellite or street tiles ---------- */
const TILES = {
  satellite: { url: (z, x, y) => `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`, credit: 'Imagery © Esri, Maxar, Earthstar Geographics', max: 19 },
  street: { url: (z, x, y) => `https://tile.openstreetmap.org/${z}/${x}/${y}.png`, credit: '© OpenStreetMap contributors', max: 19 },
};
let mapLayer = 'satellite', mapZoom = 18, mapCenter = null, mapFollow = true;
const tileCache = new Map(), track = [];

function project(lat, lon, z) {
  const n = 256 * 2 ** z, s = Math.sin(lat * Math.PI / 180);
  return [(lon + 180) / 360 * n, (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * n];
}

function tile(z, x, y) {
  const key = `${mapLayer}/${z}/${x}/${y}`;
  let img = tileCache.get(key);
  if (!img) {
    img = new Image();
    // OpenStreetMap asks apps to identify themselves with a referrer.
    img.referrerPolicy = 'strict-origin-when-cross-origin';
    img.onload = () => drawMapView();
    img.src = TILES[mapLayer].url(z, x, y);
    tileCache.set(key, img);
    if (tileCache.size > 300) tileCache.delete(tileCache.keys().next().value);
  }
  return img.complete && img.naturalWidth ? img : null;
}

function showGps(m) {
  const g = m.gps;
  const sees = relayInfo?.role !== 'viewer';
  $('mapSection').hidden = !sees;
  $('mapEmpty').hidden = !!g;
  $('liveMap').hidden = !g;
  if (!g) { setText('gpsInfo', ''); return; }
  const fresh = g.ageMs < 5000;
  if (fresh) {
    const last = track[track.length - 1];
    if (!last || last[0] !== g.lat || last[1] !== g.lon) { track.push([g.lat, g.lon]); if (track.length > 3000) track.shift(); }
  }
  if (mapFollow || !mapCenter) mapCenter = [g.lat, g.lon];
  const home = m.home && m.home.dist !== undefined ? ` · home ${m.home.dist.toFixed(0)} m at ${m.home.bearing.toFixed(0)}°` : '';
  setText('gpsInfo', `${g.lat.toFixed(6)}, ${g.lon.toFixed(6)} · ±${g.acc.toFixed(0)} m · ${g.sats} sats · ${g.speed.toFixed(1)} m/s · ${(g.ageMs / 1000).toFixed(0)} s old${fresh ? '' : ' · OUT OF DATE'}${home}`);
  $('gpsInfo').classList.toggle('bad', !fresh);
  drawMapView();
}

function drawMapView() {
  const c = $('liveMap');
  if (c.hidden || !mapCenter || !fcSlow?.gps) return;
  const g = c.getContext('2d'), W = c.width, H = c.height;
  const [cx, cy] = project(mapCenter[0], mapCenter[1], mapZoom);
  g.fillStyle = '#0c1015'; g.fillRect(0, 0, W, H);
  const n = 2 ** mapZoom;
  for (let tx = Math.floor((cx - W / 2) / 256); tx <= Math.floor((cx + W / 2) / 256); tx++) {
    for (let ty = Math.floor((cy - H / 2) / 256); ty <= Math.floor((cy + H / 2) / 256); ty++) {
      if (ty < 0 || ty >= n) continue;
      const img = tile(mapZoom, ((tx % n) + n) % n, ty);
      if (img) g.drawImage(img, tx * 256 - cx + W / 2, ty * 256 - cy + H / 2);
    }
  }
  const at = (lat, lon) => { const [x, y] = project(lat, lon, mapZoom); return [x - cx + W / 2, y - cy + H / 2]; };
  g.strokeStyle = '#ffd24a'; g.lineWidth = 2; g.beginPath();
  track.forEach((p, i) => { const [x, y] = at(p[0], p[1]); i ? g.lineTo(x, y) : g.moveTo(x, y); });
  g.stroke();
  const fix = fcSlow.gps, [px, py] = at(fix.lat, fix.lon);
  const home = fcSlow.home;
  if (home) {
    const [hx, hy] = at(home.lat, home.lon);
    g.strokeStyle = '#ff8ad8'; g.setLineDash([6, 6]); g.beginPath(); g.moveTo(px, py); g.lineTo(hx, hy); g.stroke(); g.setLineDash([]);
    g.fillStyle = '#ff8ad8'; g.font = 'bold 15px system-ui'; g.fillText('H', hx - 5, hy + 5);
  }
  const mPerPx = 156543.03 * Math.cos(fix.lat * Math.PI / 180) / n;
  g.strokeStyle = 'rgba(74,163,255,.9)'; g.fillStyle = 'rgba(74,163,255,.2)'; g.beginPath(); g.arc(px, py, Math.max(4, fix.acc / mPerPx), 0, 7); g.fill(); g.stroke();
  const yaw = (fcNow ? fcNow.a[2] : fix.course) * Math.PI / 180;
  g.save(); g.translate(px, py); g.rotate(yaw);
  g.fillStyle = fix.ageMs < 5000 ? '#3ecf8e' : '#93a0b2'; g.strokeStyle = '#000'; g.lineWidth = 1;
  g.beginPath(); g.moveTo(0, -13); g.lineTo(8, 9); g.lineTo(0, 4); g.lineTo(-8, 9); g.closePath(); g.fill(); g.stroke();
  g.restore();
  setText('mapCredit', TILES[mapLayer].credit);
}
$('mapSat').onclick = () => { mapLayer = 'satellite'; $('mapSat').className = 'on'; $('mapStreet').className = ''; drawMapView(); };
$('mapStreet').onclick = () => { mapLayer = 'street'; $('mapStreet').className = 'on'; $('mapSat').className = ''; drawMapView(); };
$('mapIn').onclick = () => { mapZoom = Math.min(TILES[mapLayer].max, mapZoom + 1); drawMapView(); };
$('mapOut').onclick = () => { mapZoom = Math.max(3, mapZoom - 1); drawMapView(); };
$('mapFollowBtn').onclick = () => { mapFollow = true; if (fcSlow?.gps) mapCenter = [fcSlow.gps.lat, fcSlow.gps.lon]; drawMapView(); };
{
  let drag = null;
  const c = $('liveMap');
  c.addEventListener('pointerdown', e => { c.setPointerCapture(e.pointerId); drag = [e.clientX, e.clientY]; });
  c.addEventListener('pointerup', () => { drag = null; });
  c.addEventListener('pointermove', e => {
    if (!drag || !mapCenter) return;
    const scale = c.width / c.getBoundingClientRect().width;
    const n = 256 * 2 ** mapZoom;
    const [cx, cy] = project(mapCenter[0], mapCenter[1], mapZoom);
    const nx = cx - (e.clientX - drag[0]) * scale, ny = cy - (e.clientY - drag[1]) * scale;
    mapCenter = [Math.atan(Math.sinh(Math.PI * (1 - 2 * ny / n))) * 180 / Math.PI, nx / n * 360 - 180];
    mapFollow = false;
    drag = [e.clientX, e.clientY];
    drawMapView();
  });
}

/* ---------- Settings ---------- */
let paramsKey = '';
function renderParams(list) {
  const viewer = relayInfo?.role === 'viewer';
  $('paramsBox').hidden = viewer;
  const key = list.map(p => p.name).join('|');
  if (key !== paramsKey) {
    paramsKey = key;
    const rows = [];
    let group = null;
    for (const p of list) {
      if (p.group !== group) {
        group = p.group;
        const h = document.createElement('tr');
        const th = Object.assign(document.createElement('th'), { textContent: group, colSpan: 3 });
        h.append(th);
        rows.push(h);
      }
      const tr = document.createElement('tr');
      const name = Object.assign(document.createElement('td'), { textContent: p.name });
      const val = document.createElement('td');
      const input = Object.assign(document.createElement('input'), { type: 'number', step: 'any', min: p.min, max: p.max });
      input.dataset.param = p.name;
      input.setAttribute('aria-label', p.name);
      input.onchange = () => raw({ type: 'param_set', name: p.name, value: Number(input.value) });
      val.append(input);
      const desc = Object.assign(document.createElement('td'), { textContent: `${p.description} (${p.min} to ${p.max})`, className: 'muted' });
      tr.append(name, val, desc);
      rows.push(tr);
    }
    $('params').replaceChildren(...rows);
  }
  for (const p of list) {
    const input = document.querySelector(`[data-param="${p.name}"]`);
    if (input && document.activeElement !== input) input.value = String(p.value);
  }
}

/* ---------- Replay a downloaded flight log ---------- */
$('replayFile').onchange = async e => {
  const file = e.target.files[0];
  if (!file) return;
  const lines = (await file.text()).trim().split('\n');
  const head = lines.shift().split(',');
  const col = n => head.indexOf(n);
  if (col('roll') < 0 || !lines.length) { setText('replayInfo', 'This isn\'t a flight log.'); return; }
  const num = v => (v === '' || v === undefined ? null : Number(v));
  const rows = lines.map(l => {
    const c = l.split(',');
    return { t: Number(c[0]), mode: c[col('mode')], a: [num(c[col('roll')]), num(c[col('pitch')]), num(c[col('yaw')])], r: [num(c[col('rollRate')]), num(c[col('pitchRate')]), num(c[col('yawRate')])], ta: [num(c[col('targetRoll')]), num(c[col('targetPitch')]), num(c[col('targetHeading')])], lat: num(c[col('lat')]), lon: num(c[col('lon')]) };
  });
  replay = { rows, i: 0 };
  $('replayStop').hidden = false;
  $('replaySlider').hidden = false;
  $('replaySlider').max = String(rows.length - 1);
};
$('replaySlider').oninput = () => { if (replay) { replay.i = Number($('replaySlider').value); } };
$('replayStop').onclick = () => {
  replay = null;
  $('replayStop').hidden = true;
  $('replaySlider').hidden = true;
  $('replayFile').value = '';
  setText('replayInfo', '');
};
setInterval(() => {
  if (!replay) return;
  const r = replay.rows[replay.i];
  if (!r) return;
  drawHorizon(r.a[0], r.a[1], r.a[2], r.ta[2]);
  setText('attRoll', deg(r.a[0])); setText('attPitch', deg(r.a[1])); setText('attYaw', deg(r.a[2], 0));
  setText('replayInfo', `REPLAY ${new Date(r.t).toLocaleTimeString()} · ${r.mode.replace('_', ' ')} · row ${replay.i + 1} of ${replay.rows.length}`);
  $('replaySlider').value = String(replay.i);
  // The log is near 100 rows a second, and this draws 20 times a second.
  replay.i = Math.min(replay.rows.length - 1, replay.i + 5);
}, 50);

/* ---------- Any device: setup, skills and assistant. All of it stays hidden on a phone without it. ---------- */
function applyDevicePanel(m) {
  const operator = relayInfo?.role !== 'viewer';
  const d = m.device;
  $('setupSection').hidden = !d && !lastSetup?.pending?.length;
  if (m.assistant) {
    $('assistantSection').hidden = !operator;
    applyAssistantCfg(m.assistant);
  }
  if (!d) { $('deviceSkills').replaceChildren(); $('deviceBehaviors').replaceChildren(); $('packExport').hidden = true; return; }
  const btn = (label, fn) => { const b = Object.assign(document.createElement('button'), { textContent: label }); b.onclick = fn; b.disabled = !operator; return b; };
  $('deviceSkills').replaceChildren(...d.skills.map(s => btn(s, () => cmd('run_skill', { id: s }))), ...(d.skills.length ? [btn('Stop skill', () => cmd('stop_skill'))] : []));
  $('deviceBehaviors').replaceChildren(...d.behaviors.map(b => btn(`Run ${b}`, () => cmd('run_behavior', { id: b }))), ...(d.behaviors.length ? [btn('Stop behavior', () => cmd('stop_behavior'))] : []));
  setText('setupDevice', `${d.name} · ${d.class} · hazard ${d.hazard}${d.signedOff ? ' · signed off' : ' · hardware closed'}`);
  if (!relayInfo) {
    $('packExport').hidden = false;
    $('packExport').href = `/export/pack/${encodeURIComponent(d.id)}?token=${encodeURIComponent(token)}`;
  }
}

let lastSetup = null;
function onSetup(m) {
  lastSetup = m;
  if (m.device || m.pending.length) $('setupSection').hidden = false;
  setText('setupStage', `Stage ${m.stage} of 11: ${m.stageName}`);
  const operator = relayInfo?.role !== 'viewer';
  $('setupPending').replaceChildren(...m.pending.map(p => {
    const row = document.createElement('div');
    const t = Object.assign(document.createElement('span'), { textContent: `Needs your OK: ${p.detail}` });
    t.style.flex = '1';
    const yes = Object.assign(document.createElement('button'), { textContent: 'Approve', disabled: !operator });
    yes.onclick = () => raw({ type: 'setup_approve', id: p.id, allow: true });
    const no = Object.assign(document.createElement('button'), { textContent: 'Refuse', disabled: !operator });
    no.onclick = () => raw({ type: 'setup_approve', id: p.id, allow: false });
    row.append(t, yes, no);
    return row;
  }));
}

function onDevice(m) {
  const rows = m.actuators || [];
  const table = $('deviceJoints');
  if (table.rows.length !== rows.length) {
    table.replaceChildren(...rows.map(() => { const tr = document.createElement('tr'); tr.append(document.createElement('td'), document.createElement('td'), document.createElement('td')); return tr; }));
  }
  rows.forEach((a, i) => {
    const c = table.rows[i].cells;
    c[0].textContent = a.id;
    c[1].textContent = `${a.value} ${a.unit === 'norm' ? '' : a.unit}`;
    c[2].textContent = `range ${a.min} to ${a.max}`;
    c[2].className = 'muted';
  });
  setText('packNote', m.fallen ? 'Fall detected' : (m.skill ? `Running ${m.skill}` : ''));
}

$('packFile').onchange = async e => {
  const f = e.target.files[0];
  if (!f) return;
  raw({ type: 'device_import', text: await f.text(), name: f.name.replace(/\.[^.]+$/, '').slice(0, 60) });
  e.target.value = '';
  setText('packNote', 'Importing...');
};
$('mavSave').onclick = () => raw({ type: 'mavlink_cfg', host: $('mavHost').value });

function applyAssistantCfg(c) {
  $('aiKind').value = c.kind;
  if (document.activeElement !== $('aiBase')) $('aiBase').value = c.base;
  if (document.activeElement !== $('aiModel')) $('aiModel').value = c.model;
  setText('aiKeyNote', c.hasKey ? 'A key is saved.' : 'No key saved. Local models may not need one.');
}
function aiLine(role, text) {
  const d = Object.assign(document.createElement('div'), { className: role, textContent: role === 'tool' ? `Used ${text}` : text });
  $('aiLog').append(d);
  $('aiLog').scrollTop = $('aiLog').scrollHeight;
}
$('aiKind').onchange = () => {
  $('aiBase').value = $('aiKind').value === 'anthropic' ? 'https://api.anthropic.com' : 'http://192.168.1.20:11434/v1';
  $('aiModel').value = $('aiKind').value === 'anthropic' ? 'claude-opus-5-5' : '';
};
$('aiSave').onclick = () => {
  const m = { type: 'assistant_cfg', kind: $('aiKind').value, base: $('aiBase').value, model: $('aiModel').value };
  if ($('aiKey').value) m.key = $('aiKey').value;
  raw(m);
  $('aiKey').value = '';
};
function aiSend() {
  const text = $('aiInput').value.trim();
  if (!text) return;
  aiLine('user', text);
  raw({ type: 'assistant_say', text });
  $('aiInput').value = '';
  $('aiSend').disabled = true;
}
$('aiSend').onclick = aiSend;
$('aiInput').addEventListener('keydown', e => { if (e.key === 'Enter') aiSend(); });
$('aiReset').onclick = () => { $('aiLog').replaceChildren(); raw({ type: 'assistant_reset' }); };

/** A relay answers /session for a signed-in user; the phone itself has no such page. */
async function boot() {
  try {
    const res = await fetch('/session');
    if (res.ok) relayInfo = await res.json();
  } catch (e) { /* Treated as local mode. */ }
  if (!relayInfo) {
    if (token) connect(); else showPair('Enter the code shown on the phone.');
    return;
  }
  for (const [id, name] of [['xTel', 'telemetry'], ['xCmd', 'commands'], ['xEvt', 'events'], ['xSen', 'sensors'], ['xFlt', 'flight']]) $(id).href = `/export/${name}.csv`;
  $('mcpSection').hidden = true;
  $('account').hidden = false;
  $('who').textContent = `${relayInfo.user} (${relayInfo.role})`;
  if (relayInfo.role === 'operator') {
    $('enrollSection').hidden = false;
    $('enrollBtn').onclick = showEnrollQr;
  }
  if (relayInfo.role === 'viewer') {
    document.querySelectorAll('main button:not(#stop), main input, main select').forEach(el => { el.disabled = true; });
    $('cameraSection').hidden = true;
    $('channelsBox').hidden = true;
    document.querySelectorAll('#xTel, #xCmd, #xEvt, #xSen, #xFlt').forEach(el => { el.hidden = true; });
  }
  connect();
}
boot();

setInterval(() => {
  if (!lastTelAt) return;
  const age = Date.now() - lastTelAt;
  if (age > 2000) $('reason').textContent = `No telemetry for ${(age / 1000).toFixed(0)} s. The car stops itself if it was being driven.`;
}, 500);
