import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ensureManagedToken, tokenPath, writeManagedToken } from './managed-token';

let dir: string;

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'paboot-token-'));
});

afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

describe('ensureManagedToken', () => {
  it('creates a token when there is none, readable only by the owner', () => {
    const token = ensureManagedToken(dir);
    expect(token).toMatch(/^[0-9a-f]{64}$/);
    expect(fs.readFileSync(tokenPath(dir), 'utf8').trim()).toBe(token);
    expect(fs.statSync(tokenPath(dir)).mode & 0o777).toBe(0o600);
  });

  it('reuses an existing token — restarting must not invalidate the phone', () => {
    const first = ensureManagedToken(dir);
    expect(ensureManagedToken(dir)).toBe(first);
  });

  it('tightens permissions on a file that was already there', () => {
    fs.writeFileSync(tokenPath(dir), 'existing\n', { mode: 0o644 });
    expect(ensureManagedToken(dir)).toBe('existing');
    expect(fs.statSync(tokenPath(dir)).mode & 0o777).toBe(0o600);
  });

  it('creates the data dir when it does not exist yet', () => {
    const nested = path.join(dir, 'daemon-data');
    expect(ensureManagedToken(nested)).toMatch(/^[0-9a-f]{64}$/);
  });

  it('returns null instead of throwing when the path is unusable', () => {
    // A file where a directory should be: mkdir fails, and that must not blow up
    // a caller that is only trying to start a daemon.
    const blocked = path.join(dir, 'blocked');
    fs.writeFileSync(blocked, 'not a directory');
    expect(ensureManagedToken(path.join(blocked, 'data'))).toBeNull();
  });
});

describe('writeManagedToken', () => {
  it('overwrites the existing token and locks it down', () => {
    ensureManagedToken(dir);
    expect(writeManagedToken(dir, 'a-brand-new-secret')).toBe(true);
    expect(fs.readFileSync(tokenPath(dir), 'utf8').trim()).toBe('a-brand-new-secret');
    expect(fs.statSync(tokenPath(dir)).mode & 0o777).toBe(0o600);
  });

  it('returns false instead of throwing when the path is unusable', () => {
    const blocked = path.join(dir, 'blocked');
    fs.writeFileSync(blocked, 'not a directory');
    expect(writeManagedToken(path.join(blocked, 'data'), 'x')).toBe(false);
  });
});
