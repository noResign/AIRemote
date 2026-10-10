import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/**
 * The token for a daemon **we** spawned.
 *
 * Two things are deliberately *not* done (`electron_desktop_plan.md` §2):
 * - not passed as `--token`: argv is world-readable via `ps`, and this token is
 *   equivalent to a remote shell on that machine;
 * - not scraped from stdout: that couples us to the daemon's log format.
 *
 * Instead the desktop owns the file the daemon itself reads: `<dataDir>/token`.
 * Created if missing, `0600`, 32 bytes of hex — the same shape the daemon writes
 * when it generates one itself, so either side can own it.
 */

export const TOKEN_BYTES = 32;

export function tokenPath(dataDir: string): string {
  return path.join(dataDir, 'token');
}

/** A fresh random token in the same shape the daemon generates for itself. */
export function generateToken(): string {
  return crypto.randomBytes(TOKEN_BYTES).toString('hex');
}

/**
 * Write `token` to `<dataDir>/token` (`0600`), replacing whatever is there.
 * 「改 token」uses this; a running daemon only reads its token at startup, so the
 * caller has to restart it for the new value to take effect.
 */
export function writeManagedToken(dataDir: string, token: string): boolean {
  const file = tokenPath(dataDir);
  try {
    fs.mkdirSync(dataDir, { recursive: true, mode: 0o700 });
    fs.writeFileSync(file, `${token}\n`, { mode: 0o600 });
    fs.chmodSync(file, 0o600);
    return true;
  } catch {
    return false;
  }
}

/**
 * Read the token, creating one when the file is absent. Returns null only when we
 * genuinely cannot produce one (unwritable directory) — the caller reports that
 * rather than spawning a daemon the client could never authenticate against.
 */
export function ensureManagedToken(dataDir: string): string | null {
  const file = tokenPath(dataDir);
  try {
    const existing = fs.readFileSync(file, 'utf8').trim();
    if (existing) {
      // Tighten a file that predates us — a world-readable token defeats the
      // whole point of keeping it off the command line.
      try {
        fs.chmodSync(file, 0o600);
      } catch {
        /* re-owning someone else's file is not our business */
      }
      return existing;
    }
  } catch {
    /* missing or unreadable — fall through and write one */
  }

  const token = generateToken();
  return writeManagedToken(dataDir, token) ? token : null;
}
