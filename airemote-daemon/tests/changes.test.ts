import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { getDiff, listChanges } from '../src/changes';

let dir: string;

function git(...args: string[]): void {
  execFileSync('git', args, { cwd: dir, stdio: 'pipe' });
}

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'airemote-changes-'));
});

afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

describe('git changes', () => {
  it('returns non-git workspaces as isGitRepo=false', async () => {
    const result = await listChanges(dir);
    expect(result.isGitRepo).toBe(false);
    expect(result.files).toEqual([]);
  });

  it('lists modified, untracked and renamed files', async () => {
    git('init', '-q');
    git('config', 'user.email', 'test@example.com');
    git('config', 'user.name', 'Test');
    fs.writeFileSync(path.join(dir, 'tracked.txt'), 'one');
    fs.writeFileSync(path.join(dir, 'rename-me.txt'), 'rename');
    git('add', '.');
    git('commit', '-qm', 'init');

    fs.writeFileSync(path.join(dir, 'tracked.txt'), 'two');
    fs.writeFileSync(path.join(dir, 'untracked.txt'), 'new');
    git('mv', 'rename-me.txt', 'renamed.txt');

    const result = await listChanges(dir);
    expect(result.isGitRepo).toBe(true);

    const tracked = result.files.find((f) => f.path === 'tracked.txt');
    expect(tracked).toMatchObject({ status: 'modified', staged: false });

    const untracked = result.files.find((f) => f.path === 'untracked.txt');
    expect(untracked).toMatchObject({ status: 'untracked', staged: false });

    const renamed = result.files.find((f) => f.path === 'renamed.txt');
    expect(renamed).toMatchObject({ status: 'renamed', staged: true, oldPath: 'rename-me.txt' });
  });

  it('returns diff for modified and untracked files', async () => {
    git('init', '-q');
    git('config', 'user.email', 'test@example.com');
    git('config', 'user.name', 'Test');
    fs.writeFileSync(path.join(dir, 'tracked.txt'), 'one\n');
    git('add', '.');
    git('commit', '-qm', 'init');

    fs.writeFileSync(path.join(dir, 'tracked.txt'), 'two\n');
    fs.writeFileSync(path.join(dir, 'untracked.txt'), 'new file\n');

    const tracked = await getDiff(dir, 'tracked.txt');
    expect(tracked.binary).toBe(false);
    expect(tracked.patch).toContain('+two');

    const untracked = await getDiff(dir, 'untracked.txt');
    expect(untracked.status).toBe('untracked');
    expect(untracked.patch).toContain('+new file');
  });

  it('rejects paths outside the workspace', async () => {
    await expect(getDiff(dir, '../outside.txt')).rejects.toMatchObject({ code: 'path_outside_workspace' });
    await expect(getDiff(dir, '/tmp/outside.txt')).rejects.toMatchObject({ code: 'path_outside_workspace' });
  });
});
