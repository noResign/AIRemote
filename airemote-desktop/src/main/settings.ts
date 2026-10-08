import { app, safeStorage } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { isLoopback } from '../shared/net';
import type { AppPrefs } from '../shared/ipc';

export { isLoopback };

/**
 * Persisted desktop settings, in `<userData>/settings.json`. Secrets are split
 * by trust level:
 *
 * - loopback daemons: the token is **never stored** — it is read from the
 *   daemon's own `0600` file on demand (see connection/manager.ts).
 * - remote daemons: the token goes through `safeStorage`. When the platform has
 *   no keyring (Linux without libsecret/kwallet) we deliberately do **not** fall
 *   back to plaintext — it lives in memory for this session only and the UI says
 *   so.
 */
export interface RecentConnection {
  baseUrl: string;
  name: string | null;
  lastUsedAt: number;
}

interface StoredConnection {
  host: string;
  port: number;
  tls: boolean;
  name: string | null;
  /** base64 of safeStorage ciphertext. Absent for loopback targets. */
  tokenEnc?: string;
}

interface SettingsFile {
  connection: StoredConnection | null;
  recent: RecentConnection[];
  prefs: AppPrefs;
}

const EMPTY: SettingsFile = { connection: null, recent: [], prefs: { closeBehavior: 'ask', desktopNotifications: true } };
const MAX_RECENT = 5;

/** Token kept only for this process lifetime (no keyring, or loopback). */
let memoryToken: string | null = null;

function settingsPath(): string {
  return path.join(app.getPath('userData'), 'settings.json');
}

function read(): SettingsFile {
  try {
    const raw = fs.readFileSync(settingsPath(), 'utf8');
    const parsed = JSON.parse(raw) as Partial<SettingsFile>;
    return {
      connection: parsed.connection ?? null,
      recent: Array.isArray(parsed.recent) ? parsed.recent : [],
      prefs: { ...EMPTY.prefs, ...(parsed.prefs ?? {}) },
    };
  } catch {
    return { ...EMPTY };
  }
}

function write(next: SettingsFile): void {
  const file = settingsPath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(next, null, 2)}\n`, { mode: 0o600 });
}

export interface StoredTarget {
  host: string;
  port: number;
  tls: boolean;
  name: string | null;
}

/** Restore the last target and its token, if it can be recovered. */
export function loadConnection(): { target: StoredTarget; token: string | null } | null {
  const { connection } = read();
  if (!connection) return null;
  const target: StoredTarget = {
    host: connection.host,
    port: connection.port,
    tls: connection.tls,
    name: connection.name,
  };
  if (connection.tokenEnc && isLoopback(connection.host) === false) {
    try {
      const token = safeStorage.decryptString(Buffer.from(connection.tokenEnc, 'base64'));
      return { target, token };
    } catch {
      return { target, token: null };
    }
  }
  return { target, token: memoryToken };
}

export function saveConnection(target: StoredTarget, token: string | null): void {
  const next = read();
  const stored: StoredConnection = {
    host: target.host,
    port: target.port,
    tls: target.tls,
    name: target.name,
  };
  if (token && !isLoopback(target.host)) {
    if (safeStorage.isEncryptionAvailable()) {
      stored.tokenEnc = safeStorage.encryptString(token).toString('base64');
      memoryToken = null;
    } else {
      memoryToken = token;
    }
  } else {
    memoryToken = token;
  }
  next.connection = stored;
  write(next);
  addRecentInternal(next, `http${target.tls ? 's' : ''}://${target.host}:${target.port}`, target.name);
}

export function clearConnection(): void {
  const next = read();
  next.connection = null;
  memoryToken = null;
  write(next);
}

export function listRecent(): RecentConnection[] {
  return read().recent;
}

function addRecentInternal(file: SettingsFile, baseUrl: string, name: string | null): void {
  const rest = file.recent.filter((r) => r.baseUrl !== baseUrl);
  file.recent = [{ baseUrl, name, lastUsedAt: Date.now() }, ...rest].slice(0, MAX_RECENT);
  write(file);
}

/** Record a connection only after it has actually succeeded. */
export function rememberConnection(baseUrl: string, name: string | null): void {
  const next = read();
  addRecentInternal(next, baseUrl, name);
}

/**
 * Window close behaviour and notifications. These live in main rather than the
 * renderer's localStorage because main has to act on them before (and without)
 * a renderer: the close button is handled by the window, not by React.
 */
export function loadPrefs(): AppPrefs {
  return read().prefs;
}

export function savePrefs(patch: Partial<AppPrefs>): AppPrefs {
  const file = read();
  file.prefs = { ...file.prefs, ...patch };
  write(file);
  return file.prefs;
}
