// Stress test on a running phone over USB: adds load in stages and measures whether control keeps its deadlines.
//   node tools/stress.mjs <host:port> <token> <adb> <serial> [seconds-per-stage] [--out file.json]
// Stages: idle; armed and driven at 20 Hz; + camera streaming; + 4 more ground stations and AI polling at 10 Hz;
// + every CPU core on the phone kept busy by other processes; + screen off. Load is added, never removed, until the end.
// What matters most: no false failsafe, and the loop's worst step staying well under the 500 ms command timeout.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const argv = process.argv.slice(2);
const outIdx = argv.indexOf('--out');
const outFile = outIdx >= 0 ? argv.splice(outIdx, 2)[1] : null;
const [host, token, adb, serial, secsArg] = argv;
const SECS = Number(secsArg || 40);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const sh = (...a) => { try { return execFileSync(adb, ['-s', serial, 'shell', ...a], { encoding: 'utf8', timeout: 8000 }); } catch { return ''; } };
// Reading the phone's state takes up to a second. It must not block, or the drive stream stops and the phone
// rightly fails safe: an early version of this test did exactly that.
const shAsync = (...a) => new Promise(res => execFile(adb, ['-s', serial, 'shell', ...a], { encoding: 'utf8', timeout: 8000 }, (e, out) => res(e ? '' : out)));
const q = (a, p) => a.length ? [...a].sort((x, y) => x - y)[Math.min(a.length - 1, Math.floor(a.length * p))] : NaN;
const r1 = v => Number.isFinite(v) ? Math.round(v * 10) / 10 : null;

function link() {
  const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
  const s = { ws, tel: null, fcs: null, offset: 0, seq: 0, acks: new Map(), telAt: [], modes: [] };
  ws.on('message', raw => {
    const m = JSON.parse(raw.toString());
    if (m.type === 'hello') s.offset = m.t - Date.now();
    if (m.type === 'telemetry') { s.tel = m.data; s.telAt.push(performance.now()); s.modes.push(m.data.mode); }
    if (m.type === 'fcs') s.fcs = m;
    if (m.type === 'ack') s.acks.set(m.seq, { ...m, at: performance.now() });
    if (m.type === 'pong') s.rtt?.push(performance.now() - m.c);
  });
  ws.on('error', () => {});
  s.open = new Promise((ok, no) => { ws.on('open', ok); ws.on('close', no); });
  s.send = (cmd, args = {}) => {
    s.seq = Math.max(s.seq + 1, Date.now() * 1000);
    const seq = s.seq;
    ws.send(JSON.stringify({ type: 'cmd', seq, sentAt: Date.now() + s.offset, cmd, ...args }));
    return seq;
  };
  s.ask = async (cmd, args) => { const seq = s.send(cmd, args); for (let i = 0; i < 300 && !s.acks.has(seq); i++) await sleep(10); return s.acks.get(seq) ?? { accepted: false, code: 'no_ack' }; };
  return s;
}

const pid = async () => (await shAsync('pidof', 'com.smartphonex.app')).trim().split(/\s+/)[0];
/** App CPU (% of one core), Android's thermal status, battery temperature, and the fastest core's clock. */
async function phoneState() {
  const p = await pid();
  const top = p ? await shAsync('top', '-b', '-n', '1', '-p', p) : '';
  const cpuLine = top.split('\n').find(l => l.trim().startsWith(p));
  const cols = cpuLine?.trim().split(/\s+/) ?? [];
  const cpu = Number(cols[8]);
  const thermal = Number(/Thermal Status: (\d)/.exec(await shAsync('dumpsys', 'thermalservice'))?.[1]);
  const batt = Number(/temperature: (\d+)/.exec(await shAsync('dumpsys', 'battery'))?.[1]) / 10;
  const freqs = (await shAsync('cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null')).trim().split(/\s+/).map(Number);
  return { cpu: Number.isFinite(cpu) ? cpu : null, thermal: Number.isFinite(thermal) ? thermal : null, battC: r1(batt), bigMHz: freqs[0] ? Math.round(freqs[0] / 1000) : null, littleMHz: freqs[1] ? Math.round(freqs[1] / 1000) : null };
}

const main = link();
await main.open;
main.rtt = [];
await sleep(1500);
await main.ask('stop'); await main.ask('set_mode', { mode: 'DISARMED' });
const startProfile = main.tel?.profile;
// The simulator, so nothing physical moves.
await main.ask('select_profile', { id: 'sim-buggy' });
await sleep(800);

const results = [];
let driving = false, video = null, frames = 0, extra = [], mcpTimer = null, hogs = null;
setInterval(() => { if (main.ws.readyState === 1) main.ws.send(JSON.stringify({ type: 'ping', c: performance.now() })); }, 100);

async function stage(name, setup) {
  await setup?.();
  await sleep(3000); // Settle.
  main.rtt = []; main.telAt = []; main.modes = []; frames = 0;
  const loopMax = [], loopHz = [], fcDelay = [], fcTask = [], phone = [];
  const over0 = main.tel?.loopOverruns ?? 0;
  for (let i = 0; i < SECS; i++) {
    await sleep(1000);
    loopMax.push(main.tel?.loopDtMaxMs); loopHz.push(main.tel?.loopHz);
    if (main.fcs?.timing) { fcDelay.push(main.fcs.timing.delayMaxMs); fcTask.push(main.fcs.timing.taskMaxUs); }
    if (i % 5 === 2) phoneState().then(v => phone.push(v));
  }
  const gaps = main.telAt.slice(1).map((t, i) => t - main.telAt[i]);
  const failsafes = main.modes.filter(m => m === 'FAILSAFE').length;
  const ph = k => phone.map(p => p[k]).filter(v => v != null);
  const row = {
    stage: name,
    loopHzMin: r1(Math.min(...loopHz)), loopWorstMsP50: r1(q(loopMax, 0.5)), loopWorstMsMax: r1(Math.max(...loopMax)),
    overruns: (main.tel?.loopOverruns ?? 0) - over0,
    fcDelayMaxMs: r1(Math.max(...fcDelay, NaN)), fcTaskMaxUs: fcTask.length ? Math.max(...fcTask) : null,
    rttP50: r1(q(main.rtt, 0.5)), rttP99: r1(q(main.rtt, 0.99)), rttMax: r1(Math.max(...main.rtt)),
    telHz: r1(main.telAt.length / SECS), telGapMaxMs: r1(Math.max(...gaps)),
    videoFps: video ? r1(frames / SECS) : null,
    failsafes, mode: main.tel?.mode,
    appCpu: r1(Math.max(...ph('cpu'))), thermal: Math.max(...ph('thermal'), 0), battC: Math.max(...ph('battC'), 0),
    bigMHz: q(ph('bigMHz'), 0.5) ?? null,
  };
  results.push(row);
  console.log(`${name.padEnd(22)} loop>=${row.loopHzMin} Hz, worst ${row.loopWorstMsP50}/${row.loopWorstMsMax} ms (typ/max), overruns ${row.overruns} | fc delay ${row.fcDelayMaxMs} ms | RTT ${row.rttP50}/${row.rttP99}/${row.rttMax} ms | tel ${row.telHz} Hz gap ${row.telGapMaxMs} ms | video ${row.videoFps ?? '-'} fps | failsafes ${failsafes} | app CPU ${row.appCpu}% thermal ${row.thermal} batt ${row.battC} C big ${row.bigMHz} MHz`);
}

try {
  await stage('1 idle');
  await stage('2 armed, driven 20 Hz', async () => {
    const a = await main.ask('set_mode', { mode: 'MANUAL' });
    if (!a.accepted) console.log(`      couldn't arm: ${a.message || a.code}`);
    driving = true;
    (async () => { let k = 0; while (driving) { main.send('drive', { throttle: 0.3 * Math.sin(k / 20), steering: 0.5 * Math.cos(k / 13) }); k++; await sleep(50); } })();
  });
  await stage('3 + camera stream', async () => {
    main.ws.send(JSON.stringify({ type: 'camera_cfg', enabled: true }));
    await sleep(1500);
    video = new WebSocket(`ws://${host}/video?token=${token}`);
    // Acknowledge each frame, as the ground station does: the phone sends the next one only after that.
    video.on('message', () => { frames++; if (video.readyState === 1) video.send('a'); });
    video.on('error', () => {});
  });
  await stage('4 + 4 clients, AI 10 Hz', async () => {
    for (let i = 0; i < 4; i++) { const l = link(); await l.open.catch(() => {}); extra.push(l); }
    mcpTimer = setInterval(() => {
      fetch(`http://${host}/mcp`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'vehicle_get_telemetry', arguments: {} } }) }).catch(() => {});
    }, 100);
  });
  await stage('5 + all cores busy', async () => {
    // Other apps hogging the phone: busy loops at normal priority on all but two cores, so adb stays reachable.
    // Each one ends by itself, even if this script or the USB link dies.
    const cores = Number(sh('nproc').trim()) || 8;
    const n = Math.max(1, cores - 2);
    hogs = spawn(adb, ['-s', serial, 'shell', `for i in $(seq 1 ${n}); do timeout ${SECS + 15} sh -c 'while :; do :; done' & done; wait`], { stdio: 'ignore' });
  });
  await stage('6 + screen off', async () => { sh('input', 'keyevent', 'KEYCODE_SLEEP'); });
} finally {
  driving = false;
  await main.ask('stop').catch(() => {}); await main.ask('set_mode', { mode: 'DISARMED' }).catch(() => {});
  sh('input', 'keyevent', 'KEYCODE_WAKEUP');
  hogs?.kill();
  // Belt and braces: each busy loop also has its own time limit.
  sh('pkill -f "while :"');
  clearInterval(mcpTimer);
  video?.close();
  main.ws.send(JSON.stringify({ type: 'camera_cfg', enabled: false }));
  for (const l of extra) l.ws.close();
  if (startProfile) await main.ask('select_profile', { id: startProfile }).catch(() => {});
  await sleep(500);
  if (outFile) writeFileSync(outFile, JSON.stringify({ at: new Date().toISOString(), secs: SECS, results }, null, 2));
  const bad = results.filter(r => r.failsafes > 0 || r.loopWorstMsMax > 100);
  console.log(`\n${bad.length ? `PROBLEMS in: ${bad.map(r => r.stage).join(', ')}` : 'No false failsafes, and the loop never stalled over 100 ms'}`);
  process.exit(0);
}
