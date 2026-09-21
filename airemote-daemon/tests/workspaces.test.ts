import fs from 'node:fs';
import { afterEach, describe, expect, it } from 'vitest';
import { Db } from '../src/db';
import { existingDirs, workspaceContains, workspaceContainsAny, workspaceRoots } from '../src/workspace-service';

const dbs: Db[] = [];

function openDb(): Db {
  const db = new Db(':memory:');
  dbs.push(db);
  return db;
}

afterEach(() => {
  for (const db of dbs.splice(0)) db.close();
});

describe('Db workspaces + permissions', () => {
  it('seeds a default workspace and filters sessions by workspace', () => {
    const db = openDb();
    const ws = db.seedDefaultWorkspace(process.cwd(), 'ask');
    expect(db.listWorkspaces()).toHaveLength(1);
    expect(db.getDefaultWorkspace()?.id).toBe(ws.id);

    db.createSession({ id: 's-1', runtime: 'claude', workspaceId: ws.id, permissionMode: 'ask', cwd: process.cwd() });
    db.createSession({ id: 's-2', runtime: 'claude', workspaceId: ws.id, permissionMode: 'acceptEdits', cwd: ws.path });

    expect(db.listSessions().map((s) => s.id).sort()).toEqual(['s-1', 's-2']);
    expect(db.listSessions(ws.id).map((s) => s.id).sort()).toEqual(['s-1', 's-2']);
    expect(db.listSessions('other')).toEqual([]);
  });

  it('persists session permission grants', () => {
    const db = openDb();
    const ws = db.seedDefaultWorkspace(process.cwd(), 'ask');
    db.createSession({ id: 's-1', runtime: 'claude', workspaceId: ws.id, permissionMode: 'ask', cwd: ws.path });

    expect(db.hasPermissionGrant('s-1', 'Bash')).toBe(false);
    db.addPermissionGrant('s-1', 'Bash');
    db.addPermissionGrant('s-1', 'Bash'); // idempotent
    expect(db.hasPermissionGrant('s-1', 'Bash')).toBe(true);
    expect(db.listPermissionGrants('s-1').map((g) => g.tool_name)).toEqual(['Bash']);

    db.deletePermissionGrant('s-1', 'Bash');
    expect(db.hasPermissionGrant('s-1', 'Bash')).toBe(false);
  });

  it('allows a subdirectory of an existing workspace as its own workspace', () => {
    const db = openDb();
    const ws = db.seedDefaultWorkspace(process.cwd(), 'ask');
    const childPath = `${ws.path}/child`;

    // Containment still holds (cwd validation relies on it)...
    expect(workspaceContains(ws.path, childPath)).toBe(true);
    // ...but registering the nested path is no longer refused.
    const child = db.createWorkspace({ name: 'child', path: childPath });
    expect(db.listWorkspaces().map((w) => w.path).sort()).toEqual([childPath, ws.path].sort());
    expect(db.getWorkspace(child.id)?.path).toBe(childPath);
  });

  it('deletes permission grants with their session', () => {
    const db = openDb();
    const ws = db.seedDefaultWorkspace(process.cwd(), 'ask');
    db.createSession({ id: 's-1', runtime: 'claude', workspaceId: ws.id, permissionMode: 'ask', cwd: ws.path });
    db.addPermissionGrant('s-1', 'Bash');
    db.deleteSession('s-1');
    expect(db.listPermissionGrants('s-1')).toEqual([]);
  });
});

describe('workspace extra dirs', () => {
  it('stores extra dirs per workspace, idempotently, and cascades on delete', () => {
    const db = openDb();
    const ws = db.seedDefaultWorkspace(process.cwd(), 'ask');
    expect(db.listWorkspaceDirs(ws.id)).toEqual([]);

    db.addWorkspaceDir(ws.id, '/srv/extra');
    db.addWorkspaceDir(ws.id, '/srv/extra'); // idempotent
    db.addWorkspaceDir(ws.id, '/srv/other');
    expect(db.listWorkspaceDirs(ws.id)).toEqual(['/srv/extra', '/srv/other']);
    expect(db.listAllWorkspaceDirs().get(ws.id)).toEqual(['/srv/extra', '/srv/other']);

    db.removeWorkspaceDir(ws.id, '/srv/extra');
    expect(db.listWorkspaceDirs(ws.id)).toEqual(['/srv/other']);

    db.deleteWorkspace(ws.id);
    expect(db.listWorkspaceDirs(ws.id)).toEqual([]);
    expect(db.listAllWorkspaceDirs().size).toBe(0);
  });

  it('keeps granted dirs scoped to their own workspace', () => {
    const db = openDb();
    const a = db.seedDefaultWorkspace(process.cwd(), 'ask');
    const b = db.createWorkspace({ name: 'b', path: `${a.path}/child` });
    db.addWorkspaceDir(a.id, '/srv/extra');

    const rootsA = workspaceRoots(db, db.getWorkspace(a.id)!);
    const rootsB = workspaceRoots(db, db.getWorkspace(b.id)!);
    expect(rootsA).toEqual([a.path, '/srv/extra']);
    expect(rootsB).toEqual([b.path]);

    // The whole point of workspace scope: a grant serves every session under
    // its own workspace, and nothing else.
    expect(workspaceContainsAny(rootsA, '/srv/extra/pkg')).toBe(true);
    expect(workspaceContainsAny(rootsB, '/srv/extra/pkg')).toBe(false);
  });

  it('drops vanished dirs at spawn time without forgetting them in config', () => {
    const cwd = fs.realpathSync(process.cwd());
    expect(existingDirs([process.cwd()])).toEqual([cwd]);
    expect(existingDirs([`${cwd}/definitely-not-here`])).toEqual([]);
  });
});

describe('workspace browse shortcuts', () => {
  it('stores shortcuts separately from granted dirs and cascades on delete', () => {
    const db = openDb();
    const ws = db.seedDefaultWorkspace(process.cwd(), 'ask');
    expect(db.listWorkspaceShortcuts(ws.id)).toEqual([]);

    db.addWorkspaceShortcut(ws.id, '/srv/bookmark');
    db.addWorkspaceShortcut(ws.id, '/srv/bookmark'); // idempotent
    expect(db.listWorkspaceShortcuts(ws.id)).toEqual(['/srv/bookmark']);
    expect(db.listAllWorkspaceShortcuts().get(ws.id)).toEqual(['/srv/bookmark']);

    // 书签不是授权，授权也不是书签：两张表互不影响。
    expect(db.listWorkspaceDirs(ws.id)).toEqual([]);
    db.addWorkspaceDir(ws.id, '/srv/granted');
    expect(db.listWorkspaceShortcuts(ws.id)).toEqual(['/srv/bookmark']);
    expect(db.listWorkspaceDirs(ws.id)).toEqual(['/srv/granted']);

    db.removeWorkspaceShortcut(ws.id, '/srv/bookmark');
    expect(db.listWorkspaceShortcuts(ws.id)).toEqual([]);
    expect(db.listWorkspaceDirs(ws.id)).toEqual(['/srv/granted']);

    db.addWorkspaceShortcut(ws.id, '/srv/bookmark');
    db.deleteWorkspace(ws.id);
    expect(db.listWorkspaceShortcuts(ws.id)).toEqual([]);
    expect(db.listAllWorkspaceShortcuts().size).toBe(0);
  });
});
