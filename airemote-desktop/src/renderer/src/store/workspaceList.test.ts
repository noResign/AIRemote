import { describe, expect, it } from 'vitest';
import {
  applyWorkspacePatch,
  fallbackWorkspaceId,
  removeWorkspace,
  replaceWorkspace,
  setWorkspaceDirs,
} from './workspaceList';
import type { WorkspaceDto } from '../../../shared/contract';

function ws(id: string, overrides: Partial<WorkspaceDto> = {}): WorkspaceDto {
  return {
    id,
    name: id,
    path: `/home/${id}`,
    dirs: [],
    shortcutDirs: [],
    isDefault: false,
    enabled: true,
    sessionCount: 0,
    createdAt: 0,
    lastUsedAt: 0,
    ...overrides,
  };
}

describe('replaceWorkspace', () => {
  it('appends a workspace that is not in the list yet', () => {
    expect(replaceWorkspace([ws('a')], ws('b')).map((w) => w.id)).toEqual(['a', 'b']);
  });

  it('replaces in place, so a rename does not jump the rail dropdown', () => {
    const list = [ws('a'), ws('b'), ws('c')];
    const renamed = replaceWorkspace(list, ws('b', { name: 'B!' }));
    expect(renamed.map((w) => w.id)).toEqual(['a', 'b', 'c']);
    expect(renamed[1]?.name).toBe('B!');
  });
});

describe('removeWorkspace', () => {
  it('drops by id and leaves the rest alone', () => {
    expect(removeWorkspace([ws('a'), ws('b')], 'a').map((w) => w.id)).toEqual(['b']);
  });
});

describe('setWorkspaceDirs', () => {
  it('replaces only the targeted workspace dirs', () => {
    const list = [ws('a', { dirs: ['/x'] }), ws('b', { dirs: ['/y'] })];
    const next = setWorkspaceDirs(list, 'a', ['/x', '/z']);
    expect(next[0]?.dirs).toEqual(['/x', '/z']);
    expect(next[1]?.dirs).toEqual(['/y']);
  });
});

describe('applyWorkspacePatch', () => {
  it('clears the previous default when a new one is set', () => {
    const list = [ws('a', { isDefault: true }), ws('b')];
    const next = applyWorkspacePatch(list, ws('b', { isDefault: true }), { isDefault: true });
    expect(next.map((w) => w.isDefault)).toEqual([false, true]);
  });

  it('leaves other rows untouched for a plain rename', () => {
    const list = [ws('a', { isDefault: true }), ws('b')];
    const next = applyWorkspacePatch(list, ws('b', { name: 'B!' }), {});
    expect(next[0]?.isDefault).toBe(true);
    expect(next[1]?.name).toBe('B!');
  });
});

describe('fallbackWorkspaceId', () => {
  it('prefers the default, else the first, else null', () => {
    expect(fallbackWorkspaceId([ws('a'), ws('b', { isDefault: true })])).toBe('b');
    expect(fallbackWorkspaceId([ws('a'), ws('b')])).toBe('a');
    expect(fallbackWorkspaceId([])).toBeNull();
  });
});
