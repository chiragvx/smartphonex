// Accounts and keys for the relay. Only hashes are stored, so a leaked data file exposes no secret.
import { createHash, randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCb);
const sha256 = s => createHash('sha256').update(s).digest('hex');
export const ROLES = ['viewer', 'operator'];

function safeEqual(a, b) {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

export class AuthStore {
  constructor(dataDir) {
    mkdirSync(dataDir, { recursive: true });
    this.file = join(dataDir, 'auth.json');
    this.data = existsSync(this.file)
      ? JSON.parse(readFileSync(this.file, 'utf8'))
      : { users: {}, aiKeys: {}, vehicleKeyHash: null };
  }

  save() {
    const tmp = this.file + '.tmp';
    writeFileSync(tmp, JSON.stringify(this.data, null, 2), { mode: 0o600 });
    renameSync(tmp, this.file);
  }

  async addUser(name, role, password) {
    if (!/^[a-zA-Z0-9_.-]{1,40}$/.test(name)) throw new Error('bad user name');
    if (!ROLES.includes(role)) throw new Error(`role must be one of ${ROLES.join(', ')}`);
    if (password.length < 10) throw new Error('password must be at least 10 characters');
    const salt = randomBytes(16).toString('hex');
    const hash = (await scrypt(password, salt, 32)).toString('hex');
    this.data.users[name] = { role, salt, hash };
    this.save();
  }

  removeUser(name) {
    delete this.data.users[name];
    this.save();
  }

  user(name) {
    return Object.hasOwn(this.data.users, name) ? this.data.users[name] : null;
  }

  async checkPassword(name, password) {
    const u = this.user(name);
    // Do the same work for an unknown user, so timing doesn't reveal which names exist.
    const hash = (await scrypt(password, u?.salt ?? 'no-such-user-salt', 32)).toString('hex');
    return u !== null && safeEqual(hash, u.hash);
  }

  newVehicleKey() {
    const key = randomBytes(32).toString('base64url');
    this.data.vehicleKeyHash = sha256(key);
    this.save();
    return key;
  }

  checkVehicleKey(key) {
    return !!this.data.vehicleKeyHash && safeEqual(sha256(key), this.data.vehicleKeyHash);
  }

  newAiKey(name) {
    if (!/^[a-zA-Z0-9_.-]{1,40}$/.test(name)) throw new Error('bad key name');
    const key = 'spx_' + randomBytes(32).toString('base64url');
    this.data.aiKeys[name] = sha256(key);
    this.save();
    return key;
  }

  removeAiKey(name) {
    delete this.data.aiKeys[name];
    this.save();
  }

  /** Returns the key's name, or null. */
  checkAiKey(key) {
    const h = sha256(key);
    for (const [name, stored] of Object.entries(this.data.aiKeys)) if (safeEqual(h, stored)) return name;
    return null;
  }
}
