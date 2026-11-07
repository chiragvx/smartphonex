// Shared by the relay acceptance test and the measurement tool.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';

export const sleep = ms => new Promise(r => setTimeout(r, ms));
export const pct = (values, p) => {
  if (!values.length) return null;
  const s = [...values].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * p))];
};

export async function login(base, user, password) {
  const res = await fetch(`${base}/login`, {
    method: 'POST', redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded', origin: base },
    body: new URLSearchParams({ user, password }),
  });
  if (res.status !== 303) throw new Error(`login failed: ${res.status}`);
  return res.headers.get('set-cookie').split(';')[0];
}

/** A ground-station connection through the relay, which keeps timing records. */
export class RemoteLink {
  constructor(base, cookie) {
    this.url = base.replace(/^http/, 'ws') + '/ws';
    this.base = base;
    this.cookie = cookie;
    this.tel = null;
    this.net = null;
    this.relay = null;
    this.offset = 0;
    this.acks = new Map();
    this.rtts = [];
    this.telGaps = [];
    this.telAges = [];
    this.uplinks = [];
    this.procUs = [];
    this.telCount = 0;
    this.lastTelAt = 0;
    this.vehicleOnline = null;
    this.outages = [];
    this.linkDrops = 0;
    this.events = [];
    this.lastProbe = null;
    this.seq = 0;
    this.sentAt = new Map();
    this.pingsSent = 0;
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url, { headers: { cookie: this.cookie, origin: this.base } });
      this.ws.on('message', raw => this.on(JSON.parse(raw.toString())));
      this.ws.on('error', e => { if (!this.isOpen) reject(e); });
      this.ws.on('close', () => { this.isOpen = false; this.linkDrops++; this.onDrop?.(); });
      this.ws.on('open', () => { this.isOpen = true; resolve(this); });
    });
  }

  on(m) {
    const now = Date.now();
    switch (m.type) {
      case 'hello': this.hello = m; this.offset = m.t - now; if (m.net) this.net = m.net; break;
      case 'vehicle':
        if (this.vehicleOnline === true && !m.online) this.offlineAt = now;
        if (this.vehicleOnline === false && m.online && this.offlineAt) this.outages.push(now - this.offlineAt);
        this.vehicleOnline = m.online;
        break;
      case 'pong': {
        const rtt = performance.now() - m.c;
        this.rtts.push(rtt);
        this.offset = m.t + rtt / 2 - now;
        break;
      }
      case 'telemetry':
        if (m.net) this.net = m.net;
        if (m.relay) this.relay = m.relay;
        (m.events || []).forEach(e => this.events.push(e));
        if (this.tel && m.data.t < this.tel.t) break;
        if (this.lastTelAt) this.telGaps.push(now - this.lastTelAt);
        this.lastTelAt = now;
        this.telAges.push(now + this.offset - m.data.t);
        this.tel = m.data;
        this.telCount++;
        break;
      case 'ack': {
        this.acks.set(m.seq, m);
        const sent = this.sentAt.get(m.seq);
        if (sent !== undefined && m.recvAt) {
          this.uplinks.push(m.recvAt - sent);
          this.procUs.push(m.procUs);
          this.sentAt.delete(m.seq);
        }
        break;
      }
      case 'probe': this.lastProbe = m; break;
    }
  }

  ping() {
    if (this.ws.readyState === 1) { this.pingsSent++; this.ws.send(JSON.stringify({ type: 'ping', c: performance.now() })); }
  }
  raw(o) { if (this.ws.readyState === 1) this.ws.send(typeof o === 'string' ? o : JSON.stringify(o)); }
  nextSeq() { this.seq = Math.max(this.seq + 1, Date.now() * 1000); return this.seq; }
  now() { return Date.now() + this.offset; }
  fire(cmd, args = {}, over = {}) {
    const seq = over.seq ?? this.nextSeq();
    const sentAt = over.sentAt ?? this.now();
    this.acks.delete(seq);
    if (over.seq === undefined && over.sentAt === undefined) this.sentAt.set(seq, sentAt);
    this.raw({ type: 'cmd', seq, sentAt, cmd, ...args });
    return seq;
  }
  async send(cmd, args = {}, over = {}, waitMs = 8000) {
    const seq = this.fire(cmd, args, over);
    const end = Date.now() + waitMs;
    while (Date.now() < end) {
      if (this.acks.has(seq)) return this.acks.get(seq);
      await sleep(10);
    }
    return { accepted: false, code: 'no_ack' };
  }
  fault(fault, value) { this.raw({ type: 'fault', fault, value }); }
  close() { this.ws.close(); }
}

export class Mcp {
  constructor(base, key) { this.base = base; this.key = key; this.id = 0; this.durations = []; this.relayMs = []; }
  async rpc(method, params) {
    const started = performance.now();
    const res = await fetch(`${this.base}/mcp`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.key}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
      body: JSON.stringify({ jsonrpc: '2.0', id: ++this.id, method, params }),
    });
    const text = await res.text();
    this.durations.push(performance.now() - started);
    const v = Number(res.headers.get('x-spx-vehicle-ms'));
    if (v) this.relayMs.push(v);
    if (res.status !== 200) return { httpStatus: res.status };
    return JSON.parse(text);
  }
  async tool(name, args = {}) {
    const r = await this.rpc('tools/call', { name, arguments: args });
    if (r.httpStatus) return r;
    return { ...JSON.parse(r.result.content[0].text), isError: r.result.isError };
  }
}
