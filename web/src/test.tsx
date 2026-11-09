// Prove it's ready. The phone runs the checks and keeps the reports. Leaving this page ends any test.
import { useEffect, useState } from 'preact/hooks';
import { armed, cmd, isOperator, nav, onPong, ping, S, type GroundStage } from './store';
import { CheckRow, HoldToRun, Panel } from './parts';
import { fetchFiles } from './review';
import { openDraft } from './plan';

function Checklist() {
  const list = S.ops?.checklist ?? [];
  return (
    <Panel title="Checklist">
      <div id="checklist">
        {list.map(c => (
          <label class="tick" key={c.item}><input type="checkbox" checked={c.done} disabled={!isOperator()} onChange={e => cmd('checklist_set', { item: c.item, done: e.currentTarget.checked }, { quiet: true })} />{c.item}</label>
        ))}
      </div>
    </Panel>
  );
}

/** A small square round where the phone is now, with a fence a little outside it, to walk or drive. */
export function groundTestPlan(how: 'walk' | 'drive'): { plan: any; fence: any } | string {
  const gps = S.fcs?.gps;
  const g = gps?.nav;
  if (!gps) return 'No position yet. Go outside, with Location on, and wait for GPS.';
  if (g && g.health !== 'good') return `GPS isn't good enough yet (±${Math.round(g.radius)} m, ${g.health}). Wait a minute in the open.`;
  const lat = g?.lat ?? gps.lat, lon = g?.lon ?? gps.lon;
  const radius = Math.max(5, Math.ceil((g?.radius ?? gps.acc) * 1.3) + 1);
  const side = how === 'walk' ? Math.max(20, 3 * radius) : Math.max(30, 4 * radius);
  const margin = how === 'walk' ? 12 : 20;
  const m = 111320, k = m * Math.cos(lat * Math.PI / 180);
  const at = (n: number, e: number): [number, number] => [+(lat + n / m).toFixed(7), +(lon + e / k).toFixed(7)];
  const corners = [at(side, 0), at(side, side), at(0, side), at(0, 0)];
  return {
    plan: {
      format: 'smartphonex.plan/1', name: how === 'walk' ? 'Ground test, walked' : 'Ground test, driven',
      defaults: { speed: how === 'walk' ? 1.2 : 2, radius }, onEnd: 'hold', onLinkLoss: 'hold', zones: [],
      steps: corners.map((c, i) => ({ id: `corner-${i + 1}`, do: 'goto', at: c })),
    },
    fence: { polygon: [at(-margin, -margin), at(side + margin, -margin), at(side + margin, side + margin), at(-margin, side + margin)], keepOut: [], maxDistM: 0, maxAltM: 0, action: 'hold', outerM: 0 },
  };
}

const MARK: Record<string, string> = { pass: '✓', warn: '!', fail: '✕', todo: '', skip: '–' };

/** Ground test: what the vehicle has been seen to do, gathered by the phone. Each step says how to do it. */
function GroundTestPanel() {
  const gt = S.ops?.groundTest;
  const [msg, setMsg] = useState('');
  if (!gt) return null;
  const plane = S.hello?.caps?.vehicleType === 'plane';
  const next = gt.stages.find(s => s.status === 'todo' || s.status === 'fail');
  const make = () => {
    const r = groundTestPlan(gt.how);
    if (typeof r === 'string') { setMsg(r); return; }
    setMsg('');
    openDraft(r.plan, r.fence);
    nav.go('plan');
  };
  const row = (s: GroundStage) => (
    <div key={s.id} class={`gstage ${s.status} ${s === next ? 'next' : ''}`} data-stage={s.id}>
      <span class={`mark ${s.status === 'pass' ? 'good' : s.status === 'warn' ? 'warn' : s.status === 'fail' ? 'bad' : 'off'}`}>{MARK[s.status]}</span>
      <div class="grow">
        <b>{s.label}</b>{s.ranAs && <span class="dim small"> · {s.ranAs === 'walk' ? 'walked' : 'driven'}</span>}
        {s.detail && <div class={s.status === 'fail' ? 'badText' : 'dim'}>{s.detail}</div>}
        {(s.status === 'todo' || s.status === 'fail' || s === next) && <div class="fix">{s.how}</div>}
        {s.id === 'outputs' && isOperator() && s.status !== 'pass' && <div class="row">
          <button id="outputsRight" onClick={() => cmd('ground_test_confirm', { ok: true }, { label: 'Outputs move the right way' })}>They all moved the right way</button>
          <button class="dangerOutline" onClick={() => cmd('ground_test_confirm', { ok: false }, { label: 'An output moved the wrong way' })}>One moved the wrong way</button>
        </div>}
        {s.id === 'route' && s.status !== 'pass' && isOperator() && !plane && <div class="row">
          <button id="makeTestPlan" onClick={make}>Make a test plan here</button>
        </div>}
        {s.id === 'route' && gt.route && <div class="warnText" id="routeLive">Running {gt.route.name}: {gt.route.reached} of {gt.route.of} points, off the line by up to {gt.route.maxOffM} m</div>}
      </div>
    </div>
  );
  return (
    <Panel title="Ground test" id="groundTest" right={<span class={`pill ${gt.done ? 'good' : 'off'}`} id="groundTestScore">{gt.passed} of {gt.needed}</span>}>
      <p class="dim">What the vehicle has been seen to do, measured by the phone. Do them in order, outdoors for the last ones.</p>
      {isOperator() && <div class="row">
        <span class="dim">How:</span>
        <button data-how="walk" class={gt.how === 'walk' ? 'sel' : ''} onClick={() => cmd('ground_test_how', { how: 'walk' }, { quiet: true })}>Walk it</button>
        <button data-how="drive" class={gt.how === 'drive' ? 'sel' : ''} onClick={() => cmd('ground_test_how', { how: 'drive' }, { quiet: true })}>Drive it</button>
      </div>}
      <p class="dim small">{gt.how === 'walk'
        ? 'Walk it: carry the phone round the plan, armed, with nothing connected that can move. It proves navigation, arrivals, timing and the fence on real GPS.'
        : 'Drive it: the vehicle steers itself round the plan, slowly, with you ready on STOP. Start with walking.'}</p>
      {plane && <p class="warnText small">For a plane, do the first four here; the route and fence need a ground or water profile (or Walk it on a car profile), and flight paths are tried on the simulated plane.</p>}
      {msg && <p class="badText" id="groundTestMsg">{msg}</p>}
      <div id="groundStages">{gt.stages.map(row)}</div>
      {gt.done && <p class="goodText"><b>Ground test passed.</b> Keep the first real runs short, slow and in sight.</p>}
      {isOperator() && <details><summary class="dim">Start again</summary>
        <button onClick={() => { if (confirm("Clear this vehicle's ground test results?")) cmd('ground_test_reset', {}, { label: 'Ground test cleared' }); }}>Clear the results</button>
      </details>}
    </Panel>
  );
}

function Diagnostics() {
  const r = S.ops?.report;
  const running = S.ops?.diag;
  return (
    <Panel title="Automatic tests">
      <p class="dim">Sensors, GPS, control timing, stop and link-loss. No hardware moves. Keep the phone still.</p>
      <button id="runTests" class="big" disabled={!!running || armed() || !isOperator()} onClick={() => cmd('diagnostics_run', {}, { label: 'Run tests' })}>{running ? `Testing: ${running}` : 'Run tests'}</button>
      {r && <div id="report">
        <h3 class={r.passed ? 'goodText' : 'badText'}>{r.passed ? 'Passed' : 'Failed'}, {new Date(r.t).toLocaleString()}</h3>
        {r.results.map((x, i) => <CheckRow key={i} c={{ label: x.test, status: x.status, detail: x.detail, fix: '' }} />)}
      </div>}
    </Panel>
  );
}

function LinkTest() {
  const [result, setResult] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const run = () => {
    setBusy(true);
    setResult('');
    const times: number[] = [];
    let sent = 0;
    const off = onPong(rtt => times.push(rtt));
    const timer = setInterval(() => { ping(); sent++; }, 100);
    setTimeout(() => {
      clearInterval(timer);
      setTimeout(() => {
        off();
        const n = times.length;
        const avg = n ? times.reduce((a, b) => a + b, 0) / n : 0;
        const jitter = n ? Math.sqrt(times.reduce((a, b) => a + (b - avg) ** 2, 0) / n) : 0;
        const worst = n ? Math.max(...times) : 0;
        const lost = Math.max(0, 1 - n / sent) * 100;
        const limit = S.hello?.profiles?.find((p: any) => p.id === S.tel?.profile)?.commandTimeoutMs ?? 500;
        const status = !n || worst >= limit ? 'fail' : lost > 5 || worst > limit / 2 ? 'warn' : 'pass';
        const text = n ? `Round trip ${avg.toFixed(0)} ms, jitter ${jitter.toFixed(0)} ms, worst ${worst.toFixed(0)} ms, ${lost.toFixed(0)}% lost. This vehicle's limit is ${limit} ms.` : 'No answers at all.';
        setResult(text);
        setBusy(false);
        cmd('test_record', { test: 'Link', status, detail: text }, { quiet: true });
      }, 600);
    }, 5000);
  };
  return (
    <Panel title="Link test">
      <p class="dim">Five seconds of timing. It goes into the next test report.</p>
      <button id="linkTest" disabled={busy || !isOperator()} onClick={run}>{busy ? 'Measuring...' : 'Test link'}</button>
      {result && <p id="linkResult">{result}</p>}
    </Panel>
  );
}

function OutputTests() {
  const [propsOff, setPropsOff] = useState(false);
  const [sizes, setSizes] = useState<Record<string, number>>({});
  useEffect(() => () => { cmd('test_stop', {}, { quiet: true }); }, []);
  const profile = S.hello?.profiles?.find((p: any) => p.id === S.tel?.profile);
  const device = S.device?.actuators;
  const outs: { name: string; motor: boolean; forwardOnly: boolean }[] = device
    ? device.map((a: any) => ({ name: a.id, motor: false, forwardOnly: false }))
    : (profile?.channels ?? []).map((c: any) => ({ name: c.name, motor: c.kind !== 'SERVO', forwardOnly: c.kind === 'MOTOR_FORWARD' }));
  const param = (n: string, d: number) => S.params.find(p => p.name === n)?.value ?? d;
  const motorLimit = param('TEST_MAX_MOTOR', 0.15), servoLimit = param('TEST_MAX_SERVO', 0.5);
  const off = armed() || !isOperator();
  return (
    <Panel title="Output tests">
      <p class="dim">{armed() ? 'Disarm to test outputs.' : 'Hold a button to run an output. Letting go stops it at once. Restrain anything that can move.'}</p>
      <label class="tick"><input id="propsOff" type="checkbox" checked={propsOff} onChange={e => setPropsOff(e.currentTarget.checked)} />Props are off, or wheels off the ground</label>
      {!!S.ops?.test && <p class="warnText" id="testRunning">Running: {S.ops.test.output} at {S.ops.test.value.toFixed(2)} from {S.ops.test.owner.toLowerCase()}</p>}
      <div id="outputTests">
        {outs.map(o => {
          const limit = o.motor ? motorLimit : servoLimit;
          const size = Math.min(sizes[o.name] ?? limit / 2, limit);
          const out = S.tel?.out?.[outs.indexOf(o)];
          return (
            <div class="testRow" key={o.name}>
              <div class="row"><b class="grow">{o.name}</b><span class="dim">{out ? `${out[1]} µs` : ''}</span></div>
              <label>Size {size.toFixed(2)} of limit {limit.toFixed(2)} <input type="range" min={0} max={limit} step={0.01} value={size} onInput={e => setSizes({ ...sizes, [o.name]: Number(e.currentTarget.value) })} /></label>
              <div class="row">
                {!o.forwardOnly && <HoldToRun label="Hold: reverse" output={o.name} value={-size} confirmed={propsOff} disabled={off} />}
                <HoldToRun label="Hold: forward" output={o.name} value={size} confirmed={propsOff} disabled={off} />
              </div>
            </div>
          );
        })}
      </div>
    </Panel>
  );
}

function SensorPlausibility() {
  const list = S.config?.sensors ?? [];
  const values = S.sensorValues;
  const live = list.filter((s: any) => s.enabled);
  return (
    <Panel title="Sensors, live">
      {!live.length && <p class="dim">No sensors are streaming. Turn them on in Setup.</p>}
      <div class="sensorGrid">
        {live.map((s: any) => <div key={s.id}><b>{s.name}</b><div class="dim mono">{values[s.id]?.map(v => Number(v).toFixed(2)).join(' ') ?? 'waiting'} {s.unit}</div></div>)}
      </div>
    </Panel>
  );
}

function Reports() {
  const [list, setList] = useState<any[] | null>(null);
  return (
    <Panel title="Saved reports" right={<a href="#" onClick={e => { e.preventDefault(); fetchFiles().then(f => setList(f.reports)); }}>Load</a>}>
      {list?.map(r => <details key={r.t}><summary class={r.passed ? 'goodText' : 'badText'}>{new Date(r.t).toLocaleString()}: {r.passed ? 'passed' : 'failed'} ({r.name})</summary>{r.results.map((x: any, i: number) => <CheckRow key={i} c={{ label: x.test, status: x.status, detail: x.detail, fix: '' }} />)}</details>)}
      {list && !list.length && <p class="dim">None yet.</p>}
    </Panel>
  );
}

/** The motor stepped through three throttles while the microphone measures it: the curve flights are checked against. */
function GroundRun() {
  const [clear, setClear] = useState(false);
  const param = (n: string, d: number) => S.params.find(p => p.name === n)?.value ?? d;
  if (param('MIC_PROP', 0) < 1) return null;
  const run = S.ops?.groundRun;
  const prop = S.fcs?.prop;
  const g = prop?.groundRun;
  const lo = Math.round(param('PROP_GR_LO', 0.3) * 100), hi = Math.round(param('PROP_GR_HI', 0.6) * 100);
  const strength = (s: number) => Math.abs(s - 1) < 0.005 ? 'as strong as the reference' : `${(Math.abs(s - 1) * 100).toFixed(1)}% ${s > 1 ? 'stronger' : 'weaker'} than the reference`;
  return (
    <Panel title="Ground run">
      <p class="dim">Before each flight: with the plane restrained, hold the button while the motor steps through {lo}% to {hi}% throttle, about 3 s each. It measures the speed and tones at each step. Flights are checked against them, and the pack's strength is measured. Letting go stops the motor at once.</p>
      <label class="tick"><input id="grClear" type="checkbox" checked={clear} onChange={e => setClear(e.currentTarget.checked)} />The plane is restrained and everyone is clear of the propeller</label>
      <HoldToRun label="Hold: ground run" command="ground_run" stop="ground_run_stop" confirmed={clear} disabled={armed() || !isOperator()} />
      {run && run.state !== 'IDLE' && <p id="grStatus" class={run.state === 'FAILED' ? 'badText' : run.state === 'RUNNING' ? 'warnText' : run.state === 'DONE' ? 'goodText' : 'dim'}>{run.message}{prop?.rpm != null && run.state === 'RUNNING' ? ` (hearing ${prop.rpm} rpm)` : ''}</p>}
      {g && <div class="dim" id="grStored">
        <div>Throttle {g.throttles.split(',').join('%, ')}%</div>
        <div>Reference: {g.referenceRpm.split(',').join(', ')} rpm</div>
        <div>Latest, {new Date(g.lastAt).toLocaleTimeString()}: {g.lastRpm.split(',').join(', ')} rpm. Pack {strength(g.strength)}</div>
        {g.changed && <div class="warnText">Setup changed: {g.changed}. Stored as the new reference.</div>}
        <a href="#" onClick={e => { e.preventDefault(); if (confirm('Forget the ground runs for this vehicle? The next one becomes the reference.')) cmd('ground_run_forget', {}); }}>Forget</a>
      </div>}
    </Panel>
  );
}

export function TestPage() {
  const ready = S.ops?.readiness ?? [];
  return (
    <div class="pageCols">
      <div>
        <GroundTestPanel />
        <Panel title="Ready to arm?"><div id="readiness">{ready.map(c => <CheckRow key={c.id} c={c} />)}</div></Panel>
        <Checklist />
        <SensorPlausibility />
      </div>
      <div>
        <Diagnostics />
        <LinkTest />
        <OutputTests />
        <GroundRun />
        <Reports />
      </div>
    </div>
  );
}
