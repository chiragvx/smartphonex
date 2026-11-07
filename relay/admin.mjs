// Manage who can reach the vehicle. Run on the machine holding the relay's data directory.
//   node admin.mjs add-user <name> <viewer|operator> [password]    (generates one if omitted)
//   node admin.mjs remove-user <name>
//   node admin.mjs add-ai-key <name>
//   node admin.mjs remove-ai-key <name>
//   node admin.mjs new-vehicle-key                                 (replaces the current key)
//   node admin.mjs list
import { randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AuthStore } from './auth.mjs';

const dataDir = process.env.RELAY_DATA || join(dirname(fileURLToPath(import.meta.url)), 'data');
const store = new AuthStore(dataDir);
const [cmd, a, b, c] = process.argv.slice(2);

switch (cmd) {
  case 'add-user': {
    const password = c || randomBytes(9).toString('base64url');
    await store.addUser(a, b, password);
    console.log(`user ${a} (${b}), password: ${password}`);
    break;
  }
  case 'remove-user': store.removeUser(a); console.log(`removed ${a}; their sessions end within 30 s`); break;
  case 'add-ai-key': console.log(store.newAiKey(a)); break;
  case 'remove-ai-key': store.removeAiKey(a); console.log(`removed ${a}`); break;
  case 'new-vehicle-key': console.log(store.newVehicleKey()); break;
  case 'list':
    console.log('users:', Object.entries(store.data.users).map(([n, u]) => `${n} (${u.role})`).join(', ') || 'none');
    console.log('AI keys:', Object.keys(store.data.aiKeys).join(', ') || 'none');
    console.log('vehicle key set:', !!store.data.vehicleKeyHash);
    break;
  default:
    console.error('usage: see the top of admin.mjs');
    process.exit(2);
}
