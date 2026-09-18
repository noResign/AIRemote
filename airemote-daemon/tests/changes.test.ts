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

function gitIn(cwd: string, ...args: string[]): void {
  execFileSync('git', args, { cwd, stdio: 'pipe' });
}

function initRepo(repoDir: string): void {
  gitIn(repoDir, 'init', '-q');
  gitIn(repoDir, 'config', 'user.email', 'test@example.com');
  gitIn(repoDir, 'config', 'user.name', 'Test');
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
    expect(result.dir).toBe('');
    expect(result.repos).toEqual([]);

    const tracked = result.files.find((f) => f.path === 'tracked.txt');
    expect(tracked).toMatchObject({ status: 'modified', staged: false, additions: 1, deletions: 1 });

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

describe('directory scope', () => {
  it('lists git repos among the direct children when the root is not a repo', async () => {
    fs.mkdirSync(path.join(dir, 'beta'));
    fs.mkdirSync(path.join(dir, 'alpha'));
    fs.mkdirSync(path.join(dir, 'plain'));
    fs.mkdirSync(path.join(dir, '.hidden'));
    initRepo(path.join(dir, 'beta'));
    initRepo(path.join(dir, 'alpha'));

    const result = await listChanges(dir);
    expect(result.isGitRepo).toBe(false);
    expect(result.dir).toBe('');
    expect(result.repos).toEqual(['alpha', 'beta']);
  });

  it('does not look past the direct children', async () => {
    const nested = path.join(dir, 'wrapper', 'inner');
    fs.mkdirSync(nested, { recursive: true });
    initRepo(nested);

    const result = await listChanges(dir);
    expect(result.isGitRepo).toBe(false);
    expect(result.repos).toEqual([]);
  });

  it('scopes changes to a subdirectory, keeping workspace-relative paths', async () => {
    const repo = path.join(dir, 'alpha');
    fs.mkdirSync(repo);
    initRepo(repo);
    fs.writeFileSync(path.join(repo, 'keep.txt'), 'one\n');
    gitIn(repo, 'add', '.');
    gitIn(repo, 'commit', '-qm', 'init');
    fs.writeFileSync(path.join(repo, 'keep.txt'), 'two\n');
    fs.writeFileSync(path.join(repo, 'untracked.txt'), 'new\n');

    const scoped = await listChanges(dir, 'alpha');
    expect(scoped.dir).toBe('alpha');
    expect(scoped.isGitRepo).toBe(true);
    expect(scoped.repos).toEqual([]);
    expect(scoped.files.map((f) => f.path)).toEqual(['alpha/keep.txt', 'alpha/untracked.txt']);
  });

  it('diffs a file inside a scoped subdirectory', async () => {
    const repo = path.join(dir, 'alpha');
    fs.mkdirSync(repo);
    initRepo(repo);
    fs.writeFileSync(path.join(repo, 'keep.txt'), 'one\n');
    gitIn(repo, 'add', '.');
    gitIn(repo, 'commit', '-qm', 'init');
    fs.writeFileSync(path.join(repo, 'keep.txt'), 'two\n');

    const diff = await getDiff(dir, 'alpha/keep.txt', 'alpha');
    expect(diff.status).toBe('modified');
    expect(diff.patch).toContain('+two');
  });

  it('rejects a dir that escapes the workspace or is not a directory', async () => {
    await expect(listChanges(dir, '../')).rejects.toMatchObject({ code: 'path_outside_workspace' });
    await expect(listChanges(dir, '/tmp')).rejects.toMatchObject({ code: 'path_outside_workspace' });
    await expect(listChanges(dir, 'missing')).rejects.toMatchObject({ code: 'directory_not_found' });

    fs.writeFileSync(path.join(dir, 'plain.txt'), 'x');
    await expect(listChanges(dir, 'plain.txt')).rejects.toMatchObject({ code: 'not_a_directory' });
  });
});
