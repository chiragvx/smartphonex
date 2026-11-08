// Configure it: device, assistant, trim, triggers, settings, sensors, camera.
import { useEffect, useState } from 'preact/hooks';
import { armed, cmd, getToken, isOperator, raw, S, type Trigger } from './store';
import { Panel } from './parts';

function Device() {
  const profiles = S.hello?.profiles ?? [];
  const setup = S.setup;
  const [text, setText] = useState('');
  return (
    <Panel title="Device">
      <label>This phone controls
        <select id="profileSelect" value={S.tel?.profile} disabled={!isOperator()} onChange={e => cmd('select_profile', { id: e.currentTarget.value }, { label: 'Choose device' }).then(r => { if (r.accepted) setTimeout(() => location.reload(), 300); })}>
          {profiles.map((p: any) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </label>
      {S.hello?.device && <p class="dim">{S.hello.device.name} · {S.hello.device.class} · hazard {S.hello.device.hazard} · {S.hello.device.signedOff ? 'signed off' : 'hardware closed until signed off'}</p>}
      {setup && (setup.device || setup.pending?.length > 0) && <div id="setupState">
        <p>Stage {setup.stage} of 11: {setup.stageName}</p>
        {setup.pending.map((p: any) => (
          <div class="approval" key={p.id}>
            <p>{p.detail}</p>
            <div class="row"><button onClick={() => raw({ type: 'setup_approve', id: p.id, allow: true })}>Approve</button><button onClick={() => raw({ type: 'setup_approve', id: p.id, allow: false })}>Refuse</button></div>
          </div>
        ))}
      </div>}
      {isOperator() && <details>
        <summary>Import a device pack</summary>
        <textarea rows={4} placeholder="Paste a device description or URDF" value={text} onInput={e => setText(e.currentTarget.value)} />
        <button onClick={() => { raw({ type: 'device_import', text }); setText(''); }} disabled={!text}>Import</button>
        <p class="dim">An import always restarts the safety stages.</p>
      </details>}
    </Panel>
  );
}

function Assistant() {
  const cfg = S.assistantCfg;
  const [text, setText] = useState('');
  const [key, setKey] = useState('');
  const [form, setForm] = useState<any>(null);
  if (!isOperator() || !cfg) return null;
  const f = form ?? cfg;
  const say = () => { if (!text.trim()) return; S.assistantBusy = true; S.assistant = [...S.assistant, { role: 'user', text }]; raw({ type: 'assistant_say', text }); setText(''); };
  return (
    <Panel title="Setup assistant">
      <details>
        <summary>{cfg.model} · {cfg.hasKey ? 'key saved' : 'no key yet'}</summary>
        <label>Kind <select value={f.kind} onChange={e => setForm({ ...f, kind: e.currentTarget.value })}><option value="anthropic">Anthropic</option><option value="openai">OpenAI-compatible</option></select></label>
        <label>Address <input value={f.base} onInput={e => setForm({ ...f, base: e.currentTarget.value })} /></label>
        <label>Model <input value={f.model} onInput={e => setForm({ ...f, model: e.currentTarget.value })} /></label>
        <label>Key <input type="password" autoComplete="off" value={key} onInput={e => setKey(e.currentTarget.value)} placeholder={cfg.hasKey ? 'Saved on the phone' : ''} /></label>
        <button onClick={() => { raw({ type: 'assistant_cfg', kind: f.kind, base: f.base, model: f.model, ...(key ? { key } : {}) }); setKey(''); setForm(null); }}>Save</button>
      </details>
      <div class="chat" id="aiLog">{S.assistant.map((m, i) => <p key={i} class={m.role}>{m.text}</p>)}</div>
      <div class="row">
        <input class="grow" id="aiInput" placeholder="Describe your device..." value={text} onInput={e => setText(e.currentTarget.value)} onKeyDown={e => { if (e.key === 'Enter') say(); }} />
        <button id="aiSend" disabled={S.assistantBusy} onClick={say}>Send</button>
        <button onClick={() => { S.assistant = []; raw({ type: 'assistant_reset' }); }}>Clear</button>
      </div>
    </Panel>
  );
}

function Trim() {
  const trims = S.ops?.trims ?? {};
  const names = Object.keys(trims);
  const isArmed = armed();
  const step = isArmed ? 0.01 : 0.005;
  const set = (name: string, body: Record<string, unknown>) => cmd('trim_set', { output: name, ...body }, { label: `Trim ${name}` });
  return (
    <Panel title="Trim">
      <p class="dim">{isArmed ? 'Armed: small steps only.' : 'Saved at once, per device.'}</p>
      <div id="trims">
        {names.map(n => {
          const t = trims[n];
          return (
            <div class="testRow" key={n} data-trim={n}>
              <div class="row"><b class="grow">{n}</b><span class="mono">{t.trim >= 0 ? '+' : ''}{t.trim.toFixed(3)}</span></div>
              <div class="row">
                <button onClick={() => set(n, { trim: t.trim - step })}>−</button>
                <button onClick={() => set(n, { trim: t.trim + step })}>+</button>
                <button onClick={() => set(n, { trim: 0 })}>Center</button>
                <button onClick={() => cmd('trim_undo', { output: n }, { label: 'Undo' })}>Undo</button>
                <label class="tick"><input type="checkbox" checked={t.reverse} disabled={isArmed} onChange={e => set(n, { reverse: e.currentTarget.checked })} />Reverse</label>
              </div>
              <label>Low limit <input type="number" step={0.05} min={-1} max={0} value={t.lo} disabled={isArmed} onChange={e => set(n, { lo: Number(e.currentTarget.value) })} /></label>
              <label>High limit <input type="number" step={0.05} min={0} max={1} value={t.hi} disabled={isArmed} onChange={e => set(n, { hi: Number(e.currentTarget.value) })} /></label>
            </div>
          );
        })}
      </div>
      <div class="row">
        <button onClick={() => raw({ type: 'fc_action', action: 'set_level' })}>Set level</button>
        <button onClick={() => raw({ type: 'fc_action', action: 'calibrate_gyro' })}>Calibrate gyro</button>
        <button onClick={() => raw({ type: 'fc_action', action: 'set_home' })}>Set home here</button>
      </div>
    </Panel>
  );
}

const blank = { id: '', label: '', type: 'photo', output: '', value: 0.5, ms: 500, skill: '', text: '', danger: false, armedOnly: true, cooldownMs: 500, maxCount: 0, aiAllowed: false, everyMeters: 0, everySeconds: 0, sensor: '', op: '<', compare: 0 };

function Triggers() {
  const list = S.ops?.triggers ?? [];
  const [f, setF] = useState<typeof blank | null>(null);
  const names = Object.keys(S.ops?.trims ?? {});
  const edit = (t: Trigger) => setF({ ...blank, ...t, type: t.action.type, output: t.action.output ?? '', value: t.action.value, ms: t.action.ms, skill: t.action.skill ?? '', text: t.action.text ?? '', sensor: t.sensor ?? '', op: t.op ?? '<' });
  const save = () => {
    if (!f) return;
    const id = f.id || f.label.toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 40);
    cmd('trigger_save', { trigger: {
      id, label: f.label || id, danger: f.danger, armedOnly: f.armedOnly, cooldownMs: f.cooldownMs, maxCount: f.maxCount, aiAllowed: f.aiAllowed,
      everyMeters: f.everyMeters, everySeconds: f.everySeconds, ...(f.sensor ? { sensor: f.sensor, op: f.op, compare: f.compare } : {}),
      action: { type: f.type, ...(f.output ? { output: f.output } : {}), value: f.value, ms: f.ms, ...(f.skill ? { skill: f.skill } : {}), ...(f.text ? { text: f.text } : {}) },
    } }, { label: 'Save trigger' }).then(r => { if (r.accepted) setF(null); });
  };
  return (
    <Panel title="Triggers" right={isOperator() && <button id="addTrigger" onClick={() => setF({ ...blank })}>Add</button>}>
      <div class="list">
        {list.map(t => (
          <div class="item" key={t.id}>
            <span class="grow"><b>{t.label}</b> <span class="dim">{t.action.type}{t.danger ? ', two steps' : ''}{t.everyMeters ? `, every ${t.everyMeters} m` : ''}{t.everySeconds ? `, every ${t.everySeconds} s` : ''}{t.sensor ? `, ${t.sensor} ${t.op} ${t.compare}` : ''}</span></span>
            {isOperator() && <><button onClick={() => edit(t)}>Edit</button><button onClick={() => cmd('trigger_delete', { id: t.id }, { label: 'Delete trigger' })}>✕</button></>}
          </div>
        ))}
      </div>
      {f && <div class="form" id="triggerForm">
        <label>Name <input id="trigLabel" value={f.label} onInput={e => setF({ ...f, label: e.currentTarget.value })} /></label>
        <label>Does <select id="trigType" value={f.type} onChange={e => setF({ ...f, type: e.currentTarget.value })}>
          <option value="photo">Take a photo</option><option value="marker">Mark the log</option><option value="alert">Show an alert</option><option value="pulse">Pulse an output</option><option value="set">Set an output</option><option value="skill">Run a skill</option>
        </select></label>
        {(f.type === 'pulse' || f.type === 'set') && <>
          <label>Output <select value={f.output} onChange={e => setF({ ...f, output: e.currentTarget.value })}><option value="">Choose</option>{names.map(n => <option key={n} value={n}>{n}</option>)}</select></label>
          <label>Value, -1 to 1 <input type="number" step={0.05} value={f.value} onChange={e => setF({ ...f, value: Number(e.currentTarget.value) })} /></label>
          {f.type === 'pulse' && <label>For, ms <input type="number" value={f.ms} onChange={e => setF({ ...f, ms: Number(e.currentTarget.value) })} /></label>}
        </>}
        {f.type === 'skill' && <label>Skill <select value={f.skill} onChange={e => setF({ ...f, skill: e.currentTarget.value })}><option value="">Choose</option>{(S.hello?.caps?.skills ?? []).map((n: string) => <option key={n} value={n}>{n}</option>)}</select></label>}
        {(f.type === 'marker' || f.type === 'alert') && <label>Text <input value={f.text} onInput={e => setF({ ...f, text: e.currentTarget.value })} /></label>}
        <label>Every this many metres (0 is off) <input type="number" value={f.everyMeters} onChange={e => setF({ ...f, everyMeters: Number(e.currentTarget.value) })} /></label>
        <label>Every this many seconds (0 is off) <input type="number" value={f.everySeconds} onChange={e => setF({ ...f, everySeconds: Number(e.currentTarget.value) })} /></label>
        <label>When a sensor reads (id or id:axis, blank is off) <input value={f.sensor} onInput={e => setF({ ...f, sensor: e.currentTarget.value })} /></label>
        {f.sensor && <div class="row"><select value={f.op} onChange={e => setF({ ...f, op: e.currentTarget.value })}><option value="<">below</option><option value=">">above</option></select><input type="number" value={f.compare} onChange={e => setF({ ...f, compare: Number(e.currentTarget.value) })} /></div>}
        <label>Wait between firings, ms <input type="number" value={f.cooldownMs} onChange={e => setF({ ...f, cooldownMs: Number(e.currentTarget.value) })} /></label>
        <label>Most firings (0 is no limit) <input type="number" value={f.maxCount} onChange={e => setF({ ...f, maxCount: Number(e.currentTarget.value) })} /></label>
        <label class="tick"><input type="checkbox" checked={f.armedOnly} onChange={e => setF({ ...f, armedOnly: e.currentTarget.checked })} />Only while armed</label>
        <label class="tick"><input type="checkbox" checked={f.danger} onChange={e => setF({ ...f, danger: e.currentTarget.checked, aiAllowed: false })} />Dangerous: takes two steps, and never AI</label>
        <label class="tick"><input type="checkbox" checked={f.aiAllowed} disabled={f.danger} onChange={e => setF({ ...f, aiAllowed: e.currentTarget.checked })} />AI may fire it</label>
        <div class="row"><button id="trigSave" onClick={save}>Save</button><button onClick={() => setF(null)}>Cancel</button></div>
      </div>}
    </Panel>
  );
}

function Settings() {
  const [q, setQ] = useState('');
  const list = S.params.filter(p => !q || `${p.name} ${p.description} ${p.group}`.toLowerCase().includes(q.toLowerCase()));
  const groups = [...new Set<string>(list.map(p => p.group))];
  const history = S.paramHistory;
  return (
    <Panel title="Settings">
      <input id="paramSearch" placeholder="Search settings" value={q} onInput={e => setQ(e.currentTarget.value)} />
      {groups.map(g => (
        <details key={g} open={!!q}>
          <summary>{g}</summary>
          {list.filter(p => p.group === g).map(p => (
            <label key={p.name} class="param" title={p.description}>
              <span><b>{p.name}</b><span class="dim"> {p.description}</span></span>
              <input data-param={p.name} type="number" value={p.value} min={p.min} max={p.max} step="any" disabled={!isOperator()} onChange={e => raw({ type: 'param_set', name: p.name, value: Number(e.currentTarget.value) })} />
            </label>
          ))}
        </details>
      ))}
      {history.length > 0 && <details id="paramHistory">
        <summary>History</summary>
        {history.map((h: any, i: number) => (
          <div class="item" key={i}>
            <span class="grow dim">{new Date(h.t).toLocaleString()}: {h.name} {h.old} to {h.value}</span>
            {isOperator() && <button onClick={() => raw({ type: 'param_set', name: h.name, value: h.old })}>Restore</button>}
          </div>
        ))}
      </details>}
    </Panel>
  );
}

function Sensors() {
  const c = S.config;
  const list: any[] = c?.sensors ?? [];
  return (
    <Panel title="Sensors">
      <label>Data budget, kbps (0 is none) <input id="budget" type="number" value={c?.budgetKbps ?? 0} disabled={!isOperator()} onChange={e => raw({ type: 'budget', kbps: Number(e.currentTarget.value) })} /></label>
      <div id="sensorList">
        {list.map(s => (
          <div class="sensorRow" key={s.id}>
            <label class="tick grow"><input type="checkbox" data-sensor={s.id} checked={s.enabled} disabled={!isOperator() || !!s.unavailable} onChange={e => raw({ type: 'sensor_cfg', id: s.id, enabled: e.currentTarget.checked })} />{s.name}{s.private ? ' (private)' : ''}{s.unavailable ? `: ${s.unavailable}` : ''}</label>
            <label>Hz <input type="number" value={s.rateHz} disabled={!isOperator()} onChange={e => raw({ type: 'sensor_cfg', id: s.id, rateHz: Number(e.currentTarget.value) })} /></label>
            <label>Priority <input type="number" value={s.priority} min={0} max={9} disabled={!isOperator()} onChange={e => raw({ type: 'sensor_cfg', id: s.id, priority: Number(e.currentTarget.value) })} /></label>
          </div>
        ))}
      </div>
    </Panel>
  );
}

function Camera() {
  const c = S.config;
  const cam = c?.camera;
  if (!cam || !isOperator()) return null;
  const list: any[] = c.cameras ?? [];
  const current = list.find(x => x.id === cam.cameraId);
  return (
    <Panel title="Camera">
      <label class="tick"><input type="checkbox" checked={cam.enabled} onChange={e => raw({ type: 'camera_cfg', enabled: e.currentTarget.checked })} />On</label>
      <label>Camera <select value={cam.cameraId} onChange={e => raw({ type: 'camera_cfg', cameraId: e.currentTarget.value })}>{list.map(x => <option key={x.id} value={x.id}>{x.name}</option>)}</select></label>
      <label>Size <select value={`${cam.width}x${cam.height}`} onChange={e => { const [w, h] = e.currentTarget.value.split('x').map(Number); raw({ type: 'camera_cfg', width: w, height: h }); }}>{(current?.sizes ?? [`${cam.width}x${cam.height}`]).map((s: string) => <option key={s} value={s}>{s}</option>)}</select></label>
      <label>Frames a second <input type="number" value={cam.fps} onChange={e => raw({ type: 'camera_cfg', fps: Number(e.currentTarget.value) })} /></label>
      <label>Quality <input type="number" value={cam.quality} onChange={e => raw({ type: 'camera_cfg', quality: Number(e.currentTarget.value) })} /></label>
      <label class="tick"><input type="checkbox" checked={!!c.aiAccess} onChange={e => raw({ type: 'ai_access', value: e.currentTarget.checked })} />AI may read the camera and location</label>
    </Panel>
  );
}

function Connect() {
  const [qr, setQr] = useState('');
  const [note, setNote] = useState('');
  const newCode = async () => {
    setNote('Creating code...');
    const res = await fetch('/enroll/new', { method: 'POST' });
    if (!res.ok) { setNote('Could not create a code.'); return; }
    const b = await res.json();
    setQr(b.svg); // Built by our own server from its own URL.
    setNote(`Works once, for ${Math.floor(b.expiresInSeconds / 60)} minutes.${b.encrypted ? '' : ' This address is not encrypted, so a phone on another network will not accept it.'}`);
  };
  return (
    <Panel title="Connect">
      {S.relay ? <>
        <p class="dim">Show a phone this code to link it to this relay.</p>
        {isOperator() && <button id="enrollBtn" onClick={newCode}>Show QR code</button>}
        <div id="enrollQr" class="qr" dangerouslySetInnerHTML={{ __html: qr }} />
        <p class="dim" id="enrollNote">{note}</p>
        <form method="post" action="/logout"><button>Sign out</button></form>
      </> : <>
        <p class="dim">Connect an AI agent over MCP:</p>
        <textarea id="mcpCmd" readOnly rows={3} value={`claude mcp add --transport http smartphonex ${location.origin}/mcp --header "Authorization: Bearer ${getToken()}"`} />
      </>}
    </Panel>
  );
}

function Backup() {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const q = (media: boolean) => `/backup?token=${encodeURIComponent(getToken())}${media ? '&media=1' : ''}`;
  const restore = async (f: File | undefined) => {
    if (!f) return;
    if (!confirm(`Replace this phone's settings, devices and logs with ${f.name}? What's there now is kept aside on the phone, and the app restarts.`)) return;
    setBusy(true); setNote('Sending...');
    try {
      const res = await fetch('/backup/restore', { method: 'POST', headers: { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/zip' }, body: f });
      const b = await res.json().catch(() => ({}));
      setNote(res.ok ? 'Restored. The app is restarting and will reconnect.' : `Refused: ${b.error ?? res.status}`);
    } catch { setNote('Could not reach the phone.'); }
    setBusy(false);
  };
  return (
    <Panel title="Backup">
      <p class="dim">Settings, devices, missions and logs in one file. Secrets stay on the phone: its access token, AI key and relay link.</p>
      <div class="row">
        <a class="btn" id="backupBtn" href={q(false)} download>Download backup</a>
        <a class="btn" id="backupMediaBtn" href={q(true)} download>With photos and video</a>
      </div>
      {isOperator() && <label class="filebtn">Restore from file
        <input id="restoreFile" type="file" accept=".zip,application/zip" hidden disabled={busy || armed()} onChange={(e) => restore((e.target as HTMLInputElement).files?.[0])} />
      </label>}
      {armed() && <p class="dim">Disarm to restore.</p>}
      <p class="dim" id="backupNote">{note}</p>
    </Panel>
  );
}

type Health = {
  exits: { t: number; text: string; unexpected: boolean; detail?: string }[];
  crashes: { name: string; t: number; summary: string }[];
  supported: boolean;
  update?: { enabled: boolean; current: string; latest?: string; newer?: boolean; page?: string; apk?: string; error?: string; checked?: number };
};

/** How the app has been, a report of this phone for the compatibility table, and updates. On the phone's own address only. */
function AppHealth() {
  const [h, setH] = useState<Health | null>(null);
  const [busy, setBusy] = useState(false);
  const q = `token=${encodeURIComponent(getToken())}`;
  const load = () => fetch(`/diagnostics?${q}`).then(r => r.ok ? r.json() : null).then(setH).catch(() => setH(null));
  useEffect(() => { load(); }, []);
  const checkNow = async () => {
    setBusy(true);
    try { await fetch('/diagnostics/update', { method: 'POST', headers: { Authorization: `Bearer ${getToken()}` } }); } catch { /* Shown as not checked. */ }
    await load(); setBusy(false);
  };
  const when = (t: number) => new Date(t).toLocaleString();
  const u = h?.update;
  return (
    <Panel title="App health">
      {!h ? <p class="dim">Loading...</p> : <>
        {u?.enabled && <div class="row" id="updateRow">
          <span class="grow">{u.newer ? <b>Version {u.latest} is out.</b> : u.error ? <span class="dim">Couldn't check for updates: {u.error}</span> : u.checked ? `Up to date (${u.current})` : 'Not checked yet'}
            {u.newer && u.page && <> <a href={u.page} target="_blank" rel="noopener noreferrer">Get it</a>. Back up first.</>}</span>
          <button onClick={checkNow} disabled={busy}>{busy ? 'Checking...' : 'Check now'}</button>
        </div>}
        <p class="dim">{h.supported ? 'How the app stopped, most recent first:' : 'This Android version doesn\'t say why the app stopped. Crashes are still recorded.'}</p>
        <div id="exitList">
          {h.exits.length === 0 && h.supported && <p class="dim">Nothing recorded yet.</p>}
          {h.exits.slice(0, 8).map(e => <div class="item" key={e.t}><span class={`pill ${e.unexpected ? 'warn' : 'off'}`}>{e.unexpected ? 'Unexpected' : 'Normal'}</span> <span class="grow">{when(e.t)}: {e.text}</span></div>)}
        </div>
        {h.crashes.length > 0 && <>
          <p><b>Crashes</b></p>
          {h.crashes.slice(0, 8).map(c => <div class="item" key={c.name}><span class="grow">{when(c.t)}: {c.summary}</span><a class="btn" href={`/diagnostics/crash/${c.name}?${q}`} target="_blank" rel="noopener noreferrer">Open</a></div>)}
        </>}
        <p class="dim">The phone report lists this phone's model, sensors, GPS signal and timing, with no location or IDs. Sharing it helps others pick a phone.</p>
        <a class="btn" id="phoneReport" href={`/diagnostics/phone?${q}`} download>Download phone report</a>
      </>}
    </Panel>
  );
}

export function Setup() {
  return (
    <div class="pageCols">
      <div><Device /><Assistant /><Trim /><Connect />{!S.relay && <Backup />}{!S.relay && <AppHealth />}</div>
      <div><Triggers /><Settings /><Sensors /><Camera /></div>
    </div>
  );
}
