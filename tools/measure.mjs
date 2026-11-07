// One Phase 2 measurement run through the relay. Adds a row to results/phase2.csv and saves details.
//   node tools/measure.mjs <relay-url> <user> <password> <ai-key> --label "5G strong" [--secs 180] [--drive] [--probe]
// --drive holds manual control at zero throttle, which counts false failsafes: the main remote risk.
// Network labels come from the phone itself. The label says what you were doing during the run.
import { appendFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { login, Mcp, pct, RemoteLink, sleep } from './remote-lib.mjs';

const argv = process.argv.slice(2);
const flag = n => argv.includes(`--${n}`);
const opt = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 ? argv[i + 1] : d; };
const [base, user, password, aiKey] = argv;
const label = opt('label', 'unlabelled');
const secs = Number(opt('secs', 180));
const drive = flag('drive');
if (!aiKey || aiKey.startsWith('--')) { console.error('usage: see the top of tools/measure.mjs'); process.exit(2); }

const cookie = await login(base, user, password);
const l = new RemoteLink(base, cookie);
const mcp = new Mcp(base, aiKey);
const reconnects = [];
await l.open();
l.onDrop = () => reconnect();
let closing = false;
async function reconnect() {
  if (closing) return;
  const down = Date.now();
  for (;;) {
    await sleep(500);
    try { await l.open(); reconnects.push(Date.now() - down); return; } catch { /* Keep trying until the run ends. */ }
    if (closing) return;
  }
}

await sleep(1500);
const startNet = l.net;
const startBytes = l.net ? { tx: l.net.txBytes, rx: l.net.rxBytes } : null;
const startedAt = Date.now();
const startOverruns = l.tel?.loopOverruns ?? 0;
const firstEvent = l.events.length;
let failsafes = 0, lastMode = l.tel?.mode, stale = 0, duplicates = 0, mcpFails = 0, mcpCalls = 0, wrong = 0;
const hz = [], dts = [], radios = new Set();
if (startNet) radios.add(startNet.radio || startNet.transport);

if (drive) {
  const r = await l.send('set_mode', { mode: 'MANUAL' });
  if (!r.accepted) console.log('could not arm manual:', r.code);
}

const pinger = setInterval(() => l.ping(), 200);
const stats = setInterval(() => {
  if (!l.tel) return;
  hz.push(l.tel.loopHz); dts.push(l.tel.loopDtMaxMs);
  if (l.net) radios.add(l.net.radio || l.net.transport);
  if (l.tel.mode !== lastMode) {
    if (l.tel.mode === 'FAILSAFE') {
      failsafes++;
      console.log(`  ${new Date().toLocaleTimeString()} failsafe: ${l.tel.failsafeReason}`);
      // Re-arm so the rest of the run can still count further trips.
      if (drive) l.fire('set_mode', { mode: 'MANUAL' });
    }
    lastMode = l.tel.mode;
  }
}, 500);
const driver = drive ? setInterval(() => { if (l.tel?.mode === 'MANUAL') l.fire('drive', { throttle: 0, steering: 0 }); }, 50) : null;
const acks = setInterval(() => {
  for (const a of l.acks.values()) { if (a.code === 'stale') stale++; if (a.code === 'duplicate') duplicates++; else if (!a.accepted && a.code !== 'stale') wrong++; }
  l.acks.clear();
}, 1000);
const mcpPoll = setInterval(async () => {
  mcpCalls++;
  try { if ((await mcp.tool('vehicle_get_status')).httpStatus) mcpFails++; } catch { mcpFails++; }
}, 2000);
let probe = null;

console.log(`Running "${label}" for ${secs} s${drive ? ' with manual control held' : ''}. Started on ${[...radios].join(', ') || 'unknown network'}.`);
await sleep(secs * 1000);

clearInterval(pinger); clearInterval(stats); clearInterval(acks); clearInterval(mcpPoll);
if (driver) clearInterval(driver);
if (drive) await l.send('stop');

const endNet = l.net;
const endBytes = endNet ? { tx: endNet.txBytes, rx: endNet.rxBytes } : null;
const elapsed = (Date.now() - startedAt) / 1000;
const rate = (a, b) => (a !== null && b !== null && b >= a ? (b - a) / elapsed / 1024 : null);
if (flag('probe')) {
  l.raw({ type: 'probe' });
  const t = Date.now();
  while (!l.lastProbe && Date.now() - t < 25000) await sleep(200);
  probe = l.lastProbe;
}

const f = (v, d = 0) => (v === null || v === undefined || Number.isNaN(v) ? '' : Number(v).toFixed(d));
const jitter = l.rtts.length > 1 ? l.rtts.slice(1).reduce((s, v, i) => s + Math.abs(v - l.rtts[i]), 0) / (l.rtts.length - 1) : null;
const netEvents = l.events.slice(firstEvent).filter(e => e.kind === 'network').map(e => e.message);
const row = {
  when: new Date().toISOString(), label, secs: Math.round(elapsed), drive: drive ? 'yes' : 'no',
  networks: [...radios].join(' > '),
  signal: endNet?.signal ?? '',
  rtt_p50_ms: f(pct(l.rtts, .5)), rtt_p95_ms: f(pct(l.rtts, .95)), rtt_p99_ms: f(pct(l.rtts, .99)), rtt_max_ms: f(Math.max(...l.rtts, 0)),
  jitter_ms: f(jitter, 1),
  pings_unanswered_pct: f(l.pingsSent ? (1 - l.rtts.length / l.pingsSent) * 100 : null, 1),
  uplink_p50_ms: f(pct(l.uplinks, .5)), uplink_p95_ms: f(pct(l.uplinks, .95)),
  phone_proc_p50_us: f(pct(l.procUs, .5)),
  telemetry_hz: f(l.telCount / elapsed, 1), telemetry_age_p50_ms: f(pct(l.telAges, .5)), telemetry_age_p95_ms: f(pct(l.telAges, .95)), telemetry_gap_max_ms: f(Math.max(...l.telGaps, 0)),
  mcp_p50_ms: f(pct(mcp.durations, .5)), mcp_p95_ms: f(pct(mcp.durations, .95)), mcp_fail: `${mcpFails}/${mcpCalls}`,
  phone_kbps_up: f(rate(startBytes?.tx ?? null, endBytes?.tx ?? null) * 8, 0), phone_kbps_down: f(rate(startBytes?.rx ?? null, endBytes?.rx ?? null) * 8, 0),
  failsafes, phone_outages: l.outages.length, phone_outage_max_ms: f(Math.max(...l.outages, 0)), browser_drops: reconnects.length, browser_reconnect_max_ms: f(Math.max(...reconnects, 0)),
  stale_rejected: stale, duplicate_rejected: duplicates,
  loop_hz_min: f(Math.min(...hz), 1), loop_dt_max_ms: f(Math.max(...dts, 0), 1), loop_overruns: (l.tel?.loopOverruns ?? 0) - startOverruns,
  loss_pct_icmp: probe?.lossPct ?? '', icmp_rtt_avg_ms: probe?.rttAvgMs ?? '', icmp_jitter_ms: probe?.jitterMs ?? '',
};

closing = true;
const outDir = opt('results', 'results/cellular');
mkdirSync(outDir, { recursive: true });
const csv = `${outDir}/phase2.csv`;
const q = v => `"${String(v).replaceAll('"', '""')}"`;
if (!existsSync(csv)) appendFileSync(csv, Object.keys(row).join(',') + '\n');
appendFileSync(csv, Object.values(row).map(q).join(',') + '\n');
const safe = label.replace(/[^a-zA-Z0-9_-]+/g, '-').slice(0, 40);
writeFileSync(`${outDir}/${Date.now()}-${safe}.json`,JSON.stringify({ row, netEvents, outages: l.outages, reconnects, rtts: l.rtts.map(Math.round), telGapsOver500: l.telGaps.filter(g => g > 500) }, null, 1));

console.log(JSON.stringify(row, null, 1));
if (netEvents.length) console.log('Network changes:\n  ' + netEvents.join('\n  '));
l.close();
process.exit(0);
