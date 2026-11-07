// Prove it's ready. The phone runs the checks and keeps the reports. Leaving this page ends any test.
import { useEffect, useState } from 'preact/hooks';
import { armed, cmd, isOperator, onPong, ping, S } from './store';
import { CheckRow, HoldToRun, Panel } from './parts';
import { fetchFiles } from './review';

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

export function TestPage() {
  const ready = S.ops?.readiness ?? [];
  return (
    <div class="pageCols">
      <div>
        <Panel title="Ready to arm?"><div id="readiness">{ready.map(c => <CheckRow key={c.id} c={c} />)}</div></Panel>
        <Checklist />
        <SensorPlausibility />
      </div>
      <div>
        <Diagnostics />
        <LinkTest />
        <OutputTests />
        <Reports />
      </div>
    </div>
  );
}
