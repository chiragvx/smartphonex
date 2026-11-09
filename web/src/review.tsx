// Learn from a run: charts, track, video and events on one timeline.
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { authQuery, S } from './store';
import { CheckRow, LayerSwitch, Panel, useMap } from './parts';
import { drawVehicle, type TileMap } from './map';

const get = (path: string) => fetch(`/${path}${authQuery()}`);
export const fetchFiles = async () => (await get('review/files')).json();
const frameUrl = (run: string, t: number) => `/review/frame/${run}/${t}${authQuery()}`;
const fileUrl = (kind: string, name: string) => `/review/file/${kind}/${name}${authQuery()}`;

type Row = Record<string, number | string | null>;
function parseCsv(text: string): Row[] {
  const lines = text.trim().split('\n');
  const head = lines[0].split(',');
  return lines.slice(1).map(l => {
    const c = l.split(',');
    const r: Row = {};
    head.forEach((h, i) => { const v = c[i]; r[h] = v === '' || v === undefined ? null : isNaN(Number(v)) ? v : Number(v); });
    return r;
  });
}

// Validated for this page's dark surface with the data viz palette checker.
const BLUE = '#3987e5', ORANGE = '#d95926';
type Series = { key: string; label: string; color: string };

/** One measure per chart, with a shared time axis and one crosshair. */
function Chart({ title, rows, series, unit, cursor, setCursor }: { title: string; rows: Row[]; series: Series[]; unit: string; cursor: number; setCursor: (t: number) => void }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const t0 = rows[0]?.t as number, t1 = rows[rows.length - 1]?.t as number;
  const [lo, hi] = useMemo(() => {
    let a = Infinity, b = -Infinity;
    for (const r of rows) for (const s of series) { const v = r[s.key]; if (typeof v === 'number') { if (v < a) a = v; if (v > b) b = v; } }
    if (a === Infinity) return [0, 1];
    if (a === b) return [a - 1, b + 1];
    return [a - (b - a) * 0.05, b + (b - a) * 0.05];
  }, [rows, series]);
  const nearest = useMemo(() => {
    let best = rows[0];
    for (const r of rows) { if ((r.t as number) >= cursor) { best = r; break; } best = r; }
    return best;
  }, [rows, cursor]);
  useEffect(() => {
    const c = ref.current!;
    const w = c.clientWidth, h = c.clientHeight;
    if (!w) return;
    const ratio = devicePixelRatio || 1;
    c.width = w * ratio; c.height = h * ratio;
    const g = c.getContext('2d')!;
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    const left = 40, right = 6, top = 6, bottom = 18;
    const X = (t: number) => left + (t - t0) / (t1 - t0 || 1) * (w - left - right);
    const Y = (v: number) => top + (1 - (v - lo) / (hi - lo)) * (h - top - bottom);
    g.font = '11px system-ui, sans-serif';
    g.fillStyle = '#8b949e'; g.strokeStyle = 'rgba(255,255,255,.08)'; g.lineWidth = 1;
    g.textBaseline = 'middle';
    for (let i = 0; i <= 3; i++) {
      const v = lo + (hi - lo) * i / 3;
      g.beginPath(); g.moveTo(left, Y(v)); g.lineTo(w - right, Y(v)); g.stroke();
      g.fillText(v.toFixed(Math.abs(hi - lo) < 5 ? 2 : 0), 2, Y(v));
    }
    g.textBaseline = 'alphabetic';
    const dur = (t1 - t0) / 1000;
    for (let i = 0; i <= 4; i++) {
      const t = t0 + (t1 - t0) * i / 4;
      const label = `${(dur * i / 4).toFixed(dur < 20 ? 1 : 0)} s`;
      g.fillText(label, Math.min(X(t), w - 30), h - 4);
    }
    g.lineJoin = 'round'; g.lineWidth = 2;
    for (const s of series) {
      g.strokeStyle = s.color;
      g.beginPath();
      let pen = false;
      const step = Math.max(1, Math.floor(rows.length / w));
      for (let i = 0; i < rows.length; i += step) {
        const v = rows[i][s.key];
        if (typeof v !== 'number') { pen = false; continue; }
        const x = X(rows[i].t as number), y = Y(v);
        pen ? g.lineTo(x, y) : g.moveTo(x, y);
        pen = true;
      }
      g.stroke();
    }
    g.strokeStyle = '#e6edf3'; g.lineWidth = 1;
    g.beginPath(); g.moveTo(X(cursor), top); g.lineTo(X(cursor), h - bottom); g.stroke();
  });
  const pick = (e: PointerEvent) => {
    if (!e.buttons && e.type !== 'pointerdown') return;
    const r = ref.current!.getBoundingClientRect();
    const f = (e.clientX - r.left - 40) / (r.width - 46);
    setCursor(t0 + Math.max(0, Math.min(1, f)) * (t1 - t0));
  };
  return (
    <figure class="chart">
      <figcaption>
        <b>{title}</b>
        {series.map(s => <span key={s.key} class="legend"><i style={{ background: s.color }} />{s.label} {typeof nearest?.[s.key] === 'number' ? (nearest[s.key] as number).toFixed(2) : '--'} {unit}</span>)}
      </figcaption>
      <canvas ref={ref} onPointerDown={pick} onPointerMove={pick} style={{ touchAction: 'none' }} />
    </figure>
  );
}

function Track({ rows, photos, cursor, setCursor }: { rows: Row[]; photos: any[]; cursor: number; setCursor: (t: number) => void }) {
  const [, force] = useState(0);
  const pts = useMemo(() => rows.filter(r => typeof r.lat === 'number'), [rows]);
  const state = useRef({ pts, cursor, photos });
  state.current = { pts, cursor, photos };
  const { ref, map } = useMap((m: TileMap) => {
    m.overlay = (mm, g) => {
      const { pts: p, cursor: c, photos: ph } = state.current;
      g.strokeStyle = '#ffd33d'; g.lineWidth = 3; g.beginPath();
      p.forEach((r, i) => { const s = mm.toScreen(r.lat as number, r.lon as number); i ? g.lineTo(s.x, s.y) : g.moveTo(s.x, s.y); });
      g.stroke();
      ph.filter(x => x.lat != null).forEach(x => {
        const s = mm.toScreen(x.lat, x.lon);
        g.fillStyle = '#fff'; g.strokeStyle = '#0e1116'; g.lineWidth = 2;
        g.beginPath(); g.rect(s.x - 6, s.y - 5, 12, 10); g.fill(); g.stroke();
      });
      const here = p.find(r => (r.t as number) >= c) ?? p[p.length - 1];
      if (here) drawVehicle(mm, g, here.lat as number, here.lon as number, (here.yaw as number) ?? 0, (here.gpsAcc as number) ?? 0);
    };
    m.onClick = (ll) => {
      let best: Row | null = null, d = Infinity;
      for (const r of state.current.pts) { const x = Math.hypot((r.lat as number) - ll.lat, (r.lon as number) - ll.lon); if (x < d) { d = x; best = r; } }
      if (best) setCursor(best.t as number);
    };
    force(x => x + 1);
  });
  useEffect(() => { map.current?.fit(pts.map(r => ({ lat: r.lat as number, lon: r.lon as number }))); }, [pts]);
  useEffect(() => { map.current?.draw(); });
  return (
    <div class="mapwrap small">
      <canvas ref={ref} id="reviewMap" />
      <LayerSwitch map={map.current} />
      {!pts.length && <div class="onmap dim">This run has no positions.</div>}
    </div>
  );
}

function Run({ name, frames, photos, onClose }: { name: string; frames: number; photos: any[]; onClose: () => void }) {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [err, setErr] = useState('');
  const [cursor, setCursor] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [events, setEvents] = useState<any[]>([]);
  const [times, setTimes] = useState<number[]>([]);
  const run = name.replace(/\.csv$/, '');
  useEffect(() => {
    setRows(null); setErr('');
    get(`review/file/log/${name}`).then(r => (r.ok ? r.text() : Promise.reject(r.status))).then(t => {
      const parsed = parseCsv(t);
      if (!parsed.length) { setErr('This log is empty.'); return; }
      setRows(parsed);
      setCursor(parsed[0].t as number);
    }).catch(() => setErr('Could not load this log.'));
    if (frames) get(`review/frames/${run}`).then(r => r.json()).then(setTimes).catch(() => {});
    get('export/events.csv').then(r => r.text()).then(t => setEvents(parseCsv(t))).catch(() => {});
  }, [name]);
  const t0 = rows?.[0]?.t as number, t1 = rows?.[rows.length - 1]?.t as number;
  useEffect(() => {
    if (!playing || !rows) return;
    const id = setInterval(() => setCursor(c => { const n = c + 200; if (n >= t1) { setPlaying(false); return t1; } return n; }), 200);
    return () => clearInterval(id);
  }, [playing, rows]);
  const frameT = useMemo(() => {
    if (!times.length) return null;
    let best = times[0];
    for (const t of times) { if (Math.abs(t - cursor) < Math.abs(best - cursor)) best = t; }
    return Math.abs(best - cursor) < 5000 ? best : null;
  }, [times, cursor]);
  if (err) return <Panel title={name}><p class="badText">{err}</p><button onClick={onClose}>Back</button></Panel>;
  if (!rows) return <p class="dim">Loading {name}...</p>;
  const inRun = events.filter(e => typeof e.t === 'number' && e.t >= t0 && e.t <= t1 && ['mode', 'failsafe', 'trigger', 'mission', 'photo', 'fence', 'marker', 'nav', 'sim'].includes(e.kind));
  // A sent plan logs each point against its expected time: "reached point 4 (step field), 12 s late".
  const arrivals = inRun.filter(e => e.kind === 'mission' && e.code === 'arrived').map(e => {
    const m = /reached point (\d+)(?: \(step ([^)]+)\))?(?:, (?:(on time)|(\d+) s (late|early)))?/.exec(String(e.message));
    return m && { t: e.t as number, point: Number(m[1]), step: m[2] ?? '', late: m[3] ? 0 : m[4] ? Number(m[4]) * (m[5] === 'early' ? -1 : 1) : null };
  }).filter(Boolean) as { t: number; point: number; step: string; late: number | null }[];
  const misses = inRun.filter(e => (e.kind === 'mission' && (e.code === 'miss' || e.code === 'aborted')) || (e.kind === 'failsafe' && ['divert', 'motor_out', 'outer', 'time_low', 'link_lost', 'no_zone'].includes(String(e.code))));
  const here = rows.find(r => (r.t as number) >= cursor);
  const runPhotos = photos.filter(p => p.t >= t0 && p.t <= t1);
  return (
    <div class="review" id="runView">
      <div class="row">
        <button onClick={onClose}>All runs</button>
        <b>{new Date(t0).toLocaleString()}, {((t1 - t0) / 1000).toFixed(0)} s, {rows.length} rows</b>
        <span class="grow" />
        <a class="btn" href={fileUrl('log', name)} download={name}>Download CSV</a>
      </div>
      <div class="scrub">
        <button id="play" onClick={() => setPlaying(!playing)}>{playing ? 'Pause' : 'Play'}</button>
        <input id="scrub" type="range" min={t0} max={t1} value={cursor} onInput={e => setCursor(Number(e.currentTarget.value))} />
        <span class="mono">{((cursor - t0) / 1000).toFixed(1)} s · {String(here?.mode ?? '')}</span>
      </div>
      <div class="reviewGrid">
        <div>
          <Track rows={rows} photos={runPhotos} cursor={cursor} setCursor={setCursor} />
          {frames > 0 && <div class="video replay">{frameT ? <img id="replayFrame" src={frameUrl(run, frameT)} /> : <div class="onmap dim">No picture at this moment</div>}</div>}
        </div>
        <div>
          <Chart title="Attitude" unit="°" rows={rows} cursor={cursor} setCursor={setCursor} series={[{ key: 'roll', label: 'Roll', color: BLUE }, { key: 'pitch', label: 'Pitch', color: ORANGE }]} />
          <Chart title="Outputs" unit="" rows={rows} cursor={cursor} setCursor={setCursor} series={[{ key: 'throttle', label: 'Throttle', color: BLUE }, { key: 'steering', label: 'Steering', color: ORANGE }]} />
          <Chart title="Altitude" unit="m" rows={rows} cursor={cursor} setCursor={setCursor} series={[{ key: 'alt', label: 'GPS', color: BLUE }]} />
        </div>
      </div>
      {arrivals.some(a => a.late !== null) && <Panel title="Plan against actual">
        <p class="dim small">How far ahead of or behind the plan's expected time it reached each point. Steadily later means the planning numbers are too hopeful for this vehicle: lower PLAN_SPEED or PLAN_CLIMB.</p>
        <div class="list" id="planVsActual">
          {arrivals.map((a, i) => (
            <div key={i} class="item click" onClick={() => setCursor(a.t)}>
              <span class="mono dim">{((a.t - t0) / 1000).toFixed(0)} s</span>
              <span class="grow">point {a.point}{a.step && <span class="dim"> · {a.step}</span>}</span>
              {a.late !== null && <span class={Math.abs(a.late) < 10 ? 'goodText' : a.late > 0 ? 'warnText' : ''}>{a.late === 0 ? 'on time' : a.late > 0 ? `${a.late} s late` : `${-a.late} s early`}</span>}
            </div>
          ))}
        </div>
        {misses.length > 0 && <><h3>What didn't go to plan</h3><ul class="warnings">{misses.map((e, i) => <li key={i} class="click" onClick={() => setCursor(e.t)}>{String(e.message)}</li>)}</ul></>}
      </Panel>}
      <Panel title="Events">
        <div class="list" id="runEvents">
          {inRun.map((e, i) => <div key={i} class={`item ${Math.abs(e.t - cursor) < 500 ? 'sel' : ''}`} onClick={() => setCursor(e.t)}><span class="mono dim">{((e.t - t0) / 1000).toFixed(1)} s</span><span class="grow">{e.message}</span></div>)}
          {!inRun.length && <p class="dim">The phone only keeps recent events. None remain for this run.</p>}
        </div>
      </Panel>
      {runPhotos.length > 0 && <Panel title="Photos in this run"><Photos list={runPhotos} setCursor={setCursor} /></Panel>}
    </div>
  );
}

function Photos({ list, setCursor }: { list: any[]; setCursor?: (t: number) => void }) {
  return (
    <div class="photos" id="photos">
      {list.map(p => (
        <figure key={p.name} onClick={() => setCursor?.(p.t)}>
          <img loading="lazy" src={fileUrl('photo', p.name)} alt={`Photo from ${new Date(p.t).toLocaleString()}`} />
          <figcaption class="dim">{new Date(p.t).toLocaleTimeString()}{p.lat != null ? ` · ${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}` : ' · no location'}</figcaption>
        </figure>
      ))}
    </div>
  );
}

export function Review() {
  const [files, setFiles] = useState<any>(null);
  const [err, setErr] = useState('');
  const [open, setOpen] = useState<{ name: string; frames: number } | null>(null);
  const load = () => fetchFiles().then(setFiles).catch(() => setErr('Could not list the logs. Is the phone connected?'));
  useEffect(() => { load(); }, [S.link]);
  if (S.relay && S.relay.role !== 'operator') return <p class="dim">Logs are for operators.</p>;
  if (open) return <Run name={open.name} frames={open.frames} photos={files?.photos ?? []} onClose={() => setOpen(null)} />;
  return (
    <div class="pageCols">
      <div>
        <Panel title="Runs" right={<button onClick={load}>Refresh</button>}>
          {err && <p class="badText">{err}</p>}
          <div class="list" id="runs">
            {files?.logs.map((l: any) => (
              <div class="item click" key={l.name} data-run={l.name} onClick={() => setOpen({ name: l.name, frames: l.frames })}>
                <span class="grow">{new Date(l.t).toLocaleString()}</span>
                <span class="dim">{(l.bytes / 1024).toFixed(0)} KB{l.frames ? ` · ${l.frames} pictures` : ''}</span>
              </div>
            ))}
            {files && !files.logs.length && <p class="dim">No runs yet. Arming starts one.</p>}
          </div>
        </Panel>
        <Panel title="Export">
          <div class="row">
            {['telemetry', 'commands', 'events', 'sensors', 'flight'].map(n => <a class="btn" key={n} href={`/export/${n}.csv${authQuery()}`}>{n}</a>)}
          </div>
        </Panel>
      </div>
      <div>
        <Panel title="Photos">{files?.photos.length ? <Photos list={files.photos} /> : <p class="dim">No photos yet.</p>}</Panel>
        <Panel title="Test reports">
          {files?.reports.map((r: any) => (
            <details key={r.t}>
              <summary class={r.passed ? 'goodText' : 'badText'}>{new Date(r.t).toLocaleString()}: {r.passed ? 'passed' : 'failed'} ({r.name})</summary>
              {r.results.map((x: any, i: number) => <CheckRow key={i} c={{ label: x.test, status: x.status, detail: x.detail, fix: '' }} />)}
            </details>
          ))}
          {files && !files.reports.length && <p class="dim">None yet. Run them in Test.</p>}
        </Panel>
      </div>
    </div>
  );
}
