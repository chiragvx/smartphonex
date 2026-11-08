// Fails if the ground station grows past its budget. The phone serves it over mobile data, so size is a feature.
//   node tools/size-budget.mjs
import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const web = new URL('../app/src/main/assets/web/assets/', import.meta.url);
// Gzipped kilobytes. Raise one only on purpose, in the same change that needs it.
const budget = { 'index.js': 40, 'index.css': 5, 'ticker.js': 3 };

let failed = false;
for (const [name, kb] of Object.entries(budget)) {
  const size = gzipSync(readFileSync(new URL(name, web)), { level: 9 }).length / 1024;
  const ok = size <= kb;
  failed ||= !ok;
  console.log(`${ok ? 'ok  ' : 'OVER'} ${name.padEnd(10)} ${size.toFixed(1)} KB gzipped, budget ${kb} KB`);
}
process.exit(failed ? 1 : 0);
