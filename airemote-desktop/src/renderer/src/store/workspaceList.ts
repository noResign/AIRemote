import type { WorkspaceDto } from '../../../shared/contract';

/**
 * List surgery for the workspace page, kept pure: the store patches are the
 * part most likely to silently reorder the rail's dropdown, so they are tested
 * without a daemon.
 */

/** Insert or replace by id, keeping position (a rename must not jump the list). */
export function replaceWorkspace(list: WorkspaceDto[], workspace: WorkspaceDto): WorkspaceDto[] {
  const index = list.findIndex((item) => item.id === workspace.id);
  if (index < 0) return [...list, workspace];
  const next = [...list];
  next[index] = workspace;
  return next;
}

export function removeWorkspace(list: WorkspaceDto[], id: string): WorkspaceDto[] {
  return list.filter((item) => item.id !== id);
}

export function setWorkspaceDirs(list: WorkspaceDto[], id: string, dirs: string[]): WorkspaceDto[] {
  return list.map((item) => (item.id === id ? { ...item, dirs } : item));
}

/**
 * `isDefault` is exclusive on the daemon, but a PATCH response only carries the
 * changed row — so the client has to clear the old flag itself.
 */
export function applyWorkspacePatch(
  list: WorkspaceDto[],
  workspace: WorkspaceDto,
  patch: { isDefault?: boolean },
): WorkspaceDto[] {
  const cleared = patch.isDefault ? list.map((item) => ({ ...item, isDefault: false })) : list;
  return replaceWorkspace(cleared, workspace);
}

/** What the shell falls back to when the chosen workspace is gone. */
export function fallbackWorkspaceId(list: WorkspaceDto[]): string | null {
  return (list.find((item) => item.isDefault) ?? list[0])?.id ?? null;
}
