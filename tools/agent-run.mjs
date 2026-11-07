// The full AI path: runs a real Claude agent and times its thinking separately from the vehicle.
//   node tools/agent-run.mjs <claude-exe> <relay-url> <ai-key> "<request>"
// Inference time is measured from when a tool result arrives to the agent's next tool call, as seen
// in its output stream. It includes model and internet time, and can't separate those further.
import { spawn } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [exe, base, key, request] = process.argv.slice(2);
if (!request) { console.error('usage: node tools/agent-run.mjs <claude-exe> <relay-url> <ai-key> "<request>"'); process.exit(2); }

const dir = mkdtempSync(join(tmpdir(), 'spx-agent-'));
const config = join(dir, 'mcp.json');
writeFileSync(config, JSON.stringify({ mcpServers: { smartphonex: { type: 'http', url: `${base}/mcp`, headers: { Authorization: `Bearer ${key}` } } } }));

const start = Date.now();
const calls = [], thinks = [], toolTimes = [];
let lastResultAt = start, firstCall = null, final = '', buffer = '';
const openCalls = new Map();

const child = spawn(exe, ['-p', request, '--mcp-config', config, '--strict-mcp-config', '--allowedTools', 'mcp__smartphonex__*', '--tools', '', '--output-format', 'stream-json', '--verbose'], { stdio: ['ignore', 'pipe', 'inherit'] });
child.stdout.on('data', chunk => {
  buffer += chunk;
  let i;
  while ((i = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, i);
    buffer = buffer.slice(i + 1);
    let m;
    try { m = JSON.parse(line); } catch { continue; }
    const now = Date.now();
    for (const c of m.message?.content ?? []) {
      if (c.type === 'tool_use') {
        if (firstCall === null) firstCall = now;
        thinks.push(now - lastResultAt);
        openCalls.set(c.id, now);
        calls.push(c.name.replace('mcp__smartphonex__', ''));
      }
      if (c.type === 'tool_result' && openCalls.has(c.tool_use_id)) {
        toolTimes.push(now - openCalls.get(c.tool_use_id));
        lastResultAt = now;
      }
    }
    if (m.type === 'result') final = m.result ?? '';
  }
});

const code = await new Promise(r => child.on('close', r));
rmSync(dir, { recursive: true, force: true });
const sum = a => a.reduce((x, y) => x + y, 0);
const avg = a => (a.length ? sum(a) / a.length : 0);
const row = {
  when: new Date().toISOString(), total_s: ((Date.now() - start) / 1000).toFixed(1), tool_calls: calls.length,
  inference_avg_ms: avg(thinks).toFixed(0), inference_max_ms: Math.max(0, ...thinks),
  tool_roundtrip_avg_ms: avg(toolTimes).toFixed(0), tool_roundtrip_max_ms: Math.max(0, ...toolTimes), exit: code,
};
mkdirSync('results', { recursive: true });
const csv = 'results/phase2-agent.csv';
if (!existsSync(csv)) appendFileSync(csv, Object.keys(row).join(',') + '\n');
appendFileSync(csv, Object.values(row).join(',') + '\n');
console.log('Calls:', calls.join(' > '));
console.log(final);
console.log(JSON.stringify(row, null, 1));
process.exit(code ?? 1);
