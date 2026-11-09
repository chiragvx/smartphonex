// The relay: signs people and agents in, then forwards traffic to the phone's outgoing connection.
// It holds no vehicle logic. The phone still validates every command and owns all safety behavior.
import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { appendFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import QRCode from 'qrcode';
import { WebSocketServer } from 'ws';
import { AuthStore } from './auth.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const SESSION_MS = 12 * 3600_000;
const LOGIN_FAILS = 5;
const LOGIN_LOCK_MS = 5 * 60_000;
const BROWSER_MSGS_PER_SEC = 60;
const MCP_REQS_PER_SEC = 20;
const RPC_TIMEOUT_MS = 15_000;
const VEHICLE_SILENT_MS = 10_000;
const MAX_BODY = 256 * 1024;
const MAX_BROWSER_BUFFER = 256 * 1024;
const COOKIE = 'spx_session';
const ENROLL_MS = 5 * 60_000;
const sha256 = s => createHash('sha256').update(s).digest('hex');
const esc = s => String(s).replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);

const ENROLL_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Connect Vehicle</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px;background:#10141a;color:#e6ebf2;font:16px system-ui,sans-serif}
main{width:100%;max-width:360px;text-align:center;background:#1a2029;border:1px solid #2d3644;border-radius:8px;padding:24px}h1{font-size:20px;margin:0 0 12px}p{color:#93a0b2}
a{display:block;padding:14px;font-weight:600;color:#04213f;background:#4aa3ff;border-radius:6px;text-decoration:none}</style></head>
<body><main><h1>Connect this phone</h1><p>Open the SmartPhoneX app to link this phone to <b>{{host}}</b>.</p><a href="{{link}}">Open SmartPhoneX</a>
<p>Nothing happens? Check that the app is installed. This code works once and expires after 5 minutes.</p></main></body></html>`;

const LOGIN_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>SmartPhoneX Sign In</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px;background:#10141a;color:#e6ebf2;font:15px system-ui,sans-serif}
form{width:100%;max-width:340px;background:#1a2029;border:1px solid #2d3644;border-radius:8px;padding:24px}h1{font-size:18px;margin:0 0 16px}
label{display:block;margin-bottom:12px;color:#93a0b2;font-size:13px}input{display:block;width:100%;margin-top:4px;padding:10px;font:inherit;color:inherit;background:#28313e;border:1px solid #2d3644;border-radius:6px}
button{width:100%;padding:10px;font:inherit;font-weight:600;color:#04213f;background:#4aa3ff;border:0;border-radius:6px;cursor:pointer}p{color:#ff8a8a;margin:0 0 12px}</style></head>
<body><form method="post" action="/login"><h1>SmartPhoneX</h1>{{error}}<label>User<input name="user" autocomplete="username" required autofocus></label>
<label>Password<input name="password" type="password" autocomplete="current-password" required></label><button>Sign in</button></form></body></html>`;

class Bucket {
  constructor(perSec) { this.perSec = perSec; this.tokens = perSec; this.at = Date.now(); }
  take() {
    const now = Date.now();
    this.tokens = Math.min(this.perSec, this.tokens + (now - this.at) / 1000 * this.perSec);
    this.at = now;
    if (this.tokens < 1) return false;
    this.tokens--;
    return true;
  }
}

export function createRelay({ dataDir = join(HERE, 'data'), webDir = join(HERE, '..', 'app', 'src', 'main', 'assets', 'web'), trustProxy = false } = {}) {
  const store = new AuthStore(dataDir);
  mkdirSync(dataDir, { recursive: true });
  const auditFile = join(dataDir, 'audit.log');
  const audit = (event, fields = {}) => {
    try {
      appendFileSync(auditFile, JSON.stringify({ t: new Date().toISOString(), event, ...fields }) + '\n');
    } catch (e) {
      console.error('audit write failed:', e.message); // A full disk must not take the relay down.
    }
  };

  const assets = {
    '/': ['text/html; charset=utf-8', 'index.html'],
    '/classic/': ['text/html; charset=utf-8', 'classic/index.html'],
    '/classic/app.js': ['text/javascript; charset=utf-8', 'classic/app.js'],
  };
  const sessions = new Map();
  const loginFails = new Map();
  const browsers = new Map();
  const mcpBuckets = new Map();
  const pending = new Map();
  const enrollments = new Map();
  let vehicle = null;
  let vehicleLastSeen = 0;
  let nextId = 1;
  let vehicleConnects = 0;
  let offlineSince = Date.now();
  let lastOutageMs = 0;

  // Only the hop our own proxy added can be believed. Cloudflare overwrites CF-Connecting-IP, so it's trusted only
  // in 'cloudflare' mode. Any other proxy appends the address it saw to X-Forwarded-For, so take the last entry:
  // earlier ones come from the client and can be made up to get round sign-in lockouts.
  const clientIp = req => {
    if (trustProxy === 'cloudflare' && req.headers['cf-connecting-ip']) return String(req.headers['cf-connecting-ip']).trim();
    if (trustProxy) { const last = String(req.headers['x-forwarded-for'] ?? '').split(',').pop()?.trim(); if (last) return last; }
    return req.socket.remoteAddress;
  };
  const isHttps = req => trustProxy && req.headers['x-forwarded-proto'] === 'https';

  function cookieSession(req) {
    const m = (req.headers.cookie || '').match(new RegExp(`(?:^|;\\s*)${COOKIE}=([A-Za-z0-9_-]+)`));
    if (!m) return null;
    const s = sessions.get(m[1]);
    if (!s) return null;
    const u = store.user(s.user);
    if (Date.now() > s.expires || !u) { sessions.delete(m[1]); return null; }
    s.role = u.role; // A role change applies at once.
    s.id = m[1];
    return s;
  }

  /** Blocks other sites from using a signed-in browser's cookie. */
  function sameOrigin(req) {
    const origin = req.headers.origin;
    if (!origin) return true; // Non-browser clients send none, and they still need a credential.
    try { return new URL(origin).host === req.headers.host; } catch { return false; }
  }

  function send(res, status, body, type = 'text/plain; charset=utf-8', extra = {}) {
    res.writeHead(status, {
      'content-type': type,
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'x-frame-options': 'DENY',
      // Not no-referrer: that makes browsers send "Origin: null", which fails the same-origin check.
      'referrer-policy': 'same-origin',
      // The 3D view (MapLibre) fetches imagery and terrain tiles rather than loading them as images, and runs its
      // worker from a blob; the no-go button asks OpenStreetMap's Overpass. Only those hosts are added.
      'content-security-policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data: https://tile.openstreetmap.org https://server.arcgisonline.com https://s3.amazonaws.com; connect-src 'self' ws: wss: https://tile.openstreetmap.org https://server.arcgisonline.com https://s3.amazonaws.com https://overpass-api.de; worker-src 'self' blob:; frame-ancestors 'none'; form-action 'self'",
      ...extra,
    });
    res.end(body);
  }

  const readBody = req => new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', c => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });

  function loginBlocked(keys) {
    const now = Date.now();
    return keys.some(k => { const f = loginFails.get(k); return f && f.until > now; });
  }
  function loginFailed(keys) {
    for (const k of keys) {
      const f = loginFails.get(k) || { n: 0, until: 0 };
      f.n++;
      if (f.n >= LOGIN_FAILS) { f.until = Date.now() + LOGIN_LOCK_MS; f.n = 0; }
      loginFails.set(k, f);
    }
  }

  const publicOrigin = req => `${isHttps(req) ? 'https' : 'http'}://${req.headers.host}`;

  /** A signed-in operator asks to link a phone. The QR code holds a web link any scanner can open. */
  async function handleEnrollNew(req, res, session) {
    if (!sameOrigin(req)) return send(res, 403, 'Forbidden');
    if (session.role !== 'operator') return send(res, 403, 'Operators only');
    const code = randomBytes(24).toString('base64url');
    enrollments.set(sha256(code), Date.now() + ENROLL_MS);
    const url = `${publicOrigin(req)}/enroll/open?code=${code}`;
    audit('enroll_created', { user: session.user });
    send(res, 200, JSON.stringify({
      url,
      svg: await QRCode.toString(url, { type: 'svg', margin: 2, errorCorrectionLevel: 'M' }),
      expiresInSeconds: ENROLL_MS / 1000,
      encrypted: isHttps(req),
    }), 'application/json');
  }

  /** Where a scanned QR code lands. Showing it uses nothing up: only the app's claim does. */
  function handleEnrollOpen(req, res, url) {
    const code = url.searchParams.get('code') || '';
    if (!/^[A-Za-z0-9_-]{32}$/.test(code)) return send(res, 400, 'Invalid link');
    const origin = publicOrigin(req);
    const link = `smartphonex://enroll?relay=${encodeURIComponent(origin)}&code=${code}`;
    send(res, 200, ENROLL_PAGE.replace('{{host}}', esc(req.headers.host)).replace('{{link}}', esc(link)), 'text/html; charset=utf-8');
  }

  /** The app trades a one-time code for the vehicle key. The previous phone is disconnected. */
  async function handleEnrollClaim(req, res) {
    if (!sameOrigin(req)) return send(res, 403, 'Forbidden');
    const keys = [`enroll-ip:${clientIp(req)}`];
    if (loginBlocked(keys)) return send(res, 429, JSON.stringify({ error: 'too_many_attempts' }), 'application/json');
    let code = '';
    try { code = String(JSON.parse(await readBody(req)).code ?? ''); } catch { /* Treated as a wrong code. */ }
    const hash = sha256(code);
    const expires = enrollments.get(hash);
    enrollments.delete(hash);
    if (!expires || Date.now() > expires) {
      loginFailed(keys);
      audit('enroll_denied', { ip: clientIp(req) });
      return send(res, 401, JSON.stringify({ error: 'invalid_or_expired' }), 'application/json');
    }
    keys.forEach(k => loginFails.delete(k));
    const vehicleKey = store.newVehicleKey();
    audit('enroll_claimed', { ip: clientIp(req) });
    if (vehicle) vehicle.terminate();
    send(res, 200, JSON.stringify({ vehicleKey }), 'application/json');
  }

  async function handleLogin(req, res) {
    if (!sameOrigin(req)) return send(res, 403, 'Forbidden');
    const form = new URLSearchParams(await readBody(req));
    const user = form.get('user') || '';
    const password = form.get('password') || '';
    const ip = clientIp(req);
    const keys = [`ip:${ip}`, `user:${user}`];
    const page = msg => LOGIN_PAGE.replace('{{error}}', `<p role="alert">${msg}</p>`);
    if (loginBlocked(keys)) {
      audit('login_blocked', { user, ip });
      return send(res, 429, page('Too many attempts. Try again in a few minutes.'), 'text/html; charset=utf-8');
    }
    if (!(await store.checkPassword(user, password))) {
      loginFailed(keys);
      audit('login_failed', { user, ip });
      return send(res, 401, page('Wrong user or password.'), 'text/html; charset=utf-8');
    }
    keys.forEach(k => loginFails.delete(k));
    const id = randomBytes(32).toString('base64url');
    sessions.set(id, { user, role: store.user(user).role, expires: Date.now() + SESSION_MS });
    audit('login', { user, ip });
    const secure = isHttps(req) ? '; Secure' : '';
    send(res, 303, '', 'text/plain', {
      location: '/',
      'set-cookie': `${COOKIE}=${id}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${SESSION_MS / 1000}${secure}`,
    });
  }

  function rpc(kind, body) {
    return new Promise((resolve, reject) => {
      if (!vehicle) return reject(new Error('offline'));
      const id = nextId++;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('timeout')); }, RPC_TIMEOUT_MS);
      pending.set(id, { resolve, reject, timer });
      vehicle.send(JSON.stringify({ rpc: id, kind, body }));
    });
  }

  const rpcError = (id, code, message) => JSON.stringify({ jsonrpc: '2.0', id, error: { code, message } });

  async function handleMcp(req, res) {
    if (req.method !== 'POST') return send(res, 405, '');
    const bearer = (req.headers.authorization || '').replace(/^Bearer /, '');
    const keyName = bearer ? store.checkAiKey(bearer) : null;
    if (!keyName) {
      audit('mcp_denied', { ip: clientIp(req) });
      return send(res, 401, 'Unauthorized');
    }
    if (!mcpBuckets.has(keyName)) mcpBuckets.set(keyName, new Bucket(MCP_REQS_PER_SEC));
    if (!mcpBuckets.get(keyName).take()) {
      audit('mcp_limited', { key: keyName });
      return send(res, 429, 'Too many requests', 'text/plain', { 'retry-after': '1' });
    }
    const body = await readBody(req);
    let parsed = {};
    try { parsed = JSON.parse(body); } catch { /* The phone reports the parse error. */ }
    const started = performance.now();
    try {
      const r = await rpc('mcp', body);
      const ms = performance.now() - started;
      if (parsed.method === 'tools/call') audit('mcp_call', { key: keyName, tool: parsed.params?.name, ms: Math.round(ms) });
      send(res, r.status, r.body ?? '', 'application/json', { 'x-spx-vehicle-ms': ms.toFixed(1) });
    } catch (e) {
      const offline = e.message === 'offline';
      audit('mcp_failed', { key: keyName, reason: e.message });
      send(res, offline ? 503 : 504, rpcError(parsed.id ?? null, -32000, offline ? 'Vehicle is offline' : 'Vehicle did not respond in time'), 'application/json');
    }
  }

  /** The same tools over plain HTTP, for anything without MCP. Same key and limits as MCP. */
  async function handleApi(req, res, pathname) {
    const bearer = (req.headers.authorization || '').replace(/^Bearer /, '');
    const keyName = bearer ? store.checkAiKey(bearer) : null;
    if (!keyName) { audit('api_denied', { ip: clientIp(req) }); return send(res, 401, 'Unauthorized'); }
    if (!mcpBuckets.has(keyName)) mcpBuckets.set(keyName, new Bucket(MCP_REQS_PER_SEC));
    if (!mcpBuckets.get(keyName).take()) return send(res, 429, 'Too many requests', 'text/plain', { 'retry-after': '1' });
    try {
      let r;
      if (pathname === '/api/tools') {
        if (req.method !== 'GET') return send(res, 405, '');
        r = await rpc('api_list', '');
      } else {
        if (req.method !== 'POST') return send(res, 405, '');
        const name = decodeURIComponent(pathname.slice('/api/tools/'.length));
        const raw = await readBody(req);
        let args = {};
        try { args = raw ? JSON.parse(raw) : {}; } catch { return send(res, 400, 'Body must be JSON'); }
        audit('api_call', { key: keyName, tool: name });
        r = await rpc('api', JSON.stringify({ name, args }));
      }
      send(res, r.status, r.body ?? '', 'application/json');
    } catch (e) {
      send(res, e.message === 'offline' ? 503 : 504, JSON.stringify({ error: e.message }), 'application/json');
    }
  }

  async function route(req, res) {
    const url = new URL(req.url, 'http://relay');
    const { pathname } = url;
    if (pathname === '/enroll/open' && req.method === 'GET') return handleEnrollOpen(req, res, url);
    if (pathname === '/enroll/claim' && req.method === 'POST') return handleEnrollClaim(req, res);
    if (pathname === '/health') return send(res, 200, 'ok');
    if (pathname === '/favicon.ico') return send(res, 204, '');
    if (pathname === '/mcp') return handleMcp(req, res);
    if (pathname === '/api/tools' || pathname.startsWith('/api/tools/')) return handleApi(req, res, pathname);
    if (pathname === '/login') {
      if (req.method === 'POST') return handleLogin(req, res);
      return send(res, 200, LOGIN_PAGE.replace('{{error}}', ''), 'text/html; charset=utf-8');
    }
    const session = cookieSession(req);
    if (pathname === '/logout' && req.method === 'POST') {
      if (!sameOrigin(req)) return send(res, 403, 'Forbidden');
      if (session) { sessions.delete(session.id); closeSessionBrowsers(session.id); audit('logout', { user: session.user }); }
      return send(res, 303, '', 'text/plain', { location: '/login', 'set-cookie': `${COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0` });
    }
    if (!session) {
      if (pathname === '/' || pathname === '/classic/') return send(res, 303, '', 'text/plain', { location: '/login' });
      return send(res, 401, 'Sign in first');
    }
    if (pathname === '/enroll/new' && req.method === 'POST') return handleEnrollNew(req, res, session);
    if (pathname === '/session') {
      return send(res, 200, JSON.stringify({ user: session.user, role: session.role, vehicleOnline: !!vehicle }), 'application/json');
    }
    if (pathname.startsWith('/export/')) {
      if (session.role !== 'operator') return send(res, 403, 'Operators only');
      try {
        const r = await rpc('csv', pathname.slice('/export/'.length));
        return send(res, r.status, r.body ?? '', 'text/csv; charset=utf-8');
      } catch (e) {
        return send(res, e.message === 'offline' ? 503 : 504, `Vehicle unavailable: ${e.message}`);
      }
    }
    if (pathname.startsWith('/review/')) {
      if (session.role !== 'operator') return send(res, 403, 'Operators only');
      const path = pathname.slice('/review/'.length);
      if (!/^[a-zA-Z0-9_./-]{1,160}$/.test(path) || path.includes('..')) return send(res, 400, 'Bad path');
      try {
        const r = await rpc('review', path);
        if (r.status !== 200 || r.body == null) return send(res, r.status, '');
        if (path === 'files' || path.startsWith('frames/')) return send(res, 200, r.body, 'application/json');
        const type = path.startsWith('frame/') || path.startsWith('file/photo/') ? 'image/jpeg' : 'text/csv; charset=utf-8';
        return send(res, 200, Buffer.from(r.body, 'base64'), type);
      } catch (e) {
        return send(res, e.message === 'offline' ? 503 : 504, `Vehicle unavailable: ${e.message}`);
      }
    }
    if (pathname.startsWith('/assets/') && /^\/assets\/[a-zA-Z0-9_.-]+$/.test(pathname) && req.method === 'GET') {
      let file;
      try { file = readFileSync(join(webDir, pathname.slice(1))); } catch { return send(res, 404, 'Not found'); }
      return send(res, 200, file, pathname.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/javascript; charset=utf-8');
    }
    const asset = assets[pathname];
    if (asset && req.method === 'GET') return send(res, 200, readFileSync(join(webDir, asset[1])), asset[0]);
    send(res, 404, 'Not found');
  }

  function closeSessionBrowsers(sessionId) {
    for (const b of browsers.values()) if (b.session.id === sessionId) b.ws.close(1008, 'signed out');
    for (const v of videoViewers.values()) if (v.session.id === sessionId) v.ws.close(1008, 'signed out');
  }

  const server = createServer((req, res) => {
    route(req, res).catch(e => {
      if (!res.headersSent) send(res, e.message === 'too large' ? 413 : 500, 'Request failed');
      else res.destroy();
    });
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_BODY * 16 });
  const refuse = (socket, status) => { socket.write(`HTTP/1.1 ${status}\r\nConnection: close\r\n\r\n`); socket.destroy(); };

  server.on('upgrade', (req, socket, head) => {
    const { pathname } = new URL(req.url, 'http://relay');
    if (pathname === '/vehicle') {
      const key = (req.headers.authorization || '').replace(/^Bearer /, '');
      if (!key || !store.checkVehicleKey(key)) {
        audit('vehicle_denied', { ip: clientIp(req) });
        return refuse(socket, '401 Unauthorized');
      }
      return wss.handleUpgrade(req, socket, head, ws => onVehicle(ws, req));
    }
    if (pathname === '/vehicle/video') {
      const key = (req.headers.authorization || '').replace(/^Bearer /, '');
      if (!key || !store.checkVehicleKey(key)) return refuse(socket, '401 Unauthorized');
      return wss.handleUpgrade(req, socket, head, onVehicleVideo);
    }
    if (pathname === '/video') {
      if (!sameOrigin(req)) return refuse(socket, '403 Forbidden');
      const session = cookieSession(req);
      // The camera is for operators. Viewers can watch telemetry and stop the vehicle.
      if (!session || session.role !== 'operator') {
        audit('video_denied', { user: session?.user, ip: clientIp(req) });
        return refuse(socket, '403 Forbidden');
      }
      return wss.handleUpgrade(req, socket, head, ws => onVideoViewer(ws, session));
    }
    if (pathname === '/ws') {
      if (!sameOrigin(req)) {
        audit('ws_bad_origin', { origin: req.headers.origin, ip: clientIp(req) });
        return refuse(socket, '403 Forbidden');
      }
      const session = cookieSession(req);
      // Accept, then close with 1008: browsers hide the status of a refused upgrade from pages.
      return wss.handleUpgrade(req, socket, head, ws => {
        if (!session) return ws.close(1008, 'sign in first');
        onBrowser(ws, session);
      });
    }
    refuse(socket, '404 Not Found');
  });

  const videoViewers = new Map();
  let vehicleVideo = null;
  const tellVideoViewers = () => { if (vehicle) vehicle.send(JSON.stringify({ op: 'video', viewers: videoViewers.size })); };

  function onVehicleVideo(ws) {
    if (vehicleVideo) vehicleVideo.terminate();
    vehicleVideo = ws;
    ws.on('message', (data, isBinary) => {
      if (!isBinary) return;
      // Skip a viewer who's behind: live video is only useful if it's current.
      for (const v of videoViewers.values()) {
        if (v.ws.readyState === 1 && v.ws.bufferedAmount < MAX_BROWSER_BUFFER) v.ws.send(data, { binary: true });
      }
      // Tell the phone it may send the next frame. It holds frames until then, so no queue can build.
      if (ws.readyState === 1) ws.send('a');
    });
    ws.on('error', () => {});
    ws.on('close', () => { if (vehicleVideo === ws) vehicleVideo = null; });
  }

  function onVideoViewer(ws, session) {
    const id = `v${nextId++}`;
    videoViewers.set(id, { ws, session });
    audit('video_viewer_connected', { user: session.user });
    tellVideoViewers();
    ws.on('error', () => {});
    ws.on('close', () => {
      videoViewers.delete(id);
      audit('video_viewer_disconnected', { user: session.user });
      tellVideoViewers();
    });
  }

  function notifyBrowsers() {
    const msg = JSON.stringify({ type: 'vehicle', online: !!vehicle, connects: vehicleConnects, lastOutageMs });
    for (const b of browsers.values()) if (b.ws.readyState === 1) b.ws.send(msg);
  }

  function onVehicle(ws, req) {
    if (vehicle) { vehicle.terminate(); } // Half-open connections are common on cellular: newest wins.
    vehicle = ws;
    vehicleLastSeen = Date.now();
    vehicleConnects++;
    if (offlineSince) { lastOutageMs = Date.now() - offlineSince; offlineSince = 0; }
    audit('vehicle_connected', { ip: clientIp(req), outageMs: lastOutageMs });
    for (const b of browsers.values()) vehicle.send(JSON.stringify({ ch: b.id, op: 'open', role: b.session.role }));
    tellVideoViewers();
    notifyBrowsers();

    ws.on('message', raw => {
      vehicleLastSeen = Date.now();
      const text = raw.toString();
      let m;
      try { m = JSON.parse(text); } catch { return; }
      if (m.op === 'hb') { ws.send('{"op":"hb"}'); return; }
      if (m.rpc !== undefined) {
        const p = pending.get(m.rpc);
        if (p) { clearTimeout(p.timer); pending.delete(m.rpc); p.resolve(m); }
        return;
      }
      const b = browsers.get(m.ch);
      if (!b || b.ws.readyState !== 1 || typeof m.data !== 'string') return;
      // Telemetry goes stale fast: drop it for a slow viewer instead of queueing without limit.
      if (b.ws.bufferedAmount > MAX_BROWSER_BUFFER && m.data.startsWith('{"type":"telemetry"')) return;
      b.ws.send(m.data);
    });
    ws.on('pong', () => { vehicleLastSeen = Date.now(); });
    ws.on('error', () => {});
    ws.on('close', () => {
      if (vehicle !== ws) return;
      vehicle = null;
      offlineSince = Date.now();
      audit('vehicle_disconnected');
      for (const [id, p] of pending) { clearTimeout(p.timer); pending.delete(id); p.reject(new Error('offline')); }
      notifyBrowsers();
    });
  }

  function allowed(role, text) {
    if (role === 'operator') return true;
    let m;
    try { m = JSON.parse(text); } catch { return false; }
    // Viewers can watch, and anyone who can see the car can stop it.
    return m?.type === 'ping' || (m?.type === 'cmd' && m.cmd === 'stop');
  }

  function onBrowser(ws, session) {
    const id = `b${nextId++}`;
    const b = { id, ws, session, bucket: new Bucket(BROWSER_MSGS_PER_SEC), limited: false };
    browsers.set(id, b);
    audit('browser_connected', { user: session.user, role: session.role });
    ws.send(JSON.stringify({ type: 'vehicle', online: !!vehicle, connects: vehicleConnects, lastOutageMs }));
    if (vehicle) vehicle.send(JSON.stringify({ ch: id, op: 'open', role: session.role }));

    ws.on('message', raw => {
      const text = raw.toString();
      if (!b.bucket.take()) {
        // Past the limit, only a genuine stop command still gets through.
        let stop = false;
        try { const m = JSON.parse(text); stop = m?.type === 'cmd' && m.cmd === 'stop'; } catch { /* Not a stop. */ }
        if (!stop) {
          if (!b.limited) audit('browser_limited', { user: session.user });
          b.limited = true;
          return;
        }
      }
      b.limited = false;
      if (!allowed(b.session.role, text)) {
        audit('browser_forbidden', { user: session.user });
        let seq = null;
        try { seq = JSON.parse(text).seq ?? null; } catch { /* Not JSON: no seq to echo. */ }
        ws.send(JSON.stringify({ type: 'ack', seq, accepted: false, code: 'not_permitted', message: 'Your account can only watch and stop.' }));
        return;
      }
      if (!vehicle) {
        let m = {};
        try { m = JSON.parse(text); } catch { /* Ignored. */ }
        if (m.type === 'cmd') ws.send(JSON.stringify({ type: 'ack', seq: m.seq ?? null, cmd: m.cmd, accepted: false, code: 'vehicle_offline', message: 'Vehicle is offline' }));
        return;
      }
      vehicle.send(JSON.stringify({ ch: id, op: 'msg', data: text }));
    });
    ws.on('error', () => {});
    ws.on('close', () => {
      browsers.delete(id);
      audit('browser_disconnected', { user: session.user });
      if (vehicle) vehicle.send(JSON.stringify({ ch: id, op: 'close' }));
    });
  }

  const sweep = setInterval(() => {
    const now = Date.now();
    if (vehicle && now - vehicleLastSeen > VEHICLE_SILENT_MS) {
      audit('vehicle_silent');
      vehicle.terminate();
    }
    if (vehicle) vehicle.ping();
    for (const [id, s] of sessions) if (now > s.expires) { sessions.delete(id); closeSessionBrowsers(id); }
    for (const b of browsers.values()) {
      const u = store.user(b.session.user);
      if (!u || now > b.session.expires) b.ws.close(1008, 'session ended');
      else b.session.role = u.role;
    }
    // A removed, downgraded or expired operator loses the camera at once.
    for (const v of videoViewers.values()) {
      const u = store.user(v.session.user);
      if (!u || u.role !== 'operator' || now > v.session.expires) v.ws.close(1008, 'session ended');
    }
    for (const [k, f] of loginFails) if (f.until && f.until < now) loginFails.delete(k);
    for (const [h, expires] of enrollments) if (now > expires) enrollments.delete(h);
    store.data = new AuthStore(dataDir).data; // Pick up admin changes without a restart.
  }, 5000);

  return {
    server,
    store,
    close() {
      clearInterval(sweep);
      for (const c of wss.clients) c.terminate();
      return new Promise(r => server.close(r));
    },
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8787);
  const relay = createRelay({
    dataDir: process.env.RELAY_DATA || join(HERE, 'data'),
    ...(process.env.RELAY_WEB ? { webDir: process.env.RELAY_WEB } : {}),
    // 1: behind a proxy that appends X-Forwarded-For (Caddy, nginx). cloudflare: behind a Cloudflare tunnel.
    trustProxy: process.env.TRUST_PROXY === 'cloudflare' ? 'cloudflare' : process.env.TRUST_PROXY === '1',
  });
  relay.server.listen(port, process.env.HOST || '127.0.0.1', () => console.log(`relay listening on ${process.env.HOST || '127.0.0.1'}:${port}`));
}
