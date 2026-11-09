// Build what it will do: a plan file on a map, checked live by the phone, or poses on a timeline for jointed devices.
import { useEffect, useRef, useState } from 'preact/hooks';
import { cmd, isOperator, S, type FenceData } from './store';
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
};

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
const undoStack: Draft[] = [];
const redoStack: Draft[] = [];

/** A new id for a step or zone, unique in the plan. */
function newId(kind: string) {
  const used = new Set<string>([...draft.plan.steps.map(s => s.id), ...(draft.plan.zones ?? []).map(z => z.id)]);
  for (let n = 1; ; n++) if (!used.has(`${kind}-${n}`)) return `${kind}-${n}`;
}
/** The position a step is drawn and inserted at. */
const anchor = (s: Step): LL | undefined => s.at ?? s.center ?? s.area?.[0] ?? s.path?.[0] ?? s.points?.[s.points.length - 1];

type Tool = 'goto' | 'survey' | 'corridor' | 'orbit' | 'hold' | 'land' | 'zone-landing' | 'zone-ditch' | 'home' | 'fence' | 'keepout';
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
    if (!plan.steps.length) { setChecked(null); return; }
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
    const r = await cmd('plan_upload', { plan: draft.plan }, { label: 'Send plan' });
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
  const importFile = async (file: File) => {
    try {
      const g = JSON.parse(await file.text());
      if (g.format === FORMAT) { edit(() => { draft.plan = normalise(g); }); setSel(-1); return; }
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
  const stepProblems = (id: string) => (checked?.problems ?? []).filter(p => p.step?.split('#')[0] === id).length;

  return (
    <div class="plan">
      <div class="mainView">
        <div class="viewBar">
          {TOOLS.filter(([id]) => plane || id !== 'land').map(([id, name]) => (
            <button key={id} data-tool={id} class={tool === id ? 'sel' : ''} onClick={() => { setTool(id); drawing.current = []; refresh(); }}>{name}</button>
          ))}
          <button onClick={undo} disabled={!undoStack.length}>Undo</button>
          <button onClick={redo} disabled={!redoStack.length}>Redo</button>
          <button id="newPlan" disabled={!plan.steps.length && !(plan.zones ?? []).length} onClick={() => { edit(() => { draft.plan = emptyPlan(); }); setSel(-1); setChecked(null); }}>New plan</button>
        </div>
        <div class="mapwrap">
          <canvas ref={ref} id="planMap" />
          <LayerSwitch map={map.current} />
          <div class="onmap dim">{toolHelp}</div>
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
            {checked.warnings.length > 0 && <ul class="warnings">{checked.warnings.map((p, i) => <li key={i} class="click" onClick={() => focus(p.lat, p.lon, p.step)}>{p.step && <b>{p.step}: </b>}{p.text}</li>)}</ul>}
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
            <label class="filebtn">Open file<input type="file" accept=".json,.geojson" onChange={e => { const f = e.currentTarget.files?.[0]; if (f) importFile(f); }} /></label>
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
