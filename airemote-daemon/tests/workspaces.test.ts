import { afterEach, describe, expect, it } from 'vitest';
import { Db } from '../src/db';
import { workspaceContains } from '../src/workspace-service';

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
