// The connection and everything known about the phone. The phone is the single truth: values here
// are only what it last said, each with its age, and controls show what it answered.
import { useEffect, useState } from 'preact/hooks';

export type Check = { id: string; label: string; status: string; detail: string; fix: string };
export type Trigger = { id: string; label: string; danger: boolean; armedOnly: boolean; count: number; cooldownMs: number; maxCount: number; aiAllowed: boolean; everyMeters: number; everySeconds: number; sensor: string | null; op: string | null; compare: number; action: { type: string; output: string | null; value: number; ms: number; skill: string | null; text: string | null } };
export type MissionItem = { lat: number; lon: number; alt?: number | null; throttle?: number | null; holdS?: number; radiusM?: number; triggers?: string[] };
export type MissionData = { name: string; onEnd: string; onLinkLoss: string; items: MissionItem[] };
export type FenceData = { polygon: [number, number][]; keepOut: [number, number][][]; maxDistM: number; maxAltM: number; action: string };
export type Ops = {
  t: number; flightMode: string; owner: string | null; killed: boolean; readiness: Check[]; armProblem: string | null;
  test?: { output: string; value: number; owner: string };
  groundRun?: { state: 'IDLE' | 'RUNNING' | 'DONE' | 'STOPPED' | 'FAILED'; message: string | null; step: number; steps: number; throttle?: number };
  mission: { state: string; name: string | null; index: number; total: number; dist: number | null; bearing: number | null; onLinkLoss: string | null };
  missionData?: MissionData;
  fence: { set: boolean; breach: string | null; data?: FenceData };
  triggers: Trigger[]; diag: string | null; ground: number;
  report?: Report; checklist: { item: string; done: boolean }[];
  trims: Record<string, { trim: number; reverse: boolean; lo: number; hi: number }>;
  connections: Check[];
  face?: { shown: boolean; mode: string; requested: string | null; held: boolean; blocked?: string };
};
export type Report = { t: number; device: string; name: string; passed: boolean; results: { test: string; status: string; detail: string }[] };
export type LogEvent = { id?: number; t: number; kind: string; source: string; code: string; message: string };
export type Alert = { id: number; level: 'info' | 'warning' | 'critical'; text: string; t: number };
export type Toast = { seq: number; label: string; state: 'sent' | 'ok' | 'refused' | 'lost'; message: string };

export const S = {
  link: false, relay: null as null | { user: string; role: string }, vehicleOnline: true, needPair: '', rtt: null as number | null,
  hello: null as any, tel: null as any, telAt: 0, ops: null as Ops | null, fc: null as any, fcs: null as any, fcAt: 0,
  params: [] as any[], paramHistory: [] as any[], setup: null as any, device: null as any, sensorValues: {} as Record<string, number[]>,
  sensorStats: null as any, config: null as any, assistant: [] as { role: string; text: string }[], assistantBusy: false, assistantCfg: null as any,
  events: [] as LogEvent[], alerts: [] as Alert[], toasts: [] as Toast[], net: null as any, relayInfo: null as any,
  video: { url: '', rotate: 0, ageMs: 0, fps: 0, live: false },
  sticks: { t: 0, s: 0, p: 0, r: 0 }, hidden: false, hiddenSince: 0, offset: 0, cfgMessage: '',
};

const listeners = new Set<() => void>();
let dirty = false;
function changed() {
  if (dirty) return;
  dirty = true;
  // A frame at a time at most, so a busy link can't choke a laptop.
  setTimeout(() => { dirty = false; listeners.forEach(l => l()); }, 100);
}
export function useStore() {
  const [, set] = useState(0);
  useEffect(() => { const l = () => set(x => x + 1); listeners.add(l); return () => { listeners.delete(l); }; }, []);
  return S;
}
export const isOperator = () => !S.relay || S.relay.role === 'operator';
export const armed = () => S.tel?.mode === 'MANUAL' || S.tel?.mode === 'AUTO';
/** How old the phone's data is. Over 1.5 s the screen says it can't be trusted. */
export const staleMs = () => (S.telAt ? Date.now() - S.telAt : Infinity);
export const stale = () => staleMs() > 1500;

let token = '';
let ws: WebSocket | null = null;
let videoWs: WebSocket | null = null;
let wantVideo = false;
let lastSeq = 0;
let retry: any = null;
const waiting = new Map<number, { resolve: (r: { accepted: boolean; message: string; code: string }) => void; label: string; quiet: boolean; timer: any }>();
let toastSeq = 0;

export const getToken = () => token;
export const authQuery = () => (S.relay ? '' : `?token=${encodeURIComponent(token)}`);

function readHash() {
  const h = new URLSearchParams(location.hash.slice(1));
  const fromUrl = h.get('token') || new URLSearchParams(location.search).get('token');
  const pair = h.get('pair');
  const page = h.get('page');
  if (location.hash || location.search.includes('token')) history.replaceState(null, '', location.pathname);
  try {
    if (fromUrl) localStorage.setItem('spx-token', fromUrl);
    token = fromUrl || localStorage.getItem('spx-token') || '';
  } catch { token = fromUrl || ''; }
  return { pair, page };
}

export async function pair(code: string) {
  S.needPair = 'Checking...';
  changed();
  try {
    const res = await fetch('/pair', { method: 'POST', body: JSON.stringify({ code }) });
    const body = await res.json();
    if (res.ok) {
      token = body.token;
      try { localStorage.setItem('spx-token', token); } catch { /* This browser just won't remember it. */ }
      S.needPair = '';
      connect();
    } else {
      S.needPair = res.status === 429 ? `Too many wrong codes. Try again in ${body.retryAfterSeconds} s, using the new code.` : 'Wrong code. Check the phone: it changes every few minutes.';
    }
  } catch {
    S.needPair = 'Could not reach the phone. Check that it is on and on this network.';
  }
  changed();
}

export const raw = (o: unknown) => { if (ws?.readyState === 1) ws.send(JSON.stringify(o)); };

function nextSeq() { lastSeq = Math.max(lastSeq + 1, Date.now() * 1000); return lastSeq; }

/** Sends a command and tracks it: sent, then accepted or refused with the phone's reason. */
export function cmd(name: string, args: Record<string, unknown> = {}, opts: { label?: string; quiet?: boolean } = {}) {
  const seq = nextSeq();
  raw({ type: 'cmd', seq, sentAt: Date.now() + S.offset, cmd: name, ...args });
  return new Promise<{ accepted: boolean; message: string; code: string }>(resolve => {
    const label = opts.label ?? name.replace(/_/g, ' ');
    const quiet = !!opts.quiet;
    const timer = setTimeout(() => {
      waiting.delete(seq);
      if (!quiet) addToast(label, 'lost', 'No answer from the phone');
      resolve({ accepted: false, code: 'lost', message: 'No answer from the phone' });
    }, 3000);
    waiting.set(seq, { resolve, label, quiet, timer });
    if (!quiet) addToast(label, 'sent', '', seq);
  });
}

export function addToast(label: string, state: Toast['state'], message: string, seq = -++toastSeq) {
  S.toasts = [...S.toasts.filter(t => t.seq !== seq).slice(-3), { seq, label, state, message }];
  if (state !== 'sent') setTimeout(() => { S.toasts = S.toasts.filter(t => t.seq !== seq); changed(); }, state === 'ok' ? 2000 : 6000);
  changed();
}

function raiseAlert(level: Alert['level'], text: string) {
  const now = Date.now();
  if (S.alerts.some(a => a.text === text && now - a.t < 10000)) return;
  S.alerts = [...S.alerts.slice(-20), { id: now + Math.random(), level, text, t: now }];
  if (level !== 'info') speak(level, text);
}

let muted = false;
export const setMuted = (m: boolean) => { muted = m; changed(); };
export const isMuted = () => muted;
let audio: AudioContext | null = null;
function speak(level: string, text: string) {
  if (muted) return;
  try {
    audio ??= new AudioContext();
    const o = audio.createOscillator();
    const g = audio.createGain();
    o.frequency.value = level === 'critical' ? 880 : 520;
    g.gain.value = 0.15;
    o.connect(g).connect(audio.destination);
    o.start();
    o.stop(audio.currentTime + (level === 'critical' ? 0.5 : 0.2));
    speechSynthesis?.speak(new SpeechSynthesisUtterance(text));
  } catch { /* Sound isn't available here. The banner still shows. */ }
}
export const ackAlert = (id: number) => { S.alerts = S.alerts.filter(a => a.id !== id); changed(); };

function eventAlert(e: LogEvent) {
  if (e.kind === 'failsafe' || e.kind === 'collision') raiseAlert('critical', e.message);
  else if (e.kind === 'trigger' && e.code.endsWith('failed')) raiseAlert('warning', e.message);
  else if (e.kind === 'alert') raiseAlert('warning', e.message);
  else if (e.kind === 'mission' && e.code === 'complete') raiseAlert('info', 'Mission complete');
}

function onMessage(m: any) {
  switch (m.type) {
    case 'hello':
      S.hello = m; S.offset = m.t - Date.now(); S.net = m.net; S.relayInfo = m.relay;
      if (m.params) S.params = m.params;
      S.config = m;
      if (m.assistant) S.assistantCfg = m.assistant;
      if (wantVideo) syncVideo();
      break;
    case 'config': S.config = { ...S.config, ...m }; break;
    case 'ops': S.ops = m; if (S.hello && m.caps) S.hello.caps = m.caps; break;
    case 'fc': S.fc = m; S.fcAt = Date.now(); break;
    case 'fcs': S.fcs = m; break;
    case 'params': S.params = m.list; if (m.history) S.paramHistory = m.history; break;
    case 'setup': S.setup = m; break;
    case 'device': S.device = m; break;
    case 'assistant': S.assistant = [...S.assistant, { role: m.role, text: m.text }]; break;
    case 'assistantIdle': S.assistantBusy = false; break;
    case 'assistantCfg': S.assistantCfg = m.cfg; break;
    case 'sensors': Object.assign(S.sensorValues, m.d); break;
    case 'sensorStats': S.sensorStats = m; break;
    case 'cfgAck': S.cfgMessage = m.ok ? '' : `${m.what}: ${m.message}`; if (!m.ok) addToast(String(m.what), 'refused', m.message); break;
    case 'vehicle': S.vehicleOnline = m.online; break;
    case 'pong': { const rtt = performance.now() - m.c; S.rtt = rtt; S.offset = m.t + rtt / 2 - Date.now(); pongListeners.forEach(l => l(rtt)); break; }
    case 'telemetry':
      (m.events || []).forEach((e: LogEvent) => { S.events = [...S.events.slice(-300), e]; if (S.telAt) eventAlert(e); });
      if (m.net) S.net = m.net;
      if (m.relay) S.relayInfo = m.relay;
      if (S.tel && m.data.t < S.tel.t) break; // A late packet must not rewind the display.
      S.tel = m.data; S.telAt = Date.now();
      break;
    case 'ack': {
      const w = waiting.get(m.seq);
      if (!w) break;
      waiting.delete(m.seq);
      clearTimeout(w.timer);
      if (!w.quiet) addToast(w.label, m.accepted ? 'ok' : 'refused', m.message, m.seq);
      w.resolve({ accepted: m.accepted, message: m.message, code: m.code });
      break;
    }
  }
  changed();
}

const pongListeners = new Set<(rtt: number) => void>();
export const onPong = (l: (rtt: number) => void) => { pongListeners.add(l); return () => { pongListeners.delete(l); }; };
export const ping = () => raw({ type: 'ping', c: performance.now(), w: Date.now() });

function connect() {
  clearTimeout(retry);
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const q = S.relay ? '' : `?token=${encodeURIComponent(token)}`;
  const socket = new WebSocket(`${proto}://${location.host}/ws${q}`);
  ws = socket;
  socket.onopen = () => { S.link = true; ping(); changed(); };
  socket.onmessage = e => onMessage(JSON.parse(e.data));
  socket.onclose = e => {
    if (ws !== socket) return;
    S.link = false;
    S.sticks = { t: 0, s: 0, p: 0, r: 0 };
    changed();
    if (e.code === 1008) { // The phone or relay refused these credentials.
      if (S.relay) { location.href = '/login'; return; }
      token = '';
      try { localStorage.removeItem('spx-token'); } catch { /* Nothing to forget. */ }
      S.needPair = 'This browser needs pairing again. Enter the code on the phone.';
      changed();
      return;
    }
    retry = setTimeout(connect, 1000);
  };
  socket.onerror = () => socket.close();
}

export function setVideoWanted(on: boolean) { wantVideo = on; syncVideo(); }
function syncVideo() {
  const should = wantVideo && !!S.config?.camera?.enabled;
  if (should && !videoWs && S.link) openVideo();
  if (!should && videoWs) { const v = videoWs; videoWs = null; v.close(); S.video.live = false; changed(); }
}
function openVideo() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const q = S.relay ? '' : `?token=${encodeURIComponent(token)}`;
  const w = new WebSocket(`${proto}://${location.host}/video${q}`);
  w.binaryType = 'arraybuffer';
  videoWs = w;
  let frames = 0;
  let windowAt = Date.now();
  w.onmessage = e => {
    if (typeof e.data === 'string') return;
    const view = new DataView(e.data);
    const capturedAt = Number(view.getBigInt64(0));
    const url = URL.createObjectURL(new Blob([new Uint8Array(e.data, 9)], { type: 'image/jpeg' }));
    const old = S.video.url;
    S.video.url = url; S.video.rotate = view.getUint8(8) * 90; S.video.ageMs = Date.now() + S.offset - capturedAt; S.video.live = true;
    if (old) setTimeout(() => URL.revokeObjectURL(old), 500);
    frames++;
    if (Date.now() - windowAt > 2000) { S.video.fps = frames * 1000 / (Date.now() - windowAt); frames = 0; windowAt = Date.now(); }
    // Directly on the phone there's no relay, so this browser tells it to send the next frame.
    if (!S.relay && w.readyState === 1) w.send('a');
    changed();
  };
  w.onclose = () => { if (videoWs !== w) return; videoWs = null; S.video.live = false; setTimeout(syncVideo, 1000); changed(); };
  w.onerror = () => w.close();
}

// --- Control: a steady stream of stick values while a person is driving.

const keys = new Set<string>();
const typing = () => { const a = document.activeElement?.tagName; return a === 'INPUT' || a === 'TEXTAREA' || a === 'SELECT'; };
export const pointerSticks = { active: false, t: 0, s: 0, p: 0, r: 0 };
let beat = 0;
let padStopWas = false;

function keyAxis(plus: string[], minus: string[]) {
  return (plus.some(k => keys.has(k)) ? 1 : 0) - (minus.some(k => keys.has(k)) ? 1 : 0);
}

function readGamepad() {
  const pad = [...(navigator.getGamepads?.() ?? [])].find(Boolean);
  if (!pad) return null;
  const stop = !!pad.buttons[1]?.pressed;
  if (stop && !padStopWas) cmd('stop', {}, { label: 'Stop' });
  padStopWas = stop;
  const dz = (v = 0) => (Math.abs(v) < 0.08 ? 0 : v);
  return { t: -dz(pad.axes[1]), s: -dz(pad.axes[0]), p: -dz(pad.axes[3]), r: -dz(pad.axes[2]) };
}

function controlTick() {
  if (++beat % 20 === 0) ping();
  const profile = S.hello?.profiles?.find((p: any) => p.id === S.tel?.profile);
  if (!S.tel || !isOperator() || !profile) return;
  const hiddenLong = S.hidden && Date.now() - S.hiddenSince > 10000;
  const pad = readGamepad();
  let t = keyAxis(['KeyW', 'ArrowUp'], ['KeyS', 'ArrowDown']);
  let s = keyAxis(['KeyA', 'ArrowLeft'], ['KeyD', 'ArrowRight']);
  let p = keyAxis(['KeyI'], ['KeyK']);
  let r = keyAxis(['KeyJ'], ['KeyL']);
  if (pointerSticks.active) { t = pointerSticks.t; s = pointerSticks.s; p = pointerSticks.p; r = pointerSticks.r; }
  else if (pad && !t && !s) { t = pad.t; s = pad.s; p = pad.p; r = pad.r; }
  // A hidden tab can't be watching the vehicle, so after a moment it stops counting as a person.
  if (S.hidden) { t = 0; s = 0; p = 0; r = 0; }
  S.sticks = { t, s, p, r };
  const moved = !!(t || s || p || r);
  // In a mission, untouched sticks must say nothing, as any drive command is a person taking over.
  const send = !hiddenLong && !S.ops?.diag && (S.tel.mode === 'MANUAL' || (S.tel.mode === 'AUTO' && moved));
  if (send) cmd('drive', { throttle: t * profile.maxThrottle, steering: s * profile.maxSteering, pitch: p, roll: r }, { quiet: true });
}

export function start() {
  const { pair: code, page } = readHash();
  addEventListener('keydown', e => {
    if (typing() || e.repeat) return;
    if (e.code === 'Space') { e.preventDefault(); cmd('stop', {}, { label: 'Stop' }); return; }
    if (/^Digit[1-9]$/.test(e.code) && isOperator()) {
      // Number keys fire the matching trigger button. A dangerous one still needs its two steps on screen.
      const t = S.ops?.triggers[Number(e.code.slice(5)) - 1];
      if (t && !t.danger) cmd('trigger_fire', { id: t.id }, { label: t.label });
      return;
    }
    keys.add(e.code);
    if (e.code.startsWith('Arrow')) e.preventDefault();
  });
  addEventListener('keyup', e => keys.delete(e.code));
  addEventListener('blur', () => keys.clear());
  document.addEventListener('visibilitychange', () => {
    S.hidden = document.hidden;
    S.hiddenSince = Date.now();
    if (document.hidden) keys.clear();
    changed();
  });
  S.hidden = document.hidden;
  const ticker = new Worker(new URL('./ticker.ts', import.meta.url), { type: 'module' });
  ticker.onmessage = controlTick;
  setInterval(changed, 500); // Ages keep counting up even if nothing arrives.
  (async () => {
    try {
      const res = await fetch('/session');
      if (res.ok) S.relay = await res.json();
    } catch { /* Not on a relay. */ }
    if (S.relay || token) connect();
    else if (code) pair(code);
    else S.needPair = 'Enter the code shown on the phone.';
    changed();
  })();
  return page;
}
