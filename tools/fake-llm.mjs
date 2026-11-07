// A stand-in LLM for testing the built-in assistant: it speaks both wire formats and records requests.
// It costs nothing and proves the app's requests, not any model's behavior.
//   node tools/fake-llm.mjs <port>
import { createServer } from 'node:http';
const port = Number(process.argv[2] || 8799);
const seen = [];
createServer((req, res) => {
  let body = '';
  req.on('data', c => { body += c; });
  req.on('end', () => {
    if (req.url === '/seen') { res.end(JSON.stringify(seen)); return; }
    const j = JSON.parse(body || '{}');
    seen.push({ url: req.url, headers: { key: req.headers['x-api-key'], auth: req.headers.authorization, version: req.headers['anthropic-version'], beta: req.headers['anthropic-beta'] }, body: j });
    res.setHeader('content-type', 'application/json');
    if (req.url === '/v1/messages') {
      const afterTool = JSON.stringify(j.messages.at(-1)).includes('tool_result');
      res.end(JSON.stringify(afterTool
        ? { content: [{ type: 'text', text: 'You are at stage 1. What is your device?' }], stop_reason: 'end_turn' }
        : { content: [{ type: 'thinking', thinking: '', signature: 'sig1' }, { type: 'tool_use', id: 'tu1', name: 'setup_status', input: {} }], stop_reason: 'tool_use' }));
    } else if (req.url === '/chat/completions') {
      const afterTool = j.messages.at(-1).role === 'tool';
      res.end(JSON.stringify({ choices: [afterTool
        ? { message: { role: 'assistant', content: 'Local model here. Stage noted.' }, finish_reason: 'stop' }
        : { message: { role: 'assistant', content: null, tool_calls: [{ id: 'c1', type: 'function', function: { name: 'setup_status', arguments: '{}' } }] }, finish_reason: 'tool_calls' }] }));
    } else { res.statusCode = 404; res.end('{}'); }
  });
}).listen(port, '0.0.0.0', () => console.log('fake llm on', port));
