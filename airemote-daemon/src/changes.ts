import { execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const MAX_PATCH_CHARS = 512 * 1024;
const MAX_UNTRACKED_BYTES = 1024 * 1024;

export type ChangeStatus = 'modified' | 'added' | 'deleted' | 'renamed' | 'untracked' | 'conflicted';

export interface ChangedFile {
  path: string;
  oldPath: string | null;
  status: ChangeStatus;
  staged: boolean;
  binary: boolean;
  isDirectory: boolean;
}

export interface ChangesResult {
  isGitRepo: boolean;
  gitRoot: string | null;
  files: ChangedFile[];
}

export interface DiffResult {
  path: string;
  oldPath: string | null;
  status: ChangeStatus;
  binary: boolean;
  truncated: boolean;
  patch: string;
}

export class ChangesError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly httpStatus = 400,
  ) {
    super(message);
  }
}

interface GitOk {
  ok: true;
  stdout: string;
}

interface GitFail {
  ok: false;
  code: 'git_unavailable' | 'git_failed';
  stderr: string;
}

async function runGit(cwd: string, args: string[]): Promise<GitOk | GitFail> {
  try {
    const { stdout } = await execFileAsync('git', ['-C', cwd, ...args], {
      encoding: 'utf8',
      maxBuffer: 16 * 1024 * 1024,
      windowsHide: true,
    });
    return { ok: true, stdout };
  } catch (err) {
    const e = err as NodeJS.ErrnoException & { stderr?: string | Buffer };
    if (e.code === 'ENOENT') {
      return { ok: false, code: 'git_unavailable', stderr: 'git executable not found' };
    }
    const stderr = typeof e.stderr === 'string' ? e.stderr : e.stderr?.toString() ?? e.message;
    return { ok: false, code: 'git_failed', stderr };
  }
}

async function gitRootFor(workspacePath: string): Promise<string | null> {
  const result = await runGit(workspacePath, ['rev-parse', '--show-toplevel']);
  if (!result.ok) {
    if (result.code === 'git_unavailable') {
      throw new ChangesError('git is not available on the daemon host', 'git_unavailable', 503);
    }
    return null;
  }
  const root = result.stdout.trim();
  if (!root) return null;
  return fs.realpathSync(root);
}

function insideWorkspace(workspacePath: string, absolute: string): boolean {
  const rel = path.relative(workspacePath, absolute);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function toWirePath(workspacePath: string, gitRoot: string, gitPath: string): { path: string; isDirectory: boolean } | null {
  const absolute = path.resolve(gitRoot, gitPath);
  if (!insideWorkspace(workspacePath, absolute)) return null;
  const rel = path.relative(workspacePath, absolute);
  const isDirectory = gitPath.endsWith('/') || (fs.existsSync(absolute) && fs.statSync(absolute).isDirectory());
  return { path: rel.split(path.sep).join('/'), isDirectory };
}

function mapStatus(x: string, y: string): ChangeStatus {
  if (x === '?' && y === '?') return 'untracked';
  if (x === 'U' || y === 'U') return 'conflicted';
  if (x === 'R' || y === 'R' || x === 'C' || y === 'C') return 'renamed';
  if (x === 'A' || y === 'A') return 'added';
  if (x === 'D' || y === 'D') return 'deleted';
  return 'modified';
}

function isStaged(x: string, y: string): boolean {
  return x !== ' ' && x !== '?' && x !== '!';
}

interface ParsedStatusEntry {
  x: string;
  y: string;
  path: string;
  oldPath: string | null;
}

function parseStatusZ(output: string): ParsedStatusEntry[] {
  const tokens = output.split('\0');
  const entries: ParsedStatusEntry[] = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (!token) continue;
    const x = token[0] ?? ' ';
    const y = token[1] ?? ' ';
    const rawPath = token.slice(3);
    if (x === '?' && y === '?') {
      entries.push({ x, y, path: rawPath, oldPath: null });
      continue;
    }
    if (x === 'R' || y === 'R' || x === 'C' || y === 'C') {
      const oldPath = tokens[i + 1] ?? '';
      i += 1;
      entries.push({ x, y, path: rawPath, oldPath });
      continue;
    }
    entries.push({ x, y, path: rawPath, oldPath: null });
  }
  return entries;
}

export async function listChanges(workspacePath: string): Promise<ChangesResult> {
  const gitRoot = await gitRootFor(workspacePath);
  if (!gitRoot) {
    return { isGitRepo: false, gitRoot: null, files: [] };
  }

  const result = await runGit(workspacePath, ['status', '--porcelain=v1', '-z', '--untracked-files=normal', '--', '.']);
  if (!result.ok) {
    throw new ChangesError(`git status failed: ${result.stderr}`, 'git_failed', 500);
  }

  const files: ChangedFile[] = [];
  for (const entry of parseStatusZ(result.stdout)) {
    const current = toWirePath(workspacePath, gitRoot, entry.path);
    if (!current) continue;
    const old = entry.oldPath ? toWirePath(workspacePath, gitRoot, entry.oldPath) : null;
    const status = mapStatus(entry.x, entry.y);
    files.push({
      path: current.path,
      oldPath: old?.path ?? null,
      status,
      staged: isStaged(entry.x, entry.y),
      binary: false,
      isDirectory: current.isDirectory,
    });
  }

  files.sort((a, b) => a.path.localeCompare(b.path));
  return { isGitRepo: true, gitRoot, files };
}

function resolveRelativePath(workspacePath: string, relativePath: string): string {
  if (!relativePath || path.isAbsolute(relativePath)) {
    throw new ChangesError('path must be a relative path', 'path_outside_workspace', 400);
  }
  const absolute = path.resolve(workspacePath, relativePath);
  if (!insideWorkspace(workspacePath, absolute) || absolute === workspacePath) {
    throw new ChangesError('path is outside workspace', 'path_outside_workspace', 400);
  }
  return absolute;
}

function readUtf8Prefix(filePath: string): { text: string; truncated: boolean; binary: boolean } {
  const stat = fs.statSync(filePath);
  if (stat.isDirectory()) {
    throw new ChangesError('path is a directory', 'directory_diff_not_supported', 400);
  }
  const fd = fs.openSync(filePath, 'r');
  try {
    const size = Math.min(stat.size, MAX_UNTRACKED_BYTES);
    const buffer = Buffer.alloc(size);
    const bytes = fs.readSync(fd, buffer, 0, size, 0);
    const data = buffer.subarray(0, bytes);
    const binary = data.includes(0);
    if (binary) return { text: '', truncated: stat.size > size, binary: true };
    const text = data.toString('utf8');
    return { text, truncated: stat.size > size, binary: false };
  } finally {
    fs.closeSync(fd);
  }
}

function untrackedPatch(relativePath: string, text: string): string {
  const lines = text.split('\n');
  return [
    '--- /dev/null',
    `+++ b/${relativePath}`,
    `@@ -0,0 +1,${Math.max(lines.length, 1)} @@`,
    ...lines.map((line) => `+${line}`),
  ].join('\n');
}

export async function getDiff(workspacePath: string, relativePath: string): Promise<DiffResult> {
  const absolute = resolveRelativePath(workspacePath, relativePath);
  const gitRoot = await gitRootFor(workspacePath);
  if (!gitRoot) {
    throw new ChangesError('workspace is not a git repository', 'not_git_repo', 400);
  }

  const changes = await listChanges(workspacePath);
  const normalized = relativePath.split(path.sep).join('/');
  const entry = changes.files.find((file) => file.path === normalized || file.oldPath === normalized);
  if (!entry) {
    throw new ChangesError('file has no pending changes', 'file_not_changed', 404);
  }
  if (entry.isDirectory) {
    throw new ChangesError('cannot diff an untracked directory directly', 'directory_diff_not_supported', 400);
  }

  let patch = '';
  if (entry.status === 'untracked') {
    const content = readUtf8Prefix(absolute);
    if (content.binary) {
      return { path: normalized, oldPath: entry.oldPath, status: entry.status, binary: true, truncated: content.truncated, patch: '' };
    }
    patch = untrackedPatch(normalized, content.text);
    return {
      path: normalized,
      oldPath: entry.oldPath,
      status: entry.status,
      binary: false,
      truncated: content.truncated || patch.length > MAX_PATCH_CHARS,
      patch: patch.slice(0, MAX_PATCH_CHARS),
    };
  }

  let staged = false;
  const unstaged = await runGit(workspacePath, ['diff', '--no-ext-diff', '--no-textconv', '-M', '--unified=3', '--', relativePath]);
  if (unstaged.ok && unstaged.stdout) {
    patch = unstaged.stdout;
  } else {
    const cached = await runGit(workspacePath, ['diff', '--cached', '--no-ext-diff', '--no-textconv', '-M', '--unified=3', '--', relativePath]);
    if (cached.ok && cached.stdout) {
      patch = cached.stdout;
      staged = true;
    }
  }

  if (!patch) {
    throw new ChangesError('no diff produced for this file', 'file_not_changed', 404);
  }

  const binary = /^Binary files .* differ$/m.test(patch) || patch.includes('GIT binary patch');
  const truncated = patch.length > MAX_PATCH_CHARS;
  return {
    path: normalized,
    oldPath: entry.oldPath,
    status: entry.status,
    binary,
    truncated,
    patch: binary ? '' : patch.slice(0, MAX_PATCH_CHARS),
  };
}
