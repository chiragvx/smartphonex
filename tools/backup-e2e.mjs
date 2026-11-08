// Backup and restore on a running phone: download, change a setting, restore, and check the setting came back.
//   node tools/backup-e2e.mjs <host:port> <token>
// Leaves the phone on the profile it found.
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';
import { crc32 } from 'node:zlib';

const [host, token] = process.argv.slice(2);
const base = `http://${host}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => { ok ? passed++ : failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };

async function link() {
  const ws = new WebSocket(`ws://${host}/ws?token=${token}`);
  const s = { ws, hello: null, tel: null, acks: new Map(), seq: 0, offset: 0 };
  ws.on('message', raw => {
    const m = JSON.parse(raw.toString());
    if (m.type === 'hello') { s.hello = m; s.offset = m.t - Date.now(); }
    if (m.type === 'telemetry') s.tel = m.data;
    if (m.type === 'ack') s.acks.set(m.seq, m);
  });
  ws.on('error', () => {});
  await new Promise((ok, no) => { ws.on('open', ok); ws.on('close', no); });
  for (let i = 0; i < 100 && !s.hello; i++) await sleep(50);
  s.cmd = async (name, args = {}) => {
    s.seq = Math.max(s.seq + 1, Date.now() * 1000);
    const q = s.seq;
    ws.send(JSON.stringify({ type: 'cmd', seq: q, sentAt: Date.now() + s.offset, cmd: name, ...args }));
    for (let i = 0; i < 300 && !s.acks.has(q); i++) await sleep(10);
    return s.acks.get(q) ?? { accepted: false, code: 'no_ack' };
  };
  return s;
}

/** A stored (uncompressed) zip, enough to send hand-made entries. */
function zip(entries) {
  const local = [], central = [];
  let off = 0;
  for (const [name, text] of entries) {
    const n = Buffer.from(name), d = Buffer.from(text), c = crc32(d);
    const h = Buffer.alloc(30); h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt32LE(c, 14); h.writeUInt32LE(d.length, 18); h.writeUInt32LE(d.length, 22); h.writeUInt16LE(n.length, 26);
    local.push(h, n, d);
    const e = Buffer.alloc(46); e.writeUInt32LE(0x02014b50, 0); e.writeUInt16LE(20, 4); e.writeUInt16LE(20, 6); e.writeUInt32LE(c, 16); e.writeUInt32LE(d.length, 20); e.writeUInt32LE(d.length, 24); e.writeUInt16LE(n.length, 28); e.writeUInt32LE(off, 42);
    central.push(e, n);
    off += 30 + n.length + d.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...local, cd, end]);
}

const restore = body => fetch(`${base}/backup/restore`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/zip' }, body });

let s = await link();
const original = s.hello.activeProfile;
await s.cmd('stop'); await s.cmd('set_mode', { mode: 'DISARMED' });
const saved = original === 'boat' ? 'car' : 'boat';
const other = saved === 'boat' ? 'plane' : 'boat';
await s.cmd('select_profile', { id: saved });
await sleep(800);

const noAuth = await fetch(`${base}/backup`);
check('Backup needs the token', noAuth.status === 401, noAuth.status);
const res = await fetch(`${base}/backup?token=${token}`);
const file = Buffer.from(await res.arrayBuffer());
check('Backup downloads', res.ok && file.readUInt32LE(0) === 0x04034b50, `${res.status}, ${(file.length / 1024).toFixed(1)} KB`);
check('Named as an attachment', /attachment; filename="smartphonex-\d{8}-\d{4}\.zip"/.test(res.headers.get('content-disposition') ?? ''), res.headers.get('content-disposition'));
const names = file.toString('latin1');
check('Has the manifest and settings', names.includes('smartphonex-backup.json') && names.includes('profiles.json'));
check('Leaves secrets out', !names.includes('token.txt') && !names.includes('llm-key') && !names.includes('relay.json'));
check('Leaves media out unless asked', !/photos\/|video\//.test(names));
const withMedia = await fetch(`${base}/backup?token=${token}&media=1`);
check('Backup with media downloads', withMedia.ok, `${((await withMedia.arrayBuffer()).byteLength / 1024).toFixed(1)} KB`);

const m = JSON.stringify({ format: 'smartphonex.backup/1', dataVersion: 1, app: 'test', created: 1, media: false });
const unauth = await fetch(`${base}/backup/restore`, { method: 'POST', body: file });
check('Restore needs the token', unauth.status === 401, unauth.status);
for (const [label, bad] of [['../evil', zip([['smartphonex-backup.json', m], ['../evil', 'x']])], ['token.txt', zip([['smartphonex-backup.json', m], ['token.txt', 'stolen']])],
  ['no manifest', zip([['profiles.json', '{}']])], ['newer data', zip([['smartphonex-backup.json', m.replace('"dataVersion":1', '"dataVersion":99')]])], ['not a zip', Buffer.from('hello')]]) {
  const r = await restore(bad);
  const b = await r.json().catch(() => ({}));
  check(`Refuses ${label}`, r.status === 400 && b.ok === false, `${r.status} ${b.error ?? ''}`);
}

const arm = await s.cmd('set_mode', { mode: 'MANUAL' });
if (arm.accepted) {
  const r = await restore(file);
  check('Refused while armed', r.status === 409, r.status);
  await s.cmd('stop'); await s.cmd('set_mode', { mode: 'DISARMED' });
  await sleep(300);
} else console.log(`SKIP  Refused while armed  [couldn't arm here: ${arm.code ?? arm.reason}]`);

await s.cmd('select_profile', { id: other });
await sleep(800);
s.ws.close();
s = await link();
check('Setting changed before restore', s.hello.activeProfile === other, s.hello.activeProfile);
s.ws.close();

const t0 = Date.now();
const r = await restore(file);
const body = await r.json().catch(() => ({}));
check('Restore accepted', r.ok && body.ok === true && body.restarting === true, JSON.stringify(body));

// The service rebuilds itself a moment after replying. See it go away first, then wait for it on the same token.
let down = false;
for (let i = 0; i < 40 && !down; i++) {
  try { const x = await link(); x.ws.close(); await sleep(100); } catch { down = true; }
}
check('Service restarts to apply it', down);
let back = null;
for (let i = 0; i < 60 && !back; i++) {
  await sleep(500);
  try { const x = await link(); if (x.hello) back = x; else x.ws.close(); } catch { /* still restarting */ }
}
check('App comes back after restoring', !!back, `${((Date.now() - t0) / 1000).toFixed(1)} s`);
if (back) {
  check('Setting is back to the backup', back.hello.activeProfile === saved, back.hello.activeProfile);
  check('Same token still works', true);
  const again = await fetch(`${base}/backup?token=${token}`);
  check('Server works after restart', again.ok, again.status);
  await back.cmd('select_profile', { id: original });
  await sleep(800);
  back.ws.close();
}

console.log(`\n${failed ? `FAILED (${failed} of ${passed + failed})` : `ALL PASSED (${passed})`}`);
process.exit(failed ? 1 : 0);
