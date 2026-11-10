// Pieces shared by the workspaces.
import { useEffect, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { addToast, cmd, pointerSticks, S, armed, isOperator, type Check } from './store';
import { TileMap, type Layer } from './map';

export const statusClass = (s: string) => (s === 'ok' || s === 'pass' ? 'good' : s === 'warn' ? 'warn' : s === 'fail' || s === 'bad' ? 'bad' : 'off');
const symbol = (s: string) => (s === 'ok' || s === 'pass' ? '✓' : s === 'warn' ? '!' : s === 'fail' || s === 'bad' ? '✕' : '–');

export function CheckRow({ c }: { c: Pick<Check, 'label' | 'status' | 'detail' | 'fix'> }) {
  return (
    <div class="check">
      <span class={`mark ${statusClass(c.status)}`}>{symbol(c.status)}</span>
      <div><b>{c.label}</b><div class="dim">{c.detail}</div>{c.fix && <div class="fix">{c.fix}</div>}</div>
    </div>
  );
}

const openKey = (id: string) => `spx-open-${id}`;
const readOpen = (id: string, dflt: boolean) => { try { const v = localStorage.getItem(openKey(id)); return v === null ? dflt : v === '1'; } catch { return dflt; } };

/** A card. With `fold`, it can be closed to its title, and remembers that per browser. */
export function Panel({ title, children, right, fold, closed, id }: { title?: string; children: ComponentChildren; right?: ComponentChildren; fold?: string; closed?: boolean; id?: string }) {
  const [open, setOpen] = useState(() => (fold ? readOpen(fold, !closed) : true));
  if (!fold) return (
    <section class="panel" id={id}>
      {(title || right) && <header><h2>{title}</h2>{right}</header>}
      {children}
    </section>
  );
  return (
    <details class="panel fold" id={id} open={open} onToggle={e => { const o = (e.currentTarget as HTMLDetailsElement).open; setOpen(o); try { localStorage.setItem(openKey(fold), o ? '1' : '0'); } catch { /* Fine. */ } }}>
      <summary><h2>{title}</h2>{right}</summary>
      {children}
    </details>
  );
}

/** Light, high-contrast colours for reading in sunlight. Saved per browser. */
export function getSun() { try { return localStorage.getItem('spx-sun') === '1'; } catch { return false; } }
export function applySun(on: boolean) {
  document.documentElement.dataset.theme = on ? 'sun' : '';
  try { localStorage.setItem('spx-sun', on ? '1' : '0'); } catch { /* Fine. */ }
}

/** Slide to arm: a plain click can't do it. */
export function SlideToArm({ label, onDone }: { label: string; onDone: () => void }) {
  const track = useRef<HTMLDivElement>(null);
  const [x, setX] = useState(0);
  const start = useRef<number | null>(null);
  const max = () => (track.current?.clientWidth ?? 200) - 56;
  return (
    <div class="slide" ref={track} id="slideArm">
      <span>{label}</span>
      <div
        class="thumb" style={{ transform: `translateX(${x}px)` }}
        onPointerDown={e => { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); start.current = e.clientX - x; }}
        onPointerMove={e => { if (start.current !== null) setX(Math.max(0, Math.min(max(), e.clientX - start.current))); }}
        onPointerUp={() => { if (x >= max() - 6) onDone(); start.current = null; setX(0); }}
        onPointerCancel={() => { start.current = null; setX(0); }}
      >➜</div>
    </div>
  );
}

/** Fires once after being held this long. Letting go early does nothing. */
export function HoldButton({ label, ms, danger, onHeld, id, disabled }: { label: string; ms: number; danger?: boolean; onHeld: () => void; id?: string; disabled?: boolean }) {
  const [p, setP] = useState(0);
  const timer = useRef<any>(null);
  const stop = () => { clearInterval(timer.current); timer.current = null; setP(0); };
  useEffect(() => stop, []);
  return (
    <button
      id={id} disabled={disabled} class={`hold ${danger ? 'danger' : ''}`}
      onPointerDown={e => {
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        const t0 = Date.now();
        timer.current = setInterval(() => {
          const f = (Date.now() - t0) / ms;
          setP(Math.min(1, f));
          if (f >= 1) { stop(); onHeld(); }
        }, 30);
      }}
      onPointerUp={stop} onPointerCancel={stop} onLostPointerCapture={stop}
    >
      <i style={{ width: `${p * 100}%` }} />
      <span>{label}</span>
    </button>
  );
}

/** Runs while pressed. If this page dies mid-press, the phone stops it by itself. */
export function HoldToRun({ label, output, value, confirmed, disabled, command = 'test_output', stop = 'test_stop' }: { label: string; output?: string; value?: number; confirmed: boolean; disabled?: boolean; command?: string; stop?: string }) {
  const timer = useRef<any>(null);
  const [on, setOn] = useState(false);
  const latest = useRef({ value, confirmed });
  latest.current = { value, confirmed };
  const end = () => {
    if (timer.current === null) return;
    clearInterval(timer.current);
    timer.current = null;
    setOn(false);
    cmd(stop, {}, { quiet: true });
  };
  useEffect(() => end, []);
  const beat = () => cmd(command, output === undefined ? { confirmed: latest.current.confirmed } : { output, ...latest.current }, { quiet: true }).then(r => {
    if (!r.accepted && r.code !== 'lost') { end(); addToast(label, 'refused', r.message); }
  });
  return (
    <button
      class={`run ${on ? 'on' : ''}`} disabled={disabled}
      onPointerDown={e => {
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        setOn(true);
        beat();
        timer.current = setInterval(beat, 100);
      }}
      onPointerUp={end} onPointerCancel={end} onLostPointerCapture={end}
    >{label}</button>
  );
}

export function Stick({ x, y, onMove }: { x: 'horizontal' | null; y: 'vertical' | null; onMove: (dx: number, dy: number) => void }) {
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ x: 0, y: 0 });
  const set = (e: PointerEvent) => {
    const r = box.current!.getBoundingClientRect();
    const dx = x ? Math.max(-1, Math.min(1, (e.clientX - r.left - r.width / 2) / (r.width / 2))) : 0;
    const dy = y ? Math.max(-1, Math.min(1, (r.top + r.height / 2 - e.clientY) / (r.height / 2))) : 0;
    setPos({ x: dx, y: dy });
    onMove(dx, dy);
  };
  const release = () => { setPos({ x: 0, y: 0 }); onMove(0, 0); };
  return (
    <div class="stick" ref={box} style={{ touchAction: 'none' }}
      onPointerDown={e => { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); set(e); }}
      onPointerMove={e => { if (e.buttons || e.pointerType === 'touch') set(e); }}
      onPointerUp={release} onPointerCancel={release}>
      <div class="knob" style={{ transform: `translate(${pos.x * 40}px, ${-pos.y * 40}px)` }} />
    </div>
  );
}

/** Two on-screen sticks. Letting go centres them. Left: throttle and steering. Right: pitch and roll. */
export function Sticks({ surfaces }: { surfaces: boolean }) {
  const count = useRef(0);
  const touch = (active: boolean) => { pointerSticks.active = active; };
  useEffect(() => () => { pointerSticks.active = false; count.current = 0; }, []);
  return (
    <div class="sticks">
      <Stick x="horizontal" y="vertical" onMove={(dx, dy) => { pointerSticks.t = dy; pointerSticks.s = -dx; touch(!!(dx || dy || pointerSticks.p || pointerSticks.r)); }} />
      {surfaces && <Stick x="horizontal" y="vertical" onMove={(dx, dy) => { pointerSticks.p = dy; pointerSticks.r = -dx; touch(!!(dx || dy || pointerSticks.t || pointerSticks.s)); }} />}
    </div>
  );
}

export function Horizon({ roll, pitch, heading, stale }: { roll: number; pitch: number; heading?: number; stale?: boolean }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const c = ref.current!;
    const w = c.clientWidth, h = c.clientHeight;
    if (!w) return;
    const r = devicePixelRatio || 1;
    if (c.width !== w * r) { c.width = w * r; c.height = h * r; }
    const g = c.getContext('2d')!;
    g.setTransform(r, 0, 0, r, 0, 0);
    g.clearRect(0, 0, w, h);
    g.save();
    g.translate(w / 2, h / 2);
    // Roll is positive right side down. Like a real attitude indicator, the horizon turns the other way, so its
    // right end rises when the vehicle rolls right. The phone's own horizon (AppUi.kt) does the same.
    g.rotate(-roll * Math.PI / 180);
    const shift = pitch / 45 * h / 2;
    g.fillStyle = '#3a79b7'; g.fillRect(-w * 2, -h * 2 + shift, w * 4, h * 2);
    g.fillStyle = '#7a5230'; g.fillRect(-w * 2, shift, w * 4, h * 2);
    g.strokeStyle = '#fff'; g.lineWidth = 2;
    g.beginPath(); g.moveTo(-w * 2, shift); g.lineTo(w * 2, shift); g.stroke();
    g.lineWidth = 1; g.font = '10px system-ui, sans-serif'; g.fillStyle = '#fff';
    for (const d of [-30, -20, -10, 10, 20, 30]) {
      const y = shift - d / 45 * h / 2;
      g.beginPath(); g.moveTo(-18, y); g.lineTo(18, y); g.stroke();
      g.fillText(String(d), 22, y + 3);
    }
    g.restore();
    g.strokeStyle = '#ffd33d'; g.lineWidth = 3;
    g.beginPath(); g.moveTo(w / 2 - 42, h / 2); g.lineTo(w / 2 - 12, h / 2); g.moveTo(w / 2 + 12, h / 2); g.lineTo(w / 2 + 42, h / 2); g.stroke();
    if (heading !== undefined) {
      g.fillStyle = 'rgba(0,0,0,.6)'; g.fillRect(w / 2 - 24, 4, 48, 20);
      g.fillStyle = '#fff'; g.font = 'bold 13px system-ui, sans-serif'; g.textAlign = 'center';
      g.fillText(`${Math.round((heading + 360) % 360)}°`, w / 2, 19);
      g.textAlign = 'start';
    }
    if (stale) { g.fillStyle = 'rgba(0,0,0,.55)'; g.fillRect(0, 0, w, h); }
  });
  return <canvas class="horizon" id="horizon" ref={ref} />;
}

/** A map tied to a component's life, with the layer switch. */
export function useMap(setup: (m: TileMap) => void) {
  const ref = useRef<HTMLCanvasElement>(null);
  const map = useRef<TileMap | null>(null);
  useEffect(() => {
    const m = new TileMap(ref.current!);
    try { m.layer = (localStorage.getItem('spx-layer') as Layer) || 'satellite'; } catch { /* Default it is. */ }
    map.current = m;
    setup(m);
    m.draw();
    return () => m.destroy();
  }, []);
  return { ref, map };
}

export function LayerSwitch({ map }: { map: TileMap | null }) {
  return (<>
    <div class="zoomCtl" role="group" aria-label="Zoom">
      <button aria-label="Zoom in" title="Zoom in" onClick={() => map?.zoomBy(1)}>+</button>
      <button aria-label="Zoom out" title="Zoom out" onClick={() => map?.zoomBy(-1)}>−</button>
    </div>
    <div class="maplayer">
      {(['satellite', 'street'] as Layer[]).map(l => (
        <button key={l} class={map?.layer === l ? 'sel' : ''} onClick={() => { if (!map) return; map.layer = l; try { localStorage.setItem('spx-layer', l); } catch { /* Fine. */ } map.draw(); }}>{l === 'satellite' ? 'Satellite' : 'Street'}</button>
      ))}
    </div>
  </>);
}

export const canAct = () => isOperator() && !!S.tel;
export const modeLabel = (m?: string) => ({ MANUAL: 'Manual', STABILIZE: 'Stabilize', HOLD_HEADING: 'Hold heading', RETURN_HOME: 'Return home', AUTO: 'Mission', HOLD: 'Hold' } as Record<string, string>)[m ?? ''] ?? m ?? '--';
export const isArmed = armed;
export const fmt = (v: number | null | undefined, d = 1) => (v === null || v === undefined ? '--' : v.toFixed(d));
