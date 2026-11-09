// Run the device now.
import { useEffect, useState } from 'preact/hooks';
import { armed, cmd, isOperator, raw, S, setVideoWanted, stale } from './store';
import { canAct, CheckRow, fmt, HoldButton, Horizon, LayerSwitch, modeLabel, Panel, SlideToArm, Sticks, useMap } from './parts';
import { drawHome, drawPlan, drawVehicle, type TileMap } from './map';

const trail: { lat: number; lon: number }[] = [];
const mmss = (s: number) => { const t = Math.max(0, Math.round(s)); return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`; };

export function firePrepared(t: { id: string; label: string }) {
  cmd('trigger_prepare', { id: t.id }, { quiet: true }).then(r => { if (r.accepted) cmd('trigger_fire', { id: t.id }, { label: t.label }); });
}

/** What the GPS guard's state means, in words. */
const NAV_TEXT: Record<string, string> = {
  good: 'Good', settling: 'GPS settling', degraded: 'GPS too rough to navigate', glitch: 'GPS jumped, checking it', lost: 'GPS position lost',
};

function MapView({ goHere }: { goHere: boolean }) {
  const [, redraw] = useState(0);
  const { ref, map } = useMap((m: TileMap) => {
    m.overlay = (mm, g) => {
      const ops = S.ops;
      if (trail.length > 1) {
        g.strokeStyle = '#ffd33d'; g.lineWidth = 2; g.beginPath();
        trail.forEach((p, i) => { const s = mm.toScreen(p.lat, p.lon); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); });
        g.stroke();
      }
      const running = ops?.mission.state === 'RUNNING' || ops?.mission.state === 'PAUSED';
      drawPlan(mm, g, ops?.missionData?.items ?? [], running ? ops!.mission.index : -1, ops?.fence.data ?? null);
      // Safe zones of the sent plan, and where it's going if it's diverting.
      for (const z of (ops?.planData?.zones ?? []) as { kind: string; at: [number, number]; radius?: number }[]) {
        const s = mm.toScreen(z.at[0], z.at[1]);
        g.beginPath(); g.arc(s.x, s.y, Math.max(6, (z.radius ?? 20) / mm.metersPerPixel()), 0, 7);
        g.lineWidth = 2; g.strokeStyle = z.kind === 'ditch' ? '#fb923c' : '#4ade80'; g.stroke();
      }
      const d = ops?.divert;
      if (d) {
        const s = mm.toScreen(d.lat, d.lon);
        g.beginPath(); g.arc(s.x, s.y, 16, 0, 7); g.lineWidth = 3; g.strokeStyle = '#e5a823'; g.setLineDash([5, 4]); g.stroke(); g.setLineDash([]);
      }
      const home = S.fcs?.home;
      if (home) drawHome(mm, g, home.lat, home.lon);
      const gps = S.fcs?.gps;
      const nav = gps?.nav;
      if (gps && nav) drawVehicle(mm, g, nav.lat, nav.lon, S.fc?.a?.[2] ?? gps.course ?? 0, nav.radius, nav.health === 'good', { lat: gps.lat, lon: gps.lon });
      else if (gps) drawVehicle(mm, g, gps.lat, gps.lon, S.fc?.a?.[2] ?? gps.course ?? 0, gps.acc, false);
    };
    redraw(x => x + 1);
  });
  const gps = S.fcs?.gps;
  useEffect(() => {
    const m = map.current;
    if (!m) return;
    if (gps) {
      const last = trail[trail.length - 1];
      if (!last || last.lat !== gps.lat || last.lon !== gps.lon) { trail.push({ lat: gps.lat, lon: gps.lon }); if (trail.length > 3000) trail.shift(); }
      if (!m.userMoved) { m.center = { lat: gps.lat, lon: gps.lon }; if (m.zoom < 17) m.zoom = 18; }
    }
    m.onClick = p => {
      if (!goHereRef.on) return;
      if (confirm(`Send the vehicle here?\n${p.lat.toFixed(6)}, ${p.lon.toFixed(6)}`)) cmd('goto', { lat: p.lat, lon: p.lon }, { label: 'Go here' });
    };
    m.draw();
  });
  goHereRef.on = goHere;
  return (
    <div class="mapwrap">
      <canvas ref={ref} id="map" />
      <LayerSwitch map={map.current} />
      {!gps && <div class="onmap dim">No position yet. Turn on Location in Setup.</div>}
      {gps?.nav && gps.nav.health !== 'good' && <div class="onmap" id="navWarn">{NAV_TEXT[gps.nav.health] ?? 'Navigation waits'} (±{Math.round(gps.nav.radius)} m). Missions and Return home wait, stopped.</div>}
      <button class="center-on" onClick={() => { const m = map.current; if (m) { m.userMoved = false; m.draw(); } }}>Follow</button>
    </div>
  );
}
const goHereRef = { on: false };

/** The numbers a pilot looks for, over the map or video: height, speed, way home, time left. */
function Readout() {
  const f = S.fcs;
  const gps = f?.gps;
  const home = f?.home;
  const left = S.ops?.timeLeftS;
  const arrow = home?.bearing != null ? { transform: `rotate(${home.bearing - (S.fc?.a?.[2] ?? 0)}deg)` } : undefined;
  return (
    <div class={`readout ${stale() ? 'isStale' : ''}`} id="readout">
      <div><i>Height</i><b>{home?.height != null ? `${Math.round(home.height) || 0} m` : '--'}</b></div>
      <div><i>Speed</i><b>{gps ? `${gps.speed.toFixed(1)} m/s` : '--'}</b></div>
      <div><i>Home</i><b>{home?.dist != null ? <>{home.dist < 1000 ? `${home.dist.toFixed(0)} m` : `${(home.dist / 1000).toFixed(2)} km`} {arrow && <span class="homeArrow" style={arrow} title="Way home, from where it points">↑</span>}</> : 'not set'}</b></div>
      {left !== undefined && <div><i>Time left</i><b class={left < 60 ? 'badText' : left < 180 ? 'warnText' : ''}>{mmss(left)}</b></div>}
    </div>
  );
}

function VideoView() {
  const v = S.video;
  const on = !!S.config?.camera?.enabled;
  useEffect(() => { setVideoWanted(true); return () => setVideoWanted(false); }, []);
  useEffect(() => setVideoWanted(true), [on]);
  return (
    <div class="video">
      {v.url ? <img id="video" src={v.url} style={{ transform: v.rotate ? `rotate(${v.rotate}deg)` : '' }} /> : null}
      {!v.live && <div class="onmap dim">{on ? 'Waiting for the camera...' : 'Camera is off'}</div>}
      {v.live && <div class="videoInfo">{v.fps.toFixed(0)} fps · {Math.max(0, v.ageMs).toFixed(0)} ms old</div>}
    </div>
  );
}

function ArmPanel() {
  const ops = S.ops;
  const isArmed = armed();
  if (!isOperator()) return null;
  const fails = ops?.readiness.filter(c => c.status === 'fail') ?? [];
  const sum = isArmed ? null : fails.length ? <span class="pill warn">{fails.length} to fix</span> : <span class="pill good">Ready</span>;
  return (
    <Panel title={isArmed ? 'Armed' : 'Arm'} right={sum}>
      {isArmed
        ? <button id="disarm" class="big" onClick={() => cmd('set_mode', { mode: 'DISARMED' }, { label: 'Disarm' })}>Disarm</button>
        : fails.length === 0
          ? <SlideToArm label={S.tel?.mode === 'FAILSAFE' ? 'Slide to clear and arm' : 'Slide to arm'} onDone={() => cmd('set_mode', { mode: 'MANUAL' }, { label: 'Arm' })} />
          : fails.map(c => <CheckRow key={c.id} c={c} />)}
      {ops?.readiness.filter(c => c.status === 'warn').map(c => <CheckRow key={c.id} c={c} />)}
      {S.fcs?.prop?.warning && <CheckRow c={{ label: 'Propeller sensor', status: 'warn', detail: S.fcs.prop.warning, fix: 'Its airspeed and rpm readings are not trusted until this clears.' }} />}
      <details id="killBox">
        <summary>Kill</summary>
        <p class="dim">Cuts every output at once, with no glide or slow-down.</p>
        <HoldButton id="kill" label="Hold 2 s to kill" ms={2000} danger onHeld={() => cmd('kill', {}, { label: 'Kill' })} />
      </details>
    </Panel>
  );
}

function ModePanel() {
  const modes = S.fcs?.modes ?? {};
  const caps = S.hello?.caps;
  const show = ['MANUAL', 'STABILIZE', 'HOLD_HEADING', 'RETURN_HOME', 'HOLD'];
  return (
    <Panel title={caps?.modeNoun ?? 'Mode'}>
      <div class="chips" id="modes">
        {show.map(m => {
          const problem = modes[m];
          const cur = S.ops?.flightMode === m;
          return <button key={m} data-fm={m} class={cur ? 'sel' : ''} disabled={!isOperator() || (!!problem && !cur)} title={problem ?? ''} onClick={() => cmd('set_flight_mode', { mode: m }, { label: modeLabel(m) })}>{modeLabel(m)}</button>;
        })}
      </div>
      {modes.STABILIZE && <p class="dim">Assisted modes: {modes.STABILIZE}</p>}
    </Panel>
  );
}

function MissionPanel() {
  const m = S.ops?.mission;
  if (!m?.name) return null;
  const canRun = isOperator();
  const items = S.ops?.missionData?.items ?? [];
  const planned = items.length ? (items[items.length - 1] as any).expectAt as number | undefined : undefined;
  const late = m.lateS;
  return (
    <Panel title={`${m.hash ? 'Plan' : 'Mission'}: ${m.name}`}>
      <p id="missionState">{m.state.toLowerCase()}, point {Math.min(m.index + 1, m.total)} of {m.total}{m.step && ` (${m.step})`}{m.dist != null && `, ${m.dist.toFixed(0)} m to go`}</p>
      {m.hash && (m.state === 'RUNNING' || m.state === 'PAUSED') && <p id="planTiming" class="dim">
        T+{mmss(m.elapsedS ?? 0)}{planned !== undefined && ` of about ${mmss(planned + (late ?? 0))}`}
        {late !== undefined && <>{' · '}<span class={Math.abs(late) < 10 ? 'goodText' : late > 0 ? 'warnText' : ''}>{Math.abs(late) < 2 ? 'on time' : late > 0 ? `${Math.round(late)} s late` : `${Math.round(-late)} s early`}</span></>}
      </p>}
      {canRun && <div class="row">
        {m.state === 'RUNNING' && <><button onClick={() => cmd('mission_pause', {}, { label: 'Pause' })}>Pause</button><button onClick={() => cmd('mission_skip', {}, { label: 'Skip' })}>Skip</button><button onClick={() => cmd('mission_abort', {}, { label: 'Abort' })}>Abort</button></>}
        {m.state === 'PAUSED' && <><button onClick={() => cmd('mission_resume', {}, { label: 'Resume' })}>Resume</button><button onClick={() => cmd('mission_abort', {}, { label: 'Abort' })}>Abort</button></>}
        {m.state !== 'RUNNING' && m.state !== 'PAUSED' && <HoldButton id="missionStart" label="Hold to start" ms={1200} onHeld={() => cmd('mission_start', m.hash ? { hash: m.hash } : {}, { label: 'Start mission' })} />}
      </div>}
    </Panel>
  );
}

/** What the phone is doing about trouble: flight time, a stopped motor, going somewhere other than home. */
function SafetyPanel() {
  const o = S.ops;
  if (!o || (o.timeLeftS === undefined && !o.divert && !o.motorOut)) return null;
  return (
    <Panel title="Safety">
      {o.timeLeftS !== undefined && <p id="timeLeft" class={o.timeLeftS < 60 ? 'badText' : o.timeLeftS < 180 ? 'warnText' : 'dim'}>Flight time left about {mmss(o.timeLeftS)}, keeping the reserve (by time armed)</p>}
      {o.motorOut && <p class="badText" id="motorOut">Motor stopped: {o.motorOut}</p>}
      {o.divert && <p class="warnText" id="divert">{o.divert.glide ? 'Gliding' : 'Going'} to {o.divert.zone === 'home' ? 'home' : `${o.divert.kind} zone ${o.divert.zone}`}: {o.divert.why}</p>}
    </Panel>
  );
}

/** The simulated plane: wind, and trouble on purpose. */
function SimPanel() {
  const s = S.ops?.sim;
  if (!s || !isOperator()) return null;
  const set = (args: Record<string, unknown>, label: string) => cmd('sim_set', args, { label });
  return (
    <Panel title="Simulation" fold="sim">
      <p class="dim small">{s.onGround ? (s.crashed ? 'Crashed on landing.' : 'On the ground. Starting a plan launches it by hand.') : `Flying at ${s.airspeed} m/s, ${s.heightM} m up.`}</p>
      <div class="grid2">
        <label>Wind, m/s <input type="number" min="0" max="30" value={s.windMps} onChange={e => set({ windMps: Number(e.currentTarget.value) }, 'Wind')} /></label>
        <label>From, ° <input type="number" value={s.windFromDeg} onChange={e => set({ windFromDeg: Number(e.currentTarget.value) }, 'Wind')} /></label>
      </div>
      <div class="row">
        <button id="simMotor" class={s.motorFailed ? 'sel' : ''} onClick={() => set({ motorFailed: !s.motorFailed }, s.motorFailed ? 'Motor back' : 'Fail the motor')}>{s.motorFailed ? 'Motor failed' : 'Fail the motor'}</button>
        <button id="simLink" class={s.linkCut ? 'sel' : ''} onClick={() => set({ linkCut: !s.linkCut }, s.linkCut ? 'Link back' : 'Cut the link')}>{s.linkCut ? 'Link cut' : 'Cut the link'}</button>
        <button onClick={() => set({ reset: true }, 'Back to the start')} disabled={armed()}>Back to the start</button>
      </div>
    </Panel>
  );
}

function TriggersPanel() {
  const list = S.ops?.triggers ?? [];
  const [armedId, setArmedId] = useState<string | null>(null);
  useEffect(() => { if (!armedId) return; const t = setTimeout(() => setArmedId(null), 5000); return () => clearTimeout(t); }, [armedId]);
  if (!list.length || !isOperator()) return null;
  return (
    <Panel title="Triggers">
      <div class="chips" id="triggers">
        {list.map(t => t.danger
          ? armedId === t.id
            ? <button key={t.id} class="dangerBtn" onClick={() => { setArmedId(null); firePrepared(t); }}>Confirm: {t.label}</button>
            : <button key={t.id} class="dangerOutline" onClick={() => setArmedId(t.id)}>{t.label}</button>
          : <button key={t.id} data-trigger={t.id} onClick={() => cmd('trigger_fire', { id: t.id }, { label: t.label })}>{t.label}{t.count ? ` (${t.count})` : ''}</button>)}
      </div>
    </Panel>
  );
}

const EXPRESSIONS = ['happy', 'excited', 'sad', 'angry', 'surprised', 'scared', 'confused', 'curious', 'sleepy', 'wink', 'love', 'neutral'];
const FACE_HUES: [number, string][] = [[190, 'Cyan'], [140, 'Green'], [45, 'Amber'], [280, 'Purple'], [215, 'Blue']];

/** The phone's face: show or hide it, set an expression, and change how it looks. */
function FacePanel() {
  const [hold, setHold] = useState<number>(5);
  const face = S.ops?.face;
  if (!isOperator() || !face) return null;
  const param = (n: string) => S.params.find(p => p.name === n)?.value;
  const set = (name: string, value: number) => raw({ type: 'param_set', name, value });
  const mode = param('FACE_MODE') ?? 0;
  const style = param('FACE_STYLE') ?? 0;
  const hue = param('FACE_HUE') ?? 190;
  const status = face.blocked ? `Can't show: ${face.blocked}` : face.shown ? `Showing${face.requested ? `: ${face.requested}${face.held ? ', held' : ''}` : ''}` : 'Hidden';
  return (
    <Panel title="Face" fold="face" closed right={<span id="faceStatus" class={`pill ${face.blocked ? 'warn' : face.shown ? 'good' : 'off'}`}>{status}</span>}>
      <div class="row">
        <button id="faceShow" onClick={() => cmd('face_show', { show: true }, { label: 'Show face' })}>Show</button>
        <button id="faceHide" onClick={() => cmd('face_show', { show: false }, { label: 'Hide face' })}>Hide</button>
        <button id="faceAuto" class={face.requested ? '' : 'sel'} onClick={() => cmd('face_express', { expression: 'auto' }, { label: 'Automatic face' })} title="The face follows the device: alert when armed, looking where it turns, alarmed in failsafe">Automatic</button>
      </div>
      <div class="chips" id="faceExpressions">
        {EXPRESSIONS.map(e => <button key={e} data-expr={e} class={face.requested === e ? 'sel' : ''} onClick={() => cmd('face_express', { expression: e, seconds: hold }, { label: e })}>{e}</button>)}
      </div>
      <label class="row dim">For
        <select id="faceHold" value={hold} onChange={e => setHold(Number(e.currentTarget.value))}>
          <option value={5}>5 seconds</option><option value={30}>30 seconds</option><option value={300}>5 minutes</option><option value={0}>until changed</option>
        </select>
      </label>
      <p class="dim">Safety always shows over an expression: failsafe looks alarmed, and kill shows crossed-out eyes.</p>
      <details>
        <summary>Look and when it shows</summary>
        <div class="row">{['Off', 'When idle', 'Robot'].map((n, i) => <button key={n} data-face-mode={i} class={mode === i ? 'sel' : ''} onClick={() => set('FACE_MODE', i)}>{n}</button>)}</div>
        <p class="dim">{mode === 0 ? 'Only shows when you press Show.' : mode === 1 ? `Comes up after ${param('FACE_IDLE_S') ?? 60} s untouched.` : 'The face is the main screen, and comes back a few seconds after the phone is used.'}</p>
        <div class="row">{['Eyes', 'Eyes and mouth', 'Visor'].map((n, i) => <button key={n} data-face-style={i} class={style === i ? 'sel' : ''} onClick={() => set('FACE_STYLE', i)}>{n}</button>)}</div>
        <div class="row">{FACE_HUES.map(([h, name]) => (
          <button key={h} data-face-hue={h} title={name} aria-label={name} class={Math.abs(hue - h) < 1 ? 'sel' : ''} onClick={() => set('FACE_HUE', h)}
            style={{ background: `hsl(${h} 90% 55%)`, width: '34px', height: '34px', borderRadius: '50%', padding: 0 }} />
        ))}</div>
        <label class="row dim">Brightness
          <input type="range" min={0.05} max={1} step={0.05} value={param('FACE_BRIGHT') ?? 0.6} onChange={e => set('FACE_BRIGHT', Number(e.currentTarget.value))} />
        </label>
      </details>
    </Panel>
  );
}

function SkillsPanel() {
  const caps = S.hello?.caps;
  if (!isOperator() || (!caps?.skills?.length && !caps?.behaviors?.length)) return null;
  return (
    <Panel title="Skills" fold="skills">
      <div class="chips">
        {caps.skills.map((id: string) => <button key={id} onClick={() => cmd('run_skill', { id }, { label: id })}>{id}</button>)}
        {caps.skills.length > 0 && <button onClick={() => cmd('stop_skill', {}, { label: 'Stop skill' })}>Stop skill</button>}
        {caps.behaviors.map((id: string) => <button key={id} onClick={() => cmd('run_behavior', { id }, { label: id })}>{id}</button>)}
        {caps.behaviors.length > 0 && <button onClick={() => cmd('stop_behavior', {}, { label: 'Stop behavior' })}>Stop behavior</button>}
      </div>
    </Panel>
  );
}

function Joints() {
  const d = S.device;
  if (!d?.actuators?.length) return null;
  return (
    <Panel title="Joints" fold="joints" right={d.hardwareOpen ? <span class="pill warn">Hardware live</span> : <span class="pill off">Preview</span>}>
      <div class="joints" id="joints">
        {d.actuators.map((a: any) => {
          const f = (a.value - a.min) / (a.max - a.min || 1);
          return <div key={a.id} class="joint"><span>{a.id}</span><div class="bar"><i style={{ width: `${f * 100}%` }} /></div><b>{a.value.toFixed(1)} {a.unit}</b></div>;
        })}
      </div>
      {d.fallen && <p class="badText">Fallen</p>}
    </Panel>
  );
}

function Stats() {
  const tel = S.tel;
  const fc = S.fc;
  const gps = S.fcs?.gps;
  const t = S.fcs?.timing;
  const rows: [string, string][] = [
    ['Roll', `${fmt(fc?.a?.[0])}°`], ['Pitch', `${fmt(fc?.a?.[1])}°`], ['Heading', `${fmt(fc?.a?.[2], 0)}°`],
    ['Loop', `${fmt(tel?.loopHz, 0)} Hz`], ['Slowest step', `${fmt(tel?.loopDtMaxMs)} ms`], ['Sensor delay', `${fmt(t?.delayAvgMs)} ms`],
    ['Vibration', `${fmt(S.fcs?.vib)}`],
  ];
  if (gps?.nav) rows.push(['Navigation', `${NAV_TEXT[gps.nav.health] ?? gps.nav.health}, ±${Math.round(gps.nav.radius)} m${gps.nav.rejected ? `, ${gps.nav.rejected} jumps ignored` : ''}`]);
  if (gps) rows.push(['GPS', `±${gps.acc.toFixed(0)} m, ${gps.sats} sats`], ['GPS signal', gps.signal != null ? `${gps.signal.toFixed(0)} dB-Hz${gps.signal < 30 ? ', weak' : ''}` : '--'], ['Speed', `${gps.speed.toFixed(1)} m/s`], ['Altitude', `${gps.alt.toFixed(0)} m`]);
  if (S.fcs?.home?.dist != null) rows.push(['Home', `${S.fcs.home.dist.toFixed(0)} m`]);
  // Experimental: the propeller heard through the phone's microphone (MIC_PROP).
  const prop = S.fcs?.prop;
  if (prop) {
    rows.push(['Propeller (mic)', prop.rpm != null ? `${prop.rpm} rpm` : prop.status]);
    if (prop.warning) rows.push(['Propeller warning', prop.warning]);
    // An estimate, never an airspeed tube: shown with its error, against this plane's cruise, or with why there's none.
    if (prop.learned !== undefined) rows.push(['Airspeed estimate (prop)', prop.airText
      ? `${prop.airText} (learned ${prop.learnedAgo})`
      : prop.learned ? `none: ${prop.airWhy ?? 'out of learned range'}` : `learning: ${prop.sectors}/8 directions, level and steady flight only`]);
    if (prop.check && prop.check.startsWith('corrected')) rows.push(['Propeller check', prop.check]);
    if (prop.wind != null) rows.push(['Wind (prop)', `${prop.wind.toFixed(0)} m/s from ${prop.windFrom}°`]);
  }
  return (
    <Panel title="All numbers" fold="numbers" closed>
      <div class={`stats ${stale() ? 'isStale' : ''}`} id="stats">{rows.map(([k, v]) => <div key={k}><b>{v}</b><i>{k}</i></div>)}</div>
    </Panel>
  );
}

function Outputs() {
  const profile = S.hello?.profiles?.find((p: any) => p.id === S.tel?.profile);
  const names: string[] = S.device?.actuators ? S.device.actuators.map((a: any) => a.id) : profile?.channels?.map((c: any) => c.name) ?? [];
  const out = S.tel?.out ?? [];
  if (!names.length) return null;
  return (
    <Panel title="Outputs" fold="outputs" closed>
      <div class="joints" id="outputs">
        {names.map((n, i) => (
          <div key={n} class="joint"><span>{n}</span><div class="bar"><i style={{ width: `${Math.max(0, Math.min(1, ((out[i]?.[1] ?? 1500) - 1000) / 1000)) * 100}%` }} /></div><b>{out[i]?.[1] ?? '--'} µs</b></div>
        ))}
      </div>
    </Panel>
  );
}

export function Operate() {
  const caps = S.hello?.caps;
  const hasMap = caps?.map !== false;
  const [view, setView] = useState<'map' | 'video'>(hasMap ? 'map' : 'video');
  const [goHere, setGoHere] = useState(false);
  const fc = S.fc;
  const cam = S.config?.camera;
  const profile = S.hello?.profiles?.find((p: any) => p.id === S.tel?.profile);
  const surfaces = profile?.channels?.some((c: any) => c.pitch || c.roll);
  return (
    <div class="operate">
      <div class="mainView">
        <div class="viewBar">
          {hasMap && <button class={view === 'map' ? 'sel' : ''} onClick={() => setView('map')}>Map</button>}
          <button class={view === 'video' ? 'sel' : ''} onClick={() => setView('video')}>Video</button>
          {canAct() && <button id="camToggle" onClick={() => raw({ type: 'camera_cfg', enabled: !cam?.enabled })}>{cam?.enabled ? 'Camera off' : 'Camera on'}</button>}
          {hasMap && view === 'map' && canAct() && <button class={goHere ? 'sel' : ''} onClick={() => setGoHere(!goHere)}>{goHere ? 'Tap the map...' : 'Go here'}</button>}
        </div>
        {view === 'map' && hasMap ? <MapView goHere={goHere} /> : <VideoView />}
        <Readout />
        <div class="hud"><Horizon roll={fc?.a?.[0] ?? 0} pitch={fc?.a?.[1] ?? 0} heading={fc?.a?.[2]} stale={stale()} /></div>
        {isOperator() && armed() && <Sticks surfaces={!!surfaces} />}
      </div>
      <aside class="side">
        <ArmPanel />
        <MissionPanel />
        <SafetyPanel />
        <ModePanel />
        <TriggersPanel />
        <SimPanel />
        <FacePanel />
        <SkillsPanel />
        <Stats />
        <Joints />
        <Outputs />
      </aside>
    </div>
  );
}
