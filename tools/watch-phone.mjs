// Watches the phone read-only and prints one line per thing worth knowing. Sends no commands.
//   node tools/watch-phone.mjs <host:port> <token> <adb-path>
import { execFile } from 'node:child_process';

const [host, token, adb] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const say = s => console.log(`${new Date().toLocaleTimeString()} ${s}`);
const INTERESTING = new Set(['mode', 'failsafe', 'collision', 'obstacle', 'link', 'pair', 'network', 'relay', 'fault', 'mission', 'system', 'sim']);

function parseCsvLine(line) {
  const out = [];
  let cur = '', quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (c === '"') quoted = false; else cur += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { out.push(cur); cur = ''; } else cur += c;
  }
  out.push(cur);
  return out;
}

const adbDevices = () => new Promise(resolve => {
  if (!adb) return resolve('');
  execFile(adb, ['devices'], { timeout: 5000 }, (err, stdout) => resolve(err ? '' : stdout.split('\n').slice(1).map(s => s.trim()).filter(Boolean).join('; ')));
});

let reachable = null, lastId = null, lastAdb = null, ticks = 0, lastStatus = '';
say(`watching ${host}`);
for (;;) {
  try {
    const res = await fetch(`http://${host}/export/events.csv?token=${token}`, { signal: AbortSignal.timeout(4000) });
    if (reachable !== true) { say(`PHONE REACHABLE at ${host} (HTTP ${res.status})`); reachable = true; }
    if (res.status === 401) say('token refused: the phone has a different token');
    if (res.ok) {
      const rows = (await res.text()).trim().split('\n').slice(1).map(parseCsvLine);
      if (lastId === null) lastId = rows.length ? Number(rows[Math.max(0, rows.length - 6)][0]) - 1 : 0;
      const fresh = rows.filter(r => Number(r[0]) > lastId);
      if (fresh.length) lastId = Number(fresh.at(-1)[0]);
      const rejected = fresh.filter(r => r[2] === 'rejected');
      for (const r of fresh) if (INTERESTING.has(r[2])) say(`${r[2]}/${r[4]} ${r[5]}`);
      if (rejected.length) say(`${rejected.length} command(s) rejected, last: ${rejected.at(-1)[4]} ${rejected.at(-1)[5].slice(0, 90)}`);
    }
    if (ticks++ % 15 === 0) {
      const s = await fetch(`http://${host}/mcp`, {
        method: 'POST', signal: AbortSignal.timeout(4000),
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'vehicle_get_status', arguments: {} } }),
      });
      if (s.ok) {
        const st = JSON.parse((await s.json()).result.content[0].text);
        const line = `mode ${st.mode}${st.failsafeReason ? ` (${st.failsafeReason})` : ''}, loop ${st.loopHz < 45 ? 'LOW ' : ''}${Math.round(st.loopHz)} Hz`;
        if (line !== lastStatus) { say(line); lastStatus = line; }
      }
    }
  } catch {
    if (reachable !== false) { say(`PHONE NOT REACHABLE at ${host}`); reachable = false; }
  }
  const d = await adbDevices();
  if (d !== lastAdb) { say(`adb: ${d || 'no device'}`); lastAdb = d; }
  await sleep(2000);
}
