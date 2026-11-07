// Measures control-loop timing and link quality in whatever state the phone is in right now.
// It does not change the phone's state, so the label must describe what you actually set up.
//   node tools/soak.mjs <token> <host:port> "<label>" [seconds] [--cpu]
const args = process.argv.slice(2);
const cpu = args.includes('--cpu');
const [token, host, label, secsArg] = args.filter(a => a !== '--cpu');
const secs = Number(secsArg || 60);
const sleep = ms => new Promise(r => setTimeout(r, ms));

let tel = null, telCount = 0, drops = 0;
const rtts = [];
const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.type === 'telemetry') { tel = m.data; telCount++; }
  if (m.type === 'pong') rtts.push(performance.now() - m.c);
};
ws.onclose = () => { drops++; console.log('LINK CLOSED'); };
await new Promise(r => { ws.onopen = r; });
setInterval(() => { if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'ping', c: performance.now() })); }, 200);
await sleep(1500);
if (cpu) ws.send(JSON.stringify({ type: 'fault', fault: 'cpu', value: Math.min(secs, 30) }));

const q = (a, p) => [...a].sort((x, y) => x - y)[Math.floor(a.length * p)] ?? NaN;
const hz = [], maxDt = [];
const startOverruns = tel.loopOverruns;
telCount = 0;
for (let i = 0; i < secs; i++) {
  await sleep(1000);
  hz.push(tel.loopHz); maxDt.push(tel.loopDtMaxMs);
}
console.log(`${label}: loop ${Math.min(...hz).toFixed(1)}-${Math.max(...hz).toFixed(1)} Hz | worst dt ${Math.max(...maxDt).toFixed(1)} ms | overruns +${tel.loopOverruns - startOverruns} | RTT p50 ${q(rtts, .5).toFixed(0)} p95 ${q(rtts, .95).toFixed(0)} max ${Math.max(...rtts).toFixed(0)} ms | telemetry ${(telCount / secs).toFixed(1)} Hz | link drops ${drops}`);
process.exit(0);
