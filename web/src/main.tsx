import { render } from 'preact';
import { useState } from 'preact/hooks';
import './style.css';
import { ackAlert, armed, authQuery, cmd, isMuted, isOperator, nav, pair, S, setMuted, stale, staleMs, start, useStore, viewOnly } from './store';
import { setTileVia } from './map';
import { applySun, getSun, modeLabel } from './parts';
import { Operate, SoloView } from './operate';
import { Plan } from './plan';
import { TestPage } from './test';
import { Setup } from './setup';
import { Review } from './review';

// Map tiles through the phone when talking to it directly (not through the relay): it keeps them for working offline.
setTileVia((layer, z, x, y) => (S.relay ? null : `/tile/${layer}/${z}/${x}/${y}${authQuery()}`));

applySun(getSun());
// A pop-out window for a second screen: one view, the status bar and STOP, and it never drives.
const solo = new URLSearchParams(location.hash.slice(1)).get('solo');
viewOnly.on = !!solo;
const initialPage = start();
const PAGES = [['operate', 'Operate'], ['plan', 'Plan'], ['test', 'Test'], ['setup', 'Setup'], ['review', 'Review']] as const;
type Page = typeof PAGES[number][0];

function Pairing() {
  const [code, setCode] = useState('');
  const submit = (v: string) => { if (v.replace(/\D/g, '').length === 6) pair(v.replace(/\D/g, '')); };
  return (
    <div class="center" id="pair">
      <h1>Pair with the phone</h1>
      <p>{S.needPair}</p>
      <input id="pairCode" inputMode="numeric" autoFocus placeholder="000 000" value={code} onInput={e => { const v = e.currentTarget.value; setCode(v); submit(v); }} />
      <p class="dim">The code is on the phone, under Connected.</p>
    </div>
  );
}

/** When this browser first saw it armed, for the time-armed clock. */
let armedSince = 0;
const clock = (ms: number) => { const t = Math.floor(ms / 1000); return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`; };
const NAV_SHORT: Record<string, string> = { settling: 'settling', degraded: 'too rough', glitch: 'jumped', lost: 'lost' };

function StatusStrip() {
  const tel = S.tel;
  const ops = S.ops;
  const isArmed = armed();
  const old = stale();
  if (isArmed && !armedSince) armedSince = Date.now();
  if (!isArmed) armedSince = 0;
  const armState = !tel ? 'No data' : ops?.killed ? 'KILLED' : tel.mode === 'FAILSAFE' ? 'FAILSAFE' : isArmed ? 'ARMED' : 'Disarmed';
  const armClass = !tel ? 'off' : ops?.killed || tel.mode === 'FAILSAFE' ? 'bad' : isArmed ? 'warn' : 'good';
  const profile = S.hello?.profiles?.find((p: any) => p.id === tel?.profile);
  const bat = S.fcs?.battery;
  const net = S.net;
  const netText = net?.transport ? (net.radio || net.transport) + (net.transport === 'cellular' && net.signal != null ? ` ${net.signal}/4` : '') : '';
  const gps = S.fcs?.gps;
  const nav = gps?.nav;
  const gpsText = !gps ? 'none' : nav && nav.health !== 'good' ? `${NAV_SHORT[nav.health] ?? nav.health} ±${Math.round(nav.radius)} m` : `±${Math.round(nav?.radius ?? gps.acc)} m · ${gps.sats}`;
  const gpsClass = !gps ? 'dimText' : nav && nav.health !== 'good' ? 'warnText' : '';
  const fails = ops?.readiness.filter(c => c.status === 'fail').length ?? 0;
  // Every field has a fixed width and is always there, so nothing moves when values change.
  return (
    <div class={`strip ${old && tel ? 'isStale' : ''}`} id="strip">
      <b id="deviceName" class="device" title={profile?.name}>{profile?.name ?? 'SmartPhoneX'}</b>
      <span id="armState" class={`pill ${armClass}`} title={tel?.failsafeReason ?? ''}>{armState}</span>
      <span id="armClock" class="clock" title="Time armed">{isArmed ? clock(Date.now() - armedSince) : ''}</span>
      <span class="slot">
        {old && tel ? <span class="badText" id="staleBadge">Data {(staleMs() / 1000).toFixed(0)} s old</span>
          : tel && !isArmed && !ops?.killed ? <span id="readySum" class={fails ? 'warnText' : 'goodText'}>{fails ? `${fails} to fix before arming` : 'Ready to arm'}</span> : null}
      </span>
      <span class="kvs">
      <span class="kv k-mode"><i>Mode</i><b id="modeState">{modeLabel(ops?.flightMode)}</b></span>
      <span class="kv k-owner"><i>In control</i><b id="ownerState">{ops?.owner?.toLowerCase() ?? 'nobody'}</b></span>
      <span class="kv k-gps"><i>GPS</i><b id="gpsState" class={gpsClass}>{gpsText}</b></span>
      <span class="kv k-bat"><i>Battery</i><b class={bat == null ? 'dimText' : bat < 25 ? 'badText' : bat < 40 ? 'warnText' : ''}>{bat == null ? '--' : `${Math.round(bat)}%`}</b></span>
      <span class="kv k-link"><i>Link</i><b id="linkState" class={S.link && S.vehicleOnline ? '' : 'badText'}>{!S.link ? 'offline' : !S.vehicleOnline ? 'phone away' : `${S.rtt?.toFixed(0) ?? '--'} ms`}{netText && ` · ${netText}`}</b></span>
      </span>
      <span class="grow" />
      {S.relay && <span class="dim who" id="who">{S.relay.user} ({S.relay.role})</span>}
      <button id="stop" class="stop" onClick={() => cmd('stop', {}, { label: 'Stop' })}>STOP</button>
    </div>
  );
}

/** Things set per browser, out of the way of the pages. */
function Menu() {
  const [sun, setSun] = useState(getSun());
  return (
    <details class="menu" id="menu">
      <summary aria-label="Screen settings">⋯</summary>
      <div class="menuBody">
        <label class="tick"><input type="checkbox" checked={!isMuted()} onChange={e => setMuted(!e.currentTarget.checked)} /> Sounds for alerts</label>
        <label class="tick"><input type="checkbox" id="sunToggle" checked={sun} onChange={e => { const v = e.currentTarget.checked; setSun(v); applySun(v); }} /> Sunlight colours</label>
        <a href="/classic/">Classic screen</a>
      </div>
    </details>
  );
}

type Notice = { level: 'bad' | 'warn' | 'info'; text: string; id?: number };

/** One line, always there: the most serious notice, with the rest a click away. It never pushes the page down. */
function Banners() {
  const [open, setOpen] = useState(false);
  const list: Notice[] = [];
  if (S.ops?.fence.breach) list.push({ level: 'bad', text: `Fence: ${S.ops.fence.breach}` });
  if (S.relay && !S.vehicleOnline) list.push({ level: 'bad', text: "The phone isn't reaching the relay. It keeps running by its own rules." });
  if (S.hidden && armed() && isOperator()) list.push({ level: 'warn', text: "This tab is hidden, so it isn't driving: sticks are zero." });
  if (S.hello && !S.hello.protocol) list.push({ level: 'bad', text: 'This phone runs an older app. Update it, or use the classic screen (menu).' });
  if (S.ops?.test) list.push({ level: 'warn', text: `Output test running: ${S.ops.test.output} from ${S.ops.test.owner.toLowerCase()}` });
  const rank = { bad: 0, warn: 1, info: 2 };
  S.alerts.forEach(a => list.push({ level: a.level === 'critical' ? 'bad' : a.level === 'warning' ? 'warn' : 'info', text: a.text, id: a.id }));
  list.sort((x, y) => rank[x.level] - rank[y.level]);
  const top = list[0];
  const alerts = S.alerts.length;
  return (
    <div class={`banners ${top ? top.level : 'none'}`} id="alertBar">
      <span class="grow alertText">{top ? top.text : 'No alerts'}</span>
      {list.length > 1 && <button class="ghostBtn" onClick={() => setOpen(!open)}>{open ? 'Hide' : `+${list.length - 1} more`}</button>}
      {alerts > 0 && <button class="ghostBtn" onClick={() => { S.alerts.slice().forEach(a => ackAlert(a.id)); setOpen(false); }}>{alerts > 1 ? 'OK to all' : 'OK'}</button>}
      {open && list.length > 1 && <div class="alertList">
        {list.map((n, i) => <div key={i} class={`alertItem ${n.level}`}><span class="grow">{n.text}</span>{n.id !== undefined && <button class="ghostBtn" onClick={() => ackAlert(n.id!)}>OK</button>}</div>)}
      </div>}
    </div>
  );
}

function Toasts() {
  return (
    <div class="toasts" id="toasts">
      {S.toasts.map(t => (
        <div key={t.seq} class={`toast ${t.state}`}>
          <b>{t.label}</b>: {t.state === 'sent' ? 'sent...' : t.state === 'ok' ? 'done' : t.message || 'refused'}
        </div>
      ))}
    </div>
  );
}

/** Line icons for the rail, drawn on a 24 grid. */
const ICONS: Record<string, string> = {
  operate: 'M12 3l7 18-7-4-7 4z',
  plan: 'M5 19c3-1 3-6 7-7s4-6 7-7M5 19a1.5 1.5 0 1 0 0 .01M19 5a1.5 1.5 0 1 0 0 .01',
  test: 'M4 12.5l5 5L20 6.5',
  setup: 'M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4',
  review: 'M12 7v5l3 2M21 12a9 9 0 1 1-9-9 9 9 0 0 1 9 9z',
};
const Icon = ({ d }: { d: string }) => <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d={d} /></svg>;

function App() {
  useStore();
  const [page, setPage] = useState<Page>((PAGES.find(p => p[0] === initialPage)?.[0] as Page) ?? 'operate');
  nav.go = p => { if (PAGES.some(x => x[0] === p)) setPage(p as Page); };
  if (S.needPair && !S.relay) return <Pairing />;
  if (solo) return (
    <div class="shell soloShell">
      <div class="work">
        <StatusStrip />
        <Banners />
        <main><SoloView kind={solo} /></main>
      </div>
      <Toasts />
    </div>
  );
  return (
    <div class={`shell page-${page}`}>
      <nav class="rail" id="tabs" aria-label="Workspaces">
        <div class="brand" title="SmartPhoneX">SPX</div>
        {PAGES.map(([id, name]) => (
          <button key={id} data-page={id} class={page === id ? 'sel' : ''} aria-current={page === id ? 'page' : undefined} onClick={() => setPage(id)}>
            <Icon d={ICONS[id]} /><span>{name}</span>
          </button>
        ))}
        <span class="grow" />
        <Menu />
      </nav>
      <div class="work">
        <StatusStrip />
        <Banners />
        <main>
          {page === 'operate' && <Operate />}
          {page === 'plan' && <Plan />}
          {page === 'test' && <TestPage />}
          {page === 'setup' && <Setup />}
          {page === 'review' && <Review />}
        </main>
      </div>
      <Toasts />
    </div>
  );
}

render(<App />, document.getElementById('app')!);
