import path from 'node:path';
import type { Express } from 'express';
import type { AppContext } from '../context.js';
import type { WorkspaceRow } from '../db.js';
import { canonicalizeExistingDirectory, existingDirs, WorkspaceValidationError } from '../workspace-service.js';

function workspaceDto(w: WorkspaceRow, sessionCount = 0, dirs: string[] = []) {
  return {
    id: w.id,
    name: w.name,
    path: w.path,
    dirs,
    isDefault: w.is_default === 1,
    enabled: w.enabled === 1,
    sessionCount,
    createdAt: w.created_at,
    lastUsedAt: w.last_used_at,
  };
}

function sendValidationError(res: import('express').Response, err: unknown): void {
  if (err instanceof WorkspaceValidationError) {
    res.status(400).json({ error: err.message, code: err.code });
    return;
  }
  res.status(500).json({ error: err instanceof Error ? err.message : String(err), code: 'internal_error' });
}

export function registerWorkspaceRoutes(app: Express, ctx: AppContext): void {
  app.get('/api/workspaces', (_req, res) => {
    const counts = ctx.db.countSessionsByWorkspace();
    const dirsByWorkspace = ctx.db.listAllWorkspaceDirs();
    res.json({
      workspaces: ctx.db
        .listWorkspaces()
        .map((w) => workspaceDto(w, counts.get(w.id) ?? 0, dirsByWorkspace.get(w.id) ?? [])),
    });
  });

  app.post('/api/workspaces', (req, res) => {
    const body = (req.body ?? {}) as { name?: unknown; path?: unknown };
    const rawPath = typeof body.path === 'string' ? body.path.trim() : '';
    if (!rawPath) {
      res.status(400).json({ error: 'path is required', code: 'bad_request' });
      return;
    }
    try {
      const realPath = canonicalizeExistingDirectory(rawPath);
      const workspaces = ctx.db.listWorkspaces();
      const duplicate = workspaces.find((w) => w.path === realPath);
      if (duplicate) {
        res.status(409).json({ error: 'workspace already exists', code: 'workspace_exists', workspace: workspaceDto(duplicate) });
        return;
      }
      const name = typeof body.name === 'string' && body.name.trim() ? body.name.trim().slice(0, 80) : path.basename(realPath) || realPath;
      const created = ctx.db.createWorkspace({ name, path: realPath, isDefault: workspaces.length === 0 });
      ctx.db.audit('create_workspace', JSON.stringify({ id: created.id, path: created.path }));
      res.status(201).json({ ok: true, workspace: workspaceDto(created, 0) });
    } catch (err) {
      sendValidationError(res, err);
    }
  });

  app.patch('/api/workspaces/:id', (req, res) => {
    const workspace = ctx.db.getWorkspace(req.params.id);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const body = (req.body ?? {}) as { name?: unknown; isDefault?: unknown; enabled?: unknown };
    const update: { name?: string; enabled?: boolean } = {};
    if (body.name !== undefined) {
      if (typeof body.name !== 'string' || !body.name.trim()) {
        res.status(400).json({ error: 'name must be a non-empty string', code: 'bad_request' });
        return;
      }
      update.name = body.name.trim().slice(0, 80);
    }
    if (body.enabled !== undefined) {
      if (typeof body.enabled !== 'boolean') {
        res.status(400).json({ error: 'enabled must be boolean', code: 'bad_request' });
        return;
      }
      update.enabled = body.enabled;
    }
    if (body.isDefault !== undefined) {
      if (typeof body.isDefault !== 'boolean') {
        res.status(400).json({ error: 'isDefault must be boolean', code: 'bad_request' });
        return;
      }
      if (body.isDefault && body.enabled === false) {
        res.status(409).json({ error: 'default workspace cannot be disabled in the same request', code: 'workspace_disabled' });
        return;
      }
      if (body.isDefault) {
        if (workspace.enabled !== 1) {
          res.status(409).json({ error: 'disabled workspace cannot be default', code: 'workspace_disabled' });
          return;
        }
        ctx.db.setDefaultWorkspace(workspace.id);
      }
    }

    ctx.db.updateWorkspace(workspace.id, update);
    ctx.db.audit('update_workspace', JSON.stringify({ id: workspace.id, update }));
    const updated = ctx.db.getWorkspace(workspace.id) as WorkspaceRow;
    const counts = ctx.db.countSessionsByWorkspace();
    res.json({
      ok: true,
      workspace: workspaceDto(updated, counts.get(updated.id) ?? 0, ctx.db.listWorkspaceDirs(updated.id)),
    });
  });

  // Extra dirs granted to a workspace. Written either here by the user or by
  // the Read/Grep approval path (routes/permissions.ts) — same store, so a dir
  // approved from a chat shows up in the management page and vice versa.
  app.post('/api/workspaces/:id/dirs', (req, res) => {
    const workspace = ctx.db.getWorkspace(req.params.id);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const body = (req.body ?? {}) as { path?: unknown };
    const rawPath = typeof body.path === 'string' ? body.path.trim() : '';
    if (!rawPath) {
      res.status(400).json({ error: 'path is required', code: 'bad_request' });
      return;
    }
    try {
      const realPath = canonicalizeExistingDirectory(rawPath);
      if (realPath === workspace.path) {
        res.status(400).json({ error: 'path is already the workspace primary dir', code: 'primary_dir' });
        return;
      }
      if (ctx.db.listWorkspaceDirs(workspace.id).includes(realPath)) {
        res.status(409).json({ error: 'dir already granted', code: 'dir_exists' });
        return;
      }
      ctx.db.addWorkspaceDir(workspace.id, realPath);
      ctx.db.audit('add_workspace_dir', JSON.stringify({ workspaceId: workspace.id, path: realPath, via: 'client' }));
      res.status(201).json({ ok: true, dirs: ctx.db.listWorkspaceDirs(workspace.id) });
    } catch (err) {
      sendValidationError(res, err);
    }
  });

  app.delete('/api/workspaces/:id/dirs', (req, res) => {
    const workspace = ctx.db.getWorkspace(req.params.id);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const body = (req.body ?? {}) as { path?: unknown };
    const rawPath = typeof body.path === 'string' ? body.path.trim() : '';
    if (!rawPath) {
      res.status(400).json({ error: 'path is required', code: 'bad_request' });
      return;
    }
    // Match however the stored entry is spelled: canonicalize when the dir still
    // exists, else fall back to the resolved form so an already-deleted dir can
    // still be cleaned out of the list.
    const stored = existingDirs([rawPath])[0] ?? path.resolve(rawPath);
    ctx.db.removeWorkspaceDir(workspace.id, stored);
    ctx.db.audit('remove_workspace_dir', JSON.stringify({ workspaceId: workspace.id, path: stored }));
    res.json({ ok: true, dirs: ctx.db.listWorkspaceDirs(workspace.id) });
  });

  app.delete('/api/workspaces/:id', (req, res) => {
    const workspace = ctx.db.getWorkspace(req.params.id);
    if (!workspace) {
      res.status(404).json({ error: 'workspace not found', code: 'workspace_not_found' });
      return;
    }
    const sessions = ctx.db.listSessions(workspace.id);
    if (sessions.length > 0) {
      res.status(409).json({
        error: `workspace still has ${sessions.length} session(s)`,
        code: 'workspace_not_empty',
        sessionCount: sessions.length,
      });
      return;
    }
    ctx.db.deleteWorkspace(workspace.id);
    if (workspace.is_default === 1) {
      const next = ctx.db.listWorkspaces().find((w) => w.enabled === 1);
      if (next) ctx.db.setDefaultWorkspace(next.id);
    }
    ctx.db.audit('delete_workspace', workspace.id);
    res.json({ ok: true, id: workspace.id });
  });
}
