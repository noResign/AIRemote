import fs from 'node:fs';
import path from 'node:path';
import type { Db, WorkspaceRow } from './db.js';
import { resolveWorkspaceCwd } from './workspace.js';

export class WorkspaceValidationError extends Error {
  constructor(
    message: string,
    readonly code: string,
  ) {
    super(message);
  }
}

/** Canonical absolute path for an existing directory. */
export function canonicalizeExistingDirectory(raw: string): string {
  const abs = path.resolve(raw);
  let real: string;
  try {
    real = fs.realpathSync(abs);
  } catch {
    throw new WorkspaceValidationError(`directory not found: ${raw}`, 'directory_not_found');
  }
  let stat: fs.Stats;
  try {
    stat = fs.statSync(real);
  } catch {
    throw new WorkspaceValidationError(`directory not accessible: ${raw}`, 'directory_not_accessible');
  }
  if (!stat.isDirectory()) {
    throw new WorkspaceValidationError(`not a directory: ${raw}`, 'not_a_directory');
  }
  return real;
}

export function workspaceContains(workspacePath: string, candidate: string): boolean {
  return resolveWorkspaceCwd(candidate, workspacePath) !== null;
}

export function findContainingWorkspace(workspaces: WorkspaceRow[], candidate: string): WorkspaceRow | undefined {
  return workspaces
    .filter((w) => w.enabled === 1 && workspaceContains(w.path, candidate))
    .sort((a, b) => b.path.length - a.path.length)[0];
}

export function nestedWorkspace(
  workspaces: WorkspaceRow[],
  candidate: string,
): { outer: WorkspaceRow; inner: string } | null {
  for (const w of workspaces) {
    if (w.path === candidate) continue;
    if (workspaceContains(w.path, candidate)) return { outer: w, inner: candidate };
    if (workspaceContains(candidate, w.path)) return { outer: w, inner: w.path };
  }
  return null;
}

export function resolveWorkspaceForRequest(db: Db, workspaceId?: string): WorkspaceRow | null {
  if (workspaceId) {
    const ws = db.getWorkspace(workspaceId);
    return ws && ws.enabled === 1 ? ws : null;
  }
  return db.getDefaultWorkspace() ?? db.listWorkspaces().find((w) => w.enabled === 1) ?? null;
}
