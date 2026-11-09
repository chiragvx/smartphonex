import { render } from 'preact';
import { useState } from 'preact/hooks';
import './style.css';
import { ackAlert, armed, authQuery, cmd, isMuted, isOperator, pair, S, setMuted, stale, staleMs, start, useStore } from './store';
import { setTileVia } from './map';
import { applySun, getSun, modeLabel } from './parts';
import { Operate } from './operate';
import { Plan } from './plan';
import { TestPage } from './test';
import { Setup } from './setup';
import { Review } from './review';

// Map tiles through the phone when talking to it directly (not through the relay): it keeps them for working offline.
setTileVia((layer, z, x, y) => (S.relay ? null : `/tile/${layer}/${z}/${x}/${y}${authQuery()}`));

applySun(getSun());
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
  return (
    <div class={`strip ${old && tel ? 'isStale' : ''}`} id="strip">
      <b id="deviceName" class="device">{profile?.name ?? 'SmartPhoneX'}</b>
      <span id="armState" class={`pill ${armClass}`} title={tel?.failsafeReason ?? ''}>{armState}</span>
      {isArmed && <span id="armClock" class="mono" title="Time armed">{clock(Date.now() - armedSince)}</span>}
      {!isArmed && tel && !ops?.killed && <span id="readySum" class={`ready ${fails ? 'warnText' : 'goodText'}`}>{fails ? `${fails} to fix before arming` : 'Ready to arm'}</span>}
      <span class="kv"><i>Mode</i><b id="modeState">{modeLabel(ops?.flightMode)}</b></span>
      <span class="kv"><i>In control</i><b id="ownerState">{ops?.owner?.toLowerCase() ?? 'nobody'}</b></span>
      <span class="kv"><i>GPS</i><b id="gpsState" class={gpsClass}>{gpsText}</b></span>
      {bat != null && <span class="kv"><i>Battery</i><b class={bat < 25 ? 'badText' : bat < 40 ? 'warnText' : ''}>{Math.round(bat)}%</b></span>}
      <span class="kv"><i>Link</i><b id="linkState" class={S.link && S.vehicleOnline ? '' : 'badText'}>{!S.link ? 'offline' : !S.vehicleOnline ? 'phone away' : `${S.rtt?.toFixed(0) ?? '--'} ms`}{netText && ` · ${netText}`}</b></span>
      {old && tel && <span class="pill bad" id="staleBadge">Data {(staleMs() / 1000).toFixed(0)} s old</span>}
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

function Banners() {
  return (
    <div class="banners">
      {S.hidden && armed() && isOperator() && <div class="banner warn">This tab is hidden, so it isn't driving: sticks are zero.</div>}
      {S.hello && !S.hello.protocol && <div class="banner bad">This phone runs an older app. Update it, or use the <a href="/classic/">classic screen</a>.</div>}
      {S.relay && !S.vehicleOnline && <div class="banner bad">The phone isn't reaching the relay. It keeps running by its own rules.</div>}
      {S.ops?.fence.breach && <div class="banner bad">Fence: {S.ops.fence.breach}</div>}
      {S.ops?.test && <div class="banner warn">Output test running: {S.ops.test.output} from {S.ops.test.owner.toLowerCase()}</div>}
      {S.alerts.length > 1 && <div class="banner info"><span class="grow">{S.alerts.length} alerts</span><button onClick={() => S.alerts.slice().forEach(a => ackAlert(a.id))}>OK to all</button></div>}
      {S.alerts.map(a => (
        <div key={a.id} class={`banner ${a.level === 'critical' ? 'bad' : a.level === 'warning' ? 'warn' : 'info'}`}>
          <span class="grow">{a.text}</span>
          <button onClick={() => ackAlert(a.id)}>OK</button>
        </div>
      ))}
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

function App() {
  useStore();
  const [page, setPage] = useState<Page>((PAGES.find(p => p[0] === initialPage)?.[0] as Page) ?? 'operate');
  if (S.needPair && !S.relay) return <Pairing />;
  return (
    <div class="shell">
      <StatusStrip />
      <Banners />
      <div class="tabsRow">
        <nav class="tabs" id="tabs">
          {PAGES.map(([id, name]) => <button key={id} data-page={id} class={page === id ? 'sel' : ''} onClick={() => setPage(id)}>{name}</button>)}
        </nav>
        <Menu />
      </div>
      <main>
        {page === 'operate' && <Operate />}
        {page === 'plan' && <Plan />}
        {page === 'test' && <TestPage />}
        {page === 'setup' && <Setup />}
        {page === 'review' && <Review />}
      </main>
      <Toasts />
    </div>
  );
}

render(<App />, document.getElementById('app')!);
