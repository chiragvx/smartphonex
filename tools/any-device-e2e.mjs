// Any-device features on a running phone or emulator, using preview only: nothing real moves.
//   node tools/any-device-e2e.mjs <host:port> <token> [fake-llm-url-as-seen-by-phone fake-llm-url-from-here]
import WebSocket from '../relay/node_modules/ws/wrapper.mjs';

const [host, token, llmFromPhone, llmHere] = process.argv.slice(2);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => { ok ? passed++ : failed++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== '' ? `  [${detail}]` : ''}`); };

const st = { acks: new Map(), cfg: [], setup: null, device: null, tel: null, chat: [], idle: false, hello: null, cfgA: null };
let seq = 0, offset = 0;
let ws;
function connect() {
  return new Promise(resolve => {
    ws = new WebSocket(`ws://${host}/ws?token=${token}`);
    ws.on('message', raw => {
      const m = JSON.parse(raw.toString());
      if (m.type === 'hello') { st.hello = m; offset = m.t - Date.now(); resolve(); }
      if (m.type === 'ack') st.acks.set(m.seq, m);
      if (m.type === 'cfgAck') st.cfg.push(m);
      if (m.type === 'setup') st.setup = m;
      if (m.type === 'device') st.device = m;
      if (m.type === 'telemetry') st.tel = m.data;
      if (m.type === 'assistant') st.chat.push(m);
      if (m.type === 'assistantIdle') st.idle = true;
      if (m.type === 'assistantCfg') st.cfgA = m.cfg;
    });
  });
}
await connect();
const cmd = async (name, args = {}) => {
  seq = Math.max(seq + 1, Date.now() * 1000);
  const s = seq;
  ws.send(JSON.stringify({ type: 'cmd', seq: s, sentAt: Date.now() + offset, cmd: name, ...args }));
  for (let i = 0; i < 300 && !st.acks.has(s); i++) await sleep(10);
  return st.acks.get(s) ?? { accepted: false, code: 'no_ack' };
};
const tool = async (name, args = {}) => {
  const res = await fetch(`http://${host}/api/tools/${name}`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify(args) });
  const j = await res.json();
  return JSON.parse(j.content[0].text);
};
const out = name => st.tel.out[st.hello.profiles.find(p => p.id === st.tel.profile).channels.findIndex(c => c.name === name)];
const dev = id => st.device?.actuators?.find(a => a.id === id);
const approve = async () => {
  for (let i = 0; i < 50 && !st.setup?.pending?.length; i++) await sleep(50);
  for (const p of st.setup?.pending ?? []) ws.send(JSON.stringify({ type: 'setup_approve', id: p.id, allow: true }));
  await sleep(300);
};
const select = async id => {
  await cmd('stop');
  await cmd('set_mode', { mode: 'DISARMED' });
  const r = await cmd('select_profile', { id });
  ws.close();
  await sleep(200);
  await connect();
  await sleep(1200);
  return r;
};
const originalProfile = st.hello.activeProfile;

// --- HTTP tools
check('HTTP tools refuse a bad key', (await fetch(`http://${host}/api/tools`, { headers: { authorization: 'Bearer nope' } })).status === 401);
const list = await (await fetch(`http://${host}/api/tools`, { headers: { authorization: `Bearer ${token}` } })).json();
check('HTTP tools list setup and device tools', ['setup_status', 'setup_propose_device', 'device_run_skill', 'vehicle_stop'].every(n => list.some(t => t.name === n)), `${list.length} tools`);

// --- Shipped packs
const ids = st.hello.profiles.map(p => p.id);
for (const p of ['pan-tilt', 'rover-diff', 'small-arm', 'biped-12', 'hexapod']) check(`Example device available: ${p}`, ids.includes(`device:${p}`));

// --- The biped, in preview
check('Biped can be selected', (await select('device:biped-12')).accepted);
check('Twelve outputs reported', st.tel.out.length === 12, `${st.tel.out.length} outputs`);
check('Device panel data arrives', st.device?.actuators?.length === 12);
check('Device starts with hardware closed', st.hello.device?.signedOff === false && st.device.hardwareOpen === false);
check('Skills are listed', st.hello.device.skills.includes('walk'), st.hello.device.skills.join(', '));
check('A skill is refused while disarmed', (await cmd('run_skill', { id: 'stand' })).accepted === false);

await cmd('set_mode', { mode: 'MANUAL' });
let driving = true;
const stream = (async () => { while (driving) { await cmd('drive', { throttle: 0, steering: 0 }); await sleep(50); } })();
await sleep(300);
check('Skill runs when armed', (await cmd('run_skill', { id: 'sit' })).accepted);
await sleep(3200);
check('Pose reaches its target and holds', Math.abs(dev('l_knee').value - 85) < 3, `knee ${dev('l_knee').value}`);
check('Limits hold through the skill', st.device.actuators.every(a => a.value >= a.min && a.value <= a.max));
await cmd('run_skill', { id: 'stand' });
await sleep(2200);
check('Stand returns the joints', Math.abs(dev('l_knee').value) < 3, `knee ${dev('l_knee').value}`);
await cmd('run_skill', { id: 'walk' });
await sleep(900);
check('Walking is reported', st.device.skill === 'walk');
const before = st.device.actuators.map(a => a.value).join();
await sleep(900);
check('Gait moves joints', st.device.actuators.map(a => a.value).join() !== before);
driving = false;
await stream;
await sleep(3400);
check('Link loss: runs the safe skill and holds, without snapping to neutral', st.tel.mode === 'FAILSAFE' && Math.abs(dev('l_knee').value - 85) < 5, `${st.tel.mode}, knee ${dev('l_knee').value}`);
check('AI is refused on a stopped device', (await tool('device_run_skill', { id: 'walk' })).code === 'not_armed');

// --- Importing and URDF
await cmd('set_mode', { mode: 'DISARMED' });
const importOne = async (text, name) => { const n = st.cfg.length; ws.send(JSON.stringify({ type: 'device_import', text, name })); for (let i = 0; i < 100 && st.cfg.length === n; i++) await sleep(20); return st.cfg.at(-1); };
check('Bad pack refused with a reason', (await importOne('{"schema":"smartphonex.device/1","name":"x"}', 'x')).ok === false);
check('URDF imports', (await importOne('<robot name="r"><joint name="elbow" type="revolute"><limit lower="-1" upper="1" velocity="1"/></joint></robot>', 'Urdf bot')).ok);
await sleep(300);
check('Imported device is selectable', st.hello.profiles.some(p => p.id === 'device:urdf-bot'));
const signedPack = { schema: 'smartphonex.device/1', id: 'sneaky', name: 'Sneaky', class: 'arm', hazard: 'toy', actuators: [{ id: 'j', min: -1, max: 1 }], commissioning: { stage: 11, signed_off_by: 'nobody', tests: { usb_pull: 'ok' }, approvals: ['restrained'] } };
await importOne(JSON.stringify(signedPack), 'Sneaky');
const packJson = await (await fetch(`http://${host}/export/pack/sneaky?token=${token}`)).json();
check('A pack can\'t bring its own sign-off', packJson.commissioning.signed_off_by === null && packJson.commissioning.stage <= 4, JSON.stringify(packJson.commissioning));

// --- An LLM's setup path, by tool
await tool('setup_begin', { description: 'A two-axis camera mount' });
const bad = await tool('setup_propose_device', { device: { name: 'Mount', class: 'spaceship' } });
check('Bad description returns fixable errors', bad.code === 'invalid' && JSON.stringify(bad.errors).includes('class'));
const good = await tool('setup_propose_device', { device: { name: 'Mount', class: 'fixed', hazard: 'toy', actuators: [{ id: 'pan', unit: 'deg', min: -90, max: 90, max_rate: 900, safe: { action: 'hold' }, mix: { turn: 1 } }], skills: [{ id: 'left', type: 'pose', params: { joints: { pan: -45 }, ms: 200 } }] } });
check('Description accepted', good.ok, good.summary);
await sleep(500);
ws.close(); await sleep(200); await connect(); await sleep(1000);
check('Proposed device is selected automatically', st.tel.profile === 'device:mount', st.tel.profile);
check('Can\'t skip to motion', (await tool('setup_move_actuator', { id: 'pan', value: 1 })).code === 'not_permitted');
check('Can\'t sign off early', (await tool('setup_sign_off')).code === 'not_permitted');
await tool('setup_advance');
const pending = await tool('setup_request_confirmation', { kind: 'preview_ok', detail: 'Do the outputs look right?' });
check('Confirmation waits for a person', pending.code === 'waiting_for_person');
check('Tools can\'t move on without one', (await tool('setup_advance')).code === 'not_ready');
await approve();
check('After a person approves, it advances', (await tool('setup_advance')).ok);

// --- The assistant, against a stand-in LLM
if (llmFromPhone) {
  check('Assistant is available', !!st.hello.assistant, JSON.stringify(st.hello.assistant));
  const say = async text => {
    st.idle = false; st.chat.length = 0;
    ws.send(JSON.stringify({ type: 'assistant_say', text }));
    for (let i = 0; i < 300 && !st.idle; i++) await sleep(50);
    return st.chat.map(c => `${c.role}:${c.text}`).join(' | ');
  };
  ws.send(JSON.stringify({ type: 'assistant_cfg', kind: 'anthropic', base: llmFromPhone, model: 'claude-opus-5-5', key: 'test-key-123' }));
  await sleep(600);
  check('Public plain-HTTP address refused', await (async () => { st.chat.length = 0; ws.send(JSON.stringify({ type: 'assistant_cfg', base: 'http://example.com' })); await sleep(400); return st.chat.some(c => /own network/.test(c.text)); })());
  check('Key is reported as saved but never sent back', st.cfgA?.hasKey === true && !JSON.stringify(st.cfgA).includes('test-key-123'), JSON.stringify(st.cfgA));
  const reply = await say('I have a robot');
  check('Claude format: tool loop finishes', /stage 1/.test(reply) && /setup_status/.test(reply), reply.slice(0, 100));
  const seen = await (await fetch(`${llmHere}/seen`)).json();
  const a0 = seen[0], a1 = seen[1];
  check('Claude format: required headers', a0.headers.key === 'test-key-123' && a0.headers.version === '2026-10-21');
  check('Claude format: no sampling settings', !('temperature' in a0.body) && !('top_p' in a0.body) && !('top_k' in a0.body));
  check('Claude format: tools use input_schema', a0.body.tools.every(t => t.input_schema && t.name));
  check('Claude format: reply goes back unchanged, with its thinking block', JSON.stringify(a1.body.messages[1].content).includes('sig1'));
  check('Claude format: tool result is sent', JSON.stringify(a1.body.messages[2]).includes('"tool_use_id":"tu1"'));
  ws.send(JSON.stringify({ type: 'assistant_cfg', kind: 'openai', base: llmFromPhone, model: 'local-model', key: '' }));
  await sleep(500);
  const reply2 = await say('Hello');
  check('OpenAI-compatible format: tool loop finishes', /Local model/.test(reply2), reply2.slice(0, 100));
  const seen2 = await (await fetch(`${llmHere}/seen`)).json();
  const o = seen2.at(-1);
  check('OpenAI-compatible format: tool result is sent, and no key after clearing', o.url === '/chat/completions' && o.body.messages.some(m => m.role === 'tool') && !o.headers.auth, JSON.stringify(o.headers));
}

await select(originalProfile);
ws.close();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
