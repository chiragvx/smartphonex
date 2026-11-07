// Puts a bad network between the phone and relay, for testing without a real one. Keeps TCP's
// ordering, so a delayed chunk holds up those behind it, like retransmission does on a real link.
//   node tools/lag-proxy.mjs <listen-port> <target-port> <control-port>
//   POST http://127.0.0.1:<control-port>/set?min=30&max=80&spike=300&spikePct=1
//   POST http://127.0.0.1:<control-port>/stall?ms=20000    (data vanishes, connections stay open)
import { connect, createServer } from 'node:net';
import { createServer as createHttp } from 'node:http';

const [listenPort, targetPort, controlPort] = process.argv.slice(2).map(Number);
const cfg = { min: 0, max: 0, spike: 0, spikePct: 0 };
let stallUntil = 0;

function delayer(dest) {
  let releaseAt = 0;
  return chunk => {
    let d = cfg.min + Math.random() * (cfg.max - cfg.min);
    if (Math.random() * 100 < cfg.spikePct) d += cfg.spike;
    const now = Date.now();
    releaseAt = Math.max(releaseAt, now + d, stallUntil);
    setTimeout(() => { if (!dest.destroyed) dest.write(chunk); }, releaseAt - now);
  };
}

createServer(client => {
  const server = connect(targetPort, '127.0.0.1');
  client.setNoDelay(true);
  server.setNoDelay(true);
  client.on('data', delayer(server));
  server.on('data', delayer(client));
  const end = () => { client.destroy(); server.destroy(); };
  for (const s of [client, server]) { s.on('close', end); s.on('error', end); }
}).listen(listenPort, '127.0.0.1');

createHttp((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/set') for (const k of Object.keys(cfg)) if (u.searchParams.has(k)) cfg[k] = Number(u.searchParams.get(k));
  if (u.pathname === '/stall') stallUntil = Date.now() + Number(u.searchParams.get('ms'));
  res.end(JSON.stringify(cfg));
}).listen(controlPort, '127.0.0.1');
console.log(`proxy :${listenPort} -> :${targetPort}, control :${controlPort}`);
