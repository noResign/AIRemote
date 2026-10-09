import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { startManagedDaemon, type StartManagedInput } from './managed-daemon';
import type { DaemonSupervisor } from './supervisor';

let dir: string;

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'airemote-managed-'));
});

afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

const base = (over: Partial<StartManagedInput> = {}): StartManagedInput => ({
  dataDir: dir,
  workspace: '/w',
  port: 4780,
  appPath: '/app',
  resourcesPath: '/app/resources',
  isPackaged: false,
  execPath: '/electron',
  ...over,
});

function supervisorStub(result: { ok: true; pid: number; listen: string | null } | { ok: false; code: string; message: string }) {
  const start = vi.fn(async () => result);
  return { supervisor: { start } as unknown as DaemonSupervisor, start };
}

describe('startManagedDaemon', () => {
  it('refuses to start when there is nothing to run, instead of failing at spawn', async () => {
    const { supervisor, start } = supervisorStub({ ok: true, pid: 1, listen: null });
    // Nothing bundled and no user command: `/nowhere/../airemote-daemon/dist/index.js`
    // does not exist, so the resolver returns null.
    const result = await startManagedDaemon(supervisor, base({ appPath: '/nowhere' }));
    expect(result).toMatchObject({ ok: false, code: 'no_daemon' });
    expect(start).not.toHaveBeenCalled();
  });

  it('starts the user command even when nothing is bundled', async () => {
    const { supervisor, start } = supervisorStub({ ok: true, pid: 7, listen: '127.0.0.1:4780' });
    const result = await startManagedDaemon(
      supervisor,
      base({ userCommand: { command: 'pnpm', args: ['exec', 'airemote'] } }),
    );
    expect(result).toMatchObject({ ok: true, pid: 7 });
    expect(start).toHaveBeenCalledOnce();
  });

  it('hands back the token so the caller can connect without re-reading the file', async () => {
    const { supervisor } = supervisorStub({ ok: true, pid: 7, listen: '127.0.0.1:4780' });
    const result = await startManagedDaemon(supervisor, base({ userCommand: { command: 'x' } }));
    expect(result.ok && result.token).toMatch(/^[0-9a-f]{64}$/);
  });

  it('reports a token failure before spawning anything', async () => {
    const blocked = path.join(dir, 'blocked');
    fs.writeFileSync(blocked, 'not a directory');
    const { supervisor, start } = supervisorStub({ ok: true, pid: 1, listen: null });
    const result = await startManagedDaemon(
      supervisor,
      base({ dataDir: path.join(blocked, 'data'), userCommand: { command: 'x' } }),
    );
    expect(result).toMatchObject({ ok: false, code: 'no_token' });
    expect(start).not.toHaveBeenCalled();
  });

  it('passes the supervisor failure straight through', async () => {
    const { supervisor } = supervisorStub({ ok: false, code: 'exited_early', message: '端口被占用' });
    const result = await startManagedDaemon(supervisor, base({ userCommand: { command: 'x' } }));
    expect(result).toMatchObject({ ok: false, code: 'exited_early', message: '端口被占用' });
  });
});
