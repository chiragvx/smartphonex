// Build what it will do: a plan file on a map, checked live by the phone, or poses on a timeline for jointed devices.
import { useEffect, useRef, useState } from 'preact/hooks';
import { authQuery, cmd, isOperator, S, type FenceData } from './store';
import { HoldButton, LayerSwitch, Panel, useMap } from './parts';
import { drawHome, drawVehicle, type LatLon, type TileMap } from './map';

// --- The plan file (docs/PLANNING.md). Positions are [lat, lon].
type LL = [number, number];
type Action = { do: 'trigger' | 'notify'; id?: string; everyM?: number; text?: string };
type Miss = string | { do: string; tolerance?: number; maxS?: number; then?: string };
type Step = {
  id: string; do: string; at?: LL; points?: LL[]; area?: LL[]; center?: LL; path?: LL[];
  alt?: number; speed?: number; radius?: number; seconds?: number; laps?: number; dir?: string;
  spacing?: number; angle?: number; width?: number; heading?: number; slope?: number; approachAlt?: number;
  times?: number; steps?: Step[]; actions?: Action[]; miss?: { altitude?: Miss; arrival?: Miss };
};
type Zone = { id: string; kind: 'landing' | 'ditch'; at: LL; radius?: number };
type PlanFile = {
  format: string; name: string; home?: LL; bvlos?: boolean; defaults?: { speed?: number; alt?: number; radius?: number };
  onEnd?: string; onLinkLoss?: string; zones?: Zone[]; steps: Step[];
};
type Issue = { step: string | null; text: string; lat?: number; lon?: number };
type Ev = { t: number; lo: number; hi: number; step: string | null; kind: string; text: string; lat: number; lon: number; alt?: number; until?: number };
type Gap = { kind: string; step: string; fromS: number; toS: number; lat: number; lon: number };
type Checked = {
  ok: boolean; problems: Issue[]; warnings: Issue[]; hash: string | null; timeline: Ev[]; gaps: Gap[];
  summary: { distanceM: number; timeS: number; timeLoS: number; timeHiS: number; batteryPct: number; maxAltM?: number; points: number };
  assumptions: Record<string, string | number>; path: [number, number, number, number][];
  advisories?: Advisory[]; disclaimer?: string;
};
type Advisory = { id: string; level: string; text: string; source: string; lat?: number; lon?: number };
type AdvLayer = { id: string; name: string; source: string; date: string; level: string; count: number; features?: { name: string | null; polygon: [number, number][] }[] };

// Advisory layers on the map: airport bands and imported zones. Information only: see the disclaimer.
let airportsView: { code: string; name: string; lat: number; lon: number; allowanceKm: number }[] = [];
let layersView: AdvLayer[] = [];
let showAirports = false;
let showLayers = true;

const FORMAT = 'smartphonex.plan/1';
const copy = <T,>(x: T): T => JSON.parse(JSON.stringify(x));
const emptyFence = (): FenceData => ({ polygon: [], keepOut: [], maxDistM: 0, maxAltM: 0, action: 'hold', outerM: 0 });
const emptyPlan = (): PlanFile => ({ format: FORMAT, name: 'Plan', onEnd: 'home', onLinkLoss: 'home', zones: [], steps: [] });
const ll = (p: LatLon): LL => [+p.lat.toFixed(7), +p.lon.toFixed(7)];
const mmss = (s: number) => { const t = Math.max(0, Math.round(s)); return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`; };
const mid = (a: LL, b: LL): LL => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
const bearing = (a: LL, b: LL) => {
  const r = Math.PI / 180, y = Math.sin((b[1] - a[1]) * r) * Math.cos(b[0] * r);
  const x = Math.cos(a[0] * r) * Math.sin(b[0] * r) - Math.sin(a[0] * r) * Math.cos(b[0] * r) * Math.cos((b[1] - a[1]) * r);
  return Math.round(((Math.atan2(y, x) / r) + 360) % 360);
};
const KIND_LABEL: Record<string, string> = {
  goto: 'Go to', path: 'Path', hold: 'Hold', orbit: 'Orbit', survey: 'Survey', corridor: 'Corridor', takeoff: 'Take off', land: 'Land', home: 'Home', repeat: 'Repeat',
};

// Kept outside the component, so switching workspace doesn't lose a half-made plan.
type Draft = { plan: PlanFile; fence: FenceData };
let draft: Draft = { plan: emptyPlan(), fence: emptyFence() };
let loadedFromPhone = false;
let fenceSent = true;
let anchorsDraft: { name: string; lat: number; lon: number }[] | null = null;
const undoStack: Draft[] = [];
const redoStack: Draft[] = [];

/** A new id for a step or zone, unique in the plan. */
function newId(kind: string) {
  const used = new Set<string>([...draft.plan.steps.map(s => s.id), ...(draft.plan.zones ?? []).map(z => z.id)]);
  for (let n = 1; ; n++) if (!used.has(`${kind}-${n}`)) return `${kind}-${n}`;
}
/** The position a step is drawn and inserted at. */
const anchor = (s: Step): LL | undefined => s.at ?? s.center ?? s.area?.[0] ?? s.path?.[0] ?? s.points?.[s.points.length - 1];

type Tool = 'goto' | 'survey' | 'corridor' | 'orbit' | 'hold' | 'land' | 'zone-landing' | 'zone-ditch' | 'home' | 'fence' | 'keepout' | 'anchor';
/** The tools, in the groups the toolbar shows them in. */
const TOOL_GROUPS: [string, Tool[]][] = [
  ['Route', ['goto', 'survey', 'corridor', 'orbit', 'hold', 'land']],
  ['Safety', ['zone-landing', 'zone-ditch', 'fence', 'keepout']],
  ['Place', ['home', 'anchor']],
];
const TOOLS: [Tool, string, string][] = [
  ['goto', 'Point', 'Click to add a point after the selected step. Drag anything to move it; click + on a leg to insert.'],
  ['survey', 'Survey', 'Click the corners of the area, then Finish.'],
  ['corridor', 'Corridor', 'Click along the path, then Finish.'],
  ['orbit', 'Orbit', 'Click the centre to circle.'],
  ['hold', 'Hold', 'Click where to wait.'],
  ['land', 'Land', 'Click the touchdown point. It lands heading the way it arrives; change it in the step.'],
  ['zone-landing', 'Landing zone', 'Click a place it can land safely if something goes wrong.'],
  ['zone-ditch', 'Ditch zone', 'Click a place it can come down with damage but hurting nobody.'],
  ['home', 'Planning home', 'Click where it will start. The phone\'s own home replaces it when sent.'],
  ['fence', 'Fence', 'Click to outline where it may go.'],
  ['keepout', 'Keep-out', 'Click to outline a place to stay out of, then Finish.'],
  ['anchor', 'Anchor', 'Click to mark points (A, B, C...) for the AI to build a plan around. It never has to guess coordinates.'],
];

/** Something on the map that can be dragged. */
type Handle = { at: LL; set: (p: LL) => void; remove?: () => void; label?: string; color: string; step?: number };

function MapPlan() {
  const [, force] = useState(0);
  const [tool, setTool] = useState<Tool>('goto');
  const [sel, setSel] = useState(-1);
  const [checked, setChecked] = useState<Checked | null>(null);
  const [checking, setChecking] = useState(false);
  const [sendMsg, setSendMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [library, setLibrary] = useState<{ name: string; savedAt: number; steps: number }[] | null>(null);
  const [picked, setPicked] = useState<Handle | null>(null);
  const [show3d, setShow3d] = useState(false);
  const drawing = useRef<LL[]>([]);
  const toolRef = useRef(tool); toolRef.current = tool;
  const selRef = useRef(sel); selRef.current = sel;
  const hoverRef = useRef(hover); hoverRef.current = hover;
  const checkedRef = useRef(checked); checkedRef.current = checked;
  const dragging = useRef<{ h: Handle; moved: boolean } | null>(null);
  const mapRef = useRef<TileMap | null>(null);
  const plane = S.hello?.caps?.vehicleType === 'plane';
  const plan = draft.plan;

  // --- Checking: every change is compiled on the phone, a moment after the last edit.
  const seq = useRef(0);
  const timer = useRef<any>(null);
  const runCheck = async () => {
    const mine = ++seq.current;
    // The plan as it is now: this can run from a click handler made before the plan was replaced.
    if (!draft.plan.steps.length) { setChecked(null); return; }
    setChecking(true);
    const r = await cmd('plan_check', { plan: withHome() }, { quiet: true });
    if (mine !== seq.current) return;
    setChecking(false);
    if (r.data) setChecked(r.data as Checked);
    else setChecked({ ok: false, problems: [{ step: null, text: r.message || 'No answer from the phone' }], warnings: [], hash: null, timeline: [], gaps: [], summary: { distanceM: 0, timeS: 0, timeLoS: 0, timeHiS: 0, batteryPct: 0, points: 0 }, assumptions: {}, path: [] });
    mapRef.current?.draw();
  };
  const queueCheck = () => { clearTimeout(timer.current); timer.current = setTimeout(runCheck, 450); };
  /** Without a phone home, the plan carries one, so it can still be checked. */
  const withHome = (): PlanFile => {
    const p = copy(draft.plan);
    if (!p.home) { const h = S.fcs?.home ?? S.fcs?.gps; if (h) p.home = [h.lat, h.lon]; }
    return p;
  };

  const refresh = () => { force(x => x + 1); mapRef.current?.draw(); };
  const remember = () => { undoStack.push(copy(draft)); if (undoStack.length > 100) undoStack.shift(); redoStack.length = 0; setSendMsg(null); };
  const edit = (fn: () => void) => { remember(); fn(); refresh(); queueCheck(); };
  const editFence = (fn: () => void) => { fenceSent = false; edit(fn); };
  const undo = () => { const d = undoStack.pop(); if (d) { redoStack.push(copy(draft)); draft = d; setSel(-1); refresh(); queueCheck(); } };
  const redo = () => { const d = redoStack.pop(); if (d) { undoStack.push(copy(draft)); draft = d; setSel(-1); refresh(); queueCheck(); } };

  /** Puts a new step after the selected one, and selects it. */
  const insert = (s: Step, at = selRef.current >= 0 ? selRef.current + 1 : draft.plan.steps.length) => {
    draft.plan.steps.splice(at, 0, s);
    setSel(at);
  };

  const handles = (): Handle[] => {
    const out: Handle[] = [];
    const p = draft.plan;
    p.steps.forEach((s, i) => {
      const c = i === selRef.current ? '#58a6ff' : '#0e1116';
      if (s.at) out.push({ at: s.at, set: v => { s.at = v; }, label: String(i + 1), color: c, step: i });
      if (s.center) out.push({ at: s.center, set: v => { s.center = v; }, label: String(i + 1), color: c, step: i });
      for (const key of ['area', 'path', 'points'] as const) {
        const arr = s[key];
        arr?.forEach((v, k) => out.push({ at: v, set: n => { arr[k] = n; }, remove: arr.length > (key === 'area' ? 3 : 2) ? () => { arr.splice(k, 1); } : undefined, label: k === 0 ? String(i + 1) : '', color: c, step: i }));
      }
    });
    (p.zones ?? []).forEach(z => out.push({ at: z.at, set: v => { z.at = v; }, remove: () => { p.zones = p.zones!.filter(x => x !== z); }, label: z.kind === 'ditch' ? 'D' : 'L', color: z.kind === 'ditch' ? '#c2410c' : '#15803d' }));
    if (p.home) out.push({ at: p.home, set: v => { p.home = v; }, remove: () => { delete p.home; }, label: 'h', color: '#3dbe6b' });
    const f = draft.fence;
    f.polygon.forEach((v, k) => out.push({ at: v, set: n => { f.polygon[k] = n; fenceSent = false; }, remove: () => { f.polygon.splice(k, 1); fenceSent = false; }, color: '#e5a823' }));
    f.keepOut.forEach(ko => ko.forEach((v, k) => out.push({ at: v, set: n => { ko[k] = n; fenceSent = false; }, remove: () => { ko.splice(k, 1); if (ko.length < 3) f.keepOut = f.keepOut.filter(x => x !== ko); fenceSent = false; }, color: '#e5484d' })));
    return out;
  };
  /** "+" between two steps' positions inserts a point there. */
  const midpoints = () => {
    const out: { at: LL; after: number }[] = [];
    const s = draft.plan.steps;
    for (let i = 0; i + 1 < s.length; i++) { const a = anchor(s[i]), b = anchor(s[i + 1]); if (a && b) out.push({ at: mid(a, b), after: i }); }
    return out;
  };

  const { ref, map } = useMap((m: TileMap) => {
    m.overlay = (mm, g) => paint(mm, g);
    const near = (at: LL, x: number, y: number, r = 14) => { const s = m.toScreen(at[0], at[1]); return Math.hypot(s.x - x, s.y - y) < r; };
    m.onPress = (_p, x, y) => {
      if (!isOperator()) return false;
      const h = handles().reverse().find(h => near(h.at, x, y));
      if (!h) return false;
      remember();
      dragging.current = { h, moved: false };
      if (h.step !== undefined) setSel(h.step);
      return true;
    };
    m.onDrag = p => { const d = dragging.current; if (d) { d.h.set(ll(p)); d.moved = true; d.h.at = ll(p); m.draw(); } };
    m.onRelease = () => {
      const d = dragging.current;
      dragging.current = null;
      if (!d) return;
      if (d.moved) { if (d.h.color === '#e5a823' || d.h.color === '#e5484d') fenceSent = false; refresh(); queueCheck(); }
      else { undoStack.pop(); setPicked(d.h); }
    };
    m.onClick = (p, x, y) => {
      if (!isOperator()) return;
      const plus = midpoints().find(mp => near(mp.at, x, y, 10));
      const at = ll(p);
      const t = toolRef.current;
      if (plus) { edit(() => insert({ id: newId('goto'), do: 'goto', at: plus.at }, plus.after + 1)); return; }
      if (t === 'survey' || t === 'corridor' || t === 'keepout') { drawing.current.push(at); refresh(); return; }
      if (t === 'fence') { editFence(() => { draft.fence.polygon.push(at); }); return; }
      edit(() => {
        const p = draft.plan;
        if (t === 'goto') insert({ id: newId('goto'), do: 'goto', at });
        else if (t === 'hold') insert({ id: newId('hold'), do: 'hold', at, seconds: 30 });
        else if (t === 'orbit') insert({ id: newId('orbit'), do: 'orbit', center: at, radius: plane ? 60 : 15, laps: 1, dir: 'cw' });
        else if (t === 'land') {
          const prev = [...p.steps].reverse().map(anchor).find(Boolean) ?? p.home;
          insert({ id: newId('land'), do: 'land', at, heading: prev ? bearing(prev, at) : 0 }, p.steps.length);
        }
        else if (t === 'home') p.home = at;
        else if (t === 'anchor') { const list = anchorsNow(); if (list.length < 26) setAnchors([...list, { name: String.fromCharCode(65 + list.length), lat: at[0], lon: at[1] }]); }
        else if (t === 'zone-landing' || t === 'zone-ditch') (p.zones ??= []).push({ id: newId('zone'), kind: t === 'zone-ditch' ? 'ditch' : 'landing', at, radius: 30 });
      });
    };
    mapRef.current = m;
    const pts = draft.plan.steps.map(anchor).filter(Boolean) as LL[];
    const gps = S.fcs?.gps;
    if (pts.length) m.fit(pts.map(([lat, lon]) => ({ lat, lon })));
    else if (gps) { m.center = { lat: gps.lat, lon: gps.lon }; m.zoom = 17; }
    force(x => x + 1);
  });

  const finishDrawing = () => {
    const pts = drawing.current;
    const t = toolRef.current;
    if (t === 'survey' && pts.length >= 3) edit(() => insert({ id: newId('survey'), do: 'survey', area: pts, spacing: plane ? 60 : 10, angle: 0 }));
    else if (t === 'corridor' && pts.length >= 2) edit(() => insert({ id: newId('corridor'), do: 'corridor', path: pts, width: 0 }));
    else if (t === 'keepout' && pts.length >= 3) editFence(() => { draft.fence.keepOut.push(pts); });
    drawing.current = [];
    refresh();
  };

  // --- Drawing.
  const paint = (m: TileMap, g: CanvasRenderingContext2D) => {
    const P = (a: LL | [number, number, ...number[]]) => m.toScreen(a[0], a[1]);
    const poly = (pts: LL[], stroke: string, fill: string, closed = true, dash: number[] = []) => {
      if (!pts.length) return;
      g.beginPath(); pts.forEach((a, i) => { const s = P(a); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); });
      if (closed) g.closePath();
      g.setLineDash(dash); g.lineWidth = 2; g.strokeStyle = stroke; if (closed) { g.fillStyle = fill; g.fill(); } g.stroke(); g.setLineDash([]);
    };
    const p = draft.plan, f = draft.fence, c = checkedRef.current;
    if (showAirports) for (const a of airportsView) {
      const s = P([a.lat, a.lon]);
      for (const [km, color] of [[12, 'rgba(250,204,21,.55)'], [8, 'rgba(250,204,21,.9)']] as const) {
        g.beginPath(); g.arc(s.x, s.y, (km + a.allowanceKm) * 1000 / m.metersPerPixel(), 0, 7);
        g.setLineDash(km === 12 ? [6, 6] : []); g.lineWidth = 2; g.strokeStyle = color; g.stroke(); g.setLineDash([]);
      }
      g.fillStyle = 'rgba(250,204,21,.95)'; g.font = '11px system-ui, sans-serif'; g.fillText(`${a.code} 8/12 km (approx.)`, s.x + 6, s.y - 6);
    }
    if (showLayers) for (const l of layersView) for (const ft of l.features ?? []) {
      poly(ft.polygon, l.level === 'red' ? '#dc2626' : l.level === 'yellow' ? '#eab308' : '#9ca3af', l.level === 'red' ? 'rgba(220,38,38,.18)' : l.level === 'yellow' ? 'rgba(234,179,8,.15)' : 'rgba(156,163,175,.18)');
    }
    poly(f.polygon, '#e5a823', 'rgba(229,168,35,.06)');
    f.keepOut.forEach(k => poly(k, '#e5484d', 'rgba(229,72,77,.22)'));
    for (const s of p.steps) { if (s.area) poly(s.area, 'rgba(255,255,255,.7)', 'rgba(255,255,255,.08)', true, [4, 4]); if (s.path) poly(s.path, 'rgba(255,255,255,.7)', '', false, [4, 4]); }
    // Zones, at their size.
    for (const z of p.zones ?? []) {
      const s = P(z.at), r = Math.max(6, (z.radius ?? 20) / m.metersPerPixel());
      g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fillStyle = z.kind === 'ditch' ? 'rgba(194,65,12,.25)' : 'rgba(21,128,61,.25)'; g.fill();
      g.lineWidth = 2; g.strokeStyle = z.kind === 'ditch' ? '#fb923c' : '#4ade80'; g.stroke();
    }
    // The path as the phone says it will really be flown or driven; stretches with no safe reach in red.
    if (c?.path.length) {
      const inGap = (t: number) => c.gaps.some(gp => t >= gp.fromS && t <= gp.toS);
      for (const [color, test] of [['#ffffff', (t: number) => !inGap(t)], ['#e5484d', inGap]] as const) {
        g.beginPath(); let on = false;
        c.path.forEach((q, i) => {
          const s = P(q); const ok = test(q[3]) || (i > 0 && test(c.path[i - 1][3]));
          if (ok) { on ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); on = true; } else on = false;
        });
        g.lineWidth = color === '#ffffff' ? 2.5 : 4; g.strokeStyle = color; g.stroke();
      }
    } else {
      const pts = p.steps.map(anchor).filter(Boolean) as LL[];
      if (pts.length > 1) poly(pts, 'rgba(255,255,255,.6)', '', false, [8, 6]);
    }
    // Where things happen, from the timeline.
    for (const e of c?.timeline ?? []) {
      if (e.kind !== 'action' && e.kind !== 'notify' && e.kind !== 'circle' && e.kind !== 'hold' && e.kind !== 'land') continue;
      const s = P([e.lat, e.lon]);
      g.beginPath(); g.arc(s.x, s.y, 5, 0, 7);
      g.fillStyle = e.kind === 'action' ? '#facc15' : e.kind === 'circle' ? '#e5a823' : '#c4b5fd'; g.fill();
      g.lineWidth = 1.5; g.strokeStyle = '#0e1116'; g.stroke();
    }
    // Problems where they are.
    for (const pr of c?.problems ?? []) {
      if (pr.lat === undefined || pr.lon === undefined) continue;
      const s = P([pr.lat, pr.lon]);
      g.lineWidth = 3; g.strokeStyle = '#e5484d';
      g.beginPath(); g.moveTo(s.x - 7, s.y - 7); g.lineTo(s.x + 7, s.y + 7); g.moveTo(s.x + 7, s.y - 7); g.lineTo(s.x - 7, s.y + 7); g.stroke();
    }
    // Half-drawn area or path.
    if (drawing.current.length) poly(drawing.current, '#58a6ff', 'rgba(88,166,255,.1)', toolRef.current !== 'corridor', [3, 3]);
    // Handles, then "+" to insert on each leg.
    g.font = 'bold 11px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    for (const h of handles()) {
      const s = P(h.at), r = h.label ? 11 : 6;
      g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fillStyle = h.color; g.fill(); g.lineWidth = 2; g.strokeStyle = '#fff'; g.stroke();
      if (h.label) { g.fillStyle = '#fff'; g.fillText(h.label, s.x, s.y + 1); }
    }
    if (isOperator()) for (const mp of midpoints()) {
      const s = P(mp.at);
      g.beginPath(); g.arc(s.x, s.y, 7, 0, 7); g.fillStyle = 'rgba(14,17,22,.75)'; g.fill(); g.lineWidth = 1.5; g.strokeStyle = 'rgba(255,255,255,.8)'; g.stroke();
      g.fillStyle = '#fff'; g.fillText('+', s.x, s.y);
    }
    g.textAlign = 'start'; g.textBaseline = 'alphabetic';
    // The moment the profile is pointing at.
    const hv = hoverRef.current;
    if (hv !== null && c?.path[hv]) { const s = P(c.path[hv]); g.beginPath(); g.arc(s.x, s.y, 7, 0, 7); g.fillStyle = '#58a6ff'; g.fill(); g.lineWidth = 2; g.strokeStyle = '#fff'; g.stroke(); }
    const home = S.fcs?.home;
    if (home) drawHome(m, g, home.lat, home.lon);
    const gps = S.fcs?.gps;
    if (gps) drawVehicle(m, g, gps.lat, gps.lon, S.fc?.a?.[2] ?? 0, gps.acc);
    // Anchors for the AI, and the AI's proposal as a dashed outline to compare.
    g.font = 'bold 11px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    for (const a of anchorsNow()) {
      const s = P([a.lat, a.lon]);
      g.beginPath(); g.moveTo(s.x, s.y); g.lineTo(s.x - 8, s.y - 16); g.lineTo(s.x + 8, s.y - 16); g.closePath();
      g.fillStyle = '#a855f7'; g.fill(); g.lineWidth = 1.5; g.strokeStyle = '#fff'; g.stroke();
      g.fillStyle = '#fff'; g.fillText(a.name, s.x, s.y - 11);
    }
    g.textAlign = 'start'; g.textBaseline = 'alphabetic';
    const prop = S.ops?.planProposal?.plan as PlanFile | undefined;
    if (prop?.steps) {
      const pts = prop.steps.map(anchor).filter(Boolean) as LL[];
      if (pts.length > 1) poly(pts, '#a855f7', '', false, [6, 5]);
      for (const st of prop.steps) if (st.area) poly(st.area, '#a855f7', 'rgba(168,85,247,.08)', true, [6, 5]);
    }
  };

  // The first time, start from what's on the phone: the sent plan and the fence.
  const phone = S.ops;
  useEffect(() => {
    if (loadedFromPhone || !phone) return;
    loadedFromPhone = true;
    if (phone.planData && !draft.plan.steps.length) draft.plan = normalise(copy(phone.planData));
    if (phone.fence.data) draft.fence = { ...emptyFence(), ...copy(phone.fence.data) };
    refresh();
    queueCheck();
    const pts = draft.plan.steps.map(anchor).filter(Boolean) as LL[];
    if (pts.length) mapRef.current?.fit(pts.map(([lat, lon]) => ({ lat, lon })));
  }, [phone]);
  useEffect(() => { queueCheck(); }, []);
  useEffect(() => { mapRef.current?.draw(); }, [checked, hover, sel]);

  const focus = (lat?: number, lon?: number, step?: string | null) => {
    if (step) { const i = plan.steps.findIndex(s => s.id === step || s.id === step.split('#')[0]); if (i >= 0) setSel(i); }
    const m = mapRef.current;
    if (m && lat !== undefined && lon !== undefined) { m.center = { lat, lon }; m.draw(); }
  };

  // --- Sending.
  const send = async () => {
    setSendMsg(null);
    if (!fenceSent) {
      const f = await cmd('fence_set', { fence: draft.fence }, { label: 'Send fence' });
      if (!f.accepted) { setSendMsg({ ok: false, text: f.message }); return; }
      fenceSent = true;
    }
    let r = await cmd('plan_upload', { plan: draft.plan }, { label: 'Send plan', quiet: true });
    if (r.code === 'needs_ack') {
      const adv: Advisory[] = r.data?.advisories ?? [];
      if (!S.ops?.advisoryDisclaimerAt) {
        if (!confirm(`${r.data?.disclaimer}\n\nI understand.`)) { setSendMsg({ ok: false, text: 'Not sent.' }); return; }
        await cmd('advisory_disclaimer_ack', {}, { quiet: true });
      }
      if (!confirm(`Before sending, read these notices. They don't stop the plan.\n\n${adv.map(a => '- ' + a.text + '\n   (' + a.source + ')').join('\n\n')}\n\nAcknowledge and send?`)) { setSendMsg({ ok: false, text: "Not sent: the notices weren't acknowledged." }); return; }
      r = await cmd('plan_upload', { plan: draft.plan, acknowledged: adv.map(a => a.id) }, { label: 'Send plan' });
    }
    if (r.data) setChecked(r.data as Checked);
    setSendMsg({ ok: r.accepted, text: r.accepted ? 'On the phone, checked against its home.' : r.message });
  };
  const onPhone = S.ops?.mission?.hash;
  const sentHash = checked?.hash && onPhone === checked.hash;

  // --- Saved plans and files.
  const listPlans = async () => { const r = await cmd('plan_list', {}, { quiet: true }); setLibrary(r.data?.plans ?? []); };
  const savePlan = async () => { const r = await cmd('plan_save', { name: plan.name, plan: draft.plan }, { label: 'Save plan' }); if (r.accepted) listPlans(); };
  const loadPlan = async (name: string) => {
    const r = await cmd('plan_load', { name }, { label: 'Open plan' });
    if (!r.data?.plan) return;
    edit(() => { draft.plan = normalise(r.data.plan); });
    setSel(-1);
    const pts = draft.plan.steps.map(anchor).filter(Boolean) as LL[];
    if (pts.length) mapRef.current?.fit(pts.map(([lat, lon]) => ({ lat, lon })));
  };
  const deletePlan = async (name: string) => { if (confirm(`Delete the saved plan "${name}"?`)) { await cmd('plan_delete', { name }, { label: 'Delete plan' }); listPlans(); } };
  const download = (text: string, name: string, type: string) => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type }));
    a.download = name; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };
  const exportPlan = () => download(JSON.stringify(draft.plan, null, 1), `${plan.name.replace(/\W+/g, '-')}.plan.json`, 'application/json');
  /** For ArduPilot and PX4 tools: the route as it will be flown, with the fence and safe zones. */
  const exportQgc = async () => {
    const r = await cmd('plan_export_qgc', { plan: withHome() }, { label: 'Export for QGroundControl' });
    if (r.accepted) download(JSON.stringify(r.data.qgc, null, 1), `${plan.name.replace(/\W+/g, '-')}.plan`, 'application/json');
  };
  const importFile = async (file: File) => {
    try {
      const g = JSON.parse(await file.text());
      if (g.format === FORMAT) { edit(() => { draft.plan = normalise(g); }); setSel(-1); return; }
      if (g.fileType === 'Plan') {
        const r = await cmd('plan_import_qgc', { qgc: g }, { label: 'Open QGroundControl plan' });
        if (!r.accepted) { setSendMsg({ ok: false, text: r.message }); return; }
        editFence(() => { draft.plan = normalise({ ...r.data.plan, name: file.name.replace(/\.\w+$/, '') }); if (r.data.fence) draft.fence = { ...emptyFence(), ...r.data.fence }; });
        setSel(-1);
        setSendMsg({ ok: true, text: ['Opened from QGroundControl.', ...(r.data.notes ?? [])].join(' · ') });
        return;
      }
      // GeoJSON: points and lines become points to go to; polygons become the fence and keep-out areas.
      const next = emptyPlan();
      next.name = g.name ?? file.name.replace(/\.\w+$/, '');
      const unring = (r: number[][]) => r.slice(0, -1).map(([lo, la]) => [la, lo] as LL);
      const fence = copy(draft.fence);
      for (const ft of g.features ?? []) {
        const pr = ft.properties ?? {};
        if (ft.geometry?.type === 'Point') { const [lon, lat, alt] = ft.geometry.coordinates; next.steps.push({ id: `goto-${next.steps.length + 1}`, do: 'goto', at: [lat, lon], ...(alt ? { alt } : {}) }); }
        else if (ft.geometry?.type === 'LineString') ft.geometry.coordinates.forEach(([lon, lat]: number[]) => next.steps.push({ id: `goto-${next.steps.length + 1}`, do: 'goto', at: [lat, lon] }));
        else if (ft.geometry?.type === 'Polygon') { if (pr.kind === 'keepout') fence.keepOut.push(unring(ft.geometry.coordinates[0])); else fence.polygon = unring(ft.geometry.coordinates[0]); }
      }
      editFence(() => { draft.plan = next; draft.fence = fence; });
      setSel(-1);
    } catch { setSendMsg({ ok: false, text: 'That file is neither a plan nor GeoJSON.' }); }
  };

  const cur = plan.steps[sel];
  const triggers = S.ops?.triggers ?? [];
  const num = (v: string) => (v === '' ? undefined : Number(v));
  const toolHelp = TOOLS.find(t => t[0] === tool)?.[2] ?? '';
  const drawingNow = (tool === 'survey' || tool === 'corridor' || tool === 'keepout') && drawing.current.length > 0;
  // Keys: Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z), Enter finishes drawing, Escape cancels it, Delete removes the selected step.
  const keys = useRef<{ undo: () => void; redo: () => void; finish: (() => void) | null; cancel: () => void; remove: (() => void) | null }>(null as any);
  keys.current = {
    undo, redo, finish: drawingNow ? finishDrawing : null,
    cancel: () => { if (drawing.current.length) { drawing.current = []; refresh(); } else if (picked) setPicked(null); else setSel(-1); },
    remove: sel >= 0 && sel < plan.steps.length ? () => { edit(() => { plan.steps.splice(sel, 1); }); setSel(-1); } : null,
  };
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t.closest('input, textarea, select, [contenteditable]')) return;
      const k = keys.current;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === 'z' && !e.shiftKey) k.undo();
      else if (mod && (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey))) k.redo();
      else if (e.key === 'Enter' && k.finish) k.finish();
      else if (e.key === 'Escape') k.cancel();
      else if ((e.key === 'Delete' || e.key === 'Backspace') && k.remove) k.remove();
      else return;
      e.preventDefault();
    };
    addEventListener('keydown', h);
    return () => removeEventListener('keydown', h);
  }, []);
  const stepProblems = (id: string) => (checked?.problems ?? []).filter(p => p.step?.split('#')[0] === id).length;

  return (
    <div class="plan">
      <div class="mainView">
        <div class="viewBar toolbar">
          {TOOL_GROUPS.map(([group, ids]) => (
            <div class="toolGroup" key={group} role="group" aria-label={group}>
              <span class="toolLabel">{group}</span>
              {ids.filter(id => plane || id !== 'land').map(id => (
                <button key={id} data-tool={id} class={tool === id ? 'sel' : ''} onClick={() => { setTool(id); drawing.current = []; refresh(); }}>{TOOLS.find(t => t[0] === id)![1]}</button>
              ))}
            </div>
          ))}
          <span class="grow" />
          <div class="toolGroup">
            <button onClick={undo} disabled={!undoStack.length} title="Ctrl+Z">Undo</button>
            <button onClick={redo} disabled={!redoStack.length} title="Ctrl+Y">Redo</button>
            <button id="view3d" class={show3d ? 'sel' : ''} disabled={!checked?.path.length} onClick={() => setShow3d(!show3d)}>3D</button>
            <button id="newPlan" disabled={!plan.steps.length && !(plan.zones ?? []).length} onClick={() => { if (!plan.steps.length || confirm('Start a new plan? The steps and zones here are cleared (Undo brings them back).')) { edit(() => { draft.plan = emptyPlan(); }); setSel(-1); setChecked(null); } }}>New plan</button>
          </div>
        </div>
        <div class="mapwrap">
          <canvas ref={ref} id="planMap" />
          <LayerSwitch map={map.current} />
          <div class="onmap dim">{toolHelp}{drawingNow && ' Enter finishes, Escape cancels.'}</div>
          {show3d && checked && <View3D c={checked} zones={plan.zones ?? []} onClose={() => setShow3d(false)} />}
          {drawingNow && <div class="mapActions">
            <button class="sel" id="finishDrawing" onClick={finishDrawing}>Finish ({drawing.current.length})</button>
            <button onClick={() => { drawing.current = []; refresh(); }}>Cancel</button>
          </div>}
          {picked && <div class="mapActions">
            {picked.remove && <button class="dangerOutline" onClick={() => { const h = picked; setPicked(null); if (h.color === '#e5a823' || h.color === '#e5484d') editFence(() => h.remove!()); else edit(() => h.remove!()); }}>Remove this corner</button>}
            <button onClick={() => setPicked(null)}>Done</button>
          </div>}
        </div>
        {checked && <Profile c={checked} hover={hover} setHover={setHover} focus={focus} plane={plane} />}
      </div>
      <aside class="side">
        <Proposal current={draft} use={(p, f) => { (f ? editFence : edit)(() => { draft.plan = normalise(copy(p)); if (f) draft.fence = { ...emptyFence(), ...copy(f) }; }); setSel(-1); }} />
        <Panel title="Check" right={checking ? <span class="dim">checking…</span> : null}>
          {!checked && <p class="dim">Add a step and the phone checks the plan as you go.</p>}
          {checked && <div id="checkResult">
            <p class={checked.ok ? 'goodText' : 'badText'} id="checkState">{checked.ok ? `Checks out · ${checked.hash}` : `${checked.problems.length} problem${checked.problems.length === 1 ? '' : 's'} to fix`}</p>
            {checked.summary.points > 0 && <div class="stats">
              <div><b>{(checked.summary.distanceM / 1000).toFixed(2)} km</b><i>distance</i></div>
              <div><b>{mmss(checked.summary.timeS)}</b><i>{mmss(checked.summary.timeLoS)}–{mmss(checked.summary.timeHiS)}</i></div>
              <div><b class={checked.summary.batteryPct > 100 - Number(checked.assumptions.reservePct ?? 20) ? 'badText' : ''}>{checked.summary.batteryPct}%</b><i>battery</i></div>
            </div>}
            {checked.problems.length > 0 && <ul class="problems" id="problems">{checked.problems.map((p, i) => <li key={i} class="click" onClick={() => focus(p.lat, p.lon, p.step)}>{p.step && <b>{p.step}: </b>}{p.text}</li>)}</ul>}
            {(checked.advisories?.length ?? 0) > 0 && <div class="advisories" id="advisories">
              <b>Advisory notices</b> <span class="dim small">· they don't stop the plan</span>
              <ul>{checked.advisories!.map(a => <li key={a.id} class={`click adv-${a.level}`} onClick={() => focus(a.lat, a.lon, null)}>{a.text} <span class="dim small">({a.source})</span></li>)}</ul>
            </div>}
            {checked.warnings.length > 0 && <ul class="warnings">{checked.warnings.map((p, i) => <li key={i} class="click" onClick={() => focus(p.lat, p.lon, p.step)}>{p.step && <b>{p.step}: </b>}{p.text}</li>)}</ul>}
            <Learned />
            <details><summary class="dim">What it assumed</summary>
              <p class="dim mono small">{Object.entries(checked.assumptions).map(([k, v]) => `${k} ${v}`).join(' · ')}</p>
              <p class="dim small">Change these on Setup, under Planning.</p>
            </details>
          </div>}
        </Panel>

        <Panel title={`Steps (${plan.steps.length})`}>
          <div class="list" id="steps">
            {plan.steps.map((s, i) => (
              <div key={s.id} class={`item click ${i === sel ? 'sel' : ''}`} onClick={() => setSel(i)}>
                <b>{i + 1}</b><span class="grow">{KIND_LABEL[s.do] ?? s.do} <span class="dim">{s.id}</span>{stepProblems(s.id) > 0 && <span class="badText"> · {stepProblems(s.id)}!</span>}</span>
                <button title="Earlier" disabled={i === 0} onClick={e => { e.stopPropagation(); edit(() => { plan.steps.splice(i - 1, 0, plan.steps.splice(i, 1)[0]); }); setSel(i - 1); }}>↑</button>
                <button title="Later" disabled={i === plan.steps.length - 1} onClick={e => { e.stopPropagation(); edit(() => { plan.steps.splice(i + 1, 0, plan.steps.splice(i, 1)[0]); }); setSel(i + 1); }}>↓</button>
                <button title="Remove" onClick={e => { e.stopPropagation(); edit(() => { plan.steps.splice(i, 1); }); setSel(-1); }}>✕</button>
              </div>
            ))}
            {!plan.steps.length && <p class="dim">None yet. Click the map, or add one:</p>}
          </div>
          <div class="row">
            {plane && <button disabled={plan.steps.some(s => s.do === 'takeoff')} onClick={() => edit(() => insert({ id: newId('takeoff'), do: 'takeoff', heading: 0, alt: plan.defaults?.alt ?? 50 }, 0))}>+ Take off</button>}
            <button onClick={() => edit(() => insert({ id: newId('home'), do: 'home' }, plan.steps.length))}>+ Go home</button>
            <button disabled={!cur?.at && !cur?.center} onClick={() => edit(() => insert({ id: newId('hold'), do: 'hold', seconds: 30 }))}>+ Hold here</button>
          </div>
          {cur && <StepEditor s={cur} plane={plane} triggers={triggers} edit={edit} num={num} defaults={plan.defaults} />}
        </Panel>

        <Panel title="Plan">
          <label>Name <input id="planName" value={plan.name} onInput={e => { plan.name = e.currentTarget.value; }} onChange={() => queueCheck()} /></label>
          <div class="grid2">
            {plane && <label>Height, m <input type="number" value={plan.defaults?.alt ?? ''} placeholder="50" onChange={e => edit(() => { (plan.defaults ??= {}).alt = num(e.currentTarget.value); })} /></label>}
            <label>Speed, m/s <input type="number" step="0.5" value={plan.defaults?.speed ?? ''} placeholder={String(checked?.assumptions.speed ?? '')} onChange={e => edit(() => { (plan.defaults ??= {}).speed = num(e.currentTarget.value); })} /></label>
          </div>
          <label>At the end <select value={plan.onEnd ?? 'hold'} onChange={e => edit(() => { plan.onEnd = e.currentTarget.value; })}><option value="home">Return home</option><option value="hold">Hold</option>{!plane && <option value="stop">Stop and disarm</option>}</select></label>
          <label>On link loss <select id="onLinkLoss" value={plan.onLinkLoss ?? 'continue'} onChange={e => edit(() => { plan.onLinkLoss = e.currentTarget.value; })}><option value="continue">Continue</option><option value="hold">Hold</option><option value="home">Home, or the best zone it can reach</option></select></label>
          <label class="tick"><input type="checkbox" checked={!!plan.bvlos} onChange={e => edit(() => { plan.bvlos = e.currentTarget.checked; })} />Beyond line of sight: every stretch must be in reach of a safe zone</label>
          <p class="dim small">{S.fcs?.home ? 'Checked from the phone\'s home.' : plan.home ? 'Checked from the planning home (h). The phone\'s home replaces it when sent.' : 'No home yet: checked from where the vehicle is, or place a planning home.'}</p>
        </Panel>

        <Panel title={`Safe zones (${plan.zones?.length ?? 0})`}>
          <p class="dim small">Where it goes if something goes wrong and home is out of reach: a landing zone first, then a ditch zone.</p>
          <div class="list">
            {(plan.zones ?? []).map(z => (
              <div key={z.id} class="item">
                <select value={z.kind} onChange={e => edit(() => { z.kind = e.currentTarget.value as Zone['kind']; })}><option value="landing">Landing</option><option value="ditch">Ditch</option></select>
                <span class="grow dim">{z.id}</span>
                <input type="number" title="Radius, m" style="width:70px" value={z.radius ?? 20} onChange={e => edit(() => { z.radius = Number(e.currentTarget.value); })} />
                <button onClick={() => edit(() => { plan.zones = plan.zones!.filter(x => x !== z); })}>✕</button>
              </div>
            ))}
          </div>
        </Panel>

        {isOperator() && <Panel title="Send">
          <button id="upload" class="big" disabled={!checked?.ok} onClick={send}>{fenceSent ? 'Send to phone' : 'Send fence and plan'}</button>
          {sendMsg && <p id={sendMsg.ok ? 'uploaded' : 'sendProblem'} class={sendMsg.ok ? 'goodText' : 'badText'}>{sendMsg.text}</p>}
          {sentHash ? <HoldButton label="Hold to start" ms={1200} onHeld={() => cmd('mission_start', { hash: onPhone }, { label: 'Start plan' })} />
            : <p class="dim small">{onPhone ? `The phone has plan ${onPhone}. Send this one to start it.` : 'Send it, then hold to start.'}</p>}
          {S.ops?.mission?.name && S.ops.mission.state !== 'RUNNING' && S.ops.mission.state !== 'PAUSED' &&
            <button onClick={() => cmd('mission_clear', {}, { label: 'Clear from the phone' })}>Clear what's on the phone</button>}
        </Panel>}

        <Panel title="Saved plans" right={<button onClick={listPlans}>{library ? 'Refresh' : 'Show'}</button>}>
          <div class="row">
            {isOperator() && <button onClick={savePlan} disabled={!plan.steps.length}>Save as "{plan.name}"</button>}
            <button onClick={exportPlan} disabled={!plan.steps.length}>Download</button>
            <button onClick={exportQgc} disabled={!checked?.ok}>For QGroundControl</button>
            <label class="filebtn">Open file<input type="file" accept=".json,.geojson,.plan" onChange={e => { const f = e.currentTarget.files?.[0]; if (f) importFile(f); }} /></label>
          </div>
          {library && <div class="list" id="library">
            {library.map(l => (
              <div key={l.name} class="item">
                <span class="grow">{l.name} <span class="dim">· {l.steps} steps · {new Date(l.savedAt).toLocaleDateString()}</span></span>
                <button onClick={() => loadPlan(l.name)}>Open</button>
                {isOperator() && <button onClick={() => deletePlan(l.name)}>✕</button>}
              </div>
            ))}
            {!library.length && <p class="dim">None saved on this phone yet.</p>}
          </div>}
        </Panel>

        <AdvisoryPanel map={mapRef.current} redraw={() => { refresh(); queueCheck(); }} />

        <Panel title="Geofence" right={fenceSent ? null : <span class="warnText small">not sent</span>}>
          <p class="dim">Outline {draft.fence.polygon.length} corners, {draft.fence.keepOut.length} keep-out areas. Plans are checked against the fence on the phone.</p>
          <label>Most distance from home, m (0 is none) <input type="number" value={draft.fence.maxDistM} onChange={e => editFence(() => { draft.fence.maxDistM = Number(e.currentTarget.value); })} /></label>
          <label>Highest above home, m (0 is none) <input type="number" value={draft.fence.maxAltM} onChange={e => editFence(() => { draft.fence.maxAltM = Number(e.currentTarget.value); })} /></label>
          <label>Outer limit beyond it, m (0 is none) <input type="number" value={draft.fence.outerM ?? 0} onChange={e => editFence(() => { draft.fence.outerM = Number(e.currentTarget.value); })} /></label>
          <label>If crossed <select id="fenceAction" value={draft.fence.action} onChange={e => editFence(() => { draft.fence.action = e.currentTarget.value; })}><option value="hold">Hold</option><option value="home">Return home</option>{!plane && <option value="stop">Stop</option>}<option value="warn">Only warn</option></select></label>
          <div class="row">
            {isOperator() && <button disabled={fenceSent} onClick={async () => { const r = await cmd('fence_set', { fence: draft.fence }, { label: 'Send fence' }); if (r.accepted) { fenceSent = true; queueCheck(); refresh(); } }}>Send fence</button>}
            <button onClick={() => editFence(() => { draft.fence = emptyFence(); })}>Clear fence</button>
          </div>
        </Panel>
      </aside>
    </div>
  );
}

/** A plan from a file or the phone, with every step and zone given an id, so the screen can refer to them. */
function normalise(p: PlanFile): PlanFile {
  const out: PlanFile = { ...emptyPlan(), ...p, format: FORMAT, steps: [], zones: [] };
  const used = new Set<string>();
  const id = (want: string | undefined, kind: string) => { let i = want; for (let n = 1; !i || used.has(i); n++) i = `${kind}-${n}`; used.add(i); return i; };
  out.steps = (p.steps ?? []).map(s => ({ ...s, id: id(s.id, s.do) }));
  out.zones = (p.zones ?? []).map(z => ({ ...z, id: id(z.id, 'zone') }));
  return out;
}

// --- The plan in 3D, loaded only when it's opened.

let terrain3d = false;
function View3D({ c, zones, onClose }: { c: Checked; zones: Zone[]; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null);
  const handle = useRef<{ setTerrain: (on: boolean) => void; close: () => void } | null>(null);
  const [terrain, setTerrain] = useState(terrain3d);
  const [err, setErr] = useState('');
  useEffect(() => {
    let dead = false;
    import('./plan3d').then(m => {
      if (dead || !box.current) return;
      const q = authQuery();
      handle.current = m.mount3d(box.current, {
        path: c.path, gaps: c.gaps,
        marks: c.timeline.filter(e => ['action', 'notify', 'hold', 'circle', 'land', 'orbit'].includes(e.kind)).map(e => ({ lat: e.lat, lon: e.lon, kind: e.kind, text: `T+${mmss(e.t)} ${e.step ?? ''}: ${e.text}` })),
        zones: zones.map(z => ({ at: z.at, radius: z.radius, kind: z.kind })),
        // Through the phone's tile cache when talking to it directly, so it works offline where it's been looked at.
        imagery: S.relay ? 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}' : `${location.origin}/tile/satellite/{z}/{x}/{y}${q}`,
      }, terrain3d);
    }).catch(e => setErr(`The 3D view didn't load: ${e?.message ?? e}`));
    return () => { dead = true; handle.current?.close(); handle.current = null; };
  }, [c]);
  return (
    <div class="view3d" id="map3d">
      <div ref={box} class="view3dMap" />
      <div class="view3dBar">
        <label class="tick"><input type="checkbox" checked={terrain} onChange={e => { const on = e.currentTarget.checked; terrain3d = on; setTerrain(on); handle.current?.setTerrain(on); }} />Terrain</label>
        <button onClick={onClose}>Close 3D</button>
      </div>
      <div class="view3dNote dim small">Heights are above home.{terrain ? ' With terrain, they\'re drawn above the ground beneath: over hills that isn\'t the same.' : ''} Red: no safe zone in reach.</div>
      {err && <div class="onmap badText">{err}</div>}
    </div>
  );
}

// --- Airspace and no-go areas: advisory only.

/** Areas from a GeoJSON or KML file, as [lat, lon] outlines. */
async function readAreas(file: File): Promise<{ name: string | null; polygon: [number, number][] }[]> {
  const text = await file.text();
  if (/\.kml$/i.test(file.name) || text.trimStart().startsWith('<')) {
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    return [...doc.getElementsByTagName('Placemark')].flatMap(pm => {
      const name = pm.getElementsByTagName('name')[0]?.textContent ?? null;
      return [...pm.getElementsByTagName('Polygon')].map(pg => {
        const ring = pg.getElementsByTagName('outerBoundaryIs')[0]?.getElementsByTagName('coordinates')[0]?.textContent ?? pg.getElementsByTagName('coordinates')[0]?.textContent ?? '';
        return { name, polygon: ring.trim().split(/\s+/).map(c => c.split(',').map(Number)).filter(c => c.length >= 2 && !isNaN(c[0])).map(([lo, la]) => [la, lo] as [number, number]) };
      });
    }).filter(a => a.polygon.length >= 3);
  }
  const g = JSON.parse(text);
  const out: { name: string | null; polygon: [number, number][] }[] = [];
  for (const ft of g.features ?? (g.type === 'Feature' ? [g] : [])) {
    const geom = ft.geometry; const name = ft.properties?.name ?? null;
    const rings = geom?.type === 'Polygon' ? [geom.coordinates[0]] : geom?.type === 'MultiPolygon' ? geom.coordinates.map((p: any) => p[0]) : [];
    for (const r of rings) out.push({ name, polygon: r.map(([lo, la]: number[]) => [la, lo] as [number, number]) });
  }
  return out.filter(a => a.polygon.length >= 3);
}

/** OpenStreetMap areas people usually may not fly or drive over, in the map's view: military land, reserves, prisons, airfields, ports. */
async function osmNoGo(south: number, west: number, north: number, east: number) {
  const box = `(${south},${west},${north},${east})`;
  const q = `[out:json][timeout:25];(way["landuse"="military"]${box};way["military"]${box};way["leisure"="nature_reserve"]${box};way["boundary"="protected_area"]${box};way["amenity"="prison"]${box};way["aeroway"="aerodrome"]${box};way["landuse"="port"]${box};way["harbour"]${box};);out geom 400;`;
  const r = await fetch('https://overpass-api.de/api/interpreter', { method: 'POST', body: 'data=' + encodeURIComponent(q), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  if (!r.ok) throw new Error(`OpenStreetMap answered ${r.status}`);
  const j = await r.json();
  return (j.elements ?? []).map((e: any) => {
    const t = e.tags ?? {};
    const kind = t.landuse === 'military' || t.military ? 'military' : t.amenity === 'prison' ? 'prison' : t.aeroway ? 'airfield' : t.landuse === 'port' || t.harbour ? 'port' : 'protected area';
    return { name: `${t.name ? t.name + ', ' : ''}${kind}`, polygon: (e.geometry ?? []).map((p: any) => [p.lat, p.lon] as [number, number]) };
  }).filter((a: any) => a.polygon.length >= 3);
}

/** Airspace and no-go layers: drawn, checked by the phone, announced, never enforced. */
function AdvisoryPanel({ map, redraw }: { map: TileMap | null; redraw: () => void }) {
  const [, force] = useState(0);
  const [busy, setBusy] = useState('');
  const today = new Date().toISOString().slice(0, 10);
  const loadLayers = async () => { const r = await cmd('layer_list', { shapes: true }, { quiet: true }); layersView = r.data?.layers ?? []; force(x => x + 1); redraw(); };
  const loadAirports = async () => {
    const c = map?.center; if (!c) return;
    const r = await cmd('airports_near', { lat: c.lat, lon: c.lon, km: 80 }, { quiet: true });
    airportsView = r.data?.airports ?? []; force(x => x + 1); redraw();
  };
  useEffect(() => { loadLayers(); }, []);
  const save = async (layer: Record<string, unknown>) => { const r = await cmd('layer_save', { layer }, { label: 'Save layer' }); if (r.accepted) loadLayers(); };
  const importAreas = async (file: File) => {
    try {
      const features = await readAreas(file);
      if (!features.length) { alert('No areas found in that file.'); return; }
      const level = prompt('What are these? red, yellow or nogo', 'red')?.trim().toLowerCase();
      if (!level || !['red', 'yellow', 'nogo'].includes(level)) return;
      const source = prompt('Where are they from? For example: Digital Sky map, traced by hand', 'Digital Sky map, traced by hand')?.trim();
      const date = prompt('When was that source looked at?', today)?.trim();
      if (!source || !date) return;
      await save({ name: file.name.replace(/\.\w+$/, ''), source, date, level, features });
    } catch { alert('That file is neither GeoJSON nor KML.'); }
  };
  const fromOsm = async () => {
    if (!map) return;
    const nw = map.fromScreen(0, 0), se = map.fromScreen(map.width, map.height);
    if (Math.abs(nw.lat - se.lat) > 0.25 || Math.abs(se.lon - nw.lon) > 0.25) { alert('Zoom in to about 25 km across or less first.'); return; }
    setBusy('Asking OpenStreetMap…');
    try {
      const features = await osmNoGo(se.lat, nw.lon, nw.lat, se.lon);
      if (!features.length) { setBusy('OpenStreetMap has no such areas in view.'); return; }
      await save({ name: `OpenStreetMap no-go near ${map.center.lat.toFixed(3)}, ${map.center.lon.toFixed(3)}`, source: 'OpenStreetMap contributors (ODbL), via Overpass: may be incomplete', date: today, level: 'nogo', features });
      setBusy(`${features.length} areas from OpenStreetMap.`);
    } catch (e) { setBusy(`Couldn't reach OpenStreetMap: ${(e as Error).message}`); }
  };
  return (
    <Panel title="Airspace and no-go">
      <p class="dim small">For information only. They may be incomplete or out of date, and never stop a plan: the operator checks Digital Sky and the local police.</p>
      <label class="tick"><input type="checkbox" id="airportBands" checked={showAirports} onChange={e => { showAirports = e.currentTarget.checked; if (showAirports) loadAirports(); else redraw(); force(x => x + 1); }} />Airport bands (India, computed: approximate)</label>
      <label class="tick"><input type="checkbox" checked={showLayers} onChange={e => { showLayers = e.currentTarget.checked; redraw(); force(x => x + 1); }} />Imported zones</label>
      <div class="list" id="layers">
        {layersView.map(l => (
          <div class="item" key={l.id}>
            <span class={`grow small adv-${l.level}`}>{l.name} <span class="dim">· {l.count} areas · {l.source}, {l.date}</span></span>
            {isOperator() && <button onClick={async () => { if (confirm(`Delete the layer "${l.name}"?`)) { await cmd('layer_delete', { id: l.id }, { label: 'Delete layer' }); loadLayers(); } }}>✕</button>}
          </div>
        ))}
      </div>
      {isOperator() && <div class="row">
        <label class="filebtn">Import zones<input type="file" accept=".geojson,.json,.kml" onChange={e => { const f = e.currentTarget.files?.[0]; if (f) importAreas(f); }} /></label>
        <button onClick={fromOsm}>No-go areas from OpenStreetMap</button>
      </div>}
      {busy && <p class="dim small">{busy}</p>}
    </Panel>
  );
}

// --- Working with an AI: points marked for it, and what it proposes.

/** The anchors: as just marked here, or as the phone has them. */
function anchorsNow(): { name: string; lat: number; lon: number }[] { return anchorsDraft ?? (S.ops?.planAnchors as any) ?? []; }
function setAnchors(list: { name: string; lat: number; lon: number }[]) {
  anchorsDraft = list;
  cmd('plan_anchors_set', { anchors: list }, { quiet: true });
}

type Change = { text: string; looser?: boolean };
const fenceArea = (p: [number, number][]) => {
  if (p.length < 3) return 0;
  const k = 111_320 * Math.cos(p[0][0] * Math.PI / 180);
  let a = 0;
  p.forEach(([la, lo], i) => { const [la2, lo2] = p[(i + 1) % p.length]; a += (lo * k) * (la2 * 111_320) - (lo2 * k) * (la * 111_320); });
  return Math.abs(a / 2);
};

/** What a proposal changes from the plan here. Anything that loosens a restriction is marked. */
function diff(cur: Draft, plan: PlanFile, fence?: FenceData): Change[] {
  const out: Change[] = [];
  const before = new Map(cur.plan.steps.map(s => [s.id, s]));
  const after = new Map((plan.steps ?? []).map(s => [s.id, s]));
  for (const [id, s] of after) {
    const b = before.get(id);
    if (!b) out.push({ text: `adds ${KIND_LABEL[s.do] ?? s.do} ${id}` });
    else if (JSON.stringify(b) !== JSON.stringify(s)) out.push({ text: `changes ${id}` });
  }
  for (const id of before.keys()) if (!after.has(id)) out.push({ text: `removes ${id}` });
  const zb = new Set((cur.plan.zones ?? []).map(z => z.id)), za = new Set((plan.zones ?? []).map(z => z.id));
  for (const z of za) if (!zb.has(z)) out.push({ text: `adds safe zone ${z}` });
  for (const z of zb) if (!za.has(z)) out.push({ text: `removes safe zone ${z}`, looser: true });
  if (cur.plan.bvlos && !plan.bvlos) out.push({ text: 'no longer requires safe zones in reach all the way (beyond line of sight off)', looser: true });
  if ((cur.plan.onLinkLoss ?? 'continue') !== (plan.onLinkLoss ?? 'continue')) out.push({ text: `on link loss: ${cur.plan.onLinkLoss ?? 'continue'} to ${plan.onLinkLoss ?? 'continue'}`, looser: cur.plan.onLinkLoss === 'home' });
  if (fence) {
    const f0 = cur.fence;
    const more = (a: number, b: number) => (a === 0 && b > 0 ? false : b === 0 && a > 0) || (a > 0 && b > a);
    if (more(f0.maxDistM, fence.maxDistM)) out.push({ text: `distance limit ${f0.maxDistM || 'none'} m to ${fence.maxDistM || 'none'}`, looser: true });
    if (more(f0.maxAltM, fence.maxAltM)) out.push({ text: `height limit ${f0.maxAltM || 'none'} m to ${fence.maxAltM || 'none'}`, looser: true });
    if (fence.keepOut.length < f0.keepOut.length) out.push({ text: `${f0.keepOut.length - fence.keepOut.length} keep-out area(s) removed`, looser: true });
    if (fenceArea(fence.polygon) > fenceArea(f0.polygon) * 1.01 || (f0.polygon.length >= 3 && fence.polygon.length < 3)) out.push({ text: 'the fence outline is bigger, or gone', looser: true });
    if (fence.action === 'warn' && f0.action !== 'warn') out.push({ text: 'crossing the fence only warns', looser: true });
    if (JSON.stringify(f0) !== JSON.stringify({ ...emptyFence(), ...fence }) && !out.some(c => c.looser)) out.push({ text: 'changes the fence' });
  }
  return out;
}

/** What the AI proposed, beside what's here: changes listed, loosening in red. A person decides. */
function Proposal({ current, use }: { current: Draft; use: (p: PlanFile, f?: FenceData) => void }) {
  const p = S.ops?.planProposal;
  const anchors = anchorsNow();
  if (!p && !anchors.length) return null;
  const changes = p ? diff(current, p.plan, p.fence) : [];
  const looser = changes.filter(c => c.looser);
  return (
    <Panel title="With the AI" right={anchors.length ? <button onClick={() => setAnchors([])}>Clear anchors</button> : null}>
      {anchors.length > 0 && <p class="dim small" id="anchors">Marked for the AI: {anchors.map(a => a.name).join(', ')}. Ask the assistant to build around them.</p>}
      {p && <div id="proposal">
        <p><b>Proposed by the AI</b> <span class="dim small">{new Date(p.at).toLocaleTimeString()}</span>{p.note && <>: {p.note}</>}</p>
        <p class={p.ok ? 'goodText small' : 'badText small'}>{p.ok ? 'It checks out on the phone.' : `${p.problems} problem(s) when checked.`} Shown dashed in purple.</p>
        {looser.length > 0 && <ul class="problems" id="looser">{looser.map((c, i) => <li key={i}>Loosens a restriction: {c.text}</li>)}</ul>}
        <ul class="small">{changes.filter(c => !c.looser).slice(0, 12).map((c, i) => <li key={i}>{c.text}</li>)}{changes.length > 12 && <li class="dim">and {changes.length - 12} more</li>}</ul>
        <div class="row">
          <button class={looser.length ? 'dangerOutline' : 'sel'} id="useProposal" onClick={() => { if (looser.length && !confirm('This proposal loosens a restriction:\n\n' + looser.map(c => '• ' + c.text).join('\n') + '\n\nUse it anyway?')) return; use(p.plan, p.fence); cmd('plan_proposal_clear', {}, { quiet: true }); }}>Use this{p.fence ? ' (and its fence)' : ''}</button>
          <button onClick={() => cmd('plan_proposal_clear', {}, { label: 'Dismiss proposal' })}>Dismiss</button>
        </div>
        <p class="dim small">Nothing is sent: check it, then send it yourself.</p>
      </div>}
    </Panel>
  );
}

/** What flights measured about this vehicle, and planning settings worth changing. A person applies them. */
function Learned() {
  const [data, setData] = useState<{ measured: { climbs: number; legs: number; glides: number }; suggestions: { param: string; current: number; suggested: number; basis: string }[] } | null>(null);
  const load = async () => { const r = await cmd('plan_learning', {}, { quiet: true }); if (r.data) setData(r.data); };
  if (!data) return <p><a href="#" class="small" onClick={e => { e.preventDefault(); load(); }}>What flights measured</a></p>;
  const m = data.measured;
  return (
    <div class="form" id="learned">
      <p class="dim small">Measured on plans flown: {m.climbs} climbs, {m.legs} legs, {m.glides} glides.</p>
      {!data.suggestions.length && <p class="dim small">Nothing to change: the planning settings match what it does{m.climbs + m.legs + m.glides === 0 ? ', or there\'s nothing measured yet' : ''}.</p>}
      {data.suggestions.map(s => (
        <div class="row" key={s.param}>
          <span class="grow small"><b>{s.param}</b> {s.current} → {s.suggested}<br /><span class="dim">{s.basis}</span></span>
          {isOperator() && <button onClick={async () => { const r = await cmd('plan_learning_apply', { param: s.param }, { label: `Apply ${s.param}` }); if (r.accepted) load(); }}>Apply</button>}
        </div>
      ))}
    </div>
  );
}

/** The fields for the selected step. */
function StepEditor({ s, plane, triggers, edit, num, defaults }: { s: Step; plane: boolean; triggers: { id: string; label: string; danger: boolean }[]; edit: (fn: () => void) => void; num: (v: string) => number | undefined; defaults?: PlanFile['defaults'] }) {
  const field = (label: string, key: keyof Step, opts: { step?: number; placeholder?: string } = {}) => (
    <label>{label} <input type="number" step={opts.step ?? 1} placeholder={opts.placeholder} value={(s[key] as number | undefined) ?? ''} onChange={e => edit(() => { (s as any)[key] = num(e.currentTarget.value); })} /></label>
  );
  const acts = s.actions ?? [];
  const missOf = (k: 'altitude' | 'arrival') => { const m = s.miss?.[k]; return typeof m === 'string' ? m : m?.do ?? ''; };
  const setMiss = (k: 'altitude' | 'arrival', v: string) => edit(() => {
    s.miss ??= {};
    if (!v) delete s.miss[k];
    else s.miss[k] = v === 'circle' || v === 'hold' ? { do: v, maxS: 60, then: 'abort' } : v;
    if (!Object.keys(s.miss).length) delete s.miss;
  });
  return (
    <div class="form" id="stepEdit">
      <h3>{KIND_LABEL[s.do] ?? s.do} <span class="dim">{s.id}</span></h3>
      <div class="grid2">
        {plane && s.do !== 'land' && s.do !== 'repeat' && field('Height above home, m', 'alt', { placeholder: String(defaults?.alt ?? 50) })}
        {s.do !== 'repeat' && field('Speed, m/s', 'speed', { step: 0.5, placeholder: String(defaults?.speed ?? 'plan') })}
        {(s.do === 'goto' || s.do === 'hold' || s.do === 'path' || s.do === 'home') && field('Arrival radius, m', 'radius', { placeholder: 'auto' })}
        {s.do === 'hold' && field('Wait, s', 'seconds')}
        {s.do === 'orbit' && <>{field('Radius, m', 'radius')}{field('Laps', 'laps', { step: 0.25 })}</>}
        {s.do === 'orbit' && <label>Direction <select value={s.dir ?? 'cw'} onChange={e => edit(() => { s.dir = e.currentTarget.value; })}><option value="cw">Clockwise</option><option value="ccw">Anticlockwise</option></select></label>}
        {s.do === 'survey' && <>{field('Line spacing, m', 'spacing')}{field('Line direction, °', 'angle')}</>}
        {s.do === 'corridor' && <>{field('Width, m', 'width')}{field('Pass spacing, m', 'spacing')}</>}
        {(s.do === 'takeoff' || s.do === 'land') && field(s.do === 'takeoff' ? 'Take off towards, °' : 'Land heading, °', 'heading')}
        {s.do === 'land' && <>{field('Approach slope, °', 'slope', { placeholder: '6' })}{field('Approach height, m', 'approachAlt', { placeholder: '30' })}</>}
        {s.do === 'repeat' && field('Times', 'times')}
      </div>
      {s.do !== 'repeat' && s.do !== 'takeoff' && <>
        <div class="dim">Actions</div>
        {acts.map((a, i) => (
          <div class="row" key={i}>
            {a.do === 'trigger'
              ? <><span class="grow">{triggers.find(t => t.id === a.id)?.label ?? a.id} {a.everyM ? `every ${a.everyM} m along the way` : 'on arriving'}</span></>
              : <span class="grow">Notify: {a.text}</span>}
            <button onClick={() => edit(() => { acts.splice(i, 1); if (!acts.length) delete s.actions; })}>✕</button>
          </div>
        ))}
        <div class="row">
          <select id="addAction" onChange={e => {
            const v = e.currentTarget.value; e.currentTarget.value = '';
            if (!v) return;
            const [kind, id] = v.split(':');
            edit(() => {
              s.actions ??= [];
              if (kind === 'notify') { const text = prompt('Message for the operator')?.trim(); if (text) s.actions.push({ do: 'notify', text }); }
              else if (kind === 'every') { const m = Number(prompt('Every how many metres?', '20')); if (m > 0) s.actions.push({ do: 'trigger', id, everyM: m }); }
              else s.actions.push({ do: 'trigger', id });
              if (!s.actions.length) delete s.actions;
            });
          }}>
            <option value="">Add an action…</option>
            {triggers.map(t => <option key={t.id} value={`at:${t.id}`}>{t.label} on arriving</option>)}
            {triggers.filter(t => !t.danger).map(t => <option key={`e${t.id}`} value={`every:${t.id}`}>{t.label} every … m</option>)}
            <option value="notify:">Notify the operator</option>
          </select>
        </div>
      </>}
      {s.do !== 'repeat' && <details><summary class="dim">If it can't</summary>
        {plane && <label>Arrives off its height <select value={missOf('altitude')} onChange={e => setMiss('altitude', e.currentTarget.value)}>
          <option value="">Plan default (circle up to 90 s, then go home)</option><option value="circle">Circle up to 60 s, then go home</option><option value="continue">Carry on, climbing</option><option value="skip">Skip it</option><option value="abort">Go home</option>
        </select></label>}
        <label>Takes far longer than planned <select value={missOf('arrival')} onChange={e => setMiss('arrival', e.currentTarget.value)}>
          <option value="">Plan default</option>{!plane && <option value="hold">Wait up to 60 s, then go home</option>}<option value="continue">Keep trying</option><option value="skip">Skip it</option><option value="abort">Go home</option>
        </select></label>
      </details>}
    </div>
  );
}

/** Height over time, with what happens when, and stretches with no safe reach shaded. Hovering shows the moment on the map. */
function Profile({ c, hover, setHover, focus, plane }: { c: Checked; hover: number | null; setHover: (i: number | null) => void; focus: (lat?: number, lon?: number, step?: string | null) => void; plane: boolean }) {
  // Drawn at its real width, so text and lines aren't stretched.
  const [W, setW] = useState(800);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = box.current; if (!el) return;
    const ro = new ResizeObserver(() => setW(Math.max(200, el.clientWidth - 16)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const H = plane ? 120 : 44, padL = 40, padB = 18, padT = 8;
  const T = Math.max(1, c.summary.timeS);
  const top = plane ? Math.max(10, Math.ceil((c.summary.maxAltM ?? 0) / 20) * 20) : 1;
  const x = (t: number) => padL + (t / T) * (W - padL - 6);
  const y = (a: number) => padT + (1 - a / top) * (H - padT - padB);
  const line = plane ? c.path.map((p, i) => `${i ? 'L' : 'M'}${x(p[3]).toFixed(1)},${y(p[2]).toFixed(1)}`).join('') : '';
  const step = [30, 60, 120, 300, 600, 900, 1800].find(s => T / s <= 8) ?? 3600;
  const ticks = Array.from({ length: Math.floor(T / step) + 1 }, (_, i) => i * step);
  const altTicks = plane ? [0, top / 2, top] : [];
  const marks = c.timeline.filter(e => ['action', 'notify', 'hold', 'circle', 'land', 'orbit'].includes(e.kind));
  const svg = useRef<SVGSVGElement>(null);
  const move = (e: PointerEvent) => {
    const r = svg.current!.getBoundingClientRect();
    const t = ((e.clientX - r.left) / r.width * W - padL) / (W - padL - 6) * T;
    let best = 0;
    c.path.forEach((p, i) => { if (Math.abs(p[3] - t) < Math.abs(c.path[best][3] - t)) best = i; });
    setHover(c.path.length ? best : null);
  };
  const hp = hover !== null ? c.path[hover] : null;
  return (
    <div class="profile" ref={box}>
      <svg ref={svg} viewBox={`0 0 ${W} ${H}`} width={W} height={H} onPointerMove={move as any} onPointerLeave={() => setHover(null)} role="img" aria-label={plane ? 'Height over time' : 'What happens over time'}>
        {c.gaps.map((g, i) => <rect key={i} x={x(g.fromS)} y={padT} width={Math.max(2, x(g.toS) - x(g.fromS))} height={H - padT - padB} fill="var(--bad)" opacity=".18" />)}
        {altTicks.map(a => <g key={a}><line x1={padL} x2={W - 6} y1={y(a)} y2={y(a)} stroke="var(--line)" stroke-width="1" /><text x={padL - 6} y={y(a) + 4} text-anchor="end" class="axis">{a} m</text></g>)}
        {ticks.map(t => <text key={t} x={x(t)} y={H - 4} text-anchor="middle" class="axis">{mmss(t)}</text>)}
        {plane && <path d={line} fill="none" stroke="var(--accent)" stroke-width="2" />}
        {marks.map((e, i) => <rect key={i} x={x(e.t) - 3} y={H - padB - 8} width="6" height="8" rx="1.5" fill={e.kind === 'action' ? '#facc15' : e.kind === 'circle' ? 'var(--warn)' : '#c4b5fd'} onClick={() => focus(e.lat, e.lon, e.step)} style="cursor:pointer"><title>{`T+${mmss(e.t)} ${e.step ?? ''}: ${e.text}`}</title></rect>)}
        {hp && <line x1={x(hp[3])} x2={x(hp[3])} y1={padT} y2={H - padB} stroke="var(--text)" stroke-width="1" />}
      </svg>
      <div class="profileInfo dim">{hp ? `T+${mmss(hp[3])}${plane ? ` · ${Math.round(hp[2])} m above home` : ''}` : plane ? 'Height above home over time. Red: no safe zone in reach. Hover to see where.' : 'What happens over time. Hover to see where.'}</div>
      <div class="list timeline" id="timeline">
        {c.timeline.filter(e => e.kind !== 'begin').map((e, i) => (
          <div key={i} class="item click" onClick={() => focus(e.lat, e.lon, e.step)}>
            <span class="mono">T+{mmss(e.t)}</span>
            <span class="dim mono small">{e.hi - e.lo >= 2 ? `${mmss(e.lo)}–${mmss(e.hi)}` : ''}</span>
            <span class="grow">{e.step && <b>{e.step} </b>}{e.text}{e.until ? ` until T+${mmss(e.until)}` : ''}</span>
            {plane && e.alt !== undefined && <span class="dim">{Math.round(e.alt)} m</span>}
          </div>
        ))}
      </div>
    </div>
  );
}

type Keyframe = { joints: Record<string, number>; ms: number };
let timeline: Keyframe[] = [];
let skillName = 'sequence1';

/** For arms and legged robots, a waypoint is a pose. */
function Timeline() {
  const [, force] = useState(0);
  const refresh = () => force(x => x + 1);
  const acts: any[] = S.device?.actuators ?? [];
  const capture = () => { timeline.push({ joints: Object.fromEntries(acts.map(a => [a.id, a.value])), ms: 800 }); refresh(); };
  const save = () => cmd('pose_capture', { skill: skillName, steps: timeline.map(k => ({ ...k.joints, ms: k.ms })), loops: 1 }, { label: 'Save sequence' });
  return (
    <div class="pageCols">
      <Panel title="Timeline">
        <p class="dim">Each step is a pose. Capture what the joints are doing now, or edit the numbers. It plays in preview until the device is signed off.</p>
        <label>Name <input id="skillName" value={skillName} onInput={e => { skillName = e.currentTarget.value; }} /></label>
        <div class="row">
          <button id="capture" onClick={capture} disabled={!acts.length}>Capture current pose</button>
          <button id="saveSeq" onClick={save} disabled={!timeline.length}>Save to phone</button>
          <button onClick={() => cmd('run_skill', { id: skillName }, { label: 'Play' })}>Play (arm first)</button>
        </div>
        <div class="list" id="keyframes">
          {timeline.map((k, i) => (
            <div class="item" key={i}>
              <b>{i + 1}</b>
              <label class="grow">Takes, ms <input type="number" value={k.ms} onChange={e => { k.ms = Number(e.currentTarget.value); refresh(); }} /></label>
              <button onClick={() => { timeline.splice(i, 1); refresh(); }}>✕</button>
            </div>
          ))}
          {!timeline.length && <p class="dim">No steps yet.</p>}
        </div>
      </Panel>
      <Panel title="Pose of the last step">
        {timeline.length ? acts.map(a => {
          const k = timeline[timeline.length - 1];
          return <label key={a.id}>{a.id} ({a.unit}) <input type="number" value={k.joints[a.id]} min={a.min} max={a.max} onChange={e => { k.joints[a.id] = Number(e.currentTarget.value); refresh(); }} /></label>;
        }) : <p class="dim">Capture a pose to edit it.</p>}
      </Panel>
    </div>
  );
}

export function Plan() {
  const caps = S.hello?.caps;
  if (!caps) return <p class="dim">Waiting for the phone...</p>;
  if (!caps.map) return <Timeline />;
  return <MapPlan />;
}
