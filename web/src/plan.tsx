// Build what it will do: places on a map, or poses on a timeline for jointed devices.
import { useEffect, useRef, useState } from 'preact/hooks';
import { cmd, isOperator, S, type FenceData, type MissionData, type MissionItem } from './store';
import { HoldButton, LayerSwitch, Panel, useMap } from './parts';
import { drawHome, drawPlan, drawVehicle, type TileMap } from './map';

type Tool = 'points' | 'fence' | 'keepout';
type Draft = { mission: MissionData; fence: FenceData };
const empty = (): Draft => ({ mission: { name: 'Mission', onEnd: 'hold', onLinkLoss: 'continue', items: [] }, fence: { polygon: [], keepOut: [], maxDistM: 0, maxAltM: 0, action: 'hold' } });
const copy = <T,>(x: T): T => JSON.parse(JSON.stringify(x));
// It lives outside the component, so switching workspace doesn't lose a half-made plan.
let draft: Draft = empty();
let loadedFromPhone = false;
const undoStack: Draft[] = [];
const redoStack: Draft[] = [];

function MapPlan() {
  const [, force] = useState(0);
  const [tool, setTool] = useState<Tool>('points');
  const [sel, setSel] = useState(-1);
  const [problems, setProblems] = useState<string[]>([]);
  const [synced, setSynced] = useState(false);
  const toolRef = useRef(tool); toolRef.current = tool;
  const dragging = useRef(-1);
  const refresh = () => { force(x => x + 1); mapRef.current?.draw(); };
  const remember = () => { undoStack.push(copy(draft)); if (undoStack.length > 100) undoStack.shift(); redoStack.length = 0; setSynced(false); };
  const mapRef = useRef<TileMap | null>(null);
  const { ref, map } = useMap((m: TileMap) => {
    m.overlay = (mm, g) => {
      const keepOut = draft.fence.keepOut;
      drawPlan(mm, g, draft.mission.items, -1, { polygon: draft.fence.polygon, keepOut }, selRef.current);
      const home = S.fcs?.home;
      if (home) drawHome(mm, g, home.lat, home.lon);
      const gps = S.fcs?.gps;
      if (gps) drawVehicle(mm, g, gps.lat, gps.lon, S.fc?.a?.[2] ?? 0, gps.acc);
    };
    m.onPress = (_p, x, y) => {
      if (toolRef.current !== 'points' || !isOperator()) return false;
      const hit = draft.mission.items.findIndex(w => { const s = m.toScreen(w.lat, w.lon); return Math.hypot(s.x - x, s.y - y) < 18; });
      if (hit < 0) return false;
      remember();
      dragging.current = hit;
      selRef.current = hit;
      setSel(hit);
      return true;
    };
    m.onDrag = p => {
      const w = draft.mission.items[dragging.current];
      if (w) { w.lat = p.lat; w.lon = p.lon; m.draw(); }
    };
    m.onRelease = () => { dragging.current = -1; force(x => x + 1); };
    m.onClick = p => {
      if (!isOperator()) return;
      remember();
      if (toolRef.current === 'points') {
        draft.mission.items.push({ lat: p.lat, lon: p.lon, holdS: 0, radiusM: 5, triggers: [] });
        selRef.current = draft.mission.items.length - 1;
        setSel(selRef.current);
      } else if (toolRef.current === 'fence') draft.fence.polygon.push([p.lat, p.lon]);
      else {
        if (!keepOutOpen.current) { draft.fence.keepOut.push([]); keepOutOpen.current = true; }
        draft.fence.keepOut[draft.fence.keepOut.length - 1].push([p.lat, p.lon]);
      }
      refresh();
    };
    mapRef.current = m;
    const known = draft.mission.items.map(w => ({ lat: w.lat, lon: w.lon }));
    const gps = S.fcs?.gps;
    if (known.length) m.fit(known);
    else if (gps) { m.center = { lat: gps.lat, lon: gps.lon }; m.zoom = 18; }
    force(x => x + 1);
  });
  const selRef = useRef(-1); selRef.current = sel;
  const keepOutOpen = useRef(false);

  // The first time, start from what's already on the phone.
  const phone = S.ops;
  useEffect(() => {
    if (loadedFromPhone || !phone) return;
    loadedFromPhone = true;
    if (phone.missionData && !draft.mission.items.length) draft.mission = copy(phone.missionData);
    if (phone.fence.data) draft.fence = copy(phone.fence.data);
    refresh();
    const pts = draft.mission.items;
    if (pts.length) mapRef.current?.fit(pts);
  }, [phone]);

  const undo = () => { const d = undoStack.pop(); if (d) { redoStack.push(copy(draft)); draft = d; setSel(-1); refresh(); } };
  const redo = () => { const d = redoStack.pop(); if (d) { undoStack.push(copy(draft)); draft = d; setSel(-1); refresh(); } };
  const edit = (fn: () => void) => { remember(); fn(); refresh(); };

  const upload = async () => {
    setProblems([]);
    const f = await cmd('fence_set', { fence: draft.fence }, { label: 'Send fence' });
    if (!f.accepted) { setProblems([f.message]); return; }
    if (!draft.mission.items.length) return;
    const r = await cmd('mission_upload', { mission: draft.mission }, { label: 'Send mission' });
    if (!r.accepted) setProblems(r.message.split('; '));
    else setSynced(true);
  };

  const exportJson = () => {
    const features: any[] = draft.mission.items.map((w, i) => ({
      type: 'Feature', geometry: { type: 'Point', coordinates: [w.lon, w.lat, w.alt ?? 0] },
      properties: { kind: 'waypoint', index: i, throttle: w.throttle ?? null, holdS: w.holdS ?? 0, radiusM: w.radiusM ?? 5, triggers: w.triggers ?? [] },
    }));
    const ring = (p: [number, number][]) => [[...p, p[0]].map(([la, lo]) => [lo, la])];
    if (draft.fence.polygon.length >= 3) features.push({ type: 'Feature', geometry: { type: 'Polygon', coordinates: ring(draft.fence.polygon) }, properties: { kind: 'fence' } });
    draft.fence.keepOut.filter(k => k.length >= 3).forEach(k => features.push({ type: 'Feature', geometry: { type: 'Polygon', coordinates: ring(k) }, properties: { kind: 'keepout' } }));
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([JSON.stringify({ type: 'FeatureCollection', name: draft.mission.name, onEnd: draft.mission.onEnd, onLinkLoss: draft.mission.onLinkLoss, features }, null, 1)], { type: 'application/geo+json' }));
    a.download = `${draft.mission.name.replace(/\W+/g, '-')}.geojson`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };
  const importJson = async (file: File) => {
    try {
      const g = JSON.parse(await file.text());
      const next = empty();
      next.mission.name = g.name ?? file.name.replace(/\.\w+$/, '');
      if (g.onEnd) next.mission.onEnd = g.onEnd;
      if (g.onLinkLoss) next.mission.onLinkLoss = g.onLinkLoss;
      const unring = (r: number[][]) => r.slice(0, -1).map(([lo, la]) => [la, lo] as [number, number]);
      for (const f of g.features ?? []) {
        const pr = f.properties ?? {};
        if (f.geometry?.type === 'Point') {
          const [lon, lat, alt] = f.geometry.coordinates;
          next.mission.items.push({ lat, lon, alt: alt || null, throttle: pr.throttle ?? null, holdS: pr.holdS ?? 0, radiusM: pr.radiusM ?? 5, triggers: pr.triggers ?? [] });
        } else if (f.geometry?.type === 'LineString') {
          f.geometry.coordinates.forEach(([lon, lat]: number[]) => next.mission.items.push({ lat, lon, holdS: 0, radiusM: 5, triggers: [] }));
        } else if (f.geometry?.type === 'Polygon') {
          if (pr.kind === 'keepout') next.fence.keepOut.push(unring(f.geometry.coordinates[0]));
          else next.fence.polygon = unring(f.geometry.coordinates[0]);
        }
      }
      remember();
      draft = next;
      setSel(-1);
      refresh();
      if (next.mission.items.length) mapRef.current?.fit(next.mission.items);
    } catch {
      setProblems(['That file is not valid GeoJSON.']);
    }
  };

  const items = draft.mission.items;
  const cur = items[sel] as MissionItem | undefined;
  const triggers = S.ops?.triggers ?? [];
  const num = (v: string) => (v === '' ? null : Number(v));
  return (
    <div class="plan">
      <div class="mainView">
        <div class="viewBar">
          {([['points', 'Points'], ['fence', 'Fence'], ['keepout', 'Keep-out']] as [Tool, string][]).map(([id, name]) => (
            <button key={id} data-tool={id} class={tool === id ? 'sel' : ''} onClick={() => { setTool(id); keepOutOpen.current = false; }}>{name}</button>
          ))}
          <button onClick={undo} disabled={!undoStack.length}>Undo</button>
          <button onClick={redo} disabled={!redoStack.length}>Redo</button>
        </div>
        <div class="mapwrap">
          <canvas ref={ref} id="planMap" />
          <LayerSwitch map={map.current} />
          <div class="onmap dim">{tool === 'points' ? 'Click to add a point. Drag to move it.' : tool === 'fence' ? 'Click to outline where it may go.' : 'Click to outline a place to stay out of.'}</div>
        </div>
      </div>
      <aside class="side">
        <Panel title="Mission">
          <label>Name <input id="missionName" value={draft.mission.name} onInput={e => { draft.mission.name = e.currentTarget.value; setSynced(false); }} /></label>
          <label>At the end <select value={draft.mission.onEnd} onChange={e => edit(() => { draft.mission.onEnd = e.currentTarget.value; })}><option value="hold">Hold</option><option value="home">Return home</option><option value="stop">Stop and disarm</option></select></label>
          <label>On link loss <select id="onLinkLoss" value={draft.mission.onLinkLoss} onChange={e => edit(() => { draft.mission.onLinkLoss = e.currentTarget.value; })}><option value="continue">Continue</option><option value="hold">Hold</option><option value="home">Return home</option></select></label>
        </Panel>
        <Panel title={`Points (${items.length})`}>
          <div class="list" id="points">
            {items.map((w, i) => (
              <div key={i} class={`item ${i === sel ? 'sel' : ''}`} onClick={() => setSel(i)}>
                <b>{i + 1}</b><span class="grow dim">{w.lat.toFixed(5)}, {w.lon.toFixed(5)}{w.triggers?.length ? ` · ${w.triggers.join(', ')}` : ''}</span>
                <button onClick={e => { e.stopPropagation(); edit(() => { items.splice(i, 1); }); setSel(-1); }}>✕</button>
              </div>
            ))}
            {!items.length && <p class="dim">None yet.</p>}
          </div>
          {cur && <div class="form" id="pointEdit">
            <h3>Point {sel + 1}</h3>
            <label>Altitude above home, m <input type="number" value={cur.alt ?? ''} onChange={e => edit(() => { cur.alt = num(e.currentTarget.value); })} /></label>
            <label>Throttle, blank for default <input type="number" step="0.05" value={cur.throttle ?? ''} onChange={e => edit(() => { cur.throttle = num(e.currentTarget.value); })} /></label>
            <label>Hold, s <input type="number" value={cur.holdS ?? 0} onChange={e => edit(() => { cur.holdS = Number(e.currentTarget.value); })} /></label>
            <label>Arrival radius, m <input type="number" value={cur.radiusM ?? 5} onChange={e => edit(() => { cur.radiusM = Number(e.currentTarget.value); })} /></label>
            <div class="dim">Triggers here</div>
            {triggers.map(t => (
              <label key={t.id} class="tick"><input type="checkbox" data-wp-trigger={t.id} checked={cur.triggers?.includes(t.id)} onChange={e => { const on = e.currentTarget.checked; edit(() => { cur.triggers = on ? [...(cur.triggers ?? []), t.id] : (cur.triggers ?? []).filter(x => x !== t.id); }); }} />{t.label}</label>
            ))}
          </div>}
        </Panel>
        <Panel title="Geofence">
          <p class="dim">Outline {draft.fence.polygon.length} points, {draft.fence.keepOut.length} keep-out areas</p>
          <label>Most distance from home, m (0 is none) <input type="number" value={draft.fence.maxDistM} onChange={e => edit(() => { draft.fence.maxDistM = Number(e.currentTarget.value); })} /></label>
          <label>Highest above home, m (0 is none) <input type="number" value={draft.fence.maxAltM} onChange={e => edit(() => { draft.fence.maxAltM = Number(e.currentTarget.value); })} /></label>
          <label>If crossed <select id="fenceAction" value={draft.fence.action} onChange={e => edit(() => { draft.fence.action = e.currentTarget.value; })}><option value="hold">Hold</option><option value="home">Return home</option><option value="stop">Stop</option><option value="warn">Only warn</option></select></label>
          <button onClick={() => edit(() => { draft.fence = empty().fence; })}>Clear fence</button>
        </Panel>
        {isOperator() && <Panel title="Send to phone">
          <button id="upload" class="big" onClick={upload}>Check and send</button>
          {synced && <p class="goodText" id="uploaded">On the phone, and checked.</p>}
          {problems.length > 0 && <ul class="problems" id="problems">{problems.map((p, i) => <li key={i}>{p}</li>)}</ul>}
          <HoldButton label="Hold to start mission" ms={1200} onHeld={() => cmd('mission_start', {}, { label: 'Start mission' })} />
          <div class="row">
            <button onClick={exportJson}>Export GeoJSON</button>
            <label class="filebtn">Import<input type="file" accept=".geojson,.json" onChange={e => { const f = e.currentTarget.files?.[0]; if (f) importJson(f); }} /></label>
          </div>
        </Panel>}
      </aside>
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
